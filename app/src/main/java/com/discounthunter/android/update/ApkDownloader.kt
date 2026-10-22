package com.discounthunter.android.update

import com.discounthunter.android.diagnostics.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release artifact, with progress and integrity checking.
 *
 * ## Why not the shared `HttpClient`
 *
 * That client is built for small JSON and HTML payloads: it caps the response at 6 MB, reads the
 * whole body into memory and reports no progress. An APK is larger than the cap and a user staring
 * at a frozen screen has no idea whether anything is happening, so this has its own streaming
 * download.
 *
 * ## Integrity
 *
 * Two independent checks, because they fail differently:
 *
 *  * **The digest.** GitHub publishes a SHA-256 for each asset. When it is present, the downloaded
 *    file must match it exactly. A mismatch is a corrupted or substituted download and the file is
 *    discarded rather than offered.
 *  * **The package itself.** The archive is opened and its package id, version code and signing
 *    certificate are read with `PackageManager`. This is what proves the file really is this
 *    application and not something else that happens to be named `.apk`.
 *
 * When GitHub publishes no digest, the package inspection still runs; the digest is simply not
 * available, and nothing pretends otherwise.
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Downloads [asset] into [target].
     *
     * The file is written to a temporary name and moved into place only once it is complete, so
     * an interrupted download can never be mistaken for a finished one.
     */
    suspend fun download(
        asset: ReleaseAsset,
        target: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(io) {
        val temporary = File(target.parentFile, target.name + ".part")

        try {
            val request = Request.Builder().url(asset.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("The download failed (HTTP ${response.code})."),
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IOException("The download returned nothing."),
                )
                val total = body.contentLength().takeIf { it > 0 } ?: asset.sizeBytes

                var read = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // Cancellation has to interrupt a long download promptly, and the
                            // check is here rather than only at the end for that reason.
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            // `<= 0`, not `< 0`: a read that returns zero is legal for a
                            // non-blocking stream, and looping on it would spin.
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            read += count
                            onProgress(read, total)
                        }
                    }
                }

                // The file itself is checked, not the counter. This is the artefact that will be
                // handed to the installer, so it is the thing worth being sure about.
                if (temporary.length() == 0L) {
                    return@withContext Result.failure(IOException("The downloaded file was empty."))
                }

                val expected = asset.sha256
                if (expected != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expected, ignoreCase = true)) {
                        return@withContext Result.failure(
                            IOException("The download did not match the published checksum."),
                        )
                    }
                } else {
                    AppLog.i("Updates: the release published no checksum; relying on package inspection")
                }

                if (target.exists() && !target.delete()) {
                    return@withContext Result.failure(
                        IOException("The previous download could not be replaced."),
                    )
                }
                if (!temporary.renameTo(target)) {
                    return@withContext Result.failure(
                        IOException("The download could not be moved into place."),
                    )
                }
                Result.success(target)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // A cancelled download is not a failure, and the partial file must not survive it.
            temporary.delete()
            throw cancellation
        } catch (error: Exception) {
            temporary.delete()
            AppLog.e("Updates: the download failed", error)
            Result.failure(error)
        }
    }
}
