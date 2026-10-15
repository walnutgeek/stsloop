package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.jsonString

/**
 * The aliases the person gave hub Buckets on the phone, by Descriptor id
 * ([byId]). Each list replaces the aliases the hub's Provider declares, so a
 * badly-recognised one can be dropped as well as a better one added; a Bucket
 * with no entry keeps the hub's. The hub never sees them.
 *
 * [learned] are the aliases the phone learned for hub Buckets from what its
 * recogniser hears (#81), by Descriptor id. They are heard on top of whatever
 * aliases apply, so the hub's own stay in force, and are kept apart so the
 * person can see and remove them.
 *
 * Kept in `files/hub/aliases.json` beside the pairing ([HubStore]), and read at
 * Session start. Forgetting the hub keeps them, so pairing the same hub again
 * finds them in place:
 * ```json
 * { "aliases": { "herdr:stsloop": ["loop agent"] },
 *   "learned": { "herdr:stsloop": ["STS loop agent"] } }
 * ```
 */
data class HubAliases(val byId: Map<String, List<String>>, val learned: Map<String, List<String>> = emptyMap()) {
    /** [snapshot] with each Bucket's aliases replaced by the phone's, where it has some; [learned] are added by [HubProvider]. */
    fun applyTo(snapshot: Descriptors?): Descriptors? {
        if (snapshot == null || byId.isEmpty()) return snapshot
        return Descriptors(snapshot.buckets.map { d -> byId[d.id]?.let { d.copy(aliases = it) } ?: d })
    }

    /** The file format [parse] reads. */
    fun toJson(): String = JsonWriter.pretty(if (learned.isEmpty()) mapOf(ALIASES to byId) else linkedMapOf(ALIASES to byId, LEARNED to learned))

    /** The result of [parse]: the aliases to use, and each problem found, as `"where: why"`. */
    data class Parsed(val aliases: HubAliases, val rejected: List<String>)

    companion object {
        /** Path under the app's `files/`. */
        const val FILE = "${HubStore.DIR}/aliases.json"

        val NONE = HubAliases(emptyMap())

        private const val ALIASES = "aliases"
        private const val LEARNED = "learned"

        /**
         * Parses the file, falling back per entry: a file that is not a JSON
         * object with an `aliases` object means [NONE]; an entry that is not an
         * array is dropped, and so is an alias that is not a string with a word.
         * `learned` is read the same way, and may be missing.
         */
        fun parse(json: String): Parsed {
            val root = try {
                Json.parseObject(json)
            } catch (e: JsonException) {
                return Parsed(NONE, listOf("file: ${e.message}"))
            }
            val entries = root[ALIASES] as? Map<*, *> ?: return Parsed(NONE, listOf("file: must have an \"$ALIASES\" object"))
            val rejected = mutableListOf<String>()
            for (key in root.keys) if (key != ALIASES && key != LEARNED) rejected += "$key: unknown key; known keys: [$ALIASES, $LEARNED]"
            val learned = when (val raw = root[LEARNED]) {
                null -> emptyMap()
                is Map<*, *> -> lists(LEARNED, raw, rejected)
                else -> {
                    rejected += "$LEARNED: must be an object; dropped"
                    emptyMap()
                }
            }
            return Parsed(HubAliases(lists(ALIASES, entries, rejected), learned), rejected)
        }

        private fun lists(key: String, entries: Map<*, *>, rejected: MutableList<String>): Map<String, List<String>> {
            val byId = LinkedHashMap<String, List<String>>()
            for ((id, raw) in entries) {
                val at = "$key[${jsonString(id as String)}]"
                if (raw !is List<*>) {
                    rejected += "$at: must be an array of strings; dropped"
                    continue
                }
                byId[id] = raw.filter { a ->
                    val ok = a is String && BucketConfig.formsOf(a).isNotEmpty()
                    if (!ok) rejected += "$at: alias ${if (a is String) jsonString(a) else a.toString()} must be a string with a word; dropped"
                    ok
                }.map { it as String }
            }
            return byId
        }
    }
}
