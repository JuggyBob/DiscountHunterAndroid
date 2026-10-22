package com.discounthunter.android.update

/**
 * One file published with a GitHub release.
 *
 * [digest] is GitHub's own SHA-256 of the file, in the form `sha256:<hex>`. It is present on
 * recent releases and absent on older ones, so it is optional: the downloader verifies the digest
 * when there is one and falls back to package inspection when there is not.
 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String = "",
    val digest: String? = null,
) {
    /** The SHA-256 hex, without the `sha256:` prefix. Null when GitHub published no digest. */
    val sha256: String?
        get() = digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)
}

/**
 * A published release of the application, as described by the GitHub Releases API.
 */
data class ReleaseInfo(
    /** The release's display name, e.g. "Discount Hunter 1.1.1". */
    val name: String,
    /** The tag, e.g. "v1.1.1". */
    val tagName: String,
    /** The version parsed out of the tag, when it could be understood. */
    val version: AppVersion?,
    /** The release notes, in Markdown. */
    val body: String,
    /** When the release was published. */
    val publishedAt: String,
    /** The human-facing page for this release. */
    val htmlUrl: String,
    /** Every file published with the release. */
    val assets: List<ReleaseAsset>,
    /** True when GitHub marked the release as a prerelease. */
    val prerelease: Boolean = false,
    /** True when the release is still a draft. */
    val draft: Boolean = false,
) {
    /** The version as a string, preferring the parsed one. */
    val versionLabel: String
        get() = version?.toString() ?: tagName.removePrefix("v").removePrefix("V")

    /**
     * The installable asset for this release, or null when there is not an unambiguous one.
     *
     * ## Why it is not simply "the first .apk"
     *
     * A release can carry several APKs — a debug build, a per-architecture build, a source archive
     * renamed — and picking the first one that ends in `.apk` can hand the user a file that is not
     * this application, or is the wrong build of it. The selection is therefore by *this project's*
     * naming convention, and the package identity is checked afterwards by inspecting the archive.
     */
    fun installableAsset(): ReleaseAsset? = ReleaseAssetSelector.select(this)
}

/**
 * Chooses which published file, if any, is the installable update.
 *
 * Kept separate from [ReleaseInfo] so the rule can be tested against a fixed set of candidate
 * assets without a network call.
 */
object ReleaseAssetSelector {

    /**
     * The naming convention this project publishes under: `discount-hunter-<version>.apk`.
     *
     * A file that does not match is not this project's release build, whatever else it is.
     */
    private val RELEASE_NAME = Regex("^discount-hunter-[0-9][0-9A-Za-z.\\-]*\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * Names that are definitely not the release build, even if they somehow match the pattern.
     *
     * The project's debug variant installs under a different application id, so installing it over
     * the release build is not an update at all.
     */
    private val EXCLUDED = listOf("debug", "unsigned", "source", "sources", "bundle", "aab")

    /**
     * Returns the one asset that should be installed, or null when there is no unambiguous choice.
     *
     * Null is a legitimate answer and the caller must handle it: a release that exists but carries
     * no installable package is a situation the user needs to be told about, not a reason to
     * download something arbitrary.
     */
    fun select(release: ReleaseInfo): ReleaseAsset? {
        val candidates = release.assets.filter { asset ->
            asset.isApk &&
                RELEASE_NAME.matches(asset.name) &&
                EXCLUDED.none { marker -> asset.name.contains(marker, ignoreCase = true) }
        }
        // Exactly one candidate, or none. Two candidates means the release publishes more than one
        // release build and there is no principled way to choose between them.
        return candidates.singleOrNull()
    }
}
