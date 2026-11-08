package com.discounthunter.android.alerts

import com.discounthunter.android.core.text.Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * What the last background check did.
 *
 * ## Why this exists
 *
 * A background feature that only ever reports "no notification" is impossible to diagnose, from
 * either side. The user cannot tell whether the check ran and found nothing, ran and was blocked by
 * a permission, or never ran at all — and the app cannot say, because a worker that logs to a
 * ring buffer the user never opens has effectively said nothing.
 *
 * This is a tiny record of the last run, shown in Settings. It is deliberately *not* a history: one
 * entry is enough to answer "is this alive, and what did it do last time".
 */
enum class AlertCheckOutcome {
    /** The check ran and found nothing to announce. */
    CHECKED,

    /** The check ran and posted at least one notification. */
    ANNOUNCED,

    /** The check was skipped because notifications are blocked or not permitted. */
    NOTIFICATIONS_BLOCKED,

    /** The check was skipped because the stores returned no deals. */
    FEED_EMPTY,

    /** The check was skipped because the user has deal alerts switched off. */
    MONITORING_OFF,

    /** The check threw, and will be retried by WorkManager. */
    FAILED,
}

/** One completed (or skipped) background check. */
data class AlertRunStatus(
    /** When the check happened, in epoch millis. */
    val checkedAt: Long,
    val outcome: AlertCheckOutcome,
    /** How many notifications were posted. */
    val announced: Int = 0,
    /** How many deals the feed contained. */
    val deals: Int = 0,
    /** How many games were saved when the check ran. */
    val wishlist: Int = 0,
    /** A short diagnostic message. Never contains credentials or store payloads. */
    val message: String = "",
)

/**
 * Reads and writes [AlertRunStatus].
 *
 * `filesDir`, not `cacheDir`: the system may clear the cache at any moment, and "when did this last
 * run" is precisely the question a user asks after the cache has been cleared.
 *
 * A corrupt or missing file reads as null, which the Settings screen shows as "no check has run
 * yet". That is the safe direction — it never claims a check succeeded when the record is gone.
 */
class AlertStatusStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Reads the last recorded check, or null when there is no usable record. */
    suspend fun load(): AlertRunStatus? = withContext(io) {
        if (!file.isFile) return@withContext null
        val status = runCatching { file.readText() }.getOrNull()?.let(AlertStatusCodec::parse)
        // Seeding the process-wide flow here is what lets a ViewModel that starts after a worker
        // see the result: the flow's initial value is null, and this is the first real value.
        status?.let { publish(it) }
        status
    }

    /**
     * Records the outcome of a check.
     *
     * The write is atomic through a sibling file, so a process death mid-write cannot leave a
     * half-written record. A failure is logged by the caller rather than thrown: this is
     * diagnostics, and diagnostics must never be the reason a notification is not posted.
     */
    suspend fun record(status: AlertRunStatus) = withContext(io) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(AlertStatusCodec.serialize(status))
            if (!temp.renameTo(file)) {
                file.writeText(AlertStatusCodec.serialize(status))
                temp.delete()
            }
        }.onSuccess {
            // Published immediately so an open Settings screen updates without being reopened.
            // The process may have been started just for this worker, in which case nobody is
            // listening — the file is the durable copy.
            publish(status)
        }
    }

    companion object {

        private val _latest = MutableStateFlow<AlertRunStatus?>(null)

        /**
         * The latest record in this process, or null before anything has been read or written.
         *
         * A process-wide flow because the writer is a worker and the reader is a ViewModel; a file
         * alone would leave the screen showing the previous run until it was reopened.
         */
        val latest: StateFlow<AlertRunStatus?> = _latest.asStateFlow()

        internal fun publish(status: AlertRunStatus) {
            _latest.value = status
        }

        /** Forgets the in-memory record, for "delete all app data". */
        fun reset() {
            _latest.value = null
        }
    }
}

/**
 * The on-disk shape of [AlertRunStatus].
 *
 * Hand-written rather than reflective, like the rest of the project's storage: the file is small,
 * the schema is explicit, and an unreadable field can be defaulted without a crash.
 */
object AlertStatusCodec {

    fun serialize(status: AlertRunStatus): String = JsonObject(
        mapOf(
            "checkedAt" to JsonPrimitive(status.checkedAt),
            "outcome" to JsonPrimitive(status.outcome.name),
            "announced" to JsonPrimitive(status.announced),
            "deals" to JsonPrimitive(status.deals),
            "wishlist" to JsonPrimitive(status.wishlist),
            "message" to JsonPrimitive(status.message.take(MAX_MESSAGE_LENGTH)),
        ),
    ).toString()

    /** Parses a record, returning null when it is not one. */
    fun parse(text: String): AlertRunStatus? {
        val root = Json.parse(text) as? JsonObject ?: return null
        val checkedAt = Json.str(root, "checkedAt").toLongOrNull() ?: return null
        val outcome = runCatching {
            AlertCheckOutcome.valueOf(Json.str(root, "outcome"))
        }.getOrNull() ?: return null
        return AlertRunStatus(
            checkedAt = checkedAt,
            outcome = outcome,
            announced = Json.int(root, "announced").coerceAtLeast(0),
            deals = Json.int(root, "deals").coerceAtLeast(0),
            wishlist = Json.int(root, "wishlist").coerceAtLeast(0),
            message = Json.str(root, "message").take(MAX_MESSAGE_LENGTH),
        )
    }

    /** A diagnostic message is truncated so the file cannot grow with an exception's stack text. */
    private const val MAX_MESSAGE_LENGTH = 300
}
