package com.discounthunter.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.discounthunter.android.core.model.StoreId

/**
 * The app palette.
 *
 * The accent is an emerald "savings" green, because the thing the user is scanning a list for is
 * how much they save. Store colours are the brands' own, tinted so they stay legible on both a
 * near-black and a near-white surface.
 */

// ---- dark -----------------------------------------------------------------------------------
val DarkBackground = Color(0xFF0F1219)
val DarkSurface = Color(0xFF161A23)
val DarkSurfaceContainer = Color(0xFF1C212C)
val DarkSurfaceContainerHigh = Color(0xFF232936)
val DarkOutline = Color(0xFF39404F)
val DarkOutlineVariant = Color(0xFF272D39)

val DarkPrimary = Color(0xFF34D399)
val DarkOnPrimary = Color(0xFF04291D)
val DarkSecondary = Color(0xFF7DD3FC)
val DarkOnSecondary = Color(0xFF06283A)
val DarkTertiary = Color(0xFFC4B5FD)
val DarkError = Color(0xFFFF8A80)

val DarkOnBackground = Color(0xFFE6E9F0)
val DarkOnSurface = Color(0xFFE6E9F0)
val DarkOnSurfaceVariant = Color(0xFF9AA4B6)

// ---- light ----------------------------------------------------------------------------------
val LightBackground = Color(0xFFF5F7FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainer = Color(0xFFEDF1F6)
val LightSurfaceContainerHigh = Color(0xFFE3E9F1)
val LightOutline = Color(0xFFCFD6E0)
val LightOutlineVariant = Color(0xFFE2E7EE)

val LightPrimary = Color(0xFF0E9F6E)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFF0369A1)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightTertiary = Color(0xFF6D4BC9)
val LightError = Color(0xFFBA1A1A)

val LightOnBackground = Color(0xFF14181F)
val LightOnSurface = Color(0xFF14181F)
val LightOnSurfaceVariant = Color(0xFF5A6474)

/** Muted strike-through price colour, tuned per theme. */
val DarkMutedPrice = Color(0xFF7C8698)
val LightMutedPrice = Color(0xFF8A93A1)

/** A full-bleed warning strip (one store unreachable, snapshot fallback, …). */
val DarkWarningContainer = Color(0xFF3A2F16)
val DarkOnWarningContainer = Color(0xFFF6D68A)
val LightWarningContainer = Color(0xFFFFF3D6)
val LightOnWarningContainer = Color(0xFF6B4E05)

/** Foreground/background pair used for a store's badge. */
data class StorePalette(val container: Color, val content: Color)

private val DarkStorePalettes = mapOf(
    StoreId.STEAM to StorePalette(Color(0xFF16293D), Color(0xFF7EC8FF)),
    StoreId.EPIC to StorePalette(Color(0xFF3A1F35), Color(0xFFFFA8D2)),
    StoreId.GOG to StorePalette(Color(0xFF2B2447), Color(0xFFC0B0FF)),
)

private val LightStorePalettes = mapOf(
    StoreId.STEAM to StorePalette(Color(0xFFDCEBFA), Color(0xFF0B4A78)),
    StoreId.EPIC to StorePalette(Color(0xFFFBE0F1), Color(0xFF8A1B62)),
    StoreId.GOG to StorePalette(Color(0xFFE6E0FB), Color(0xFF4B2E9E)),
)

fun storePalette(store: StoreId, dark: Boolean): StorePalette =
    (if (dark) DarkStorePalettes else LightStorePalettes)[store]
        ?: StorePalette(
            container = if (dark) DarkSurfaceContainerHigh else LightSurfaceContainerHigh,
            content = if (dark) DarkOnSurfaceVariant else LightOnSurfaceVariant,
        )
