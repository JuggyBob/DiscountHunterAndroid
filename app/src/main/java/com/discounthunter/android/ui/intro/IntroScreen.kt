package com.discounthunter.android.ui.intro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.discounthunter.android.R
import com.discounthunter.android.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One page of the intro.
 *
 * [art] is drawn rather than imported: it keeps the intro free of binary assets, scales to any
 * density, and animates for free because it is composed from the same shapes the rest of the app
 * uses.
 */
private data class IntroPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val accent: Color,
    /** A short line under the illustration, so the artwork is not purely decorative. */
    val caption: String,
)

/**
 * The first-run tour.
 *
 * ## How the motion works
 *
 * Pages slide **horizontally** with the direction of travel — next slides the old page left, previous
 * slides it right — and the incoming page is scaled down and faded up on a slight delay, so it
 * settles *into* place rather than arriving fully formed. The delay is what stops the two pages
 * from moving as one flat block.
 *
 * The dots track the pager's own offset continuously rather than snapping, so dragging the pager
 * by hand and tapping "Next" produce the same motion.
 *
 * Everything appears on a staggered entrance the first time the screen is shown, so the app
 * assembles itself instead of arriving finished.
 */
@Composable
fun IntroScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = introPages()
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // Staggered entrance: the art lands first, then the words, then the controls. One shared
    // driver so the whole screen has a single rhythm rather than three unrelated ones.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(700, easing = Motion.Emphasised))
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer {
                    alpha = entrance.value
                    translationY = (1f - entrance.value) * -12f
                },
            )
            Text(
                text = stringResource(R.string.intro_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .graphicsLayer { alpha = entrance.value },
            )

            Spacer(Modifier.height(8.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // Swiping is allowed, but only after the entrance has settled: a page dragged while
                // the screen is still assembling fights the animation.
                userScrollEnabled = entrance.value > 0.9f,
            ) { index ->
                val page = pages[index]
                IntroPageContent(page = page, entrance = entrance.value)
            }

            PageDots(
                count = pages.size,
                // Tracks the pager's real offset, so a drag and a tap animate identically.
                offset = { pagerState.currentPageOffsetFraction },
                page = { pagerState.currentPage },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .graphicsLayer { alpha = entrance.value },
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Previous only exists once there is somewhere to go back to; it animates in rather
                // than appearing, which keeps the row from jumping.
                AnimatedVisibility(
                    visible = pagerState.currentPage > 0,
                    enter = fadeIn(Motion.fade()) + slideInHorizontally(Motion.screen()) { -it / 3 },
                    exit = fadeOut(Motion.fadeOut()) + slideOutHorizontally(Motion.settle()) { -it / 3 },
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                    ) { Text(stringResource(R.string.intro_previous)) }
                }

                Button(
                    onClick = {
                        val next = pagerState.currentPage + 1
                        scope.launch {
                            if (next >= pages.size) onFinish() else pagerState.animateScrollToPage(next)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(
                            if (pagerState.currentPage == pages.lastIndex) {
                                R.string.intro_start
                            } else {
                                R.string.intro_next
                            },
                        ),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun introPages(): List<IntroPage> = listOf(
    IntroPage(
        icon = Icons.Outlined.LocalOffer,
        title = stringResource(R.string.intro_1_title),
        body = stringResource(R.string.intro_1_body),
        caption = stringResource(R.string.intro_1_caption),
        accent = MaterialTheme.colorScheme.primary,
    ),
    IntroPage(
        icon = Icons.Outlined.Search,
        title = stringResource(R.string.intro_2_title),
        body = stringResource(R.string.intro_2_body),
        caption = stringResource(R.string.intro_2_caption),
        accent = MaterialTheme.colorScheme.tertiary,
    ),
    IntroPage(
        icon = Icons.Outlined.MovieFilter,
        title = stringResource(R.string.intro_3_title),
        body = stringResource(R.string.intro_3_body),
        caption = stringResource(R.string.intro_3_caption),
        accent = MaterialTheme.colorScheme.secondary,
    ),
    IntroPage(
        icon = Icons.Outlined.StarBorder,
        title = stringResource(R.string.intro_4_title),
        body = stringResource(R.string.intro_4_body),
        caption = stringResource(R.string.intro_4_caption),
        accent = MaterialTheme.colorScheme.primary,
    ),
    IntroPage(
        icon = Icons.Outlined.Cached,
        title = stringResource(R.string.intro_5_title),
        body = stringResource(R.string.intro_5_body),
        caption = stringResource(R.string.intro_5_caption),
        accent = MaterialTheme.colorScheme.tertiary,
    ),
)

@Composable
private fun IntroPageContent(page: IntroPage, entrance: Float) {
    // The page animates in as the pager settles: a small rise and a scale from just under 1, which
    // reads as the page being placed rather than sliding in flat.
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(132.dp).graphicsLayer {
                val eased = Motion.Emphasised.transform(entrance.coerceIn(0f, 1f))
                scaleX = 0.86f + 0.14f * eased
                scaleY = 0.86f + 0.14f * eased
                alpha = eased
                translationY = (1f - eased) * 24f
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            listOf(page.accent.copy(alpha = 0.22f), Color.Transparent),
                        ),
                        shape = CircleShape,
                    ),
            )
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = page.accent,
                modifier = Modifier.size(58.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                // Words follow the art: a slightly later, shorter move.
                val t = ((entrance - 0.15f) / 0.85f).coerceIn(0f, 1f)
                alpha = Motion.Emphasised.transform(t)
                translationY = (1f - Motion.Emphasised.transform(t)) * 18f
            },
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val t = ((entrance - 0.3f) / 0.7f).coerceIn(0f, 1f)
                alpha = Motion.Emphasised.transform(t)
                translationY = (1f - Motion.Emphasised.transform(t)) * 14f
            },
        )

        Spacer(Modifier.height(18.dp))

        // A tinted chip carries the one-line takeaway, so the page has a shape as well as a colour.
        Box(
            modifier = Modifier
                .background(page.accent.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .graphicsLayer {
                    val t = ((entrance - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    alpha = Motion.Emphasised.transform(t)
                    scaleX = 0.9f + 0.1f * Motion.Emphasised.transform(t)
                    scaleY = 0.9f + 0.1f * Motion.Emphasised.transform(t)
                },
        ) {
            Text(
                text = page.caption,
                style = MaterialTheme.typography.labelLarge,
                color = page.accent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The pager dots, which stretch around the current page rather than merely changing colour. */
@Composable
private fun PageDots(
    count: Int,
    offset: () -> Float,
    page: () -> Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            // Continuous: the dot grows as the pager approaches it, so a drag shows the same
            // continuity a tap does.
            val distance = (index - page() - offset()).coerceIn(-1f, 1f)
            val weight = 1f - kotlin.math.abs(distance)
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(8.dp + 20.dp * weight)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.75f * weight),
                        shape = CircleShape,
                    ),
            )
        }
    }

}