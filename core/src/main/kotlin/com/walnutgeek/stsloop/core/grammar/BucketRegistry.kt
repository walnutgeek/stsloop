package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.jsonString

/**
 * One of the fixed behaviours the phone runs for a Bucket (`CONTEXT.md`,
 * Primitive). A Descriptor composes them; the phone never runs Provider code.
 * Only `note` exists so far; `draft`, `speak_item` and `request` come with the
 * agent Buckets.
 */
enum class Primitive(val json: String) {
    /** File the Turn under the Bucket, as a Note. */
    NOTE("note"),
    ;

    companion object {
        /** The Primitive called [json] on the wire, or null when the phone does not know it. */
        fun of(json: String): Primitive? = entries.firstOrNull { it.json == json }
    }
}

/**
 * A Provider's description of one Bucket for the phone (`CONTEXT.md`,
 * Descriptor): its canonical [name] (written to `turn.json` as `bucket` or
 * `focus`), the [aliases] it may be addressed by aloud (the name is always
 * one too), and the [primitives] it uses. [hub] says it is published by a
 * Provider on the hub, so it can be named with the hub offline.
 */
data class Descriptor(
    val name: String,
    val aliases: List<String>,
    val primitives: Set<Primitive>,
    val hub: Boolean = false,
) {
    companion object {
        /**
         * The Inbox (`CONTEXT.md`): the capture Bucket Focus falls back to. A
         * Note in it is unlabelled, so it is never Declared into and its name
         * never reaches `bucket`. Its name is reserved.
         */
        val INBOX = Descriptor("inbox", emptyList(), setOf(Primitive.NOTE))
    }
}

/** Publishes a set of Buckets (`CONTEXT.md`, Provider): the plug-in boundary. */
fun interface Provider {
    fun descriptors(): List<Descriptor>
}

/**
 * The phone's own Provider: the Inbox, plus the capture Buckets configured in
 * `files/buckets.json` ([BucketConfig]), each using [Primitive.NOTE].
 */
class CaptureProvider(private val config: BucketConfig) : Provider {
    override fun descriptors(): List<Descriptor> =
        listOf(Descriptor.INBOX) + config.buckets.map { Descriptor(it.name, it.aliases, setOf(Primitive.NOTE)) }
}

/**
 * Every Bucket the phone can address, gathered from its [Provider]s
 * (ADR-0005), and the phrases each is heard by. The phrase grammar reads its
 * Buckets from here; the hub's join the capture Buckets through
 * `HubProvider`, and a new registry replaces this one when they change.
 *
 * Phrases are compared as words ([Words]), and a phrase of several words is
 * also heard run together, as in [BucketConfig]. Conflicts between Buckets
 * are resolved toward hearing nothing, because an unlabelled Turn is better
 * than a wrong one; each is reported in [rejected]:
 * - The [inbox] is always present and first, and its name is reserved: any
 *   other Bucket by that name is dropped, and so is any other alias of it.
 * - A Bucket whose name another Bucket already has is dropped.
 * - A phrase two Buckets both claim is dropped from both.
 */
class BucketRegistry(providers: List<Provider>) {
    /** The Inbox, the Bucket Focus falls back to. */
    val inbox: Descriptor = Descriptor.INBOX

    /** Every Bucket, the [inbox] first, then each Provider's in order. */
    val buckets: List<Descriptor>

    /** Each conflict found, as `"where: why"`. */
    val rejected: List<String>

    /** Every spoken form of every Bucket → its name: what a lone phrase may Focus. */
    internal val focusForms: Map<List<String>, String>

    /** The forms of the Buckets a Declaration may name: those using [Primitive.NOTE], the [inbox] aside. */
    internal val declarationForms: Map<List<String>, String>

    init {
        val problems = mutableListOf<String>()
        val kept = mutableListOf(inbox)
        val reserved = BucketConfig.formsOf(inbox.name)
        for (d in providers.flatMap { it.descriptors() }) {
            if (d === inbox) continue
            val name = BucketConfig.formsOf(d.name)
            when {
                name.isEmpty() -> problems += "${jsonString(d.name)}: the name has no words; Bucket dropped"
                kept.any { k -> BucketConfig.formsOf(k.name).any { it in name } } ->
                    problems += "${jsonString(d.name)}: the name is already taken; Bucket dropped"
                else -> kept += d
            }
        }
        // Who claims each form; the Inbox's forms are its alone.
        val claims = LinkedHashMap<List<String>, MutableSet<String>>()
        for (d in kept) {
            for (phrase in listOf(d.name) + d.aliases) {
                val forms = BucketConfig.formsOf(phrase)
                if (d !== inbox && forms.any { it in reserved }) {
                    problems += "${jsonString(d.name)}: alias ${jsonString(phrase)} is reserved for the Inbox; dropped"
                    continue
                }
                for (f in forms) claims.getOrPut(f) { linkedSetOf() } += d.name
            }
        }
        val owner = LinkedHashMap<List<String>, String>()
        for ((form, names) in claims) {
            if (names.size == 1) {
                owner[form] = names.single()
            } else {
                problems += "${jsonString(form.joinToString(" "))}: claimed by ${names.joinToString(" and ")}; dropped from each"
            }
        }
        buckets = kept
        rejected = problems
        focusForms = owner
        val declarable = kept.filter(::takesNotes).map { it.name }.toSet()
        declarationForms = owner.filterValues { it in declarable }
    }

    /** The Bucket called [name], or null. */
    fun named(name: String): Descriptor? = buckets.firstOrNull { it.name == name }

    /** Whether a Turn can be filed in [bucket] as a labelled Note: it uses [Primitive.NOTE] and is not the [inbox]. */
    fun takesNotes(bucket: Descriptor): Boolean = bucket !== inbox && Primitive.NOTE in bucket.primitives

    companion object {
        /** The phone alone, with no hub: the capture Provider over [config]. */
        fun capture(config: BucketConfig) = BucketRegistry(listOf(CaptureProvider(config)))

    }
}
