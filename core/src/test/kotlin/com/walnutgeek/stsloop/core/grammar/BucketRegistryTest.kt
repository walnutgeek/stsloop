package com.walnutgeek.stsloop.core.grammar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Buckets the phone can address, gathered from its Providers (ADR-0005). */
class BucketRegistryTest {
    private fun capture(vararg buckets: Bucket) = CaptureProvider(BucketConfig(buckets.toList()))

    @Test
    fun `the capture Provider publishes the Inbox and each configured Bucket, all using note`() {
        val d = CaptureProvider(BucketConfig.DEFAULT).descriptors()
        assertEquals(listOf("inbox", "errands", "house-project", "work", "ideas"), d.map { it.name })
        assertTrue(d.all { it.primitives == setOf(Primitive.NOTE) })
        assertEquals(listOf("errands", "errand", "shopping"), d[1].aliases)
    }

    @Test
    fun `the registry holds every Provider's Buckets, Inbox first`() {
        val other = Provider { listOf(Descriptor("garden", listOf("yard"), setOf(Primitive.NOTE))) }
        val r = BucketRegistry(listOf(capture(Bucket("work", emptyList())), other))
        assertEquals(listOf("inbox", "work", "garden"), r.buckets.map { it.name })
        assertSame(Descriptor.INBOX, r.inbox)
        assertEquals("garden", r.named("garden")?.name)
        assertNull(r.named("nowhere"))
        assertEquals(emptyList<String>(), r.rejected)
    }

    @Test
    fun `with no Provider there is still an Inbox`() {
        val r = BucketRegistry(emptyList())
        assertEquals(listOf(Descriptor.INBOX), r.buckets)
    }

    @Test
    fun `the Inbox is addressable by its name but cannot be Declared into`() {
        val r = BucketRegistry.capture(BucketConfig.DEFAULT)
        assertEquals("inbox", r.focusForms[listOf("inbox")])
        assertNull(r.declarationForms[listOf("inbox")])
        assertEquals("errands", r.declarationForms[listOf("errands")])
        assertEquals("house-project", r.declarationForms[listOf("houseproject")])
    }

    @Test
    fun `a phrase two Providers both claim is dropped from both`() {
        val hub = Provider { listOf(Descriptor("stsloop", listOf("work", "agent"), setOf(Primitive.NOTE))) }
        val r = BucketRegistry(listOf(capture(Bucket("work", emptyList())), hub))
        assertNull(r.focusForms[listOf("work")])
        assertEquals("stsloop", r.focusForms[listOf("agent")])
        assertTrue(r.rejected.single().contains("\"work\""), r.rejected.toString())
    }

    @Test
    fun `a second Bucket with a name already taken is dropped`() {
        val hub = Provider { listOf(Descriptor("work", listOf("job"), setOf(Primitive.NOTE))) }
        val r = BucketRegistry(listOf(capture(Bucket("work", emptyList())), hub))
        assertEquals(listOf("inbox", "work"), r.buckets.map { it.name })
        assertNull(r.focusForms[listOf("job")])
        assertEquals(1, r.rejected.size)
    }

    @Test
    fun `the Inbox's name is reserved`() {
        val named = BucketRegistry.capture(BucketConfig(listOf(Bucket("inbox", listOf("unsorted")), Bucket("work", emptyList()))))
        assertEquals(listOf("inbox", "work"), named.buckets.map { it.name })
        assertSame(Descriptor.INBOX, named.inbox)
        assertNull(named.focusForms[listOf("unsorted")])
        assertEquals(1, named.rejected.size, named.rejected.toString())
        val aliased = BucketRegistry.capture(BucketConfig(listOf(Bucket("work", listOf("inbox", "job")))))
        assertEquals("inbox", aliased.focusForms[listOf("inbox")])
        assertEquals("work", aliased.focusForms[listOf("job")])
        assertEquals(1, aliased.rejected.size, aliased.rejected.toString())
    }

    @Test
    fun `a Bucket that does not use note is addressable but not Declared into`() {
        val hub = Provider { listOf(Descriptor("stsloop", listOf("agent"), emptySet())) }
        val r = BucketRegistry(listOf(hub))
        assertEquals("stsloop", r.focusForms[listOf("agent")])
        assertNull(r.declarationForms[listOf("agent")])
    }
}
