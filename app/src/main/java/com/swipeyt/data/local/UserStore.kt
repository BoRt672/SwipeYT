package com.swipeyt.data.local

import android.content.Context
import com.swipeyt.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Swipe(
    val track: Track,
    val liked: Boolean,
    /** How much of the preview the user heard before deciding — an implicit-feedback signal. */
    val listenedMs: Long,
    val timestamp: Long,
    val source: String,
)

@Serializable
data class GenreSeed(val name: String, val params: String)

@Serializable
data class Settings(
    val previewSeconds: Int = 30,
    /** 0f = only what the model is confident about, 1f = maximum novelty. */
    val exploration: Float = 0.35f,
    val seedGenres: List<GenreSeed> = emptyList(),
    val seedArtists: List<String> = emptyList(),
    val playlistId: String? = null,
    val onboardingDone: Boolean = false,
    val autoplay: Boolean = true,
    /** Discover without a YouTube Music account; likes stay local until the user signs in. */
    val guest: Boolean = false,
)

@Serializable
data class UserData(
    val swipes: List<Swipe> = emptyList(),
    val settings: Settings = Settings(),
)

/**
 * Single-file JSON persistence for swipes and preferences. Data volume is small (a swipe is a few
 * hundred bytes) so a whole-file rewrite, debounced, is simpler and safer than a database here.
 */
class UserStore(context: Context) {
    private val file = File(context.filesDir, "user_data.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingWrite: Job? = null

    private val _data = MutableStateFlow(load())
    val data: StateFlow<UserData> = _data.asStateFlow()
    val current: UserData get() = _data.value

    private fun load(): UserData = runCatching {
        if (file.exists()) json.decodeFromString<UserData>(file.readText()) else UserData()
    }.getOrDefault(UserData())

    fun mutate(transform: (UserData) -> UserData) {
        _data.update(transform)
        scheduleWrite()
    }

    fun updateSettings(transform: (Settings) -> Settings) = mutate { it.copy(settings = transform(it.settings)) }

    fun addSwipe(swipe: Swipe) = mutate { data ->
        data.copy(swipes = data.swipes.filterNot { it.track.videoId == swipe.track.videoId } + swipe)
    }

    fun removeSwipe(videoId: String) = mutate { data ->
        data.copy(swipes = data.swipes.filterNot { it.track.videoId == videoId })
    }

    fun resetTaste() = mutate { it.copy(swipes = emptyList()) }

    private fun scheduleWrite() {
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(300)
            val snapshot = _data.value
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(snapshot))
                tmp.delete()
            }
        }
    }
}
