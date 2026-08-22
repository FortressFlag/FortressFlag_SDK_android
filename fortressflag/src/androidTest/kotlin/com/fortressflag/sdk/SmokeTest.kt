package com.fortressflag.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Proves the instrumented-test lane works end to end before the Keystore identity tests land
// in it — an emulator job that has never run a test is a merge gate that has never gated.
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun runsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.fortressflag.sdk.test", context.packageName)
    }
}
