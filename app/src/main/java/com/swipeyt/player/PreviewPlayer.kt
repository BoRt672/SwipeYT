package com.swipeyt.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.swipeyt.data.innertube.InnerTube
import com.swipeyt.data.innertube.StreamInfo
import com.swipeyt.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

data class PlaybackState(
    val videoId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 0f..1f position inside the preview clip. */
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * Plays a short excerpt of a track with ExoPlayer. The clip is chosen from the middle of the song
 * (where the hook usually is) and looped until the user swipes. Stream URLs are resolved through
 * InnerTube and cached, and upcoming cards are prefetched so swiping feels instant.
 */
class PreviewPlayer(
    context: Context,
    private val yt: InnerTube,
    private val scope: CoroutineScope,
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val streamCache = object : LinkedHashMap<String, Deferred<StreamInfo>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<StreamInfo>>?) = size > 24
    }

    private var currentVideoId: String? = null
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var clipDurationMs = 1L
    private var listenedMs = 0L
    private var lastTickMs = 0L

    init {
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                lastTickMs = System.currentTimeMillis()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback error", error)
                val id = currentVideoId
                if (id != null) streamCache.remove(id)
                _state.update { it.copy(error = error.errorCodeName, isPlaying = false, isBuffering = false) }
            }
        })
        tickerJob = scope.launch {
            while (isActive) {
                delay(200)
                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (lastTickMs > 0) listenedMs += now - lastTickMs
                    lastTickMs = now
                    val pos = player.currentPosition.coerceAtLeast(0)
                    _state.update { it.copy(progress = (pos.toFloat() / clipDurationMs).coerceIn(0f, 1f)) }
                } else {
                    lastTickMs = System.currentTimeMillis()
                }
            }
        }
    }

    /** Milliseconds of audio the user actually heard for the current track. */
    val listened: Long get() = listenedMs

    fun prefetch(track: Track) {
        stream(track.videoId)
    }

    fun play(track: Track, previewSeconds: Int) {
        if (track.videoId == currentVideoId && _state.value.error == null) {
            player.play()
            return
        }
        loadJob?.cancel()
        currentVideoId = track.videoId
        listenedMs = 0
        _state.value = PlaybackState(videoId = track.videoId, isBuffering = true)
        player.stop()

        loadJob = scope.launch {
            try {
                val info = stream(track.videoId).await()
                if (currentVideoId != track.videoId) return@launch
                val (start, end) = clipWindow(track.durationSec, previewSeconds)
                clipDurationMs = max(1L, end - start)
                val item = MediaItem.Builder()
                    .setUri(info.url)
                    .setMediaId(track.videoId)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(start)
                            .setEndPositionMs(end)
                            .build(),
                    )
                    .build()
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(info.userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(10_000)
                player.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item))
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "stream resolve failed for ${track.videoId}", e)
                streamCache.remove(track.videoId)
                if (currentVideoId == track.videoId) {
                    _state.update { it.copy(error = e.message ?: "stream error", isBuffering = false) }
                }
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else if (currentVideoId != null) player.play()
    }

    fun pause() = player.pause()

    fun resume() {
        if (currentVideoId != null && _state.value.error == null) player.play()
    }

    fun stop() {
        loadJob?.cancel()
        currentVideoId = null
        player.stop()
        _state.value = PlaybackState()
    }

    fun release() {
        tickerJob?.cancel()
        loadJob?.cancel()
        player.release()
    }

    private fun stream(videoId: String): Deferred<StreamInfo> {
        streamCache[videoId]?.let { cached ->
            val stale = cached.isCompleted && !cached.isCancelled &&
                cached.getCompletionExceptionOrNull() == null &&
                cached.getCompleted().expiresAtMs < System.currentTimeMillis()
            if (!stale && !(cached.isCompleted && cached.getCompletionExceptionOrNull() != null)) return cached
        }
        return scope.async { yt.resolveStream(videoId) }.also { streamCache[videoId] = it }
    }

    private fun clipWindow(durationSec: Int?, previewSeconds: Int): Pair<Long, Long> {
        val previewMs = previewSeconds * 1000L
        if (durationSec == null || durationSec <= 0) return 30_000L to 30_000L + previewMs
        val totalMs = durationSec * 1000L
        if (totalMs <= previewMs + 2_000) return 0L to totalMs
        // Aim at the second third of the song; keep the whole clip inside the track.
        val start = min((totalMs * 0.33).toLong(), totalMs - previewMs - 1_000)
        return start to start + previewMs
    }

    private companion object {
        const val TAG = "PreviewPlayer"
    }
}
