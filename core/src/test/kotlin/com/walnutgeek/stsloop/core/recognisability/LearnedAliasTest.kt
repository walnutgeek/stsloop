package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.grammar.Bucket
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.Fixtures
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Protocol
import com.walnutgeek.stsloop.core.settings.BucketSettings
import com.walnutgeek.stsloop.core.settings.Edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Learned aliases (#81): a Bucket phrase the recogniser consistently hears as
 * other words gains those words as an alias, unless they are already heard as
 * something else; a removal sticks for that phrase and model; the loop says
 * each once.
 */
class LearnedAliasTest {
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors
    private val model = "nemotron/int8 greedy_search"
    private val other = "another-model greedy_search"

    private fun heard(vararg texts: String) = texts.mapIndexed { i, t -> Heard("voice@$i", t) }
    private fun six(text: String) = heard(text, text, text, text, text, text)

    /** Every phrase [s] would hear checked on [on] and heard as itself, except [misheard] → its transcripts. */
    private fun checked(s: BucketSettings, misheard: Map<String, List<Heard>>, on: String = model, from: NameCheckResults = NameCheckResults.EMPTY) =
        s.probes.fold(from) { r, p -> r.with(p.key, on, misheard[p.key] ?: six(p.spoken), 1_000L) }

    /** The settings, and the Session's registry, after [learning]'s files are written over [s]'s. */
    private fun after(s: BucketSettings, saves: List<Edit.Save>): BucketSettings = saves.fold(s) { acc, save -> acc.with(save) }

    // --- what counts as heard consistently ---

    @Test
    fun `six of six the same words, as the recogniser wrote them, punctuation and fillers aside`() {
        assertEquals("STS loop agent", AliasLearner.consistent(six("STS loop agent.")))
        assertEquals("STS loop", AliasLearner.consistent(heard("um, STS loop.", "STS loop", "sts loop", "STS LOOP!", "STS loop", "STS loop")))
    }

    @Test
    fun `five of six is enough, four is not`() {
        assertEquals("Wood glue", AliasLearner.consistent(heard("Wood glue.", "Wood glue.", "Wood glue.", "Wood glue.", "Wood glue.", "would glue")))
        assertNull(AliasLearner.consistent(heard("Wood glue.", "Wood glue.", "Wood glue.", "Wood glue.", "would glue", "would glue")))
    }

    @Test
    fun `nothing heard is never consistent`() {
        assertNull(AliasLearner.consistent(six("")))
        assertNull(AliasLearner.consistent(heard("", "", "", "", "", "Hub.")))
        assertNull(AliasLearner.consistent(emptyList()))
    }

    @Test
    fun `the words run together count as the same, and the split form is learned since it hears both`() {
        assertEquals("STS loop", AliasLearner.consistent(heard("STSloop", "STS loop", "STS loop", "STSloop", "STS loop", "STS loop")))
    }

    // --- learning ---

    @Test
    fun `stsloop agent gains what the phone hears, and a Session focuses it by that`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent."), "stsloop" to six("STS loop.")))
        val learning = s.learn(r, model)
        assertEquals(
            listOf(LearnedAlias("stsloop agent", model, "STS loop agent"), LearnedAlias("stsloop", model, "STS loop")),
            learning.learned,
        )
        assertEquals(emptyList<AliasLearner.Refusal>(), learning.refused)
        // A hub Bucket's learned aliases live with the phone's hub alias overrides.
        assertEquals(listOf(HubAliases.FILE), learning.saves.map { it.file })
        val hub = HubAliases.parse(learning.saves.single().text).aliases
        assertEquals(mapOf("herdr:stsloop" to listOf("STS loop agent", "STS loop")), hub.learned)
        assertEquals(emptyMap<String, List<String>>(), hub.byId, "the hub's own aliases stay in force")

        val registry = HubProvider.registry(BucketConfig.DEFAULT, agents, hub)
        val grammar = PhraseGrammar(registry)
        fun focus(text: String) = grammar.classify(text).command
        val stsloop = CommandInvocation(Command.FOCUS, "", focus = "stsloop agent", focusId = "herdr:stsloop")
        assertEquals(stsloop.copy(matched = "sts loop agent"), focus("STS loop agent."))
        assertEquals(stsloop.copy(matched = "sts loop"), focus("STS loop"))
        assertEquals(stsloop.copy(matched = "the loop agent"), focus("the loop agent"))

        val row = after(s, learning.saves).rows.single { it.name == "stsloop agent" }
        assertEquals(listOf("stsloop", "the loop agent"), row.aliases)
        assertEquals(listOf("STS loop agent", "STS loop"), row.learned)
        // The phrase is now heard, by its Bucket's learned alias, so it is no longer flagged.
        val probes = after(s, learning.saves).probes
        assertEquals(Level.HEARD, r.verdict(probes.single { it.key == "stsloop agent" }, probes, model)!!.level)
    }

    @Test
    fun `a capture Bucket's learned alias goes into its entry in buckets json, keeping the rest of the file`() {
        val adb = """{"buckets": [{"name": "woodglue", "aliases": ["glue"], "colour": "brown"}, {"name": "work"}]}"""
        val s = BucketSettings(adb, null, null)
        val learning = s.learn(checked(s, mapOf("woodglue" to six("Wood glue."))), model)
        assertEquals(listOf(LearnedAlias("woodglue", model, "Wood glue")), learning.learned)
        val text = learning.saves.single { it.file == BucketConfig.FILE }.text
        assertEquals(
            Bucket("woodglue", listOf("glue"), learned = listOf("Wood glue")),
            BucketConfig.parse(text).config.buckets.first(),
        )
        assertEquals("brown", ((Json.parseObject(text)["buckets"] as List<*>)[0] as Map<*, *>)["colour"], text)
        assertEquals("woodglue", PhraseGrammar(BucketConfig.parse(text).config).classify("wood glue").command?.focus)
    }

    @Test
    fun `a phrase heard well, or not consistently, learns nothing`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop" to heard("STS loop", "STS loop", "STS loop", "St. Loop", "Est loop", "STS loop")))
        assertEquals(LearningOf(), s.learn(r, model).let { LearningOf(it.learned, it.refused, it.saves) })
        // "errand" heard 5/6 as itself is heard, not misheard as other words.
        val e = checked(s, mapOf("errand" to heard("errand", "errand", "errand", "errand", "errand", "Erin.")))
        assertEquals(emptyList<LearnedAlias>(), s.learn(e, model).learned)
    }

    @Test
    fun `an alias already heard as another Bucket is refused, and reported`() {
        // "house" is an alias of house-project.
        val s = BucketSettings(null, null, agents)
        val learning = s.learn(checked(s, mapOf("house site" to six("House."))), model)
        assertEquals(emptyList<LearnedAlias>(), learning.learned)
        assertEquals(emptyList<Edit.Save>(), learning.saves)
        val refusal = learning.refused.single()
        assertEquals("house site", refusal.probe.key)
        assertEquals("House", refusal.alias)
        assertEquals("\"House\" is already heard as house-project.", refusal.why)
        assertEquals("Not learned: \"house site\" is heard as \"House\", which is already heard as house-project.", refusal.message)
    }

    @Test
    fun `an alias that is a loop command or a Bucket's command is refused`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop" to six("Repeat."), "the loop agent" to six("Status."), "house site" to six("Inbox.")))
        val refused = s.learn(r, model).refused.associate { it.probe.key to it.why }
        assertEquals(
            mapOf(
                "stsloop" to "\"Repeat\" is already the command \"repeat\".",
                "the loop agent" to "\"Status\" is already the command \"status\".",
                "house site" to "\"Inbox\" is already the command \"inbox\".",
            ),
            refused,
        )
    }

    @Test
    fun `command phrases come from the live grammar, so a hub's new command is refused too`() {
        val hub = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json").replace("\"quiet\"", "\"wood glue\"")) as Descriptors
        val s = BucketSettings("""{"buckets": [{"name": "woodglue"}]}""", null, hub)
        val learning = s.learn(checked(s, mapOf("woodglue" to six("Wood glue."))), model)
        assertEquals("\"Wood glue\" is already the command \"wood glue\".", learning.refused.single().why)
    }

    @Test
    fun `two Buckets heard as the same words both go without`() {
        val s = BucketSettings("""{"buckets": [{"name": "woodglue"}, {"name": "woodgloo"}]}""", null, null)
        val learning = s.learn(checked(s, mapOf("woodglue" to six("Would glue."), "woodgloo" to six("Would glue."))), model)
        assertEquals(emptyList<LearnedAlias>(), learning.learned)
        assertEquals(listOf("woodglue", "woodgloo"), learning.refused.map { it.probe.key })
        assertEquals("\"Would glue\" is also what woodgloo is heard as.", learning.refused[0].why)
    }

    @Test
    fun `words that run together into another Bucket's name are already heard as it`() {
        val s = BucketSettings("""{"buckets": [{"name": "woodglue"}, {"name": "woodgloo"}]}""", null, null)
        val learning = s.learn(checked(s, mapOf("woodglue" to six("Wood glue."), "woodgloo" to six("Wood glue."))), model)
        assertEquals(listOf(LearnedAlias("woodglue", model, "Wood glue")), learning.learned)
        assertEquals("\"Wood glue\" is already heard as woodglue.", learning.refused.single().why)
    }

    @Test
    fun `an alias is learned once`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent.")))
        val once = after(s, s.learn(r, model).saves)
        val again = once.learn(checked(once, mapOf("stsloop agent" to six("STS loop agent.")), from = r), model)
        assertEquals(LearningOf(), LearningOf(again.learned, again.refused, again.saves))
    }

    // --- removal ---

    @Test
    fun `a removed learned alias is not learned again for that name and model, but is for another model`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent.")))
        val learning = s.learn(r, model)
        val learned = after(s, learning.saves)
        val withRecord = r.learned(learning.learned)

        val row = learned.rows.single { it.name == "stsloop agent" }
        val removed = after(learned, listOf(learned.unlearn(row, "STS loop agent") as Edit.Save))
        assertEquals(emptyList<String>(), removed.rows.single { it.name == "stsloop agent" }.learned)
        assertEquals(mapOf<String, List<String>>(), HubAliases.parse((learned.unlearn(row, "STS loop agent") as Edit.Save).text).aliases.learned)

        val results = withRecord.unlearned("STS loop agent")
        assertEquals(listOf(RemovedAlias("stsloop agent", model, "STS loop agent")), results.removed)
        assertEquals(emptyList<LearnedAlias>(), removed.learn(results, model).learned, "the removal sticks")

        val newModel = checked(removed, mapOf("stsloop agent" to six("STS loop agent.")), on = other, from = results)
        assertEquals(listOf(LearnedAlias("stsloop agent", other, "STS loop agent")), removed.learn(newModel, other).learned)
    }

    @Test
    fun `a removal sticks even for a learned alias with no record, as one written over adb`() {
        val s = BucketSettings(null, HubAliases(emptyMap(), mapOf("herdr:stsloop" to listOf("STS loop agent"))).toJson(), agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent.")))
        val row = s.rows.single { it.name == "stsloop agent" }
        val removed = after(s, listOf(s.unlearn(row, "STS loop agent") as Edit.Save))
        assertEquals(emptyList<LearnedAlias>(), removed.learn(r.unlearned("STS loop agent"), model).learned)
    }

    @Test
    fun `editing the aliases keeps the learned ones, and a typed alias may not be another Bucket's learned one`() {
        val s = BucketSettings(null, HubAliases(emptyMap(), mapOf("herdr:stsloop" to listOf("Est loop"))).toJson(), agents)
        val row = s.rows.single { it.name == "stsloop agent" }
        val e = s.edit(row, listOf("stsloop"))
        val edited = after(s, listOf(e as Edit.Save)).rows.single { it.name == "stsloop agent" }
        assertEquals(listOf("stsloop"), edited.aliases)
        assertEquals(listOf("Est loop"), edited.learned)
        val refusal = s.edit(s.rows.single { it.name == "errands" }, listOf("est loop"))
        assertEquals(Edit.Refused("\"est loop\" is already heard as stsloop agent (its alias \"Est loop\")."), refusal)
    }

    // --- the announcement ---

    @Test
    fun `the loop says each learned alias once, by its Bucket's name`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent."), "stsloop" to six("STS loop.")))
        val learning = s.learn(r, model)
        val registry = after(s, learning.saves).registry
        val (first, say) = r.learned(learning.learned).announce(registry, model)
        assertEquals("stsloop agent also answers to STS loop agent and STS loop.", say)
        val (_, again) = first.announce(registry, model)
        assertNull(again, "said once, not every Session")
    }

    @Test
    fun `a learned alias is announced before what is still hard to hear, and is itself never hard to hear`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent."), "house site" to six("House.")))
        val learning = s.learn(r, model)
        val registry = after(s, learning.saves).registry
        val (_, say) = r.learned(learning.learned).announce(registry, model)
        assertEquals("stsloop agent also answers to STS loop agent. hard to hear: house site. try other aliases in settings.", say)
    }

    @Test
    fun `a learned alias no longer heard, removed say, is not announced`() {
        val s = BucketSettings(null, null, agents)
        val r = checked(s, mapOf("stsloop agent" to six("STS loop agent.")))
        val learning = s.learn(r, model)
        val (_, say) = r.learned(learning.learned).announce(s.registry, model)
        assertEquals("hard to hear: stsloop agent. try other aliases in settings.", say)
    }

    @Test
    fun `learned and removed aliases round-trip through the results file`() {
        val r = NameCheckResults.EMPTY.with("stsloop", model, six("STS loop."), 1_000L)
            .learned(listOf(LearnedAlias("stsloop", model, "STS loop"), LearnedAlias("woodglue", model, "Wood glue", announced = true)))
            .unlearned("Wood glue")
        assertEquals(NameCheckResults.Parsed(r, emptyList()), NameCheckResults.parse(r.toJson()))
        assertEquals(listOf(LearnedAlias("stsloop", model, "STS loop")), r.learned)
    }

    @Test
    fun `hub learned aliases round-trip through their file and are dropped per entry`() {
        val a = HubAliases(mapOf("hub" to listOf("the hub")), mapOf("herdr:stsloop" to listOf("STS loop")))
        assertEquals(HubAliases.Parsed(a, emptyList()), HubAliases.parse(a.toJson()))
        val p = HubAliases.parse("""{"aliases": {}, "learned": {"a": ["x", 3], "b": "y"}}""")
        assertEquals(mapOf("a" to listOf("x")), p.aliases.learned)
        assertEquals(2, p.rejected.size, p.rejected.toString())
    }

    @Test
    fun `a learned capture alias that another Bucket names is dropped like any alias`() {
        val p = BucketConfig.parse("""{"buckets": [{"name": "work", "learned": ["errands", "work log"]}, {"name": "errands"}]}""")
        assertEquals(listOf("work log"), p.config.buckets.first().learned)
        assertEquals(1, p.rejected.size, p.rejected.toString())
    }

    private data class LearningOf(
        val learned: List<LearnedAlias> = emptyList(),
        val refused: List<AliasLearner.Refusal> = emptyList(),
        val saves: List<Edit.Save> = emptyList(),
    )
}
