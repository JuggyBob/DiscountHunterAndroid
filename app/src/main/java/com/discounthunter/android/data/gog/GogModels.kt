package com.discounthunter.android.data.gog

import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.Screenshot
import com.discounthunter.android.core.model.Trailer
import com.discounthunter.android.core.model.TrailerKind

/** One product from the GOG catalogue. */
data class GogProduct(
    val identity: GameIdentity,
    val title: String,
    val thumbUrl: String,
    val url: String,
    val price: Double,
    val basePrice: Double,
    val discountPercent: Double,
    /**
     * The currency GOG actually quoted this in, taken from its own inalMoney.currency field.
     *
     * The catalogue endpoint ignores a currency query parameter and keeps answering in US
     * dollars, so this is the only way to know the number is not already in the user's chosen
     * currency. Blank when the payload did not say.
     */
    val currencyCode: String = "",
) {
    val hasDiscount: Boolean get() = price <= 0.0 || discountPercent >= 1.0
}

/** Scraped GOG game-page content. */
data class GogGameDetails(
    val descriptionHtml: String = "",
    val overviewHtml: String = "",
    val requirementsMinimum: String = "",
    val requirementsRecommended: String = "",
    val screenshots: List<Screenshot> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val features: List<String> = emptyList(),
) {
    val hasContent: Boolean
        get() = descriptionHtml.isNotBlank() || overviewHtml.isNotBlank() ||
            requirementsMinimum.isNotBlank() || screenshots.isNotEmpty() || trailers.isNotEmpty()
}
