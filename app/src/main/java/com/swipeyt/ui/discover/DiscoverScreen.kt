package com.swipeyt.ui.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.swipeyt.AppContainer
import com.swipeyt.domain.Recommender.Candidate
import com.swipeyt.domain.Track
import com.swipeyt.domain.hiResThumbnail
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun DiscoverScreen(container: AppContainer) {
    val vm: DiscoverViewModel = viewModel(factory = viewModelFactory { initializer { DiscoverViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.playFront() }
    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeToast()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Discover",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.deck.isEmpty() && state.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator(Modifier.size(72.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Tuning your first picks…", style = MaterialTheme.typography.bodyLarge)
                    }
                    state.deck.isEmpty() -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            state.error ?: "Nothing here yet",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = vm::retry) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Try again")
                        }
                    }
                    else -> CardStack(
                        deck = state.deck,
                        isBuffering = playback.isBuffering && playback.videoId == state.deck.first().track.videoId,
                        progress = if (playback.videoId == state.deck.first().track.videoId) playback.progress else 0f,
                        playbackError = playback.error?.takeIf { playback.videoId == state.deck.first().track.videoId },
                        onSwiped = vm::swipe,
                        onTap = vm::togglePlayPause,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Controls(
                enabled = state.deck.isNotEmpty(),
                canUndo = state.canUndo,
                isPlaying = playback.isPlaying,
                onUndo = vm::undo,
                onDislike = { vm.swipe(false) },
                onLike = { vm.swipe(true) },
                onPlayPause = vm::togglePlayPause,
            )
            Spacer(Modifier.height(20.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.large)
        }
    }
}

@Composable
private fun CardStack(
    deck: List<Candidate>,
    isBuffering: Boolean,
    progress: Float,
    playbackError: String?,
    onSwiped: (liked: Boolean) -> Unit,
    onTap: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Artwork is square and the caption needs ~150dp; keep the whole card inside the viewport.
        val cardWidth = minOf(maxWidth, maxHeight - 170.dp)
        // Back cards, drawn first so the front card is on top.
        deck.drop(1).reversed().forEachIndexed { i, candidate ->
            val depth = deck.size - 1 - i
            val scale by animateFloatAsState(1f - depth * 0.05f, label = "scale")
            val offsetY by animateFloatAsState(depth * 18f, label = "offset")
            key(candidate.track.videoId) {
                TrackCard(
                    track = candidate.track,
                    modifier = Modifier
                        .width(cardWidth)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationY = offsetY * density
                        },
                )
            }
        }
        key(deck.first().track.videoId) {
            SwipeableCard(
                cardWidth = cardWidth,
                candidate = deck.first(),
                isBuffering = isBuffering,
                progress = progress,
                playbackError = playbackError,
                onSwiped = onSwiped,
                onTap = onTap,
            )
        }
    }
}

@Composable
private fun SwipeableCard(
    cardWidth: Dp,
    candidate: Candidate,
    isBuffering: Boolean,
    progress: Float,
    playbackError: String?,
    onSwiped: (liked: Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val threshold = widthPx * 0.3f
        val horizontalFraction = (offset.value.x / threshold).coerceIn(-1f, 1f)

        TrackCard(
            track = candidate.track,
            isFront = true,
            isBuffering = isBuffering,
            progress = progress,
            playbackError = playbackError,
            likeAlpha = horizontalFraction.coerceAtLeast(0f),
            nopeAlpha = (-horizontalFraction).coerceAtLeast(0f),
            modifier = Modifier
                .width(cardWidth)
                .graphicsLayer {
                    translationX = offset.value.x
                    translationY = offset.value.y
                    rotationZ = (offset.value.x / widthPx) * 18f
                }
                .pointerInput(candidate.track.videoId) {
                    var totalDrag = Offset.Zero
                    detectDragGestures(
                        onDragStart = { totalDrag = Offset.Zero },
                        onDrag = { change, drag ->
                            change.consume()
                            totalDrag += drag
                            scope.launch { offset.snapTo(offset.value + drag) }
                        },
                        onDragEnd = {
                            val x = offset.value.x
                            if (abs(x) > threshold) {
                                val liked = x > 0
                                scope.launch {
                                    offset.animateTo(
                                        Offset(sign(x) * widthPx * 1.6f, offset.value.y * 1.5f),
                                        animationSpec = tween(220),
                                    )
                                    onSwiped(liked)
                                }
                            } else {
                                scope.launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { offset.animateTo(Offset.Zero, spring()) }
                        },
                    )
                }
                .pointerInput(candidate.track.videoId) {
                    detectTapGestures(onTap = { onTap() })
                },
        )
    }
}

@Composable
private fun TrackCard(
    track: Track,
    modifier: Modifier = Modifier,
    isFront: Boolean = false,
    isBuffering: Boolean = false,
    progress: Float = 0f,
    playbackError: String? = null,
    likeAlpha: Float = 0f,
    nopeAlpha: Float = 0f,
) {
    val shape = RoundedCornerShape(32.dp)
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFront) 8.dp else 2.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                AsyncImage(
                    model = track.thumbnailUrl?.hiResThumbnail(),
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.75f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ),
                )
                if (isFront) {
                    StampBadge("LIKE", MaterialTheme.colorScheme.primary, likeAlpha, Modifier.align(Alignment.TopStart).padding(24.dp))
                    StampBadge("NOPE", MaterialTheme.colorScheme.error, nopeAlpha, Modifier.align(Alignment.TopEnd).padding(24.dp))
                    if (isBuffering) {
                        CircularWavyProgressIndicator(
                            Modifier.align(Alignment.Center).size(64.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = playbackError != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    ) {
                        Text(
                            "Preview unavailable — tap to retry",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    track.artistLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isFront) {
                    Spacer(Modifier.height(18.dp))
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StampBadge(text: String, color: Color, alpha: Float, modifier: Modifier) {
    if (alpha <= 0.01f) return
    Text(
        text,
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                rotationZ = if (text == "LIKE") -12f else 12f
            }
            .border(4.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = color,
        fontSize = 32.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun Controls(
    enabled: Boolean,
    canUndo: Boolean,
    isPlaying: Boolean,
    onUndo: () -> Unit,
    onDislike: () -> Unit,
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(52.dp),
            shapes = IconButtonDefaults.shapes(),
        ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }

        FilledIconButton(
            onClick = onDislike,
            enabled = enabled,
            modifier = Modifier.size(72.dp),
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Icon(Icons.Filled.Close, contentDescription = "Skip", modifier = Modifier.size(34.dp)) }

        FilledIconButton(
            onClick = onLike,
            enabled = enabled,
            modifier = Modifier.size(72.dp),
            shapes = IconButtonDefaults.shapes(),
        ) { Icon(Icons.Filled.Favorite, contentDescription = "Like", modifier = Modifier.size(34.dp)) }

        FilledTonalIconButton(
            onClick = onPlayPause,
            enabled = enabled,
            modifier = Modifier.size(52.dp),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play / pause")
        }
    }
}
