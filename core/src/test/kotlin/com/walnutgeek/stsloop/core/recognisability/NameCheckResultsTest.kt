package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.Fixtures
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NameCheckResultsTest {
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors
    private val probes = Probes.of(HubProvider.registry(BucketConfig.DEFAULT, agents))
    private val model = "nemotron/int8 greedy_search"
    private fun probe(key: String) = probes.single { it.key == key }
    private fun bucket(vararg phrases: String) = phrases.map { Probes.probe(it, ProbeKind.BUCKET, phrases.first())!! }
    private fun heard(vararg texts: String) = texts.mapIndexed { i, t -> Heard("voice@$i", t) }

    /** Every probe checked and heard, except [misheard] → what it was heard as in every variant. */
    private fun allChecked(misheard: Map<String, String> = emptyMap()): NameCheckResults =
        probes.fold(NameCheckResults.EMPTY) { r, p ->
            r.with(p.key, model, heard(misheard[p.key] ?: p.spoken, misheard[p.key] ?: p.spoken), 1_000L)
        }

    @Test
    fun `nothing is checked at first, so every probe is pending`() {
        assertEquals(probes, NameCheckResults.EMPTY.pending(probes, model))
        assertNull(NameCheckResults.EMPTY.verdict("errands", model))
    }

    @Test
    fun `a result counts for its phrase and model only`() {
        val r = NameCheckResults.EMPTY.with("errands", model, heard("Errants.", "errands"), 1_000L)
        assertEquals(Level.SOMETIMES, r.verdict("errands", model)!!.level)
        assertNull(r.verdict("errands", "another-model greedy_search"), "a new model is checked again")
        assertTrue(probe("errands") !in r.pending(probes, model))
        assertTrue(probe("errands") in r.pending(probes, "another-model greedy_search"))
    }

    @Test
    fun `a phrase is looked up as words, however it is written`() {
        val r = NameCheckResults.EMPTY.with("house project", model, heard("house project"), 1_000L)
        assertEquals(Level.HEARD, r.verdict("House-Project", model)!!.level)
    }

    @Test
    fun `a newer result for the same phrase and model replaces the older`() {
        val r = NameCheckResults.EMPTY
            .with("errands", model, heard("Errants."), 1_000L)
            .with("errands", model, heard("errands"), 2_000L)
        assertEquals(Level.HEARD, r.verdict("errands", model)!!.level)
        assertEquals(1, r.results.size)
    }

    @Test
    fun `the flagged Bucket phrases are announced once, then never again`() {
        val r = allChecked(mapOf("stsloop" to "St. Loop.", "errands" to "Errants.", "which bucket" to "Rich bucket."))
            .with("errand", model, heard("errand", "Erin."), 1_000L)
        val say = r.toAnnounce(probes, model)
        // Commands are not the person's to rename, so the loop does not nag about them.
        assertEquals(listOf("errands", "errand", "stsloop"), say)
        assertEquals("hard to hear: errands, errand, stsloop. try other aliases in settings.", NameCheckResults.announcement(say))
        val after = r.announced(say, model)
        assertEquals(emptyList<String>(), after.toAnnounce(probes, model))
    }

    @Test
    fun `a phrase announced for one model is announced again when another model cannot hear it either`() {
        val other = "another-model greedy_search"
        val r = allChecked(mapOf("stsloop" to "St. Loop.")).announced(listOf("stsloop"), model)
            .with("stsloop", other, heard("St. Loop."), 2_000L)
        assertEquals(listOf("stsloop"), r.toAnnounce(probes, other))
    }

    @Test
    fun `a phrase heard as another phrase of its own Bucket is not announced`() {
        val r = allChecked(mapOf("worklog" to "Work log."))
        assertEquals(Level.HEARD, r.verdict(probes.single { it.key == "worklog" }, probes, model)!!.level)
        assertEquals(emptyList<String>(), r.toAnnounce(probes, model))
    }

    @Test
    fun `an unchecked phrase is not announced, and neither is one heard`() {
        val r = NameCheckResults.EMPTY.with("errands", model, heard("errands", "errands"), 1_000L)
        assertEquals(emptyList<String>(), r.toAnnounce(probes, model))
        assertTrue(r.pending(probes, model).isNotEmpty())
        assertNull(NameCheckResults.announcement(emptyList()))
    }

    @Test
    fun `round-trips through its file, announcements included`() {
        val r = allChecked(mapOf("stsloop" to "St. Loop.")).announced(listOf("stsloop"), model)
        val parsed = NameCheckResults.parse(r.toJson())
        assertEquals(emptyList<String>(), parsed.rejected)
        assertEquals(r, parsed.results)
    }

    @Test
    fun `an unreadable file is no results, and a bad entry is dropped alone`() {
        assertEquals(NameCheckResults.EMPTY, NameCheckResults.parse("not json").results)
        val text = """
            {"schema": 1, "results": [
              {"phrase": "errands", "model": "m", "heard": [{"variant": "a", "text": "errands"}], "checked_at": "2026-10-12T10:00:00.000Z"},
              {"phrase": "work", "model": "m", "heard": "nope"}
            ], "announced": [{"phrase": "stsloop", "model": "m"}, 7]}
        """.trimIndent()
        val parsed = NameCheckResults.parse(text)
        assertEquals(Level.HEARD, parsed.results.verdict("errands", "m")!!.level)
        assertNull(parsed.results.verdict("work", "m"))
        assertEquals(setOf(NameCheckResults.Announced("stsloop", "m")), parsed.results.announced)
        assertEquals(2, parsed.rejected.size)
    }

    @Test
    fun `the screen says how each phrase of a Bucket is heard`() {
        val r = allChecked(mapOf("stsloop" to "St. Loop."))
        assertEquals(
            "Hard to hear: \"stsloop\" (heard as \"St. Loop.\").",
            r.summary(bucket("stsloop agent", "stsloop", "the loop agent"), model),
        )
        assertEquals("Heard well: all 3.", r.summary(bucket("errands", "errand", "shopping"), model))
        assertEquals(
            "Not checked yet: \"bricks\".",
            r.summary(bucket("errands", "bricks"), model),
        )
        assertEquals("Not checked yet: no speech models.", r.summary(bucket("errands"), null))
    }

    @Test
    fun `sometimes-misheard and misheard phrases read differently on the screen`() {
        val r = NameCheckResults.EMPTY
            .with("errands", model, heard("errands", "Errants."), 1_000L)
            .with("errand", model, heard("Arrant.", "Arrant."), 1_000L)
        assertEquals(
            "Hard to hear: \"errand\" (heard as \"Arrant.\"). Sometimes misheard: \"errands\" (1 of 2, as \"Errants.\").",
            r.summary(bucket("errands", "errand"), model),
        )
    }
}
