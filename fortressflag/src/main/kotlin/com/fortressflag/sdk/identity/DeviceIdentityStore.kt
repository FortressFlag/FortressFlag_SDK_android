package com.fortressflag.sdk.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable, encrypted storage for the device identity.
 *
 * The contract's storage row for Android (`FortressFlag_Standards/contracts/device-identity.md`):
 * an app-private file encrypted with a Keystore-held AES key. The key is minted with
 * `setUserAuthenticationRequired(false)` — the analogue of the iOS keychain's
 * `AfterFirstUnlock`: the SDK resolves flags during background launches, so the identity must
 * be readable without the user having interacted with the device. The file lives in
 * [Context.getNoBackupFilesDir]: an identity riding a backup onto a second physical device
 * breaks "one device, one seat" and turns a per-device pseudonym into a cross-device one, so
 * exclusion from auto-backup is a contract requirement, not a preference.
 *
 * Mint-then-adopt-on-conflict: minting is synchronized in-process AND across processes — a
 * [java.nio.channels.FileLock] on a sibling lock file brackets every read-mint-write, so two
 * racing `start` calls (or two processes of the same app) converge on one identity, because
 * a device holding two identities bills as two, permanently. (An atomic hard-link publish
 * was considered and rejected: SELinux denies link(2) to Android apps.)
 *
 * Every failure path returns null or falls back to minting-in-memory rather than throwing:
 * identity storage trouble must never crash a customer's app (Founding §8.1).
 */
internal class DeviceIdentityStore(
    context: Context,
) {
    private val directory = File(context.noBackupFilesDir, "fortressflag")
    private val file = File(directory, "device-identity")

    /**
     * The stored identity, or a freshly minted-and-stored one. Malformed stored values are
     * treated as absent rather than trusted. If storage is completely unavailable the minted
     * identity is returned unstored — a per-launch identity serves flags correctly and errs
     * on the side of the customer's app working.
     */
    fun currentOrMint(): String {
        synchronized(LOCK) {
            withFileLock {
                // Adopt-on-conflict happens HERE: whoever wrote while we waited for the
                // lock is the winner, and this re-read adopts their value. The loser's
                // mint is discarded, never the winner's.
                read()?.let { return it }
                val minted = DeviceIdentity.mint()
                write(minted)
                return read() ?: minted
            }
            // Lock unavailable (exotic storage failure): a per-launch identity still
            // serves flags, and the next launch tries again.
            return read() ?: DeviceIdentity.mint()
        }
    }

    /**
     * The stored identity WITHOUT minting, or null. For callers that must stay cheap on the
     * calling thread (the synchronous cache load): reading an existing identity decrypts one
     * small file; minting could generate a Keystore key, which is the cost this avoids.
     */
    fun peek(): String? = synchronized(LOCK) { read() }

    /**
     * GDPR erasure for the pseudonymous identifier: delete and re-mint on next use. The
     * documented consequence is honest — this device will be counted as a new one.
     */
    fun reset() {
        synchronized(LOCK) {
            withFileLock { file.delete() }
        }
    }

    /**
     * Runs [body] holding an exclusive cross-process lock, or not at all when the lock file
     * cannot be created. In-process exclusion is the caller's synchronized(LOCK); the file
     * lock adds the cross-process half (two processes of one app both embedding the SDK).
     */
    private inline fun withFileLock(body: () -> Unit) {
        try {
            directory.mkdirs()
            RandomAccessFile(File(directory, "device-identity.lock"), "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    body()
                } finally {
                    lock.release()
                }
            }
        } catch (_: Exception) {
            // Locking trouble must not crash the host app; the caller degrades.
        }
    }

    private fun read(): String? {
        val bytes =
            try {
                if (!file.isFile) return null
                file.readBytes()
            } catch (_: Exception) {
                return null
            }
        if (bytes.size <= GCM_IV_BYTES) return null
        val plaintext =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
                cipher.init(Cipher.DECRYPT_MODE, key() ?: return null, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
            } catch (_: Exception) {
                // Undecryptable (key rotated by an OS restore, file corrupted): treated as
                // absent. A fresh mint beats trusting bytes we cannot verify.
                return null
            }
        val value = String(plaintext, Charsets.UTF_8)
        return if (DeviceIdentity.isWellFormed(value)) value else null
    }

    /** Callers hold the file lock, so temp-then-rename cannot replace a concurrent
     * winner's identity — the lock serialised us behind their write and the re-read above
     * already adopted it. Rename keeps the publish atomic against crashes mid-write. */
    private fun write(value: String) {
        var temp: File? = null
        try {
            directory.mkdirs()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key() ?: return)
            val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val candidate = File(directory, "device-identity.tmp")
            temp = candidate
            candidate.writeBytes(sealed)
            candidate.renameTo(file)
        } catch (_: Exception) {
            // Storage failing outright must not crash the host app; the caller returns the
            // minted value unstored and the next launch tries again.
        } finally {
            temp?.delete()
        }
    }

    private fun key(): SecretKey? =
        try {
            val store = KeyStore.getInstance(KEYSTORE)
            store.load(null)
            (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
        } catch (_: Exception) {
            null
        }

    private fun generateKey(): SecretKey? =
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // The AfterFirstUnlock analogue: flags resolve during background
                    // launches, before any user interaction.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKey()
        } catch (_: Exception) {
            null
        }

    private companion object {
        // Process-wide: every store instance races through the same gate, so the atomic
        // create below is only ever exercised by genuinely separate processes.
        val LOCK = Any()
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.fortressflag.sdk.device.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
