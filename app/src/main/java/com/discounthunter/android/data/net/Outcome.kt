package com.discounthunter.android.data.net

/**
 * The result of one network attempt.
 *
 * Every repository method returns this instead of throwing: a store being down degrades the feed
 * rather than breaking the screen, and the UI always has something specific to show the user.
 */
sealed interface Outcome<out T> {

    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(
        val reason: Reason,
        val message: String,
        val httpCode: Int = 0,
    ) : Outcome<Nothing> {
        /** True when trying again later has a reasonable chance of succeeding. */
        val isTransient: Boolean get() = reason == Reason.NETWORK || reason == Reason.TIMEOUT
    }

    enum class Reason {
        /** No route to the host, DNS failure, connection refused, airplane mode. */
        NETWORK,

        /** The socket was connected but no usable response arrived in time. */
        TIMEOUT,

        /** The server answered with a non-2xx status. */
        HTTP,

        /** HTTP 429 — the store is rate limiting us. */
        RATE_LIMITED,

        /** The response body could not be understood. */
        MALFORMED,

        /** The caller cancelled the request (a newer one superseded it, or the screen went away). */
        CANCELLED,
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Success)?.value
