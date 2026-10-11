package com.discounthunter.android.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A rolling in-app log.
 *
 * ## Why the app keeps its own
 *
 * The obvious export is `adb logcat`, which nobody without a computer can reach, and the crashes
 * that actually reach a user are the ones that happened minutes earlier — the exact state they hit
 * is gone by the time they think to report it. A short in-process ring buffer means a bug report can
 * carry the run-up to the failure, not just the stack trace.
 *
 * ## What is deliberately not recorded
 *
 * Nothing user-identifying. Deal titles and store ids go in because they are what a failure is
 * *about*; no account names, no recent searches, no URLs beyond the store's own. The buffer is
 * bounded, so it cannot grow without limit, and it never leaves the device unless the user exports
 * it deliberately from Settings.
 *
 * ## Cost
 *
 * [log] is a non-inline call that appends to a bounded list. It is not on any hot path — the app
 * logs on state transitions and caught failures, not per frame — but it is deliberately not
 * `inline`, because inlining a `synchronized` block into every caller is how log statements end up
 * costing more than the work they describe.
 */
object AppLog {

    private const val TAG = "DiscountHunter"
    private const val CAPACITY = 500

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entries = CopyOnWriteArrayList<String>()

    @Volatile
    var minLevel: Int = Log.DEBUG

    fun d(message: String) = append("D", message, null)
    fun i(message: String) = append("I", message, null)
    fun w(message: String, error: Throwable? = null) = append("W", message, error)

    /** Records a caught failure. Call this where a `catch` decides to carry on rather than crash. */
    fun e(message: String, error: Throwable) = append("E", message, error)

    fun log(message: String) = d(message)

    private fun append(level: String, message: String, error: Throwable?) {
        val line = buildString {
            append(stamp.format(Date()))
            append(' ')
            append(level)
            append(' ')
            append(message)
            if (error != null) {
                append('\n')
                append(StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) })
            }
        }
        entries.add(line)
        // CopyOnWriteArrayList grows; drop from the front once it is over the cap. Under lock so two
        // threads cannot both trim past the target.
        synchronized(entries) {
            while (entries.size > CAPACITY) entries.removeAt(0)
        }
        when (level) {
            "E" -> Log.e(TAG, message, error)
            "W" -> Log.w(TAG, message, error)
            else -> Log.d(TAG, message)
        }
    }

    /** The whole buffer, newest last. */
    fun dump(): String = entries.joinToString("\n")

    /** How many entries are held, for the Settings screen. */
    fun size(): Int = entries.size

    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * Writes a shareable report to the app's cache and returns it.
     *
     * The file carries device and app context alongside the log, because "it crashed" is much easier
     * to act on with the OS version, screen density and available memory next to it.
     */
    fun export(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "discount-hunter-log-${stamp.format(Date()).replace(':', '-')}.txt")
        file.writeText(report(context))
        return file
    }

    private fun report(context: Context): String = buildString {
        appendLine("Discount Hunter diagnostics")
        appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
        appendLine("App version: ${appVersion(context)}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Screen: ${context.resources.displayMetrics.widthPixels}x" +
            "${context.resources.displayMetrics.heightPixels} @ " +
            "${context.resources.displayMetrics.densityDpi}dpi")
        appendLine("Locale: ${java.util.Locale.getDefault()}")
        val memory = Runtime.getRuntime()
        appendLine(
            "Memory: max ${memory.maxMemory() / (1024 * 1024)}MB, " +
                "free ${memory.freeMemory() / (1024 * 1024)}MB",
        )
        appendLine()
        appendLine("---- log (${entries.size} entries, oldest first) ----")
        appendLine(entries.joinToString("\n"))
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (_: Exception) {
        "unknown"
    }
}