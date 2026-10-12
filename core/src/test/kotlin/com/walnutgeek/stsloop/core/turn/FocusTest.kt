package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.Provider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FocusTest {
    private val registry = BucketRegistry.capture(BucketConfig.DEFAULT)
    private val focus = Focus(registry, timeoutMs = 120_000)

    @Test
    fun `a Session starts in the Inbox, where nothing is filed`() {
        assertEquals("inbox", focus.bucket.name)
        assertNull(focus.filing)
    }

    @Test
    fun `focusing a capture Bucket files later Turns there`() {
        focus.set("errands", atMs = 5_000)
        assertEquals("errands", focus.bucket.name)
        assertEquals("errands", focus.filing)
    }

    @Test
    fun `focusing the Inbox files nothing again`() {
        focus.set("errands", atMs = 0)
        focus.set("inbox", atMs = 1_000)
        assertNull(focus.filing)
    }

    @Test
    fun `Focus returns to the Inbox once the timeout passes with nothing heard, once`() {
        focus.set("errands", atMs = 5_000)
        assertFalse(focus.tick(124_999, capturing = false))
        assertEquals("errands", focus.filing)
        assertTrue(focus.tick(125_000, capturing = false))
        assertEquals("inbox", focus.bucket.name)
        assertFalse(focus.tick(500_000, capturing = false))
    }

    @Test
    fun `the Inbox never times out`() = assertFalse(focus.tick(10_000_000, capturing = false))

    @Test
    fun `each Turn heard restarts the timeout`() {
        focus.set("errands", atMs = 0)
        focus.heard(100_000)
        assertFalse(focus.tick(219_999, capturing = false))
        assertTrue(focus.tick(220_000, capturing = false))
    }

    @Test
    fun `speech being captured holds Focus and restarts the timeout`() {
        focus.set("errands", atMs = 0)
        assertFalse(focus.tick(119_000, capturing = true))
        assertFalse(focus.tick(130_000, capturing = true))
        assertFalse(focus.tick(249_999, capturing = false))
        assertTrue(focus.tick(250_000, capturing = false))
    }

    @Test
    fun `a Turn heard late never moves the timer back`() {
        focus.set("errands", atMs = 50_000)
        focus.heard(10_000)
        assertFalse(focus.tick(169_999, capturing = false))
    }

    @Test
    fun `a Bucket that does not take Notes is focused but files nothing`() {
        val agent = Provider { listOf(Descriptor("stsloop", emptyList(), emptySet())) }
        val f = Focus(BucketRegistry(listOf(agent)), timeoutMs = 120_000)
        f.set("stsloop", atMs = 0)
        assertEquals("stsloop", f.bucket.name)
        assertNull(f.filing)
    }

    @Test
    fun `only a registered Bucket can be focused`() {
        assertThrows<IllegalArgumentException> { focus.set("nowhere", atMs = 0) }
    }
}
