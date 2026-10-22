package com.discounthunter.android.update

/**
 * A parsed application version.
 *
 * ## Why this exists
 *
 * Deciding "is the published release newer than the one installed" is the one piece of the updater
 * that must not be wrong. Comparing version strings is the classic way to get it wrong:
 * `"0.0.10" < "0.0.9"` is true as a string comparison and false as a version comparison, and
 * "0.0.9" is the older release. A user who is told a newer version exists, when it does not,
 * stops trusting the feature; a user who is never told about a real update stops trusting it too.
 *
 * ## The comparison rules
 *
 *  * Numeric components are compared **numerically**, never as text.
 *  * A leading `v` is ignored, because the release tag carries one and the version name does not.
 *  * A version with a prerelease suffix is **older** than the same version without one, which is
 *    what SemVer says and what a user expects: `1.2.0-beta.1` comes before `1.2.0`.
 *  * Build metadata after a `+` is ignored for comparison.
 *  * Anything that does not parse is *not* a version, and a caller that cannot understand a
 *    version must fail safe rather than guess.
 *
 * ## The project's own version suffix
 *
 * The application id is `1.1.0-release` and the release tag is `v1.1.0`. The `-release` suffix
 * names the *build variant*, not a prerelease: it is on every build the project ships, and it is
 * not a pre-release of anything. [installed] therefore strips it, so that the installed
 * `1.1.0-release` and the published `v1.1.0` compare as the same version rather than the
 * installed one looking like a prerelease of the published one.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** The prerelease suffix, without the leading dash. Null for a plain release. */
    val prerelease: String? = null,
) : Comparable<AppVersion> {

    /** True when this is a prerelease, which is never offered as a stable update. */
    val isPrerelease: Boolean get() = !prerelease.isNullOrBlank()

    override fun compareTo(other: AppVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A version with no prerelease suffix is the final one and outranks any of its
        // prereleases: 1.2.0 > 1.2.0-beta.1.
        return when {
            prerelease == other.prerelease -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> prerelease.compareTo(other.prerelease)
        }
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        prerelease?.let { append('-').append(it) }
    }

    companion object {

        /**
         * The suffix this project puts on every build's version name.
         *
         * It names the build variant, so it is removed before parsing rather than being treated as
         * a prerelease identifier.
         */
        const val BUILD_SUFFIX = "-release"

        /**
         * Parses [raw] into a version, or returns null when it cannot be understood.
         *
         * Null rather than a guess: a caller that cannot understand a version must not decide
         * whether an update exists.
         */
        fun parse(raw: String?): AppVersion? {
            var text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            // A leading `v` is the tag convention, not part of the number.
            if (text[0] == 'v' || text[0] == 'V') text = text.substring(1).trim()

            // Build metadata never affects precedence.
            text = text.substringBefore('+')

            val dash = text.indexOf('-')
            val core = if (dash >= 0) text.substring(0, dash) else text
            val suffix = if (dash >= 0) text.substring(dash + 1).trim().ifEmpty { null } else null

            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 4) return null

            val numbers = parts.map { part ->
                // Reject anything that is not a plain number, so "1.x" and "1.0.0.0.1" are
                // rejected rather than partially understood.
                if (part.isEmpty() || !part.all { it.isDigit() }) return null
                part.toIntOrNull() ?: return null
            }

            return AppVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                prerelease = suffix,
            )
        }

        /**
         * Parses only the numeric core of [raw], ignoring any suffix.
         *
         * This is how the *installed* version is read. The installed build's version name carries
         * a suffix naming the build variant — `1.1.0-release` for a release build, and
         * `1.1.0-release-debug` for a debug one. Read as a prerelease identifier that suffix would
         * make the installed build look *older* than the published `v1.1.0`, and the app would
         * cheerfully offer an update to itself. The suffix names the variant; it is not a
         * pre-release of anything, so it is dropped.
         */
        fun parseCore(raw: String?): AppVersion? = parse(raw)?.copy(prerelease = null)

        /**
         * The version this build is, read from the build configuration.
         *
         * Not hardcoded: the value comes from `BuildConfig.VERSION_NAME`, which is generated from
         * `versionName` in `app/build.gradle.kts`. That is the same source the about screen shows
         * the user, so the two can never disagree.
         */
        fun installed(): AppVersion? = parseCore(com.discounthunter.android.BuildConfig.VERSION_NAME)
    }
}
