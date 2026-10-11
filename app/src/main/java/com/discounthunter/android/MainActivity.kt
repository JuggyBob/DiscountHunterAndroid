package com.discounthunter.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.compose.animation.slideInVertically
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.GameReference
import com.discounthunter.android.core.model.SearchResult
import com.discounthunter.android.core.model.StoreId
import com.discounthunter.android.core.model.StoreOffer
import com.discounthunter.android.data.settings.ThemeMode
import com.discounthunter.android.data.store.LinkResult
import com.discounthunter.android.data.store.StoreLinks
import com.discounthunter.android.di.AppContainer
import com.discounthunter.android.ui.ViewModelFactories
import com.discounthunter.android.ui.deals.DealsScreen
import com.discounthunter.android.ui.deals.DealsViewModel
import com.discounthunter.android.ui.detail.DetailScreen
import com.discounthunter.android.ui.detail.DetailViewModel
import com.discounthunter.android.ui.navigation.Destinations
import com.discounthunter.android.ui.search.SearchScreen
import com.discounthunter.android.ui.search.SearchViewModel
import com.discounthunter.android.ui.settings.SettingsScreen
import com.discounthunter.android.ui.settings.SettingsViewModel
import com.discounthunter.android.ui.theme.DiscountHunterTheme
import com.discounthunter.android.ui.theme.Motion
import com.discounthunter.android.ui.wishlist.WishlistScreen
import com.discounthunter.android.ui.wishlist.WishlistViewModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.produceState
import com.discounthunter.android.ui.intro.IntroScreen

/**
 * The single activity.
 *
 * Navigation is a bottom bar for the four top-level destinations plus a detail screen pushed on top
 * of them. The bottom bar is hidden while the detail screen is open so the game gets the full
 * height — which is where the screenshots and trailers live.
 */
class MainActivity : ComponentActivity() {

    /**
     * The link the activity was started with, or the most recent one it was handed.
     *
     * `launchMode = "singleTask"` means a second deep link — tapping a Steam URL while the app is
     * already open, the normal case for a user who has it installed — is delivered to
     * [onNewIntent] rather than starting a new activity. Reading `intent?.data` only in `onCreate`
     * therefore dropped it on the floor, and the link appeared to do nothing. Writing the new intent
     * back means the same `LaunchedEffect` that handles a cold start handles a warm one.
     */
    private val pendingDeepLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingDeepLink.value = intent?.data

        val container = (application as DiscountHunterApplication).container

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = null)

            DiscountHunterTheme(themeMode = settings?.themeMode ?: ThemeMode.SYSTEM) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DiscountHunterApp(
                        container = container,
                        storeLinks = container.storeLinks,
                        deepLink = pendingDeepLink,
                        onDeepLinkHandled = { pendingDeepLink.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in step, so anything else reading it sees the newest link.
        setIntent(intent)
        if (intent.data != null) pendingDeepLink.value = intent.data
    }
}

