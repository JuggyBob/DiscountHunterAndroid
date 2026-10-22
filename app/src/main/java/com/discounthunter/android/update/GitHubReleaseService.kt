package com.discounthunter.android.update

import com.discounthunter.android.core.text.Json
import com.discounthunter.android.data.cache.CacheKeys
import com.discounthunter.android.data.cache.PayloadCache
import com.discounthunter.android.data.net.Outcome
import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the official release metadata from GitHub.
 *
 * ## The repository is fixed, and that is deliberate
 *
 * The updater points at the project's own repository, named in [REPOSITORY]. It is not configured
 * at runtime and cannot be redirected, because a release check that could be pointed somewhere else
 * would be a way to serve an arbitrary "update" to a user. The value is asserted against the
 * repository the build came from in the tests, so a rename cannot silently leave the app checking
 * a repository that does not exist.
 *
 * ## No credentials
 *
 * The Releases API is public and unauthenticated for a public repository, and that is how this
 * calls it. A personal access token would work too, but embedding one in an application would put
 * it in every user's device, where it can be extracted. The requests are therefore rate limited
 * like any other anonymous client, which is why the result is cached.
 */
class GitHubReleaseService(
    private val network: com.discounthunter.android.data.net.HttpClient,
    private val cache: PayloadCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Fetches the latest published stable release.
     *
     * The cached copy is used when the network is unavailable, which is what lets the About screen
     * still show something useful offline. The cache entry records when it was written so the
     * screen can say how old the information is.
     */
    suspend fun latest(): Outcome<ReleaseInfo> {
        val key = CacheKeys.githubLatestRelease(OWNER, REPOSITORY)
        val cached = cache.read(key, CACHE_TTL_MILLIS)
        if (cached != null) {
            parse(cached)?.let { return Outcome.Success(it) }
        }

        return when (val result = network.getJson(API_URL)) {
            is Outcome.Failure -> {
                // A cached copy is better than an error, but it must be labelled as a cached copy.
                val fallback = cached?.let { parse(it) }
                if (fallback != null) {
                    AppLog.i("Updates: the network failed, using the cached release metadata")
                    Outcome.Success(fallback)
                } else {
                    AppLog.w("Updates: the release check failed and nothing was cached")
                    result
                }
            }
            is Outcome.Success -> {
                val body = result.value.toString()
                val parsed = parse(body)
                if (parsed == null) {
                    Outcome.Failure(
                        Outcome.Reason.MALFORMED,
                        "The release information could not be read.",
                    )
                } else {
                    cache.write(key, body)
                    Outcome.Success(parsed)
                }
            }
        }
    }

    /**
     * Parses the JSON body of the latest-release endpoint.
     *
     * Null when the body is not the shape expected, so the caller can tell "no release" from
     * "something we do not understand".
     */
    fun parse(body: String): ReleaseInfo? {
        val root = Json.parse(body) as? kotlinx.serialization.json.JsonObject ?: return null

        val tag = Json.str(root, "tag_name")
        if (tag.isBlank()) return null

        val assets = Json.arr(root, "assets").orEmpty().mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = Json.str(obj, "name")
            val url = Json.str(obj, "browser_download_url")
            if (name.isBlank() || url.isBlank()) return@mapNotNull null
            ReleaseAsset(
                name = name,
                downloadUrl = url,
                sizeBytes = Json.dbl(obj, "size").toLong(),
                contentType = Json.str(obj, "content_type"),
                digest = Json.str(obj, "digest").ifBlank { null },
            )
        }

        return ReleaseInfo(
            name = Json.str(root, "name").ifBlank { tag },
            tagName = tag,
            version = AppVersion.parse(tag),
            body = Json.str(root, "body"),
            publishedAt = Json.str(root, "published_at"),
            htmlUrl = Json.str(root, "html_url"),
            assets = assets,
            prerelease = Json.bool(root, "prerelease"),
            draft = Json.bool(root, "draft"),
        )
    }

    companion object {
        /** The owner and name of this project's repository, as verified against the git remote. */
        const val OWNER = "JuggyBob"
        const val REPOSITORY = "DiscountHunterAndroid"

        /**
         * The public latest-release endpoint.
         *
         * `/releases/latest` returns the most recent *published, non-draft, non-prerelease*
         * release, which is exactly the stable release the updater should offer.
         */
        const val API_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"

        /**
         * How long release metadata is considered fresh.
         *
         * Long, because the latest release does not change often and the endpoint is rate limited
         * for anonymous callers.
         */
        const val CACHE_TTL_MILLIS = 30 * 60 * 1000L
    }
}
