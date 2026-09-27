package com.swipeyt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.webkit.CookieManager
import coil3.compose.AsyncImage
import com.swipeyt.AppContainer
import com.swipeyt.ui.onboarding.OnboardingScreen
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(container: AppContainer) {
    val auth by container.auth.state.collectAsStateWithLifecycle()
    val userData by container.userStore.data.collectAsStateWithLifecycle()
    val settings = userData.settings
    var editTaste by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    if (editTaste) {
        OnboardingScreen(container, onDone = { editTaste = false }, onCancel = { editTaste = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (auth.accountPhotoUrl != null) {
                    AsyncImage(
                        model = auth.accountPhotoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, Modifier.size(56.dp))
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        auth.accountName ?: if (auth.isLoggedIn) "YouTube Music account" else "Not signed in",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${userData.swipes.count { it.liked }} likes • ${userData.swipes.count { !it.liked }} skips",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Playback", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        ListItem(
            headlineContent = { Text("Preview length") },
            supportingContent = {
                Column {
                    Text("${settings.previewSeconds} seconds of each track")
                    Slider(
                        value = settings.previewSeconds.toFloat(),
                        onValueChange = { v -> container.userStore.updateSettings { it.copy(previewSeconds = v.roundToInt()) } },
                        valueRange = 10f..60f,
                        steps = 9,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ListItem(
            headlineContent = { Text("Autoplay previews") },
            supportingContent = { Text("Start playing as soon as a card appears") },
            trailingContent = {
                Switch(
                    checked = settings.autoplay,
                    onCheckedChange = { v -> container.userStore.updateSettings { it.copy(autoplay = v) } },
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Recommendations", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        ListItem(
            headlineContent = { Text("Adventurousness") },
            supportingContent = {
                Column {
                    Text("${(settings.exploration * 100).roundToInt()}% — how often unfamiliar artists show up")
                    Slider(
                        value = settings.exploration,
                        onValueChange = { v -> container.userStore.updateSettings { it.copy(exploration = v) } },
                        valueRange = 0f..1f,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ListItem(
            headlineContent = { Text("Genres & artists") },
            supportingContent = {
                val seeds = settings.seedGenres.map { it.name } + settings.seedArtists
                Text(if (seeds.isEmpty()) "Not set" else seeds.joinToString(", "))
            },
            leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            trailingContent = { TextButton(onClick = { editTaste = true }) { Text("Edit") } },
        )
        ListItem(
            headlineContent = { Text("Reset taste profile") },
            supportingContent = { Text("Forget all ${userData.swipes.size} swipes. Your YouTube Music playlist is kept.") },
            trailingContent = {
                TextButton(onClick = { confirmReset = true }, enabled = userData.swipes.isNotEmpty()) { Text("Reset") }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (auth.isLoggedIn) {
                OutlinedButton(onClick = { confirmLogout = true }, shape = MaterialTheme.shapes.extraLarge) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Sign out")
                }
            } else {
                Button(
                    onClick = { container.userStore.updateSettings { it.copy(guest = false) } },
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Sign in to YouTube Music")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Swipe YT talks directly to YouTube Music from this device; nothing is sent to third-party servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset taste profile?") },
            text = { Text("All local likes and skips will be forgotten and recommendations start fresh.") },
            confirmButton = {
                TextButton(onClick = {
                    container.userStore.resetTaste()
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need to sign in to YouTube Music again to keep syncing your playlist.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    container.player.stop()
                    container.playlists.invalidate()
                    container.userStore.updateSettings { it.copy(playlistId = null, guest = false) }
                    CookieManager.getInstance().removeAllCookies(null)
                    container.auth.clear()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}
