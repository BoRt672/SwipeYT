package com.swipeyt.ui.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipeyt.AppContainer
import com.swipeyt.data.local.Swipe
import com.swipeyt.domain.Recommender
import com.swipeyt.domain.Recommender.Candidate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    /** Front card first, then the cards stacked behind it. */
    val deck: List<Candidate> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val canUndo: Boolean = false,
    val toast: String? = null,
)

class DiscoverViewModel(private val container: AppContainer) : ViewModel() {
    private val recommender: Recommender = container.recommender
    private val player = container.player

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    val playback = player.state

    private var lastSwipe: Pair<Candidate, Boolean>? = null
    private var fillJob: Job? = null

    init {
        fill()
    }

    private val previewSeconds get() = container.userStore.current.settings.previewSeconds

    /** Ensures the visible stack has [DECK_SIZE] cards and the front one is playing. */
    private fun fill() {
        if (fillJob?.isActive == true) return
        fillJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.deck.isEmpty(), error = null) }
            try {
                while (_state.value.deck.size < DECK_SIZE) {
                    val next = recommender.next() ?: break
                    _state.update { it.copy(deck = it.deck + next) }
                    player.prefetch(next.track)
                }
                if (_state.value.deck.isEmpty()) {
                    _state.update { it.copy(error = "Nothing to recommend yet. Try different genres in Settings.") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fill failed", e)
                _state.update { it.copy(error = e.message ?: "Could not load recommendations") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
            playFront()
        }
    }

    fun playFront() {
        val front = _state.value.deck.firstOrNull() ?: return
        if (container.userStore.current.settings.autoplay || playback.value.videoId == front.track.videoId) {
            player.play(front.track, previewSeconds)
        }
    }

    fun retry() = fill()

    fun togglePlayPause() {
        val front = _state.value.deck.firstOrNull() ?: return
        if (playback.value.videoId != front.track.videoId || playback.value.error != null) {
            player.play(front.track, previewSeconds)
        } else {
            player.togglePlayPause()
        }
    }

    fun swipe(liked: Boolean) {
        val deck = _state.value.deck
        val front = deck.firstOrNull() ?: return
        val listened = player.listened
        lastSwipe = front to liked

        container.userStore.addSwipe(
            Swipe(
                track = front.track,
                liked = liked,
                listenedMs = listened,
                timestamp = System.currentTimeMillis(),
                source = front.source.name,
            ),
        )
        _state.update { it.copy(deck = deck.drop(1), canUndo = true, toast = null) }
        deck.getOrNull(1)?.let { player.play(it.track, previewSeconds) } ?: player.stop()

        if (liked && !container.auth.current.isLoggedIn) {
            _state.update { it.copy(toast = "Liked. Sign in to sync likes to your Swipe YT playlist.") }
        } else if (liked) {
            viewModelScope.launch {
                try {
                    container.playlists.add(front.track)
                    _state.update { it.copy(toast = "Added to Swipe YT") }
                } catch (e: Exception) {
                    Log.w(TAG, "add to playlist failed", e)
                    _state.update { it.copy(toast = "Saved locally, but YouTube Music sync failed: ${e.message}") }
                }
            }
        }
        fill()
    }

    fun undo() {
        val (candidate, liked) = lastSwipe ?: return
        lastSwipe = null
        container.userStore.removeSwipe(candidate.track.videoId)
        _state.update { it.copy(deck = listOf(candidate) + it.deck, canUndo = false, toast = null) }
        player.play(candidate.track, previewSeconds)
        if (liked && container.auth.current.isLoggedIn) {
            viewModelScope.launch {
                runCatching { container.playlists.remove(candidate.track) }
                    .onFailure { Log.w(TAG, "undo remove failed", it) }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    override fun onCleared() {
        player.pause()
    }

    private companion object {
        const val DECK_SIZE = 3
        const val TAG = "DiscoverViewModel"
    }
}
