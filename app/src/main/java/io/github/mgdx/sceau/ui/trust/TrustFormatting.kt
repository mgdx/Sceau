package io.github.mgdx.sceau.ui.trust

import android.content.res.Resources
import io.github.mgdx.sceau.R
import io.github.mgdx.sceau.core.trust.TrustStores
import io.github.mgdx.sceau.ui.result.displaySafe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Maximum size of an imported Master List: 20 MB. */
const val MAX_MASTER_LIST_BYTES: Int = 20 * 1024 * 1024

/** File refused because it is larger than the limit. */
class FileTooLargeException : Exception("TOO_LARGE")

/**
 * Reads all of [input], refusing beyond [limit] bytes, without ever allocating more than
 * `limit + 1` bytes. Throws [FileTooLargeException] if the limit is exceeded.
 */
fun readAtMost(
    input: InputStream,
    limit: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw FileTooLargeException()
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val BUFFER_SIZE = 64 * 1024

/**
 * Maximum read time for an imported file (audit V38): far beyond the time needed to read a
 * 20 MB Master List on a slow device, even from a remote documents provider.
 */
const val IMPORT_READ_TIMEOUT_MILLIS: Long = 30_000 // spelled out in `trust_import_read_timeout`

/** Read abandoned: the provider did not deliver the file within the time limit. */
class ImportReadTimeoutException : Exception("READ_TIMEOUT")

/**
 * Opens, then reads with [readAtMost], the stream returned by [open] (null: file not found,
 * [IOException]), giving up after [timeoutMillis] (audit V38). A hostile `DocumentsProvider` can
 * return a pipe that never delivers any bytes, and cancelling a coroutine does not interrupt a
 * blocking read: the read therefore runs in a separate task, which the caller does not wait for
 * beyond the time limit, and the stream is closed as soon as the read is abandoned (time limit
 * exceeded or caller cancelled), which unblocks `read` on Android. Throws
 * [ImportReadTimeoutException] after the time limit.
 */
suspend fun readImportedFile(
    open: () -> InputStream?,
    limit: Int,
    timeoutMillis: Long = IMPORT_READ_TIMEOUT_MILLIS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ByteArray {
    val stream = AtomicReference<InputStream?>()
    // Outside the caller's scope: its cancellation must not wait for a blocked read to finish.
    val reading =
        CoroutineScope(dispatcher).async {
            val input = open() ?: throw IOException("OPEN")
            stream.set(input)
            // Abandoned while opening: the caller could not close the stream.
            if (!isActive) stream.getAndSet(null)?.close()
            input.use { readAtMost(it, limit) }
        }
    try {
        return withTimeoutOrNull(timeoutMillis) { reading.await() } ?: throw ImportReadTimeoutException()
    } finally {
        if (!reading.isCompleted) {
            reading.cancel()
            try {
                stream.getAndSet(null)?.close()
            } catch (_: IOException) {
                // Best-effort close: the stream is abandoned anyway.
            }
        }
    }
}

/** Hexadecimal fingerprint grouped in blocks of four characters, in uppercase. */
fun formatFingerprint(hex: String): String = hex.uppercase(Locale.ROOT).chunked(4).joinToString(" ")

/** Maximum displayed length of an X.500 subject, in code points. */
const val SUBJECT_MAX_LENGTH = 256

/** Maximum number of lines displayed for an X.500 subject. */
const val SUBJECT_MAX_LINES = 4

/** Maximum displayed length of an unrecognized country code, in code points. */
const val COUNTRY_CODE_MAX_LENGTH = 8

/**
 * X.500 subject of a certificate or of a Master List signer, as returned by
 * `X500Principal.getName()`, which escapes neither line breaks nor bidirectional control
 * characters: sanitized by [displaySafe] before any display (audit V23).
 */
fun displaySubject(subject: String): String = displaySafe(subject, SUBJECT_MAX_LENGTH)

/** Country code read from a certificate (C attribute), sanitized when it is not recognized (audit V23). */
fun displayCountryCode(code: String): String = displaySafe(code, COUNTRY_CODE_MAX_LENGTH)

/** `MasterListParser` code for a Master List with more than [TrustStores.MAX_MASTER_LIST_CERTIFICATES] certificates. */
internal const val MASTER_LIST_TOO_MANY_CERTIFICATES = "TOO_MANY_CERTIFICATES"

/**
 * Message for a Master List rejected by `MasterListParser` (`InvalidMasterListException`). A
 * list that is too large (D22, audit V22) may be readable and properly signed: it gets a message
 * that states the cap. Other codes keep the generic message, code included.
 */
fun invalidMasterListMessage(
    resources: Resources,
    code: String,
): String =
    if (code == MASTER_LIST_TOO_MANY_CERTIFICATES) {
        resources.getQuantityString(
            R.plurals.trust_import_master_list_too_many_certificates,
            TrustStores.MAX_MASTER_LIST_CERTIFICATES,
            TrustStores.MAX_MASTER_LIST_CERTIFICATES,
        )
    } else {
        resources.getString(R.string.trust_import_invalid, code)
    }
