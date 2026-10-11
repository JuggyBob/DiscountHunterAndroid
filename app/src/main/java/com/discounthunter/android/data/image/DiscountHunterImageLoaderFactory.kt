package com.discounthunter.android.data.image

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient

/**
 * The app's single Coil [ImageLoader].
 *
 * Decisions that matter on a phone:
 *
 *  * **Bounded memory.** The memory cache takes a quarter of the heap and downsamples to the
 *    requested size, so a long deal list never holds hundreds of full-resolution bitmaps.
 *  * **Bounded disk.** 128 MB of artwork, kept in `cacheDir` so Android can reclaim it under
 *    pressure instead of growing without limit.
 *  * **Cache key = URL.** Coil keys every entry by the request's data (the image URL), so a
 *    recycled list slot can only ever show the image belonging to *its own* URL — a previous row's
 *    artwork cannot leak into a new one. This is the image-side counterpart of the identity rules
 *    used for media ownership.
 *  * **Shared connection pool.** The client is derived from the store one, so artwork reuses the
 *    same connections and DNS cache instead of opening a second set of sockets, while keeping its
 *    own (much more generous) timeouts — see `AppContainer.imageClient`.
 */
class DiscountHunterImageLoaderFactory(
    private val httpClient: OkHttpClient,
    private val application: Application,
) : ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(application)
        .okHttpClient { httpClient }
        .memoryCache {
            MemoryCache.Builder(application)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(application.cacheDir.resolve("artwork"))
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .respectCacheHeaders(false)
        .crossfade(180)
        .build()
}