/** One bottom-bar entry. */
private data class TopLevel(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val TOP_LEVEL = listOf(
    TopLevel(
        Destinations.DEALS,
        R.string.nav_deals,
        Icons.Filled.Today,
        Icons.Outlined.Today,
    ),
    TopLevel(
        Destinations.SEARCH,
        R.string.nav_search,
        Icons.Filled.Search,
        Icons.Outlined.Search,
    ),
    TopLevel(
        Destinations.WISHLIST,
        R.string.nav_wishlist,
        Icons.Filled.Favorite,
        Icons.Outlined.FavoriteBorder,
    ),
    TopLevel(
        Destinations.SETTINGS,
        R.string.nav_settings,
        Icons.Filled.Settings,
        Icons.Outlined.Settings,
    ),
)

@Composable
private fun DiscountHunterApp(
    container: AppContainer,
    storeLinks: StoreLinks,
    deepLink: StateFlow<Uri?>,
    onDeepLinkHandled: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // The first-run tour, gated on a stored preference rather than a flag in memory — which is what
    // makes it come back exactly when the user deletes all app data, as the Settings confirmation
    // promises.
    val showIntro by produceState(initialValue = true) {
        container.settings.hasSeenIntro.collect { value = !it }
    }

    // One `AnimatedContent` owns both directions, so the tour cannot slide in one way and out
    // another.
    AnimatedContent(
        targetState = showIntro,
        transitionSpec = {
            if (targetState) {
                (slideInVertically(Motion.sheet()) { it / 3 } + fadeIn(Motion.sheet())) togetherWith
                    fadeOut(Motion.fadeOut())
            } else {
                fadeIn(Motion.fade()) togetherWith
                    (slideOutVertically(Motion.settle()) { it / 4 } + fadeOut(Motion.fadeOut()))
            }
        },
        label = "intro",
    ) { intro ->
        if (intro) {
            IntroScreen(
                onFinish = { scope.launch { container.settings.setIntroSeen() } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MainShell(
                container = container,
                storeLinks = storeLinks,
                deepLink = deepLink,
                onDeepLinkHandled = onDeepLinkHandled,
            )
        }
    }
}

/** Everything behind the tour: the four tabs, the deep-link router and the snackbars. */
@Composable
private fun MainShell(
    container: AppContainer,
    storeLinks: StoreLinks,
    deepLink: StateFlow<Uri?>,
    onDeepLinkHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Destinations.topLevel

    val dealsViewModel: DealsViewModel = viewModel(factory = ViewModelFactories.deals)
    val searchViewModel: SearchViewModel = viewModel(factory = ViewModelFactories.search)
    val wishlistViewModel: WishlistViewModel = viewModel(factory = ViewModelFactories.wishlist)
    val settingsViewModel: SettingsViewModel = viewModel(factory = ViewModelFactories.settings)
    val dealsState by dealsViewModel.uiState.collectAsStateWithLifecycle()

    // Feed the wishlist the live deals so its "on sale" flags stay in step with the front page.
    LaunchedEffect(dealsState.deals) {
        wishlistViewModel.updateLiveDeals(dealsState.deals)
    }

    // Deep link support: a store URL, or discounthunter://steam/1245620.
    //
    // Keyed on the link's *value*, not on the flow. `LaunchedEffect` compares its keys with equals,
    // and a `StateFlow` instance equals itself forever, so keying on the flow runs this exactly once
    // — which would quietly drop every link that arrives after the first.
    val deepLinkUri by deepLink.collectAsStateWithLifecycle()

    LaunchedEffect(deepLinkUri) {
        val uri = deepLinkUri ?: return@LaunchedEffect
        val reference = referenceFromUri(uri)
        if (reference != null) {
            navController.navigate(Destinations.detailRoute(reference.identity, reference.title))
        }
        onDeepLinkHandled()
    }

    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    fun openGame(reference: GameReference) {
        navController.navigate(
            Destinations.detailRoute(
                identity = reference.identity,
                title = reference.title,
                thumbUrl = reference.thumbUrl,
                originUrl = reference.originUrl,
            ),
        )
    }

    // Snackbars are the right home for one-off confirmations such as "opened in Steam".
    val reportScope = rememberCoroutineScope()
    fun report(message: String) {
        reportScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    fun openUrl(url: String, store: StoreId?) {
        when (val result = storeLinks.openUrl(url, store, settingsState.settings.preferStoreApps)) {
            is LinkResult.OpenedIn -> report("Opened in ${result.storeName}.")
            is LinkResult.Failed -> report(result.message)
        }
    }

    fun getDeal(deal: Deal) {
        val result = storeLinks.openUrl(deal.storeUrl, deal.store, settingsState.settings.preferStoreApps)
        if (result is LinkResult.Failed) {
            report(result.message)
            return
        }
        val opened = result as LinkResult.OpenedIn
        val account = settingsState.settings.accountFor(deal.store)
        when {
            deal.isFree && opened.viaStoreApp && account != null -> report(
                "Opened ${deal.title} in ${deal.store.shortName} — tap Get there to claim it " +
                    "(signed in as $account).",
            )
            deal.isFree && opened.viaStoreApp -> report(
                "Opened ${deal.title} in ${deal.store.shortName}. Tap Get there to claim it for free.",
            )
            else -> report("Opened ${deal.title} in ${opened.storeName}.")
        }
    }

        Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // This scaffold exists only to host the bottom bar and the snackbar; the *screens* inside
        // apply their own insets (each one's TopAppBar consumes the status bar). Leaving the default
        // `contentWindowInsets` here padded the content by the status bar a second time, which put a
        // band of dead space above every screen's title. The bottom bar keeps its own inset handling,
        // because NavigationBar applies the navigation-bar inset itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    TOP_LEVEL.forEach { entry ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == entry.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(entry.route) {
                                    // Single-top with state restoration: switching tabs keeps each
                                    // tab's scroll position and loaded data.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (entry.route == Destinations.WISHLIST && dealsState.wishlistCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge { Text(dealsState.wishlistCount.toString()) }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = if (selected) {
                                                entry.selectedIcon
                                            } else {
                                                entry.unselectedIcon
                                            },
                                            contentDescription = null,
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (selected) {
                                            entry.selectedIcon
                                        } else {
                                            entry.unselectedIcon
                                        },
                                        contentDescription = null,
                                    )
                                }
                            },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Destinations.DEALS,
                modifier = Modifier.fillMaxSize(),
                // Between the four bottom-nav tabs the pages cross-fade rather than slide: they are
                // peers, and sliding implies a hierarchy that does not exist. Opening a game's
                // detail page does slide, because that is genuinely "deeper" — see DETAIL below.
                enterTransition = { fadeIn(Motion.fade()) },
                exitTransition = { fadeOut(Motion.fade()) },
                popEnterTransition = { fadeIn(Motion.fade()) },
                popExitTransition = { fadeOut(Motion.fade()) },
            ) {
                composable(Destinations.DEALS) {
                    DealsScreen(
                        viewModel = dealsViewModel,
                        wishlistRepository = container.wishlist,
                        onOpenGame = ::openGame,
                        onGetDeal = ::getDeal,
                    )
                }

                composable(Destinations.SEARCH) {
                    SearchScreen(
                        viewModel = searchViewModel,
                        wishlistRepository = container.wishlist,
                        onOpenGame = ::openGame,
                        onGetBestOffer = { result: SearchResult ->
                            val reference = result.reference
                            if (reference != null) openGame(reference)
                        },
                    )
                }

                composable(Destinations.WISHLIST) {
                    WishlistScreen(
                        viewModel = wishlistViewModel,
                        onOpenGame = ::openGame,
                        onGetDeal = ::getDeal,
                    )
                }

                composable(Destinations.SETTINGS) {
                    SettingsScreen(viewModel = settingsViewModel)
                }

                composable(
                    route = Destinations.DETAIL_ROUTE,
                    arguments = listOf(
                        navArgument("store") { type = NavType.StringType },
                        navArgument("id") { type = NavType.StringType },
                        navArgument("title") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("thumb") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("origin") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                    // Opening a game *is* going deeper, so it slides up from the row that was
                    // tapped, and going back reverses it exactly. The gentle vertical rise keeps the
                    // page that is still behind readable underneath.
                    enterTransition = {
                        slideInVertically(Motion.screen()) { it / 10 } + fadeIn(Motion.fade())
                    },
                    popEnterTransition = {
                        slideInVertically(Motion.settle()) { -it / 10 } + fadeIn(Motion.fade())
                    },
                    exitTransition = { fadeOut(Motion.fadeOut()) },
                    popExitTransition = { fadeOut(Motion.fadeOut()) },
                ) { entry ->
                    val detailViewModel: DetailViewModel = viewModel(
                        viewModelStoreOwner = entry,
                        factory = ViewModelFactories.detail,
                    )
                    val reference = remember(entry.arguments) {
                        referenceFromArguments(entry.arguments)
                    }
                    LaunchedEffect(reference) {
                        reference?.let { detailViewModel.load(it) }
                    }
                    DetailScreen(
                        viewModel = detailViewModel,
                        onBack = { navController.popBackStack() },
                        onGetOffer = { offer: StoreOffer ->
                            when (val result = storeLinks.openOffer(offer, settingsState.settings.preferStoreApps)) {
                                is LinkResult.Failed -> report(result.message)
                                is LinkResult.OpenedIn -> report("Opened ${offer.store.displayName}.")
                            }
                        },
                        onOpenUrl = ::openUrl,
                    )
                }
            }
        }
    }
}

