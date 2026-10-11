package com.discounthunter.android.data.repo

import com.discounthunter.android.core.model.EpicGiveaway
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.SearchResult
import com.discounthunter.android.core.text.TitleMatcher
import com.discounthunter.android.data.gog.GogProduct
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.data.net.valueOrNull
import com.discounthunter.android.data.source.EpicSource
import com.discounthunter.android.data.source.GogSource
import com.discounthunter.android.data.source.SteamSource
import com.discounthunter.android.data.steam.SteamSearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/** One search's outcome: the merged rows, plus what each store said about itself. */
data class SearchResponse(
    val results: List<SearchResult>,
    /** One entry per store that failed. Empty when every store answered. */
    val failures: List<String>,
)

/**
 * Cross-store search: Steam, GOG and the Epic freebies feed are queried at once and merged.
 *
 * ## Why merging is stricter than the desktop build
 *
 * The desktop build keyed results by *normalised title*, so two different games that shared a title
 * collapsed into a single row and their prices were then mixed together. Here a Steam hit and a GOG
 * hit are merged only when [TitleMatcher.isSameGame] proves they are the same product; otherwise
 * they stay separate rows. Duplicate titles and similarly named games therefore remain distinct —
 * which is also what makes opening a result safe, because every row carries exactly one identity.
 */
