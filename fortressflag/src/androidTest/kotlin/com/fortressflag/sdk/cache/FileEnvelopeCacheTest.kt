package com.fortressflag.sdk.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fortressflag.sdk.Environment
import com.fortressflag.sdk.LogPolicy
import com.fortressflag.sdk.support.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileEnvelopeCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = Log(LogPolicy.SILENT, "test")

    private fun cache(
        key: String = "ffc_dev_abc",
        env: Environment = Environment.DEVELOPMENT,
    ) = FileEnvelopeCache(context, key, env, log)

    @Before
    fun cleanSlate() {
        cache().clear()
        cache(key = "ffc_dev_other").clear()
    }

    @Test
    fun storesTheEnvelopeVerbatimAndSurvivesNewInstances() {
        val bytes = """{"payload":"abc","future-field":1}""".toByteArray()
        cache().store(bytes, "\"e1\"")
        // A new instance simulates an app restart: the cache is a correctness feature and
        // must survive one (Founding §8.4).
        val loaded = cache().load()!!
        // VERBATIM: re-serialising would strip fields a future server adds and break the
        // signature on reload.
        assertTrue(loaded.raw.contentEquals(bytes))
        assertEquals("\"e1\"", loaded.etag)
    }

    @Test
    fun twoConfigurationsCannotServeEachOthersValues() {
        cache().store("first".toByteArray(), null)
        assertNull(cache(key = "ffc_dev_other").load())
    }

    @Test
    fun aNullEtagRemovesTheStoredOne() {
        cache().store("a".toByteArray(), "\"e1\"")
        cache().store("b".toByteArray(), null)
        assertNull(cache().load()!!.etag)
    }

    @Test
    fun theCacheLivesOutsideAutoBackupAndPathsHideTheKey() {
        cache().store("x".toByteArray(), null)
        val root = java.io.File(context.noBackupFilesDir, "fortressflag")
        val scopeDirs = root.listFiles()!!.filter { it.isDirectory }
        assertTrue(scopeDirs.isNotEmpty())
        // The SDK key must never land in a path (screenshots, bug reports); the scope dir
        // is a hex hash prefix.
        for (dir in scopeDirs) {
            assertTrue(dir.name, dir.name.matches(Regex("[0-9a-f]{16}")))
        }
    }

    @Test
    fun clearRemovesBothFiles() {
        cache().store("x".toByteArray(), "\"e\"")
        cache().clear()
        assertNull(cache().load())
    }
}
