package com.walnutgeek.stsloop.core.settings

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.jsonString
import com.walnutgeek.stsloop.core.recognisability.AliasLearner
import com.walnutgeek.stsloop.core.recognisability.LearnedAlias
import com.walnutgeek.stsloop.core.recognisability.NameCheckResults
import com.walnutgeek.stsloop.core.recognisability.Probe
import com.walnutgeek.stsloop.core.recognisability.Probes

/**
 * The settings screen's Buckets and their aliases, as the next Session would
 * hear them: the capture Buckets of `files/buckets.json` ([bucketsFile]), then
 * the hub Buckets of the cached [snapshot] with the phone's own aliases for
 * them (`files/hub/aliases.json`, [hubAliasesFile]); each file null when there
 * is none. The Inbox is not listed: it has no aliases to edit.
 *
 * A capture Bucket's aliases are written into its entry in `buckets.json`,
 * keeping every other entry and key of the file as it was. A hub Bucket's go
 * to [HubAliases]. An edit is refused when one of its aliases has no words, is
 * the Inbox's, or is already heard as another Bucket, since the Session would
 * otherwise drop it from both and leave neither Bucket reachable by it. A
 * file a Session cannot use as a whole is refused, not replaced ([Edit.unusable]).
 *
 * Learned aliases (#81) go to the same files, kept apart from the person's:
 * a capture Bucket's under `learned` in its `buckets.json` entry, a hub
 * Bucket's under `learned` in [HubAliases]. They are added by [learn] and
 * removed by [unlearn], each checked like an edit.
 */
