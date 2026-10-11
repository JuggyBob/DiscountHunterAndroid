package com.discounthunter.android.core.model

/**
 * The storefronts Discount Hunter reads. [id] values are the same ones the desktop build used,
 * so the meaning of a stored preference never changes between the two apps.
 */
enum class StoreId(val id: Int, val displayName: String, val shortName: String) {
    STEAM(1, "Steam", "Steam"),
    EPIC(25, "Epic Games Store", "Epic"),
    GOG(7, "GOG", "GOG");

    companion object {
        /** The stores offered in the filter UI, in display order. */
        val MAJOR: List<StoreId> = listOf(STEAM, EPIC, GOG)

        fun fromId(id: Int): StoreId? = entries.firstOrNull { it.id == id }
    }
}
