package com.fortressflag.sdk

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    @Test
    fun versionIsSemverShaped() {
        // The version string goes into the X-FF-SDK header; a malformed one ships in every
        // request forever, so its shape is pinned from the first commit.
        assertTrue(BuildInfo.VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
    }
}