class SearchRepository(
    private val steam: SteamSource,
    private val gog: GogSource,
    private val epic: EpicSource,
) {

    /**
     * Runs one cross-store search.
     *
     * Each store is queried independently so one outage cannot empty the page. `supervisorScope`
     * keeps a failure in one branch from cancelling the others.
     *
     * Failures are returned rather than swallowed. The desktop build reported "no results" for a
     * store that had actually thrown, which is indistinguishable from a genuinely empty search and
     * sent people looking for typos that did not exist. A [SearchResponse.failures] entry is what
     * lets the UI say *why* a page is short.
     */
    suspend fun search(query: String, country: String): SearchResponse {
        val term = query.trim()
        if (term.length < 2) return SearchResponse(emptyList(), emptyList())

        val failures = mutableListOf<String>()

        return supervisorScope {
            val steamDeferred = async { attempt { steam.search(term, country) } }
            val gogDeferred = async { attempt { gog.search(term) } }
            val epicDeferred = async { attempt { epic.fetchFreeGames() } }

            val steamOutcome = steamDeferred.await()
            val gogOutcome = gogDeferred.await()
            val epicOutcome = epicDeferred.await()

            // One reporting point for both failure shapes — a store that answered with a failure and
            // a store that threw — so each store can be named at most once.
            report(steamOutcome, failures, "Steam")
            report(gogOutcome, failures, "GOG")
            report(epicOutcome, failures, "Epic")

            SearchResponse(
                results = merge(
                    term = term,
                    steamHits = steamOutcome.valueOrNull().orEmpty(),
                    gogProducts = gogOutcome.valueOrNull().orEmpty(),
                    giveaways = epicOutcome.valueOrNull().orEmpty(),
                ),
                failures = failures.toList(),
            )
        }
    }

    /**
     * Runs one store's call, turning anything thrown into an [Outcome.Failure].
     *
     * Cancellation is deliberately rethrown: a superseded search must unwind rather than be recorded
     * as a store outage. Everything else is normalised, because a store that throws must not be able
     * to cancel the other two branches or abort the whole search.
     */
    private suspend fun <T> attempt(block: suspend () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Outcome.Failure(
            Outcome.Reason.NETWORK,
            error.message ?: error.javaClass.simpleName.ifBlank { "search failed" },
        )
    }

    /** Records a store that failed, whether it answered with a failure or threw. */
    private fun report(outcome: Outcome<*>, failures: MutableList<String>, store: String) {
        if (outcome is Outcome.Failure) {
            failures += "$store search failed: ${outcome.message}"
        }
    }

    /**
     * Builds the result list.
     *
     * Steam hits become rows first — they carry the richest identity and price data — then GOG and
     * Epic hits either merge into a provably matching Steam row or become rows of their own.
     */
    internal fun merge(
        term: String,
        steamHits: List<SteamSearchHit>,
        gogProducts: List<GogProduct>,
        giveaways: List<EpicGiveaway>,
    ): List<SearchResult> {
        val rows = mutableListOf<SearchResult>()
        // Row positions that came from Steam, so a GOG/Epic hit can find its counterpart.
        val steamRows = mutableListOf<Int>()

        for (hit in steamHits) {
            steamRows += rows.size
            rows += SearchResult(
                title = hit.name,
                thumbUrl = hit.tinyImage.ifBlank { hit.capsuleImage },
                steamAppId = hit.identity.id.toIntOrNull(),
                steamPrice = hit.price,
            )
        }

        for (product in gogProducts) {
            val match = bestSteamRow(steamRows, rows, product.title)
            if (match != null) {
                val existing = rows[match]
                rows[match] = existing.copy(
                    gogSlug = product.identity.id,
                    gogPrice = product.price,
                    thumbUrl = existing.thumbUrl.ifBlank { product.thumbUrl },
                )
            } else {
                rows += SearchResult(
                    title = product.title,
                    thumbUrl = product.thumbUrl,
                    gogSlug = product.identity.id,
                    gogPrice = product.price,
                    // Flagged when the catalogue returned something the matcher could not vouch for;
                    // such rows are dropped unless nothing better survives.
                    isApproximate = !TitleMatcher.isSameGame(term, product.title),
                )
            }
        }

        for (giveaway in giveaways) {
            val identity: GameIdentity = giveaway.identity ?: continue
            val match = bestSteamRow(steamRows, rows, giveaway.title)
            if (match != null) {
                val existing = rows[match]
                rows[match] = existing.copy(
                    epicFree = true,
                    epicSlug = identity.id,
                    epicUrl = giveaway.url,
                )
            } else if (TitleMatcher.isSameGame(term, giveaway.title)) {
                // A brand-new row is only added for a title that genuinely matches the query: the
                // feed is entirely free games, so a loose match would push unrelated giveaways into
                // an unrelated search.
                rows += SearchResult(
                    title = giveaway.title,
                    thumbUrl = giveaway.thumbUrl,
                    epicSlug = identity.id,
                    epicUrl = giveaway.url,
                    epicFree = true,
                )
            }
        }

        // GOG's catalogue index answers multi-word queries with generic bestsellers, so keep only
        // the rows that look like the query. If that rejects everything, fall back to the raw union
        // so a valid but oddly named title stays reachable.
        val relevant = rows.filterNot { it.isApproximate }
        val candidates = relevant.ifEmpty { rows }

        return TitleMatcher.rankBy(term, candidates) { it.title }
            .sortedWith(
                // Rows with an identity and a price first, then free, then cheapest.
                compareBy<SearchResult> { if (it.hasAnyStore && it.hasPrice) 0 else 1 }
                    .thenBy { if (it.isFree) 0 else 1 }
                    .thenBy { it.cheapestPrice }
                    .thenBy { it.title },
            )
            // One row per game. Two rows sharing a key is not a cosmetic problem: the search list is
            // a `LazyColumn` keyed on the identity, and Compose throws
            // `IllegalArgumentException: Key … was already used` during layout, which kills the
            // screen. Steam's search can repeat an app id and two giveaways can share a title, so
            // the invariant is enforced here rather than assumed of the stores.
            .distinctBy { it.identity?.key ?: "title:${it.titleKey}" }
            .take(MAX_RESULTS)
    }

    /**
     * The Steam row that best represents [title], or null when none is provably the same product.
     *
     * The *best* candidate wins rather than the first one that matches. Several Steam results can
     * legitimately name the same game ("Elden Ring", "Elden Ring Remastered"), and merging a GOG or
     * Epic price into whichever happened to come first would attach it to the wrong listing — the
     * same class of defect as the screenshot bug, one level up.
     */
    private fun bestSteamRow(
        steamRows: List<Int>,
        rows: List<SearchResult>,
        title: String,
    ): Int? = steamRows
        .filter { TitleMatcher.isSameGame(rows[it].title, title) }
        .map { it to TitleMatcher.similarity(rows[it].title, title) }
        // Ties break on the row's identity so the choice is deterministic across recompositions.
        .maxWithOrNull(
            compareBy<Pair<Int, Double>> { it.second }
                .thenBy { rows[it.first].identityKey }
                .thenBy { -it.first },
        )
        ?.first

    /** A stable identity string used only for deterministic tie-breaking. */
    private val SearchResult.identityKey: String
        get() = identity?.key ?: "title:$titleKey"

    private companion object {
        const val MAX_RESULTS = 25
    }
}