/** Reconstructs the reference from the route arguments. */
private fun referenceFromArguments(
    arguments: android.os.Bundle?,
): GameReference? {
    val store = arguments?.getString("store") ?: return null
    val id = arguments.getString("id").orEmpty()
    val identity = GameIdentity.parse("$store:$id") ?: return null
    return GameReference(
        identity = identity,
        title = arguments.getString("title").orEmpty(),
        thumbUrl = arguments.getString("thumb").orEmpty(),
        originUrl = arguments.getString("origin").orEmpty(),
    )
}

/**
 * Resolves an incoming deep link to a game.
 *
 * Two shapes are understood:
 *  * the stores' own web addresses, via [GameIdentity.fromUrl];
 *  * the app's own scheme, `discounthunter://<store>/<id>` — where the store is the *host* and the
 *    id is the first path segment, so `discounthunter://steam/1245620` opens the same game as the
 *    Steam URL. Reading the host alone would have produced the id `"steam"` and a page for a game
 *    that does not exist.
 */
private fun referenceFromUri(uri: Uri?): GameReference? {
    val data = uri?.toString() ?: return null

    if (uri.scheme.equals("discounthunter", ignoreCase = true)) {
        val store = uri.host?.trim().orEmpty()
        val id = uri.pathSegments.firstOrNull()?.trim().orEmpty()
        if (store.isEmpty() || id.isEmpty()) return null
        val identity = GameIdentity.parse("$store:$id") ?: return null
        return GameReference(identity = identity, title = "", originUrl = data)
    }

    val identity = GameIdentity.fromUrl(data) ?: return null
    return GameReference(identity = identity, title = "", originUrl = data)
}
