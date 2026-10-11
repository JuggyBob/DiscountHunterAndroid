package com.discounthunter.android.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discounthunter.android.R
import com.discounthunter.android.core.model.GameDetail
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.FxRates
import com.discounthunter.android.core.model.StoreOffer
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.ui.components.ErrorState
import com.discounthunter.android.ui.components.EmptyState
import com.discounthunter.android.ui.components.NetworkArtwork
import com.discounthunter.android.ui.components.Pill
import com.discounthunter.android.ui.components.PriceLine
import com.discounthunter.android.ui.components.SectionHeader
import com.discounthunter.android.ui.components.StoreBadge

/**
 * A game's detail page.
 *
 * Sections appear only when there is something real to show. A title with no screenshots simply has
 * no screenshot section — there is no placeholder grid implying otherwise — and a trailer with no
 * playable stream gets an "open" action rather than a play button that cannot work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onGetOffer: (StoreOffer) -> Unit,
    onOpenUrl: (String, StoreId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback = state.playback

    // One player for the page, shared by the inline card and the full-screen overlay, so expanding
    // does not re-buffer or lose the playhead.
    val trailerPlayer = rememberTrailerPlayer(
        trailer = playback?.trailer,
        autoplay = state.autoplayTrailers,
        onUnplayable = { trailer ->
            viewModel.closeTrailerPlayer()
            val target = trailer.externalUrl
            if (target.isNotBlank()) onOpenUrl(target, null)
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { stringResource(R.string.deals_title) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleWishlist) {
                        Icon(
                            imageVector = if (state.isSaved) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(
                                if (state.isSaved) R.string.deal_remove_wishlist else R.string.deal_add_wishlist,
                            ),
                            tint = if (state.isSaved) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (state.error != null && state.detail == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::retry,
                    retryLabel = stringResource(R.string.detail_retry),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "hero") {
                HeroHeader(
                    heroUrl = state.heroUrl,
                    title = state.title,
                    shortDescription = state.detail?.shortDescription.orEmpty(),
                    metaChips = state.metaChips,
                    priceLine = state.priceLine,
                    convertedNote = state.convertedNote,
                    currencySymbol = state.currencySymbol,
                    isSaved = state.isSaved,
                    isLoading = state.isLoading,
                    sources = state.detail?.sources.orEmpty(),
                )
            }

            if (state.detail?.storeUrls?.isNotEmpty() == true) {
                item(key = "actions") {
                    StoreActions(
                        offers = state.offers,
                        currencySymbol = state.currencySymbol,
                        currencyCode = state.currencyCode,
                        fx = state.fxRates,
                        onGetOffer = onGetOffer,
                        onOpenUrl = onOpenUrl,
                    )
                }
            }

            if (state.trailers.isNotEmpty()) {
                item(key = "trailers-header") {
                    SectionHeader(
                        title = stringResource(R.string.detail_trailers),
                        count = state.trailers.size,
                    )
                }

                // The inline player lives in the page, between the header and the row of trailers, so
                // it can be opened without losing your place or hiding the rest of the page.
                //
                // It deliberately stays composed while the full-screen overlay is up and sliding in.
                // Removing it at the instant `isFullScreen` flips leaves a frame where the page has
                // no player and the overlay has not arrived yet — a visible flash on every expand.
                // The overlay is drawn on top, so the card underneath is simply covered.
                if (playback != null && trailerPlayer != null) {
                    item(key = "trailer-player") {
                        TrailerPlayerCard(
                            player = trailerPlayer,
                            trailer = playback.trailer,
                            onExpand = viewModel::expandTrailer,
                            onOpenExternally = { onOpenUrl(it, null) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                item(key = "trailers") {
                    TrailerRow(
                        trailers = state.trailers,
                        playing = playback?.trailer?.let { current -> current.streamUrl },
                        onPlay = viewModel::playTrailer,
                        onOpenExternally = { onOpenUrl(it, null) },
                    )
                }
            }

            if (state.screenshots.isNotEmpty()) {
                item(key = "shots-header") {
                    SectionHeader(
                        title = stringResource(R.string.detail_screenshots),
                        count = state.screenshots.size,
                    )
                }
                item(key = "shots") {
                    ScreenshotRow(
                        screenshots = state.screenshots,
                        onOpen = viewModel::openScreenshot,
                    )
                }
            }

            val detail = state.detail
            if (detail != null) {
                if (detail.aboutText.isNotBlank()) {
                    item(key = "about-header") {
                        SectionHeader(title = stringResource(R.string.detail_about))
                    }
                    item(key = "about") {
                        Text(
                            text = detail.aboutText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                if (detail.minimumRequirements.isNotBlank() || detail.recommendedRequirements.isNotBlank()) {
                    item(key = "req-header") {
                        SectionHeader(title = stringResource(R.string.detail_requirements))
                    }
                    item(key = "req") {
                        RequirementsBlock(
                            minimum = detail.minimumRequirements,
                            recommended = detail.recommendedRequirements,
                        )
                    }
                }

                if (hasMetadata(detail)) {
                    item(key = "meta-header") {
                        SectionHeader(title = stringResource(R.string.detail_details_tab))
                    }
                    item(key = "meta") {
                        MetadataBlock(
                            genres = detail.genres,
                            categories = detail.categories,
                            features = detail.features,
                            releaseDate = detail.releaseDate,
                            developers = detail.developers,
                            publishers = detail.publishers,
                        )
                    }
                }
            }

            // Price history goes last: it is reference material, not the thing the user opened the
            // page for.
            val historySources = state.historySources
            if (historySources.isNotEmpty()) {
                item(key = "history") {
                    PriceHistorySection(
                        points = state.priceHistory,
                        sources = historySources,
                        selected = state.selectedHistorySource,
                        currencySymbol = state.currencySymbol,
                        onSelectSource = viewModel::selectHistorySource,
                        onOpenSource = { source ->
                            viewModel.historyUrl(source)?.let { onOpenUrl(it, null) }
                        },
                    )
                }
            }
            // No store had a record of this game. Say so plainly rather than leaving a page that
            // looks like it failed to load.
            if (state.hasNoData) {
                item(key = "no-data") {
                    EmptyState(
                        title = stringResource(R.string.detail_no_data_title),
                        message = stringResource(R.string.detail_no_data_message),
                        icon = Icons.Outlined.SearchOff,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (state.isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    // ---- full-screen overlays ----
    //
    // Both animate in and out. A hard cut between an inline card and a full-screen takeover reads as
    // a glitch; the glide is what tells the user it is still the same video they just tapped.
    val fullScreenTrailer = playback?.takeIf { it.isFullScreen }

    if (fullScreenTrailer != null && trailerPlayer != null) {
        BackHandler(onBack = viewModel::closeTrailerPlayer)
    }

    AnimatedVisibility(
        visible = fullScreenTrailer != null && trailerPlayer != null,
        enter = fadeIn(tween(durationMillis = 200)) +
            slideInVertically(animationSpec = EXPAND_SPRING) { it },
        exit = fadeOut(tween(durationMillis = 180)) +
            slideOutVertically(animationSpec = EXPAND_SPRING) { it },
    ) {
        val shown = fullScreenTrailer
        val player = trailerPlayer
        if (shown != null && player != null) {
            TrailerPlayerOverlay(
                player = player,
                trailer = shown.trailer,
                onCollapse = viewModel::collapseTrailer,
                onClose = viewModel::closeTrailerPlayer,
                onOpenExternally = { onOpenUrl(it, null) },
            )
        }
    }

    AnimatedVisibility(
        visible = state.viewingScreenshot != null,
        enter = fadeIn(tween(durationMillis = 200)) +
            slideInVertically(animationSpec = EXPAND_SPRING) { it / 3 } +
            scaleIn(initialScale = 0.94f, animationSpec = tween(durationMillis = 260)),
        exit = fadeOut(tween(durationMillis = 160)) +
            slideOutVertically(animationSpec = EXPAND_SPRING) { it / 4 } +
            scaleOut(targetScale = 0.94f, animationSpec = tween(durationMillis = 200)),
    ) {
        state.viewingScreenshot?.let { shot ->
            ScreenshotViewer(
                screenshots = state.screenshots,
                initial = shot,
                onDismiss = viewModel::closeScreenshotViewer,
            )
        }
    }
}

/**
 * The spring both overlays expand and collapse on.
 *
 * Slightly under-damped on purpose: a tiny overshoot reads as the panel being *placed* rather than
 * simply appearing, which is the difference between a transition and a cut.
 */
