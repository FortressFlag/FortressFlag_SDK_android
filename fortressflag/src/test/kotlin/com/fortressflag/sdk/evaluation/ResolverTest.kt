package com.fortressflag.sdk.evaluation

import com.fortressflag.sdk.FlagValue
import com.fortressflag.sdk.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverTest {
    private val fresh = mapOf<String, FlagValue>("a" to FlagValue.Bool(true))
    private val cached = mapOf<String, FlagValue>("a" to FlagValue.Bool(false), "b" to FlagValue.Str("cached"))

    @Test
    fun theCascadeInItsExactOrder() {
        // fresh beats cached
        val a = Resolver.resolve("a", fresh, cached, null)
        assertEquals(ValueSource.FRESH, a.source)
        assertEquals(true, a.value.boolValue)

        // cached beats developer default — a value this device actually received always
        // beats a compiled-in guess, however old (Founding §8.4). The default answers
        // "never heard of it", not "offline".
        val b = Resolver.resolve("b", fresh, cached, FlagValue.Str("guess"))
        assertEquals(ValueSource.CACHED, b.source)
        assertEquals("cached", b.value.stringValue)

        // developer default beats false
        val c = Resolver.resolve("c", fresh, cached, FlagValue.Bool(true))
        assertEquals(ValueSource.DEVELOPER_DEFAULT, c.source)
        assertEquals(true, c.value.boolValue)

        // and the floor is false
        val d = Resolver.resolve("d", fresh, cached, null)
        assertEquals(ValueSource.SAFE_DEFAULT, d.source)
        assertEquals(false, d.value.boolValue)
    }

    @Test
    fun nothingAnywhereIsFalse() {
        val resolution = Resolver.resolve("x", null, null, null)
        assertEquals(ValueSource.SAFE_DEFAULT, resolution.source)
        assertEquals(false, resolution.value.boolValue)
    }

    @Test
    fun resolveAllIsTheUnionAndDriftsFromNothing() {
        val all = Resolver.resolveAll(fresh, cached)
        assertEquals(setOf("a", "b"), all.keys)
        // Enumeration cannot drift from single-key reads.
        assertEquals(Resolver.resolve("a", fresh, cached, null), all["a"])
        assertEquals(Resolver.resolve("b", fresh, cached, null), all["b"])
    }

    @Test
    fun changedKeysSeesDisappearance() {
        // A flag leaving the payload is a change too: it falls down the cascade, which may
        // change its effective value — that is what drives change notification when a flag
        // is archived.
        val before = mapOf<String, FlagValue>("a" to FlagValue.Bool(true), "gone" to FlagValue.Bool(true))
        val after = mapOf<String, FlagValue>("a" to FlagValue.Bool(true))
        val changed = Resolver.changedKeys(before, after, cached = null)
        assertEquals(setOf("gone"), changed)
    }

    @Test
    fun changedKeysComparesEffectiveValuesNotPayloads() {
        // A key that left the fresh payload but whose cached value matches its old fresh
        // value has not changed effectively.
        val before = mapOf<String, FlagValue>("b" to FlagValue.Str("cached"))
        val changed = Resolver.changedKeys(before, emptyMap(), cached)
        assertTrue(changed.isEmpty())
    }
}
