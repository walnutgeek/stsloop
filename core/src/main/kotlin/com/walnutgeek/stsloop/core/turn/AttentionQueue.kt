package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.hub.Item

/**
 * The Attention Queue (`CONTEXT.md`): Inbound [Item]s waiting for a machine
 * Turn, for one Session. [HalfDuplex] drains it into Silence, taking every
 * Item waiting at that moment as one machine Turn ([take], [text]): Items
 * that arrived together, or while the person was talking, are said together
 * after one earcon.
 *
 * Items are deduplicated by id, so a hub that sends an Item again after a
 * reconnect is never heard twice: an Item already waiting, or taken, is
 * ignored, and one already said is answered [Offered.SPOKEN], so the hub can
 * be told again that it was.
 *
 * Thread-safe: Items arrive on the hub connection's thread, and are taken
 * on the capture thread.
 */
class AttentionQueue {
    /** What [offer] made of an Item. */
    enum class Offered {
        /** New: it waits for Silence. */
        QUEUED,

        /** Already waiting, or being said: ignored. */
        DUPLICATE,

        /** Already said this Session: not said again; the hub should hear it was. */
        SPOKEN,
    }

    private val waiting = ArrayList<Item>()
    private val known = HashSet<String>()
    private val said = HashSet<String>()

    @Synchronized
    fun offer(item: Item): Offered = when {
        item.id in said -> Offered.SPOKEN
        !known.add(item.id) -> Offered.DUPLICATE
        else -> {
            waiting += item
            Offered.QUEUED
        }
    }

    /** Every Item waiting, oldest first, and none waits any more; empty when none is. */
    @Synchronized
    fun take(): List<Item> = waiting.toList().also { waiting.clear() }

    /** [items], once taken, have been said. */
    @Synchronized
    fun spoken(items: List<Item>) {
        for (item in items) said += item.id
    }

    /**
     * [items], once taken, were never said (their machine Turn was lost, or
     * the Session ended): forgotten, so an Item the hub sends again is queued.
     */
    @Synchronized
    fun forget(items: List<Item>) {
        for (item in items) known -= item.id
    }

    /** How many Items are waiting. */
    val size: Int @Synchronized get() = waiting.size

    companion object {
        /** What one machine Turn says for [items], after its one earcon. */
        fun text(items: List<Item>): String = items.joinToString(" ") { it.say.trim() }
    }
}
