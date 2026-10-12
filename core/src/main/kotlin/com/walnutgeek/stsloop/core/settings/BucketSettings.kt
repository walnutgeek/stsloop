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
 */
class BucketSettings(
    private val bucketsFile: String?,
    private val hubAliasesFile: String?,
    private val snapshot: Descriptors?,
) {
    /**
     * One Bucket on the screen: its [name], the [aliases] it is heard by, and
     * the Descriptor id of a hub Bucket ([hubId]; null for a capture Bucket).
     */
    data class Row(val name: String, val aliases: List<String>, val hubId: String?)

    private val config = bucketsFile?.let(BucketConfig::parse) ?: BucketConfig.Parsed(BucketConfig.DEFAULT, emptyList())
    private val hubAliases = hubAliasesFile?.let(HubAliases::parse) ?: HubAliases.Parsed(HubAliases.NONE, emptyList())
    private val registry = HubProvider.registry(config.config, hubAliases.aliases.applyTo(snapshot))

    /** What the files hold that a Session ignores, as `"where: why"`. */
    val rejected: List<String> = config.rejected + hubAliases.rejected + registry.rejected

    val rows: List<Row> = registry.buckets.filter { it !== registry.inbox }.map { d ->
        Row(d.name, d.aliases, if (d.hub) snapshot?.buckets?.firstOrNull { it.name == d.name }?.id else null)
    }

    /** The file that gives [row] exactly [aliases] (the name always counts as one too). */
    fun edit(row: Row, aliases: List<String>): Edit {
        if (aliases == row.aliases) return Edit.Unchanged
        problem(row, aliases)?.let { return Edit.Refused(it) }
        val save = if (row.hubId == null) captureEdit(row, aliases) else hubEdit(row.hubId, aliases)
        if (save !is Edit.Save) return save
        // A backstop for whatever the checks above miss: the Session must hear exactly what was asked, and nothing new may break.
        val after = if (row.hubId == null) BucketSettings(save.text, hubAliasesFile, snapshot) else BucketSettings(bucketsFile, save.text, snapshot)
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
                val heard = (listOf(other.name) + other.aliases).firstOrNull { p -> BucketConfig.formsOf(p).any { it in forms } }
                if (heard != null) return "${jsonString(alias)} is already heard as ${other.name}" + if (heard == other.name) "." else " (its alias ${jsonString(heard)})."
            }
        }
        return null
    }

    private fun captureEdit(row: Row, aliases: List<String>): Edit {
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
        list[at] = LinkedHashMap(list[at] as Map<*, *>).apply { put("aliases", aliases) }
        return Edit.Save(BucketConfig.FILE, JsonWriter.pretty(LinkedHashMap(root).apply { put("buckets", list) }))
    }

    private fun hubEdit(id: String, aliases: List<String>): Edit {
        hubAliases.rejected.firstOrNull { it.startsWith("file:") }?.let { return Edit.unusable(HubAliases.FILE, it.removePrefix("file: ")) }
        val own = snapshot?.buckets?.firstOrNull { it.id == id }?.aliases
        val byId = LinkedHashMap(hubAliases.aliases.byId)
        if (aliases == own) byId.remove(id) else byId[id] = aliases
        return Edit.Save(HubAliases.FILE, HubAliases(byId).toJson())
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
