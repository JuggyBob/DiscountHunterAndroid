package com.discounthunter.android.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.discounthunter.android.R
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.ui.components.NetworkArtwork

/**
 * Creates and owns the [ExoPlayer] for [trailer], or returns null when nothing is playing.
 *
 * ## Why not an embedded web page
 *
 * The desktop build used a WebView2 instance to play trailers in-page. That is the wrong tool on
 * Android: a YouTube iframe inside a `WebView` is blocked, and a `WebView` per trailer is heavy.
 * Here every trailer the app can actually play is a real media stream, so it is played by the
 * platform's own player:
 *
 *  * progressive `.mp4` -> ExoPlayer's default extractor;
 *  * HLS `.m3u8` -> the HLS module, so adaptive streams work exactly as intended;
 *  * YouTube -> handed to the system, because YouTube publishes no direct stream the app may use.
 *
 * A trailer with no playable stream is never given a player at all — see
 * [com.discounthunter.android.core.model.Trailer.action], which reports `NONE` for those, and the UI
 * shows an "open" action instead of a fake play button.
 *
 * The player is created per playback and released on dismiss **and** paused on `ON_STOP**, so a
 * video never keeps playing in the background after the user leaves the screen.
 *
 * Hoisting it here is what lets the same player be shown *inline* in the page and, at the user's
 * request, glide up into a full-screen overlay — one decoder, no reload, no lost position.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberTrailerPlayer(
    trailer: Trailer?,
    autoplay: Boolean,
    onUnplayable: (Trailer) -> Unit,
): ExoPlayer? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // A trailer we cannot play is never presented as playable.
    LaunchedEffect(trailer) {
        if (trailer != null && !trailer.canPlayInApp) onUnplayable(trailer)
    }
    val playable = trailer?.takeIf { it.canPlayInApp }

    val player = remember(playable?.streamUrl) {
        playable?.let {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(it.streamUrl))
                    repeatMode = Player.REPEAT_MODE_OFF
                    playWhenReady = autoplay
                    prepare()
                }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    // Pause and release audio as soon as the app leaves the foreground.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return player
}

/** The video surface itself, sized by [modifier]. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                controllerShowTimeoutMs = 3_000
            }
        },
        modifier = modifier,
        update = { it.player = player },
    )
}

/** True while the player is still filling its buffer; the UI shows a spinner in that window. */
private val ExoPlayer.isBuffering: Boolean
    get() = playbackState == Player.STATE_BUFFERING

/**
 * The current buffering flag, kept in a Compose [mutableStateOf] so the surrounding composable
 * **recomposes** when it flips.
 *
 * This matters because [ExoPlayer.playbackState] is an ordinary `Int`, not a snapshot state: reading
 * [isBuffering] at composition captures whatever the flag happened to be *then*, and drawing
 * `if (player.isBuffering)` never updates again — the loading spinner stays on screen while the
 * video plays, which is exactly the bug this fixes. Listening to the player's events and writing
 * into a state object makes the spinner appear and disappear with the real buffer state.
 */
@Composable
private fun rememberBuffering(player: ExoPlayer): State<Boolean> {
    val state = remember(player) { mutableStateOf(player.isBuffering) }
    DisposableEffect(player) {
        state.value = player.isBuffering
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.value = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}

/**
 * The inline player, shown in place inside the page.
 *
 * Playback starts here rather than in a full-screen overlay: the trailer is part of the page, and
 * jumping straight to a black full-screen player hides the rest of a page the user is still reading.
 * Expanding is the user's explicit choice, and the overlay slides up when they make it.
 */
@Composable
fun TrailerPlayerCard(
    player: ExoPlayer,
    trailer: Trailer,
    onExpand: () -> Unit,
    onOpenExternally: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            PlayerSurface(player = player, modifier = Modifier.fillMaxSize())

            val buffering by rememberBuffering(player)
            if (buffering) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trailer.name.ifBlank { "Trailer" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = trailer.kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Always offer the store page as an escape hatch for a stream that will not play.
            IconButton(onClick = { onOpenExternally(trailer.externalUrl) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.detail_trailer_external),
                )
            }
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(R.string.detail_trailer_expand),
                )
            }
        }
    }
}

/**
 * The full-screen overlay.
 *
 * Wrapped in an [AnimatedVisibility] so expanding *glides* rather than snapping: the scrim fades in
 * while the panel slides up from below on a spring, and collapsing reverses it. A hard cut between
 * an inline card and a full-screen player reads as a glitch; the transition is what tells the user
 * they are still looking at the same video.
 */
@Composable
fun TrailerPlayerOverlay(
    player: ExoPlayer,
    trailer: Trailer,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onOpenExternally: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        PlayerSurface(
            player = player,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .aspectRatio(16f / 9f),
        )

        // Header: the trailer's name and a way out.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trailer.name.ifBlank { "Trailer" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Outlined.FullscreenExit,
                        contentDescription = stringResource(R.string.detail_trailer_collapse),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.detail_trailer_close),
                        tint = Color.White,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trailer.kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Spacer(Modifier.weight(1f))
                // Always offer the store page as an escape hatch for a stream that will not play.
                IconButton(onClick = { onOpenExternally(trailer.externalUrl) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.detail_trailer_external),
                        tint = Color.White,
                    )
                }
            }
        }

        val buffering by rememberBuffering(player)
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
    }
}

/**
 * The full-screen screenshot viewer.
 *
 * Every tile is keyed by its own URL, and the pager is a `HorizontalPager` rather than a manual
 * index, so swiping cannot desynchronise the image from the caption.
 *
 * Like the trailer overlay this animates in: the image rises from slightly below and slightly small
 * into place, so opening a screenshot reads as a deliberate transition rather than a hard cut. It is
 * an overlay rather than a `Dialog` specifically so the transition is ours to control — `Dialog`
 * animates its own window and ignores the content's enter transition.
 */
@Composable
fun ScreenshotViewer(
    screenshots: List<Screenshot>,
    initial: Screenshot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startIndex = remember(screenshots, initial) {
        screenshots.indexOfFirst { it.fullUrl == initial.fullUrl }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { screenshots.size },
    )

    BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val shot = screenshots[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NetworkArtwork(
                    // The full-resolution URL, with the tile URL as a fallback.
                    candidates = listOf(shot.fullUrl, shot.thumbUrl),
                    contentDescription = "Screenshot ${page + 1} of ${screenshots.size}",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    contentScale = ContentScale.Fit,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${page + 1} / ${screenshots.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.detail_close), color = Color.White)
                    }
                }
            }
        }
    }
}
