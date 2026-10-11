package com.discounthunter.android.data.source

import com.discounthunter.android.core.model.EpicGiveaway
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.data.epic.EpicService
import com.discounthunter.android.data.gog.GogGameDetails
import com.discounthunter.android.data.gog.GogProduct
import com.discounthunter.android.data.gog.GogService
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.steam.SteamAppDetails
import com.discounthunter.android.data.steam.SteamPageDetails
import com.discounthunter.android.data.steam.SteamSearchHit
import com.discounthunter.android.data.steam.SteamService

/**
 * What the repositories need from Steam.
 *
 * The repositories depend on these three interfaces rather than on the concrete services, which
 * makes one thing possible that matters a great deal: the media-ownership guarantee can be tested
 * against fakes. A test can hand the detail assembler a Steam search that returns the *wrong* game
 * and assert that no screenshot from it reaches the page — something that would be impossible to
 * verify against the live store, and precisely the defect that had to be fixed.
 *
 * The implementations stay trivial; the interfaces carry no extra behaviour.
 */
interface SteamSource {
    suspend fun fetchDeals(limit: Int, country: String): Outcome<List<com.discounthunter.android.core.model.Deal>>
    suspend fun search(term: String, country: String): Outcome<List<SteamSearchHit>>
    suspend fun fetchAppDetails(identity: GameIdentity, country: String): Outcome<SteamAppDetails?>
    suspend fun fetchPageDetails(identity: GameIdentity): SteamPageDetails
    fun storeUrl(identity: GameIdentity): String
}

/** What the repositories need from GOG. */
interface GogSource {
    suspend fun fetchDeals(limit: Int): Outcome<List<GogProduct>>
    suspend fun search(term: String, limit: Int = 10): Outcome<List<GogProduct>>
    suspend fun probePageTitle(slug: String): String
    suspend fun fetchGameDetails(identity: GameIdentity): GogGameDetails
    fun pageUrl(identity: GameIdentity): String
    fun toDeal(product: GogProduct): com.discounthunter.android.core.model.Deal
}

/** What the repositories need from Epic. */
interface EpicSource {
    suspend fun fetchFreeGames(country: String = "US"): Outcome<List<EpicGiveaway>>
    fun pageUrl(identity: GameIdentity): String
}

class SteamSourceAdapter(private val service: SteamService) : SteamSource {
    override suspend fun fetchDeals(limit: Int, country: String) = service.fetchDeals(limit, country)
    override suspend fun search(term: String, country: String) = service.search(term, country)
    override suspend fun fetchAppDetails(identity: GameIdentity, country: String) =
        service.fetchAppDetails(identity, country)

    override suspend fun fetchPageDetails(identity: GameIdentity) = service.fetchPageDetails(identity)
    override fun storeUrl(identity: GameIdentity) =
        com.discounthunter.android.data.steam.SteamPageParser.storeUrl(identity)
}

class GogSourceAdapter(private val service: GogService) : GogSource {
    override suspend fun fetchDeals(limit: Int) = service.fetchDeals(limit)
    override suspend fun search(term: String, limit: Int) = service.search(term, limit)
    override suspend fun probePageTitle(slug: String) = service.probePageTitle(slug)
    override suspend fun fetchGameDetails(identity: GameIdentity) = service.fetchGameDetails(identity)
    override fun pageUrl(identity: GameIdentity) = service.pageUrl(identity)
    override fun toDeal(product: GogProduct) = service.toDeal(product)
}

class EpicSourceAdapter(private val service: EpicService) : EpicSource {
    override suspend fun fetchFreeGames(country: String) = service.fetchFreeGames(country)
    override fun pageUrl(identity: GameIdentity) = service.pageUrl(identity)
}
