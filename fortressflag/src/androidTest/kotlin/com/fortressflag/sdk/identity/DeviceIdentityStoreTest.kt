package com.fortressflag.sdk.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// These run against the REAL Keystore on an emulator — a Keystore stub would test our idea of
// the Keystore, and the behaviour that matters (key generation, GCM round-trip, survival
// across store instances) is exactly the part a stub would get to define for itself. The iOS
// repo applies the same rule to the keychain.
@RunWith(AndroidJUnit4::class)
class DeviceIdentityStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun cleanSlate() {
        DeviceIdentityStore(context).reset()
    }

    @Test
    fun mintsOnceAndPersists() {
        val store = DeviceIdentityStore(context)
        val first = store.currentOrMint()
        assertTrue(first, DeviceIdentity.isWellFormed(first))
        // A second store instance simulates an app restart: same identity, not a re-mint —
        // the identity is the billing primitive, and instability bills one device as many.
        assertEquals(first, DeviceIdentityStore(context).currentOrMint())
    }

    @Test
    fun racingCallsConvergeOnOneIdentity() {
        // Two racing `start` calls must converge on one identity (mint-then-adopt-on-
        // conflict): a device holding two identities bills as two, permanently.
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads =
            (1..8).map {
                // Separate store instances: the in-process lock must not be the only thing
                // making this safe.
                Thread {
                    results.add(DeviceIdentityStore(context).currentOrMint())
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        results.add(DeviceIdentityStore(context).currentOrMint())
        assertEquals(results.toString(), 1, results.toSet().size)
    }

    @Test
    fun resetMintsANewIdentity() {
        val store = DeviceIdentityStore(context)
        val first = store.currentOrMint()
        store.reset()
        val second = store.currentOrMint()
        assertTrue(second, DeviceIdentity.isWellFormed(second))
        // GDPR erasure is real: the old pseudonym is gone, and the documented consequence —
        // this device counts as a new one — is exactly what this asserts.
        assertNotEquals(first, second)
    }

    @Test
    fun corruptedFileIsTreatedAsAbsent() {
        val store = DeviceIdentityStore(context)
        store.currentOrMint()
        val file = File(File(context.noBackupFilesDir, "fortressflag"), "device-identity")
        file.writeBytes(ByteArray(40) { 0x41 })
        val next = store.currentOrMint()
        // Bytes we cannot decrypt are not an identity; a fresh mint beats trusting junk in
        // the billing path — and nothing here may throw into the host app.
        assertTrue(next, DeviceIdentity.isWellFormed(next))
    }

    @Test
    fun storedFileLivesOutsideAutoBackup() {
        val store = DeviceIdentityStore(context)
        store.currentOrMint()
        val file = File(File(context.noBackupFilesDir, "fortressflag"), "device-identity")
        assertTrue(file.isFile)
        // noBackupFilesDir is the contract's "never synced": an identity riding a backup to
        // a second device breaks one-device-one-seat.
        assertTrue(file.absolutePath.contains("no_backup"))
    }
}
