package com.swipeyt.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipeyt.AppContainer
import com.swipeyt.data.innertube.InnerTube
import com.swipeyt.data.local.GenreSeed
import kotlin.math.roundToInt

/**
 * Cold-start taste profile: genres (from YouTube Music's "Moods & genres" catalogue) and a few
 * favourite artists. Also reused from Settings as "Edit taste".
 */
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    val userData by container.userStore.data.collectAsStateWithLifecycle()
    val settings = userData.settings

    var genres by remember { mutableStateOf<List<InnerTube.Genre>?>(null) }
    var genreError by remember { mutableStateOf<String?>(null) }
    var artistInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            genres = container.innerTube.moodsAndGenres()
        } catch (e: Exception) {
            genreError = e.message ?: "Could not load genres"
            genres = emptyList()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (onCancel == null) "What do you like?" else "Edit your taste",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            if (onCancel != null) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
        }
        Text(
            "Pick a few genres to seed your first recommendations. Every swipe teaches the app more.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Text("Genres & moods", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when {
            genres == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            genres!!.isEmpty() -> Text(
                genreError ?: "No genres available",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                genres!!.forEach { genre ->
                    val selected = settings.seedGenres.any { it.params == genre.params }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            container.userStore.updateSettings { s ->
                                s.copy(
                                    seedGenres = if (selected) s.seedGenres.filterNot { it.params == genre.params }
                                    else s.seedGenres + GenreSeed(genre.name, genre.params),
                                )
                            }
                        },
                        label = { Text(genre.name) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, Modifier.width(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Favourite artists (optional)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        fun addArtist() {
            val name = artistInput.trim()
            if (name.isNotEmpty() && settings.seedArtists.none { it.equals(name, ignoreCase = true) }) {
                container.userStore.updateSettings { it.copy(seedArtists = it.seedArtists + name) }
            }
            artistInput = ""
        }
        OutlinedTextField(
            value = artistInput,
            onValueChange = { artistInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Daft Punk") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addArtist() }),
            trailingIcon = {
                IconButton(onClick = ::addArtist, enabled = artistInput.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = "Add artist")
                }
            },
            shape = MaterialTheme.shapes.large,
        )
        if (settings.seedArtists.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                settings.seedArtists.forEach { artist ->
                    InputChip(
                        selected = false,
                        onClick = {
                            container.userStore.updateSettings { it.copy(seedArtists = it.seedArtists - artist) }
                        },
                        label = { Text(artist) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", Modifier.width(16.dp)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Preview length: ${settings.previewSeconds}s", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = settings.previewSeconds.toFloat(),
            onValueChange = { v -> container.userStore.updateSettings { it.copy(previewSeconds = v.roundToInt()) } },
            valueRange = 10f..60f,
            steps = 9,
        )

        Text("Adventurousness: ${(settings.exploration * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        Text(
            "Higher means more unfamiliar artists in your deck.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = settings.exploration,
            onValueChange = { v -> container.userStore.updateSettings { it.copy(exploration = v) } },
            valueRange = 0f..1f,
        )

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (onCancel == null) {
                TextButton(onClick = onDone) { Text("Skip for now") }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = onDone,
                enabled = settings.seedGenres.isNotEmpty() || settings.seedArtists.isNotEmpty() || onCancel != null,
                shape = MaterialTheme.shapes.extraLarge,
            ) { Text(if (onCancel == null) "Start swiping" else "Done") }
        }
        Spacer(Modifier.height(16.dp))
    }
}
