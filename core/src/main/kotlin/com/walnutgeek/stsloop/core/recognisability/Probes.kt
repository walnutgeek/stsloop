package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.Words

/** Whether a phrase names a Bucket the person can give other aliases, or is a command they cannot change. */
enum class ProbeKind { BUCKET, COMMAND }

/**
 * One phrase the name-recognisability check speaks into the recogniser:
 * [spoken] is what the voice says (its words, apostrophes kept, so TTS does
 * not read a dash aloud), and [key] is how results are filed (its words as
 * the grammar reads them, see [Words]). [owner] is the Bucket's name for a
 * [ProbeKind.BUCKET] phrase, else the command's (`where_am_i`, or a hub
 * command's id); [hub] says the Bucket is the hub's.
 */
data class Probe(val spoken: String, val key: String, val kind: ProbeKind, val owner: String, val hub: Boolean = false)

/** Every phrase the loop listens for, as the check speaks it. */
object Probes {
    /**
     * Every Bucket name and alias of [registry], then every fixed command
     * phrase ([Command]), the Inbox's name, and every command phrase its
     * Buckets declare (those the phone runs, as the grammar hears them).
     * Each phrase once, by [Probe.key]: the first listing wins, so an alias
     * that is also a command phrase is the Bucket's.
     */
    fun of(registry: BucketRegistry): List<Probe> {
        val out = LinkedHashMap<String, Probe>()
        fun add(p: Probe?) {
            if (p != null && p.key !in out) out[p.key] = p
        }
        for (b in registry.buckets) {
            if (b === registry.inbox) continue
            for (phrase in listOf(b.name) + b.aliases) add(probe(phrase, ProbeKind.BUCKET, b.name, b.hub))
        }
        for (c in Command.entries) for (phrase in c.phrases) add(probe(phrase, ProbeKind.COMMAND, c.json))
        add(probe(registry.inbox.name, ProbeKind.COMMAND, registry.inbox.name))
        for (b in registry.buckets) for (c in b.commands) for (phrase in c.phrases) add(probe(phrase, ProbeKind.COMMAND, c.id, b.hub))
        return out.values.toList()
    }

    /** [phrase] as a probe; null when it has no words. */
    fun probe(phrase: String, kind: ProbeKind = ProbeKind.BUCKET, owner: String = phrase, hub: Boolean = false): Probe? {
        val text = Words.prepare(phrase)
        val words = Words.of(text)
        if (words.isEmpty()) return null
        return Probe(words.joinToString(" ") { text.substring(it.start, it.end) }, key(phrase), kind, owner, hub)
    }

    /** How a phrase is filed: its words, as the grammar reads them, separated by spaces. */
    fun key(phrase: String): String = Words.normalise(phrase).joinToString(" ")
}
