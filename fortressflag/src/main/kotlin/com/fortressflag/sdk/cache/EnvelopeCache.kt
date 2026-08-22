package com.fortressflag.sdk.cache

import android.content.Context
import com.fortressflag.sdk.Environment
import com.fortressflag.sdk.support.Log
import java.io.File
import java.security.MessageDigest

/** What the cache holds: the bytes that were signed, plus the ETag they arrived with. */
internal class CachedEnvelope(
    val raw: ByteArray,
    val etag: String?,
)

/**
 * Durable storage for the last envelope this device received.
 *
 * The cache is a **correctness feature, not an optimisation** (Founding §8.4). It is what
 * makes "offline" and "our backend is down" indistinguishable from normal operation for the
 * end user, so it must survive app restarts, and every failure to read it must degrade
 * rather than throw.
 */
internal interface EnvelopeCache {
    fun load(): CachedEnvelope?

    fun store(
        raw: ByteArray,
        etag: String?,
    )

    fun clear()
}

/**
 * File-backed cache under the app's private files directory.
 *
 * It stores **the signed envelope verbatim**, never the parsed values, and the caller
 * re-verifies on load. That single decision is what makes the cache safe: poisoning it
 * requires forging a signature rather than editing a JSON file on a rooted device, and the
 * cache inherits every guarantee the transport has, for free, and cannot drift from it.
 *
 * The directory lives under [Context.getNoBackupFilesDir]: flag state must not ride an
 * auto-backup onto a different device — it is data minimisation (Founding §7.3), and a
 * restored backup would carry values that were evaluated for the old device's identity.
 * (The library cannot ship backup-rules XML into the host app's manifest without merging
 * surprises; the no-backup directory achieves the exclusion structurally.)
 */
internal class FileEnvelopeCache(
    context: Context,
    sdkKey: String,
    environment: Environment,
    private val log: Log,
) : EnvelopeCache {
    private val directory: File

    init {
        // Scoped by a hash of the SDK key and environment so that two configurations in one
        // app — a staging and a production build sharing storage, or an app reconfiguring at
        // runtime — cannot serve each other's values. Hashed rather than used directly so
        // the key never lands in a file path that ends up in a screenshot or a bug report.
        val scope = scopeIdentifier(sdkKey, environment)
        directory = File(File(context.noBackupFilesDir, "fortressflag"), scope)
    }

    override fun load(): CachedEnvelope? {
        return try {
            val envelopeFile = File(directory, ENVELOPE_FILE)
            if (!envelopeFile.isFile) return null
            if (envelopeFile.length() > MAX_ENVELOPE_BYTES) {
                // Something wrote a file we would never have written. Treat as absent and
                // remove it: leaving it would mean retrying a doomed read every launch.
                log.warning("cached envelope is implausibly large; discarding it")
                clear()
                return null
            }
            val raw = envelopeFile.readBytes()
            val etagFile = File(directory, ETAG_FILE)
            val etag = if (etagFile.isFile) etagFile.readText(Charsets.UTF_8) else null
            CachedEnvelope(raw, etag)
        } catch (_: Exception) {
            null
        }
    }

    override fun store(
        raw: ByteArray,
        etag: String?,
    ) {
        if (raw.size > MAX_ENVELOPE_BYTES) return
        try {
            directory.mkdirs()
            // Write-temp-then-rename: a crash mid-write must not leave a truncated envelope
            // where the last good one was.
            val temp = File(directory, "$ENVELOPE_FILE.tmp")
            temp.writeBytes(raw)
            if (!temp.renameTo(File(directory, ENVELOPE_FILE))) {
                temp.delete()
                return
            }
            val etagFile = File(directory, ETAG_FILE)
            if (etag != null) {
                etagFile.writeText(etag, Charsets.UTF_8)
            } else {
                etagFile.delete()
            }
        } catch (e: Exception) {
            // A cache write failing is survivable — the in-memory values still serve this
            // launch, and the next launch falls back one further down the cascade. It is
            // never a reason to disturb the host app.
            log.error("could not write the flag cache: ${e::class.simpleName}")
        }
    }

    override fun clear() {
        try {
            File(directory, ENVELOPE_FILE).delete()
            File(directory, ETAG_FILE).delete()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val ENVELOPE_FILE = "envelope.json"
        const val ETAG_FILE = "etag.txt"

        /**
         * A hostile or broken server must not be able to fill the user's disk. Real
         * payloads are a few kilobytes; this is orders of magnitude of headroom and still
         * bounded. Shared with the transport's response cap.
         */
        const val MAX_ENVELOPE_BYTES = 1L shl 20

        /** Hex SHA-256 prefix of `"<sdkKey>|<environment>"`. */
        fun scopeIdentifier(
            sdkKey: String,
            environment: Environment,
        ): String {
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("$sdkKey|${environment.key}".toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
