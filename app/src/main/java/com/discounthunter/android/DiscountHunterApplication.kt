package com.discounthunter.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.discounthunter.android.data.image.DiscountHunterImageLoaderFactory
import com.discounthunter.android.di.AppContainer
import com.discounthunter.android.diagnostics.AppLog

/**
 * The application.
 *
 * Owns the single [AppContainer] and lets it implement [ImageLoaderFactory] so every image in the
 * app — list thumbnails, gallery tiles, the full-screen viewer — shares one disk cache, one memory
 * cache and one HTTP connection pool.
 */
class DiscountHunterApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("FATAL on ${thread.name}: ${error.javaClass.simpleName}", error)
            previous?.uncaughtException(thread, error)
        }

        container = AppContainer(this)
        container.start()
        // A crash report that says "the app closed" is not a report. Recording the last known
        // state here means an exported log opens with the state the app was in when it died.
        AppLog.i("Started ${BuildConfig.VERSION_NAME} on Android ${android.os.Build.VERSION.SDK_INT}")
    }

    override fun newImageLoader(): ImageLoader =
        DiscountHunterImageLoaderFactory(container.imageClient, this).newImageLoader()
}
