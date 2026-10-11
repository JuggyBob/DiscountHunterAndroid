package com.discounthunter.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * One place for every animation in the app.
 *
 * Motion that is tuned per screen drifts: three different springs for the same "a panel arrived"
 * beat, two easings for the same fade, a 400 ms slide next to a 120 ms one. Naming the handful of
 * motions we actually use and defining them once means a new screen joins the existing rhythm
 * instead of inventing its own.
 *
 * ## The rules this encodes
 *
 * * **Position and size animate; colour does not.** Users navigate by tracking where things went,
 *   so anything that changes *where* it is slides. Anything that only changes colour cross-fades,
 *   because a colour tween at these durations reads as a flicker rather than a transition.
 * * **Distance scales with travel.** A list-item press springs; a screen change slides a short
 *   distance. What matters is that they feel related, not that they travel the same pixels.
 * * **Nothing blocks input.** Every duration here is well under 400 ms except the expanding
 *   player, where the spring *is* the point. A press answers in [PRESSED_MILLIS], fast enough to
 *   feel connected to the finger.
 */
object Motion {

    /** Material's emphasised easing: quick to start, gentle to settle. */
    val Emphasised: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Decelerating curve, for something entering the screen. */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Accelerating curve, for something leaving. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** A colour-only change. */
    const val FADE_MILLIS = 180

    /** A press that must feel connected to the finger. */
    const val PRESSED_MILLIS = 110

    /** Leaving a screen. */
    const val SETTLE_MILLIS = 160

    /** Entering a screen. */
    const val SCREEN_MILLIS = 260

    /** Opening a sheet, panel or overlay. */
    const val SHEET_MILLIS = 280

    /** A list item settling into place after an insert or a reorder. */
    const val REORDER_MILLIS = 320

    /** Fraction of the travel a screen transition covers; the rest is shared with the fade. */
    const val SCREEN_TRAVEL = 0.10f

    /** Fraction of the travel an overlay covers. Slides further, so its direction is unmistakable. */
    const val OVERLAY_TRAVEL = 0.25f

    /**
     * The spring behind anything the user just touched — stars, chips, buttons.
     *
     * A damping ratio below 1 is what makes it *overshoot*: the element grows past its resting size
     * and settles back, which the eye reads as "that popped" rather than "that changed".
     */
    fun <T> pop(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 700f)

    /** The app's one showpiece: the trailer gliding up into full screen. */
    fun <T> expand(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.86f, stiffness = 320f)

    /** Entering a screen. */
    fun <T> screen(): FiniteAnimationSpec<T> = tween(SCREEN_MILLIS, easing = Decelerate)

    /** Leaving a screen. */
    fun <T> settle(): FiniteAnimationSpec<T> = tween(SETTLE_MILLIS, easing = Accelerate)

    /** A colour-only fade in. */
    fun fade(): FiniteAnimationSpec<Float> = tween(FADE_MILLIS, easing = Emphasised)

    /** A colour-only fade out. */
    fun fadeOut(): FiniteAnimationSpec<Float> = tween(SETTLE_MILLIS, easing = Accelerate)

    /** An overlay or panel sliding in. */
    fun <T> sheet(): FiniteAnimationSpec<T> = tween(SHEET_MILLIS, easing = Decelerate)

    /** A list item settling into place. */
    fun <T> reorder(): FiniteAnimationSpec<T> = tween(REORDER_MILLIS, easing = Emphasised)
}