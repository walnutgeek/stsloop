package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.jsonString

/**
 * A Bucket as configured: its canonical [name] (written to `turn.json` as
 * `bucket`) and the [aliases] it may be declared by aloud. The name is always
 * an alias too, so it need not be listed.
 */
data class Bucket(val name: String, val aliases: List<String>)

/**
 * The Buckets a Declaration may name (`docs/mvp.md`, "Phrase grammar").
 * Read at Session start from `files/buckets.json` in app storage, so it can be
 * edited without a rebuild.
 *
 * Every spoken phrase (a name or an alias) is compared as words, see [Words],
 * so "House-Project" and "house project" are the same phrase. A phrase of
 * several words is also heard run together ("houseproject"), since STT may
 * write it either way. No phrase, in any of those forms, may belong to two
 * Buckets: [parse] resolves such conflicts, and the constructor refuses them.
 */
data class BucketConfig(val buckets: List<Bucket>) {
    /** Every spoken form (as words) of every phrase → the Bucket's name. */
    internal val forms: Map<List<String>, String>

    init {
        val owner = HashMap<List<String>, String>()
        for (b in buckets) {
            require(Words.normalise(b.name).isNotEmpty()) { "Bucket name \"${b.name}\" has no words" }
            for (phrase in listOf(b.name) + b.aliases) {
                val f = formsOf(phrase)
                require(f.isNotEmpty()) { "${b.name}: alias \"$phrase\" has no words" }
                for (form in f) {
                    val prev = owner.put(form, b.name)
                    require(prev == null || prev == b.name) { "\"${form.joinToString(" ")}\" belongs to both $prev and ${b.name}" }
                }
            }
        }
        forms = owner
    }

    /** The file format [parse] reads. */
    fun toJson(): String = buckets.joinToString(",\n", prefix = "{\n  \"buckets\": [\n", postfix = "\n  ]\n}\n") { b ->
        "    { \"name\": ${quote(b.name)}, \"aliases\": ${b.aliases.joinToString(", ", "[", "]") { quote(it) }} }"
    }

    /** The result of [parse]: the config to use, and each problem found, as `"where: why"`. */
    data class Parsed(val config: BucketConfig, val rejected: List<String>)

    companion object {
        /** File name in app storage (`files/buckets.json`). */
        const val FILE = "buckets.json"

        /** The mvp.md Buckets, used when there is no file or it cannot be read as a whole. */
        val DEFAULT = BucketConfig(
            listOf(
                Bucket("errands", listOf("errands", "errand", "shopping")),
                Bucket("house-project", listOf("house project", "house", "the house")),
                Bucket("work", listOf("work", "worklog", "work log")),
                Bucket("ideas", listOf("idea", "ideas", "thought")),
            ),
        )

        private const val BUCKETS = "buckets"
        private const val NAME = "name"
        private const val ALIASES = "aliases"

        /** A phrase's words, plus the words run together when there are several. */
        internal fun formsOf(phrase: String): Set<List<String>> {
            val words = Words.normalise(phrase)
            return when {
                words.isEmpty() -> emptySet()
                words.size == 1 -> setOf(words)
                else -> setOf(words, listOf(words.joinToString("")))
            }
        }

        /**
         * Parses the file, falling back **per Bucket**, so one typo never
         * disables every Declaration:
         * - a file that is not strict JSON (a duplicate key included, as in every
         *   JSON file :core reads) or not an object with a `buckets` array
         *   gives [DEFAULT];
         * - a Bucket that is not an object, has no usable name, or repeats an
         *   earlier Bucket's name is dropped;
         * - an alias that is not a string or has no words is dropped, and so is
         *   an unknown key (the Bucket stays);
         * - an alias that is another Bucket's name is dropped (names win);
         * - an alias shared by two Buckets is dropped from both: either Bucket
         *   could be meant, and an unlabelled Turn is better than a wrong label.
         */
        fun parse(json: String): Parsed {
            val rejected = mutableListOf<String>()
            val root = try {
                Json.parseObject(json)
            } catch (e: JsonException) {
                return Parsed(DEFAULT, listOf("file: ${e.message}"))
            }
            val list = root[BUCKETS] as? List<*> ?: return Parsed(DEFAULT, listOf("file: must have a \"$BUCKETS\" array"))
            for (key in root.keys) if (key != BUCKETS) rejected += "$key: unknown key; known keys: [$BUCKETS]"

            class Draft(val label: String, val name: String, val aliases: MutableList<String>)

            val drafts = mutableListOf<Draft>()
            for ((index, values) in list.withIndex()) {
                val at = "$BUCKETS[$index]"
                if (values !is Map<*, *>) {
                    rejected += "$at: must be an object; Bucket dropped"
                    continue
                }
                val name = (values[NAME] as? String)?.trim()
                if (name == null || Words.normalise(name).isEmpty()) {
                    rejected += "$at: \"$NAME\" must be a string with at least one word; Bucket dropped"
                    continue
                }
                val label = "$at ($name)"
                val sameName = drafts.firstOrNull { d -> formsOf(d.name).any { it in formsOf(name) } }
                if (sameName != null) {
                    rejected += "$label: same name as ${sameName.label}; Bucket dropped"
                    continue
                }
                for (key in values.keys) if (key != NAME && key != ALIASES) rejected += "$label: $key: unknown key; known keys: [$NAME, $ALIASES]"
                val aliases = mutableListOf<String>()
                when (val raw = values[ALIASES]) {
                    null -> if (ALIASES in values) rejected += "$label: $ALIASES must be an array of strings"
                    !is List<*> -> rejected += "$label: $ALIASES must be an array of strings, was ${show(raw)}"
                    else -> for (a in raw) when {
                        a !is String -> rejected += "$label: alias ${show(a)} must be a string; dropped"
                        formsOf(a).isEmpty() -> rejected += "$label: alias ${quote(a)} has no words; dropped"
                        else -> aliases += a
                    }
                }
                drafts += Draft(label, name, aliases)
            }

            // Names win over aliases.
            for (d in drafts) {
                d.aliases.removeAll { alias ->
                    val other = drafts.firstOrNull { o -> o !== d && formsOf(o.name).any { it in formsOf(alias) } }
                    other?.let { rejected += "${d.label}: alias ${quote(alias)} is the name of ${it.label}; dropped" }
                    other != null
                }
            }
            // An alias two Buckets share is dropped from both.
            data class Claim(val draft: Draft, val alias: String, val forms: Set<List<String>>)

            val claims = drafts.flatMap { d -> d.aliases.map { Claim(d, it, formsOf(it)) } }
            val shared = mutableSetOf<Claim>()
            for ((i, c) in claims.withIndex()) {
                for (earlier in claims.subList(0, i)) {
                    if (earlier.draft !== c.draft && earlier.forms.any { it in c.forms }) {
                        rejected += "${c.draft.label}: alias ${quote(c.alias)} is also alias ${quote(earlier.alias)} " +
                            "of ${earlier.draft.label}; dropped from both"
                        shared += c
                        shared += earlier
                    }
                }
            }
            for (c in shared) c.draft.aliases.removeAll { it == c.alias }
            return Parsed(BucketConfig(drafts.map { Bucket(it.name, it.aliases.toList()) }), rejected)
        }

        private fun show(v: Any?): String = when (v) {
            is String -> quote(v)
            is Map<*, *> -> "an object"
            is List<*> -> "an array"
            else -> v.toString()
        }

        private fun quote(s: String): String = jsonString(s)
    }
}
