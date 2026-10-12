package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.jsonString

/**
 * One of the fixed behaviours the phone runs for a Bucket (`CONTEXT.md`,
 * Primitive). A Descriptor composes them; the phone never runs Provider code.
 * `draft` and `speak_item` come later.
 */
enum class Primitive(val json: String) {
    /** File the Turn under the Bucket, as a Note. */
    NOTE("note"),

    /** Send a named command to the Bucket's Provider and speak its reply. */
    REQUEST("request"),
    ;

    companion object {
        /** The Primitive called [json] on the wire, or null when the phone does not know it. */
        fun of(json: String): Primitive? = entries.firstOrNull { it.json == json }
    }
}

/**
 * A command a Bucket declares (`docs/mvp.md`, "Commands"): the [phrases]
 * that are it while the Bucket has Focus, or whatever has Focus when
 * [anywhere], and the [primitive] that runs it. [id] names it to its Provider.
 */
data class BucketCommand(
    val id: String,
    val phrases: List<String>,
    val primitive: Primitive,
    val anywhere: Boolean = false,
)

/**
 * A Provider's description of one Bucket for the phone (`CONTEXT.md`,
 * Descriptor): its canonical [name] (written to `turn.json` as `bucket` or
 * `focus`), the [aliases] it may be addressed by aloud (the name is always
 * one too), the [primitives] it uses and the [commands] it declares. [hub]
 * says it is published by a Provider on the hub, so it can be named with the
 * hub offline; such a Bucket has the Descriptor [id] its Provider knows it
 * by. [degraded] says its Descriptor named a Primitive the phone does not
 * know, so only its `request` commands are kept (ADR-0006).
 */
data class Descriptor(
    val name: String,
    val aliases: List<String>,
    val primitives: Set<Primitive>,
    val hub: Boolean = false,
    val id: String? = null,
    val commands: List<BucketCommand> = emptyList(),
    val degraded: Boolean = false,
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
 *
 * It also holds each Bucket's [BucketCommand]s as words ([commandForms]),
 * and the ones heard whatever has Focus ([anywhereForms]). A command phrase
 * that is one of the loop's own is dropped (those always win), and so is an
 * anywhere phrase two Buckets declare; one that is also a Bucket's alias is
 * heard as the command.
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

    /** Each Bucket's name → its commands' phrases as words, heard while it has Focus. */
    internal val commandForms: Map<String, Map<List<String>, BucketCommand>>

    /** The phrases of every [BucketCommand.anywhere] command, as words → its Bucket's name and the command. */
    internal val anywhereForms: Map<List<String>, Pair<String, BucketCommand>>

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
        focusForms = owner
        val declarable = kept.filter(::takesNotes).map { it.name }.toSet()
        declarationForms = owner.filterValues { it in declarable }
        commandForms = kept.associate { d ->
            val forms = LinkedHashMap<List<String>, BucketCommand>()
            for (c in d.commands) for (phrase in c.phrases) {
                val words = Words.normalise(phrase)
                when {
                    words.isEmpty() -> problems += "${jsonString(d.name)}: command ${jsonString(c.id)} phrase ${jsonString(phrase)} has no words; dropped"
                    words in FIXED -> problems += "${jsonString(d.name)}: command ${jsonString(c.id)} phrase ${jsonString(phrase)} is a loop command; dropped"
                    forms.putIfAbsent(words, c) != null -> problems += "${jsonString(d.name)}: command phrase ${jsonString(phrase)} is declared twice; the first kept"
                }
            }
            d.name to forms
        }
        // An anywhere phrase two Buckets declare is dropped from both, like an alias; one that names a Bucket hides it.
        val anywhere = LinkedHashMap<List<String>, MutableList<Pair<String, BucketCommand>>>()
        for ((bucket, forms) in commandForms) for ((words, c) in forms) if (c.anywhere) anywhere.getOrPut(words) { mutableListOf() } += bucket to c
        anywhereForms = anywhere.mapNotNull { (words, claims) ->
            if (claims.size > 1) {
                problems += "${jsonString(words.joinToString(" "))}: a command anywhere for ${claims.joinToString(" and ") { it.first }}; dropped from each"
                null
            } else {
                owner[words]?.let { problems += "${jsonString(words.joinToString(" "))}: a command anywhere, so it no longer names $it" }
                words to claims.single()
            }
        }.toMap()
        rejected = problems
    }

    /** The Bucket called [name], or null. */
    fun named(name: String): Descriptor? = buckets.firstOrNull { it.name == name }

    /** Whether a Turn can be filed in [bucket] as a labelled Note: it uses [Primitive.NOTE] and is not the [inbox]. */
    fun takesNotes(bucket: Descriptor): Boolean = bucket !== inbox && Primitive.NOTE in bucket.primitives

    companion object {
        /** The phone alone, with no hub: the capture Provider over [config]. */
        fun capture(config: BucketConfig) = BucketRegistry(listOf(CaptureProvider(config)))

        /** The loop's own command phrases, as words: always matched first, so no Bucket command can have one. */
        private val FIXED: Set<List<String>> =
            com.walnutgeek.stsloop.core.Command.entries.flatMap { c -> c.phrases.map(Words::normalise) }.toSet()
    }
}
