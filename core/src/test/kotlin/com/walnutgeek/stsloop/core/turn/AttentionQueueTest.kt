package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.hub.Item
import com.walnutgeek.stsloop.core.turn.AttentionQueue.Offered.DUPLICATE
import com.walnutgeek.stsloop.core.turn.AttentionQueue.Offered.QUEUED
import com.walnutgeek.stsloop.core.turn.AttentionQueue.Offered.SPOKEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AttentionQueueTest {
    private val queue = AttentionQueue()
    private val stsloop = Item("a1", listOf("herdr:w8:p1"), "stsloop agent is blocked.")
    private val woodglue = Item("a2", listOf("herdr:w4:p1"), "woodglue agent is blocked.")

    @Test
    fun `items wait in order and are taken together`() {
        assertEquals(QUEUED, queue.offer(stsloop))
        assertEquals(QUEUED, queue.offer(woodglue))
        assertEquals(2, queue.size)
        assertEquals(listOf(stsloop, woodglue), queue.take())
        assertEquals(emptyList<Item>(), queue.take())
    }

    @Test
    fun `items taken together are said as one sentence after another`() {
        assertEquals("stsloop agent is blocked. woodglue agent is blocked.", AttentionQueue.text(listOf(stsloop, woodglue)))
        assertEquals("stsloop agent is blocked.", AttentionQueue.text(listOf(stsloop)))
    }

    @Test
    fun `an item sent again while it waits is ignored`() {
        queue.offer(stsloop)
        assertEquals(DUPLICATE, queue.offer(stsloop.copy()))
        assertEquals(listOf(stsloop), queue.take())
    }

    @Test
    fun `an item sent again while it is being said is ignored`() {
        queue.offer(stsloop)
        queue.take()
        assertEquals(DUPLICATE, queue.offer(stsloop))
        assertEquals(0, queue.size)
    }

    @Test
    fun `an item taken but never said is forgotten, so it is queued when sent again`() {
        queue.offer(stsloop)
        queue.forget(queue.take())
        assertEquals(QUEUED, queue.offer(stsloop))
    }

    @Test
    fun `an item sent again once said is not said again, and says so`() {
        queue.offer(stsloop)
        queue.spoken(queue.take())
        assertEquals(SPOKEN, queue.offer(stsloop))
        assertEquals(0, queue.size)
    }
}
