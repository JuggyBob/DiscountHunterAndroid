package com.discounthunter.android.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.discounthunter.android.DiscountHunterApplication
import com.discounthunter.android.di.AppContainer
import com.discounthunter.android.ui.deals.DealsViewModel
import com.discounthunter.android.ui.detail.DetailViewModel
import com.discounthunter.android.ui.search.SearchViewModel
import com.discounthunter.android.ui.settings.SettingsViewModel
import com.discounthunter.android.ui.wishlist.WishlistViewModel

/**
 * View-model factories.
 *
 * One factory builds every screen's view model from the shared [AppContainer], resolved through
 * `CreationExtras`. This keeps the wiring explicit and compile-time checked without pulling in a
 * DI framework — the object graph is small enough that the extra machinery would cost more than it
 * saves.
 */
object ViewModelFactories {

    val deals: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            DealsViewModel(
                container().deals,
                container().settings,
                container().wishlist,
                container().fxRates,
            )
        }
    }

    val search: ViewModelProvider.Factory = viewModelFactory {
        initializer { SearchViewModel(container().search, container().settings, container().wishlist) }
    }

    val detail: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            DetailViewModel(
                container().gameDetail,
                container().settings,
                container().wishlist,
                container().snapshots,
                // The shared instance, not a fresh one: three screens converting the same price
                // with three different rate fetches would eventually disagree with each other.
                container().fxRates,
            )
        }
    }

    val wishlist: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            WishlistViewModel(
                container().wishlist,
                container().settings,
                container().fxRates,
            )
        }
    }

    val settings: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val container = container()
            SettingsViewModel(
                settingsRepository = container.settings,
                container = container,
                detectStoreApp = container.storeLinks::isStoreAppAvailable,
            )
        }
    }

    fun CreationExtras.container(): AppContainer =
        (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiscountHunterApplication)
            .container
}