class BucketSettings(
    private val bucketsFile: String?,
    private val hubAliasesFile: String?,
    private val snapshot: Descriptors?,
) {
    /**
     * One Bucket on the screen: its [name], the [aliases] it is heard by, and
     * the Descriptor id of a hub Bucket ([hubId]; null for a capture Bucket).
     * [learned] are the aliases the phone learned for it, heard as well.
     */
    data class Row(val name: String, val aliases: List<String>, val hubId: String?, val learned: List<String> = emptyList())

    private val config = bucketsFile?.let(BucketConfig::parse) ?: BucketConfig.Parsed(BucketConfig.DEFAULT, emptyList())
    private val hubAliases = hubAliasesFile?.let(HubAliases::parse) ?: HubAliases.Parsed(HubAliases.NONE, emptyList())
    /** The Buckets as the next Session would hear them. */
    val registry = HubProvider.registry(config.config, snapshot, hubAliases.aliases)

    /** What the files hold that a Session ignores, as `"where: why"`. */
    val rejected: List<String> = config.rejected + hubAliases.rejected + registry.rejected

    val rows: List<Row> = registry.buckets.filter { it !== registry.inbox }.map { d ->
        Row(d.name, d.aliases, if (d.hub) snapshot?.buckets?.firstOrNull { it.name == d.name }?.id else null, d.learned)
    }

    /** Every phrase the next Session listens for, as the name-recognisability check speaks it. */
    val probes: List<Probe> by lazy { Probes.of(registry) }

    /** These settings once [save] is written. */
    fun with(save: Edit.Save): BucketSettings = when (save.file) {
        BucketConfig.FILE -> BucketSettings(save.text, hubAliasesFile, snapshot)
        HubAliases.FILE -> BucketSettings(bucketsFile, save.text, snapshot)
        else -> throw IllegalArgumentException("not a Bucket file: ${save.file}")
    }

    /**
     * What learning aliases from [results] on [model] does (#81): the new
     * text of each file that changes ([saves]), the aliases [learned], to
     * record in [NameCheckResults], and each alias [refused], with why.
     */
    data class Learning(val saves: List<Edit.Save>, val learned: List<LearnedAlias>, val refused: List<AliasLearner.Refusal>)

    /**
     * Adds to each Bucket the aliases [AliasLearner] finds in [results] on
     * [model]. Each is checked like an edit, so one that would break another
     * Bucket is refused, and the files keep everything else they hold.
     */
    fun learn(results: NameCheckResults, model: String): Learning {
        val plan = AliasLearner.plan(results, probes, model)
        val saves = LinkedHashMap<String, Edit.Save>()
        val learned = mutableListOf<LearnedAlias>()
        val refused = plan.refused.toMutableList()
        var s = this
        for (c in plan.learn) {
            val row = s.rows.firstOrNull { it.name == c.probe.owner } ?: continue
            when (val e = s.setLearned(row, row.learned + c.alias)) {
                is Edit.Save -> {
                    saves[e.file] = e
                    s = s.with(e)
                    learned += LearnedAlias(c.probe.key, model, c.alias)
                }
                is Edit.Refused -> refused += AliasLearner.Refusal(c.probe, c.alias, e.problem)
                Edit.Unchanged -> {}
            }
        }
        return Learning(saves.values.toList(), learned, refused)
    }

    /** The file without [alias] among [row]'s learned aliases. The caller marks it removed ([NameCheckResults.unlearned]), so it is not learned again. */
    fun unlearn(row: Row, alias: String): Edit {
        val forms = BucketConfig.formsOf(alias)
        val kept = row.learned.filterNot { a -> BucketConfig.formsOf(a).any { it in forms } }
        return if (kept == row.learned) Edit.Unchanged else setLearned(row, kept)
    }

    private fun setLearned(row: Row, learned: List<String>): Edit {
        val save = if (row.hubId == null) captureEdit(row, BucketConfig.LEARNED, learned) else hubLearnedEdit(row.hubId, learned)
        if (save !is Edit.Save) return save
        val after = with(save)
        after.rejected.firstOrNull { it !in rejected }?.let { return Edit.Refused("That would break another Bucket: $it") }
        if (after.rows.firstOrNull { it.name == row.name }?.learned != learned) return Edit.Refused("Could not set the learned aliases of ${row.name}.")
        return save
    }

    /** The file that gives [row] exactly [aliases] (the name always counts as one too). */
    fun edit(row: Row, aliases: List<String>): Edit {
        if (aliases == row.aliases) return Edit.Unchanged
        problem(row, aliases)?.let { return Edit.Refused(it) }
        val save = if (row.hubId == null) captureEdit(row, "aliases", aliases) else hubEdit(row.hubId, aliases)
        if (save !is Edit.Save) return save
        // A backstop for whatever the checks above miss: the Session must hear exactly what was asked, and nothing new may break.
        val after = with(save)
        after.rejected.firstOrNull { it !in rejected }?.let { return Edit.Refused("That would break another Bucket: $it") }
        if (after.rows.firstOrNull { it.name == row.name }?.aliases != aliases) return Edit.Refused("Could not set the aliases of ${row.name}.")
        return save
    }

    private fun problem(row: Row, aliases: List<String>): String? {
        val inbox = BucketConfig.formsOf(Descriptor.INBOX.name)
        for (alias in aliases) {
            val forms = BucketConfig.formsOf(alias)
            if (forms.isEmpty()) return "${jsonString(alias)} has no words."
            if (forms.any { it in inbox }) return "${jsonString(alias)} is reserved for the Inbox."
            for (other in registry.buckets) {
                if (other === registry.inbox || other.name == row.name) continue
                val heard = other.phrases.firstOrNull { p -> BucketConfig.formsOf(p).any { it in forms } }
                if (heard != null) return "${jsonString(alias)} is already heard as ${other.name}" + if (heard == other.name) "." else " (its alias ${jsonString(heard)})."
            }
        }
        return null
    }

    /** `buckets.json` with [row]'s entry's [key] set to [phrases]; an empty `learned` is left out. */
    private fun captureEdit(row: Row, key: String, phrases: List<String>): Edit {
        val root = if (bucketsFile == null) {
            Json.parseObject(BucketConfig.DEFAULT.toJson()) // what a Session uses with no file
        } else {
            try {
                Json.parseObject(bucketsFile)
            } catch (e: JsonException) {
                return Edit.unusable(BucketConfig.FILE, e.message)
            }
        }
        if (root["buckets"] !is List<*>) return Edit.unusable(BucketConfig.FILE, "it has no \"buckets\" array")
        val list = (root["buckets"] as List<*>).toMutableList()
        val at = list.indexOfFirst { ((it as? Map<*, *>)?.get("name") as? String)?.trim() == row.name }
        if (at < 0) return Edit.Refused("${row.name} is no longer in ${BucketConfig.FILE}.")
        list[at] = LinkedHashMap(list[at] as Map<*, *>).apply { if (key == BucketConfig.LEARNED && phrases.isEmpty()) remove(key) else put(key, phrases) }
        return Edit.Save(BucketConfig.FILE, JsonWriter.pretty(LinkedHashMap(root).apply { put("buckets", list) }))
    }

    private fun hubEdit(id: String, aliases: List<String>): Edit {
        hubAliases.rejected.firstOrNull { it.startsWith("file:") }?.let { return Edit.unusable(HubAliases.FILE, it.removePrefix("file: ")) }
        val own = snapshot?.buckets?.firstOrNull { it.id == id }?.aliases
        val byId = LinkedHashMap(hubAliases.aliases.byId)
        if (aliases == own) byId.remove(id) else byId[id] = aliases
        return Edit.Save(HubAliases.FILE, hubAliases.aliases.copy(byId = byId).toJson())
    }

    private fun hubLearnedEdit(id: String, learned: List<String>): Edit {
        hubAliases.rejected.firstOrNull { it.startsWith("file:") }?.let { return Edit.unusable(HubAliases.FILE, it.removePrefix("file: ")) }
        val byId = LinkedHashMap(hubAliases.aliases.learned)
        if (learned.isEmpty()) byId.remove(id) else byId[id] = learned
        return Edit.Save(HubAliases.FILE, hubAliases.aliases.copy(learned = byId).toJson())
    }
}

/** A Bucket's aliases as the settings screen shows and takes them: separated by commas or new lines. */
object AliasText {
    /** Each alias of [text], trimmed, the empty ones dropped, and each kept once (as words, see [BucketConfig]). */
    fun parse(text: String): List<String> {
        val seen = mutableSetOf<Set<List<String>>>()
        return text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.filter { a ->
            val forms = BucketConfig.formsOf(a)
            forms.isEmpty() || seen.add(forms) // a phrase with no words is kept, for the edit to refuse
        }
    }

    fun format(aliases: List<String>): String = aliases.joinToString(", ")
}
