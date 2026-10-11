package com.discounthunter.android.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/** The corner radius used by artwork across the app. */
val ArtworkShape: Shape = RoundedCornerShape(10.dp)

/**
 * Loads remote artwork without blocking, and without ever showing the wrong picture.
 *
 * Three properties matter here, and all of them are about identity rather than looks:
 *
 *  1. **The request is keyed by the URL held in the item's own immutable data.** Coil caches by
 *     request data and cancels a request when its slot leaves composition, so when a `LazyColumn`
 *     recycles a row the previous row's bitmap is discarded before the new one is drawn. A card can
 *     never briefly show its neighbour's artwork.
 *  2. **Size-aware decoding.** Because this composable runs inside the layout, Coil resolves the
 *     request size from the composable's constraints and downsamples during decode: a 132 dp
 *     thumbnail never decodes a 1920 px original. This is the main memory lever for a list of
 *     hundreds of deals.
 *  3. **Candidate fallback.** Store CDNs publish several sizes for one picture and occasionally
 *     stop serving the small one. [candidates] is tried in order, so a derived small URL that fails
 *     transparently falls back to the original instead of leaving a broken tile.
 */
@Composable
fun NetworkArtwork(
    candidates: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val urls = remember(candidates) { candidates.filter { it.isNotBlank() }.distinct() }
    var attempt by remember(urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(attempt)

    // A different set of URLs always restarts from the best candidate.
    LaunchedEffect(urls) { attempt = 0 }

    if (url == null) {
        BrokenImageBox(modifier = modifier.clip(shape))
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(180)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clip(shape),
        loading = { ShimmerBox(modifier = Modifier.fillMaxSize()) },
        error = {
            if (attempt + 1 < urls.size) {
                // A smaller variant of the same picture is unavailable; use the next candidate.
                LaunchedEffect(url) { attempt = attempt + 1 }
                ShimmerBox(modifier = Modifier.fillMaxSize())
            } else {
                BrokenImageBox(modifier = Modifier.fillMaxSize())
            }
        },
    )
}

/** Convenience for the common single-URL case. */
@Composable
fun NetworkArtwork(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    contentScale: ContentScale = ContentScale.Crop,
) = NetworkArtwork(
    candidates = listOf(url),
    contentDescription = contentDescription,
    modifier = modifier,
    shape = shape,
    contentScale = contentScale,
)

