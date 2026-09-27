package com.swipeyt.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.swipeyt.AppContainer
import com.swipeyt.domain.Track
import kotlinx.coroutines.launch

/** The user's "Swipe YT" playlist as it exists on their YouTube Music account. */
@Composable
fun PlaylistScreen(container: AppContainer) {
    val tracks by container.playlists.tracks.collectAsStateWithLifecycle()
    val playback by container.player.state.collectAsStateWithLifecycle()
    val settings by container.userStore.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                container.playlists.refresh()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not load playlist"
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { if (tracks == null) refresh() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Swipe YT", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        tracks?.let { "${it.size} tracks in your YouTube Music library" } ?: "Your liked tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                settings.settings.playlistId?.let { id ->
                    IconButton(onClick = { uriHandler.openUri("https://music.youtube.com/playlist?list=$id") }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in YouTube Music")
                    }
                }
                IconButton(onClick = ::refresh, enabled = !refreshing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            Spacer(Modifier.height(8.dp))

            PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
                val list = tracks
                when {
                    list == null && error != null -> ErrorState(error!!, ::refresh)
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No tracks yet. Swipe right on something you love!",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(list.asReversed(), key = { it.setVideoId ?: it.videoId }) { track ->
                            val isCurrent = playback.videoId == track.videoId
                            TrackRow(
                                track = track,
                                isPlaying = isCurrent && playback.isPlaying,
                                onClick = {
                                    if (isCurrent) container.player.togglePlayPause()
                                    else container.player.play(track, settings.settings.previewSeconds)
                                },
                                onRemove = {
                                    scope.launch {
                                        try {
                                            container.playlists.remove(track)
                                            container.userStore.removeSwipe(track.videoId)
                                            snackbar.showSnackbar("Removed \"${track.title}\"")
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar("Remove failed: ${e.message}")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it, shape = MaterialTheme.shapes.large) }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun TrackRow(track: Track, isPlaying: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        leadingContent = {
            Box(Modifier.size(56.dp)) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                )
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .padding(2.dp),
                )
            }
        },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(track.artistLine, track.album).joinToString(" • "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                track.durationSec?.let {
                    Text("%d:%02d".format(it / 60, it % 60), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove from playlist")
                }
            }
        },
    )
}