private val EXPAND_SPRING: FiniteAnimationSpec<IntOffset> =
    androidx.compose.animation.core.spring(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )

@Composable
private fun HeroHeader(
    heroUrl: String,
    title: String,
    shortDescription: String,
    metaChips: List<String>,
    priceLine: String?,
    convertedNote: String?,
    currencySymbol: String,
    isSaved: Boolean,
    isLoading: Boolean,
    sources: List<String>,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            NetworkArtwork(
                candidates = listOf(heroUrl),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp),
                contentScale = ContentScale.Crop,
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)

            if (priceLine != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = priceLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (convertedNote != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = convertedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (shortDescription.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = shortDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (metaChips.isNotEmpty() || isSaved) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSaved) {
                        item(key = "saved") {
                            Pill(
                                text = "SAVED",
                                container = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                                content = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    items(items = metaChips, key = { it }) { chip ->
                        Pill(text = chip)
                    }
                }
            }

            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${stringResource(R.string.detail_sources)}: ${sources.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StoreActions(
    offers: List<StoreOffer>,
    currencySymbol: String,
    currencyCode: String,
    fx: FxRates?,
    onGetOffer: (StoreOffer) -> Unit,
    onOpenUrl: (String, com.discounthunter.android.core.model.StoreId?) -> Unit,
) {
    // No horizontal padding on the Column: SectionHeader and OfferRow each apply their own, and
    // padding here as well is what used to indent "Where to buy" one extra step past every other
    // section heading on the page.
    Column(modifier = Modifier) {
        SectionHeader(title = stringResource(R.string.detail_offers))
        offers.forEach { offer ->
            OfferRow(
                offer = offer,
                currencySymbol = currencySymbol,
                currencyCode = currencyCode,
                fx = fx,
                isBest = offer == offers.firstOrNull(),
                onGet = { onGetOffer(offer) },
            )
        }
    }
}

@Composable
private fun OfferRow(
    offer: StoreOffer,
    currencySymbol: String,
    currencyCode: String,
    fx: FxRates?,
    isBest: Boolean,
    onGet: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = if (isBest) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StoreBadge(store = offer.store)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = offer.store.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriceLine(
                        salePrice = offer.price,
                        originalPrice = offer.retailPrice,
                        currencySymbol = currencySymbol,
                        // The *offer's* currency is the source, not the user's chosen one. Passing
                        // `currencyCode` here made every row look already-converted, so a GOG
                        // dollar row was printed as a pound amount with no `~` and no conversion
                        // behind it — the exact lie this whole path exists to stop.
                        currencyCode = offer.currencyCode,
                        fx = fx,
                        chosenCode = currencyCode,
                        isFree = offer.isFree,
                        compact = true,
                    )
                    if (!offer.isFree && offer.savingsPercent > 0.5) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "-${offer.savingsPercent.toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            FilledTonalButton(
                onClick = onGet,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (offer.isFree) "Get free" else "Get deal",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun TrailerRow(
    trailers: List<Trailer>,
    /** Stream URL of the trailer currently loaded in the player, so its tile can be marked. */
    playing: String?,
    onPlay: (Trailer) -> Unit,
    onOpenExternally: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = trailers, key = { it.name + it.kind }) { trailer ->
            val isPlaying = trailer.streamUrl == playing
            Column(
                modifier = Modifier
                    .width(190.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (isPlaying) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    )
                    .clickable {
                        // Only a real stream gets the player; everything else is handed off, so the
                        // app never shows a play control that cannot work.
                        if (trailer.canPlayInApp) onPlay(trailer) else onOpenExternally(trailer.externalUrl)
                    }
                    .padding(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    NetworkArtwork(
                        candidates = listOf(trailer.thumbUrl),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(6.dp),
                    )
                    Icon(
                        imageVector = if (trailer.canPlayInApp) {
                            Icons.Outlined.PlayCircle
                        } else {
                            Icons.Outlined.VideoLibrary
                        },
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = trailer.name.ifBlank { "Trailer" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = trailer.kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScreenshotRow(
    screenshots: List<Screenshot>,
    onOpen: (Screenshot) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Keyed by URL: a recycled tile can never inherit the previous shot's image.
        items(items = screenshots, key = { it.fullUrl }) { shot ->
            NetworkArtwork(
                // The small variant first so a gallery of sixteen never downloads sixteen
                // 1920x1080 files; the full-size URL is only used by the viewer.
                candidates = listOf(shot.thumbUrl, shot.fullUrl),
                contentDescription = null,
                modifier = Modifier
                    .width(230.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(shot) },
                shape = RoundedCornerShape(8.dp),
            )
        }
    }
}

@Composable
private fun RequirementsBlock(minimum: String, recommended: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (minimum.isNotBlank()) {
            Text(
                text = "Minimum",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = minimum,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recommended.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Recommended",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = recommended,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetadataBlock(
    genres: List<String>,
    categories: List<String>,
    features: List<String>,
    releaseDate: String,
    developers: List<String>,
    publishers: List<String>,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (genres.isNotEmpty()) MetadataLine("Genres", genres.take(8))
        if (categories.isNotEmpty()) MetadataLine("Categories", categories.take(10))
        if (features.isNotEmpty()) MetadataLine("Features", features.take(10))
        if (releaseDate.isNotBlank()) MetadataLine("Released", listOf(releaseDate))
        if (developers.isNotEmpty()) MetadataLine("Developers", developers.take(4))
        if (publishers.isNotEmpty()) MetadataLine("Publishers", publishers.take(4))
    }
}

@Composable
private fun MetadataLine(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = values.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun hasMetadata(detail: GameDetail): Boolean =
    detail.genres.isNotEmpty() || detail.categories.isNotEmpty() || detail.features.isNotEmpty() ||
        detail.releaseDate.isNotBlank() || detail.developers.isNotEmpty() || detail.publishers.isNotEmpty()
