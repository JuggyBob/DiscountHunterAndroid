package com.discounthunter.android.data.steam

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.PriceOverview
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.TrailerKind

/** One hit from the Steam store search endpoint. */
data class SteamSearchHit(
    val identity: GameIdentity,
    val name: String,
    val tinyImage: String,
    val capsuleImage: String,
    val price: Double?,
)

/** Raw Steam data used while assembling a detail page. */
data class SteamAppDetails(
    val identity: GameIdentity,
    val name: String,
    val shortDescription: String,
    val detailedDescription: String,
    val headerImage: String,
    val capsuleImage: String,
    val developers: List<String>,
    val publishers: List<String>,
    val releaseDate: String,
    val genres: List<String>,
    val categories: List<String>,
    val price: PriceOverview?,
    val isFree: Boolean,
    val requirementsMinimum: String,
    val requirementsRecommended: String,
    val screenshots: List<Screenshot>,
    val trailers: List<Trailer>,
    val storeUrl: String,
) {
    val hasContent: Boolean get() = name.isNotBlank() && identity.isUsable
}

/** Everything scraped from a Steam store page, used to fill gaps the JSON API leaves. */
data class SteamPageDetails(
    val shortDescription: String = "",
    val requirementsMinimum: String = "",
    val requirementsRecommended: String = "",
    val screenshots: List<Screenshot> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
) {
    val hasContent: Boolean
        get() = screenshots.isNotEmpty() || trailers.isNotEmpty() ||
            requirementsMinimum.isNotBlank() || requirementsRecommended.isNotBlank() ||
            shortDescription.isNotBlank()
}
