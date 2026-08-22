package com.fortressflag.sdk.tags

import com.fortressflag.sdk.LogPolicy
import com.fortressflag.sdk.support.Base64Url
import com.fortressflag.sdk.support.Log
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {
    private val log = Log(LogPolicy.SILENT, "test")

    @Test
    fun sanitizeDropsWhatTheContractCannotCarry() {
        val kept =
            Tags.sanitize(
                mapOf(
                    "cohort" to "beta",
                    "bad key!" to "x",
                    "platform" to "spoofed", // reserved built-in name
                    "big" to "v".repeat(300),
                ),
                reserved = BuiltinTags.RESERVED_KEYS,
                log = log,
            )
        assertEquals(mapOf("cohort" to "beta"), kept)
    }

    @Test
    fun encodeIsSortedAndDeterministic() {
        val a = Tags.encode(mapOf("platform" to "android"), mapOf("b" to "2", "a" to "1"), log)
        val b = Tags.encode(mapOf("platform" to "android"), mapOf("a" to "1", "b" to "2"), log)
        // The same tag set must produce the same bytes on every request, or the ETag design
        // (M3) would see a different request per launch for an unchanged device.
        assertEquals(a, b)
        val decoded = String(Base64Url.decode(a!!)!!, Charsets.UTF_8)
        assertTrue(decoded.indexOf("\"a\"") < decoded.indexOf("\"b\""))
        assertTrue(decoded.indexOf("\"b\"") < decoded.indexOf("\"platform\""))
        val json = JSONObject(decoded)
        assertEquals("1", json.getString("a"))
        assertEquals("android", json.getString("platform"))
    }

    @Test
    fun nothingToSendIsNull() {
        // An absent header means no tags: only default states serve.
        assertNull(Tags.encode(emptyMap(), emptyMap(), log))
    }

    @Test
    fun overCountShedsCustomTagsFromTheEndOfSortedOrder() {
        val custom = (1..40).associate { "tag%02d".format(it) to "v" }
        val encoded = Tags.encode(mapOf("platform" to "android"), custom, log)!!
        val json = JSONObject(String(Base64Url.decode(encoded)!!, Charsets.UTF_8))
        assertEquals(Tags.MAX_COUNT, json.length())
        // Built-ins always survive; the shed tags are the LAST in sorted order.
        assertEquals("android", json.getString("platform"))
        assertTrue(json.has("tag01"))
        assertFalse(json.has("tag40"))
    }

    @Test
    fun overDocumentCapShedsUntilItFits() {
        val custom = (1..20).associate { "key%02d".format(it) to "v".repeat(250) }
        val encoded = Tags.encode(mapOf("platform" to "android"), custom, log)!!
        val document = Base64Url.decode(encoded)!!
        assertTrue(document.size <= Tags.MAX_DOCUMENT_BYTES)
        val json = JSONObject(String(document, Charsets.UTF_8))
        assertEquals("android", json.getString("platform"))
    }

    @Test
    fun keyCharsetIsTheContracts() {
        assertTrue(Tags.isValidKey("appVersion"))
        assertTrue(Tags.isValidKey("a.b_c-9"))
        assertFalse(Tags.isValidKey(""))
        assertFalse(Tags.isValidKey("a".repeat(65)))
        assertFalse(Tags.isValidKey("no spaces"))
        assertFalse(Tags.isValidKey("emoji🙂"))
    }

    @Test
    fun builtinsCollectMechanicalFactsOnly() {
        val tags = BuiltinTags.collect(appVersion = "2.1", appBuild = "421", osVersion = "14")
        assertEquals("android", tags["platform"])
        assertEquals("2.1", tags["appVersion"])
        assertEquals("421", tags["appBuild"])
        assertEquals("14", tags["osVersion"])
        // A value the platform cannot supply omits its tag (absent-tag semantics).
        val sparse = BuiltinTags.collect(appVersion = null, appBuild = null, osVersion = null)
        assertFalse(sparse.containsKey("appVersion"))
        assertTrue(sparse.containsKey("platform"))
        assertTrue(sparse.containsKey("sdkVersion"))
    }
}
