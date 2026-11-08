package com.discounthunter.android.alerts

/**
 * Whether a check may record what it detected as "announced".
 *
 * ## The rule
 *
 * The watch state is what stops the same sale being announced twice. Advancing it is therefore a
 * promise that the user *saw* the announcement. If the check detected something but could post
 * nothing — the notification permission was revoked between the check and the post, or the system
 * refused every notification — advancing the state would silently consume the news: granting the
 * permission later would not bring it back.
 *
 * There are two legitimate reasons to advance anyway:
 *
 *  * there was nothing to announce, so there is nothing to consume; and
 *  * this was the first run, whose purpose is to establish a baseline. It has no announcements by
 *    definition, and without the baseline every later run would look like a first run.
 *
 * Pure so the rule can be tested without a worker, a notifier or an Android permission.
 */
object AlertDeliveryPolicy {

    /**
     * @param hasAnythingToAnnounce whether the detector produced sales or free games.
     * @param posted how many notifications the notifier actually posted.
     * @param previouslyInitialised whether a baseline existed before this check.
     */
    fun shouldAdvanceState(
        hasAnythingToAnnounce: Boolean,
        posted: Int,
        previouslyInitialised: Boolean,
    ): Boolean = !hasAnythingToAnnounce || posted > 0 || !previouslyInitialised
}
