package com.swipeyt.data.repo

import com.swipeyt.data.innertube.InnerTube
import com.swipeyt.data.local.UserStore
import com.swipeyt.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manages the user's "Swipe YT" playlist on their YouTube Music account. */
class PlaylistRepository(
    private val yt: InnerTube,
    private val store: UserStore,
) {
    private val mutex = Mutex()

    private val _tracks = MutableStateFlow<List<Track>?>(null)
    /** Cached playlist contents; `null` until first loaded. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()

    /** Finds the playlist in the user's library or creates it; the id is cached locally. */
    suspend fun ensurePlaylistId(): String {
        store.current.settings.playlistId?.let { return it }
        return mutex.withLock {
            store.current.settings.playlistId ?: run {
                val existing = yt.libraryPlaylists().firstOrNull { it.title.equals(PLAYLIST_TITLE, ignoreCase = true) }
                val id = existing?.playlistId ?: yt.createPlaylist(PLAYLIST_TITLE, PLAYLIST_DESCRIPTION)
                store.updateSettings { it.copy(playlistId = id) }
                id
            }
        }
    }

    suspend fun refresh(): List<Track> {
        val id = ensurePlaylistId()
        val list = yt.playlistTracks(id)
        _tracks.value = list
        return list
    }

    suspend fun add(track: Track): Track {
        val id = ensurePlaylistId()
        val setVideoId = yt.addToPlaylist(id, track.videoId)
        val stored = track.copy(setVideoId = setVideoId)
        _tracks.update { current -> current?.let { list -> list.filterNot { it.videoId == track.videoId } + stored } }
        return stored
    }

    suspend fun remove(track: Track) {
        val id = ensurePlaylistId()
        val setVideoId = track.setVideoId
            ?: _tracks.value?.firstOrNull { it.videoId == track.videoId }?.setVideoId
            ?: yt.playlistTracks(id).firstOrNull { it.videoId == track.videoId }?.setVideoId
            ?: return
        yt.removeFromPlaylist(id, track.videoId, setVideoId)
        _tracks.update { current -> current?.filterNot { it.videoId == track.videoId } }
    }

    fun invalidate() {
        _tracks.value = null
    }

    companion object {
        const val PLAYLIST_TITLE = "Swipe YT"
        const val PLAYLIST_DESCRIPTION = "Tracks liked in the Swipe YT app"
    }
}
