package com.discounthunter.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.discounthunter.android.core.model.SearchResult
import com.discounthunter.android.core.model.WishlistEntry
import com.discounthunter.android.data.repo.SearchRepository
import com.discounthunter.android.data.settings.AppSettings
import com.discounthunter.android.data.settings.SettingsRepository
import com.discounthunter.android.data.wishlist.WishlistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val message: String? = null,
    val currencySymbol: String = "$",
    val recentSearches: List<String> = emptyList(),
    /**
     * The saved games, published so the row stars recompose when one changes.
     *
     * The star's state itself is *not* stored here — it is asked of `WishlistRepository.matches`
     * during composition, so the matching rules stay in one place. All this list has to do is make
     * the state change identity when the wishlist does, which is what re-runs that composition.
     * Without it the state was identical before and after a star was tapped, so nothing recomposed
     * and the outline star stayed an outline star until the user left the tab and came back.
     */
    val wishlistEntries: List<WishlistEntry> = emptyList(),
) {
    val showEmptyResult: Boolean get() = hasSearched && results.isEmpty() && !isSearching
}

/**
 * Cross-store search.
 *
 * ## Not racing yourself
 *
 * Typing debounces, and each keystroke cancels the previous search *and* bumps a generation
 * counter. A response may only publish itself while its generation is still current, so a slow
 * request for `"elden"` can never land under the heading for `"elden ring"`.
 *
 * Every result also carries exactly one identity (see `SearchRepository.merge`), so opening a row
 * can never be ambiguous about which game it means.
 */
class SearchViewModel(
    private val repository: SearchRepository,
    private val settingsRepository: SettingsRepository,
    private val wishlistRepository: WishlistRepository,
) : ViewModel() {

    private companion object {
        const val DEBOUNCE_MILLIS = 350L
        const val MIN_QUERY_LENGTH = 2
        const val STATE_TIMEOUT_MILLIS = 5_000L
    }

    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<SearchResult>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val hasSearched = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private var searchJob: Job? = null
    private var generation = 0
    private var settings: AppSettings = AppSettings()

    val uiState: StateFlow<SearchUiState> = combine(
        combine(query, results, searching, hasSearched, message) { text, rows, busy, searched, note ->
            Query(text, rows, busy, searched, note)
        },
        settingsRepository.settings,
        // Tapping a star must fill it in on the same frame. The only reason it did not is that
        // nothing this state depends on had changed, so Compose had no reason to recompose.
        wishlistRepository.entries,
    ) { queryState, currentSettings, saved ->
        SearchUiState(
            query = queryState.text,
            results = SavedFirstRank.rank(queryState.rows, saved, wishlistRepository::matches),
            isSearching = queryState.busy,
            hasSearched = queryState.searched,
            message = queryState.note,
            currencySymbol = currentSettings.currencySymbol,
            recentSearches = currentSettings.recentSearches,
            wishlistEntries = saved,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
        initialValue = SearchUiState(),
    )

    private data class Query(
        val text: String,
        val rows: List<SearchResult>,
        val busy: Boolean,
        val searched: Boolean,
        val note: String?,
    )

    init {
        settingsRepository.settings
            .onEach { settings = it }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------ actions

    /** Called on every keystroke; debounced so typing stays responsive. */
    fun onQueryChange(text: String) {
        query.value = text
        if (text.trim().length < MIN_QUERY_LENGTH) {
            reset()
            return
        }
        scheduleSearch(debounce = true)
    }

    /** Called from the keyboard's search key or a recent-search chip: search immediately. */
    fun searchNow() {
        if (query.value.trim().length < MIN_QUERY_LENGTH) return
        scheduleSearch(debounce = false)
    }

    fun clear() {
        query.value = ""
        reset()
    }

    fun toggleWishlist(result: SearchResult) {
        viewModelScope.launch {
            wishlistRepository.toggle(WishlistEntry.fromSearch(result, System.currentTimeMillis()))
        }
    }

    fun isSaved(result: SearchResult): Boolean =
        wishlistRepository.matches(result.wishlistKey, result.titleKey)

    fun entryFor(result: SearchResult): WishlistEntry =
        WishlistEntry.fromSearch(result, System.currentTimeMillis())

    // ------------------------------------------------------------------ loading

    private fun reset() {
        generation++
        searchJob?.cancel()
        searching.value = false
        results.value = emptyList()
        hasSearched.value = false
        message.value = null
    }

    private fun scheduleSearch(debounce: Boolean) {
        searchJob?.cancel()
        val myGeneration = ++generation

        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MILLIS)

            val term = query.value.trim()
            // A newer keystroke arrived while we were waiting.
            if (myGeneration != generation || term.length < MIN_QUERY_LENGTH) return@launch

            searching.value = true
            hasSearched.value = true
            message.value = "Searching Steam, GOG and Epic…"

            val response = repository.search(term, settings.steamCountry)

            // Only the newest search may publish. This is what stops one game's results from
            // appearing under another game's heading.
            if (myGeneration != generation) return@launch

            results.value = response.results
            searching.value = false
            message.value = when {
                response.results.isNotEmpty() && response.failures.isEmpty() -> null
                response.results.isNotEmpty() -> response.failures.joinToString(" ")
                response.failures.isNotEmpty() -> response.failures.joinToString(" ")
                else -> "Nothing found for “$term”. Check the spelling or try a shorter title."
            }

            viewModelScope.launch { settingsRepository.rememberSearch(term) }
        }
    }
}
