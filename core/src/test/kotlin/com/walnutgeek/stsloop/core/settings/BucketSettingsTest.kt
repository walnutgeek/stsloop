package com.walnutgeek.stsloop.core.settings

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.Fixtures
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BucketSettingsTest {
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors

    private fun save(e: Edit, file: String): String {
        assertTrue(e is Edit.Save, "expected a save, got $e")
        assertEquals(file, (e as Edit.Save).file)
        return e.text
    }

    private fun refused(e: Edit): String {
        assertTrue(e is Edit.Refused, "expected a refusal, got $e")
        return (e as Edit.Refused).problem
    }

    private fun BucketSettings.row(name: String) = rows.single { it.name == name }

    @Test
    fun `lists the capture Buckets, then the cached hub Buckets, never the Inbox`() {
        val s = BucketSettings(null, null, agents)
        assertEquals(listOf("errands", "house-project", "work", "ideas", "stsloop agent", "house site"), s.rows.map { it.name })
        assertEquals(listOf("errands", "errand", "shopping"), s.row("errands").aliases)
        assertEquals(null, s.row("errands").hubId)
        assertEquals("herdr:stsloop", s.row("stsloop agent").hubId)
        assertEquals(listOf("stsloop", "the loop agent"), s.row("stsloop agent").aliases)
    }

    @Test
    fun `with no hub there are only the capture Buckets`() {
        assertEquals(listOf("errands", "house-project", "work", "ideas"), BucketSettings(null, null, null).rows.map { it.name })
    }

    @Test
    fun `hub Buckets show the phone's aliases where it has some`() {
        val mine = HubAliases(mapOf("claude-code:house-site" to listOf("site"))).toJson()
        assertEquals(listOf("site"), BucketSettings(null, mine, agents).row("house site").aliases)
    }

    @Test
    fun `a capture alias edit rewrites that Bucket in buckets json, keeping the rest of the file`() {
        val adb = """{"buckets": [{"name": "errands", "aliases": ["errand"], "colour": "red"}, {"name": "work"}, 7]}"""
        val s = BucketSettings(adb, null, null)
        val text = save(s.edit(s.row("errands"), listOf("errand", "shopping")), BucketConfig.FILE)
        assertEquals(
            mapOf("buckets" to listOf(mapOf("name" to "errands", "aliases" to listOf("errand", "shopping"), "colour" to "red"), mapOf("name" to "work"), 7L)),
            Json.parseObject(text),
        )
        assertEquals(listOf("errand", "shopping"), BucketConfig.parse(text).config.buckets.first { it.name == "errands" }.aliases)
    }

    @Test
    fun `with no buckets file the edit starts from the defaults`() {
        val s = BucketSettings(null, null, null)
        val text = save(s.edit(s.row("work"), listOf("job")), BucketConfig.FILE)
        val config = BucketConfig.parse(text)
        assertEquals(emptyList<String>(), config.rejected)
        assertEquals(BucketConfig.DEFAULT.buckets.map { if (it.name == "work") it.copy(aliases = listOf("job")) else it }, config.config.buckets)
    }

    @Test
    fun `a hub alias edit is kept on the phone, by Bucket id`() {
        val s = BucketSettings(null, null, agents)
        val text = save(s.edit(s.row("stsloop agent"), listOf("loop agent")), HubAliases.FILE)
        val read = HubAliases.parse(text).aliases
        assertEquals(mapOf("herdr:stsloop" to listOf("loop agent")), read.byId)
        assertEquals(listOf("loop agent"), HubProvider.registry(BucketConfig.DEFAULT, read.applyTo(agents)).named("stsloop agent")!!.aliases)
    }

    @Test
    fun `setting a hub Bucket's aliases back to the hub's drops the phone's copy`() {
        val mine = HubAliases(mapOf("herdr:stsloop" to listOf("loop agent"), "claude-code:house-site" to listOf("site"))).toJson()
        val s = BucketSettings(null, mine, agents)
        val text = save(s.edit(s.row("stsloop agent"), listOf("stsloop", "the loop agent")), HubAliases.FILE)
        assertEquals(mapOf("claude-code:house-site" to listOf("site")), HubAliases.parse(text).aliases.byId)
    }

    @Test
    fun `an alias another Bucket is heard by is refused, naming that Bucket`() {
        val s = BucketSettings(null, null, agents)
        assertTrue(refused(s.edit(s.row("errands"), listOf("errand", "work log"))).contains("work"))
        assertTrue(refused(s.edit(s.row("errands"), listOf("ideas"))).contains("ideas"))
        assertTrue(refused(s.edit(s.row("errands"), listOf("the loop agent"))).contains("stsloop agent"))
        assertTrue(refused(s.edit(s.row("house site"), listOf("shopping"))).contains("errands"))
        assertTrue(refused(s.edit(s.row("errands"), listOf("houseproject"))).contains("house-project"), "run together, it is still house-project's")
    }

    @Test
    fun `the Inbox's name is refused as an alias`() {
        val s = BucketSettings(null, null, null)
        assertTrue(refused(s.edit(s.row("errands"), listOf("Inbox"))).contains("Inbox"))
    }

    @Test
    fun `an alias with no words is refused`() {
        val s = BucketSettings(null, null, null)
        refused(s.edit(s.row("errands"), listOf("--")))
    }

    @Test
    fun `an existing adb conflict elsewhere does not block an edit`() {
        // "job" is shared by work and ideas, so it is dropped from both; errands can still be edited.
        val adb = """{"buckets": [{"name": "errands"}, {"name": "work", "aliases": ["job"]}, {"name": "ideas", "aliases": ["job"]}]}"""
        val s = BucketSettings(adb, null, null)
        assertTrue(s.rejected.isNotEmpty())
        save(s.edit(s.row("errands"), listOf("shopping")), BucketConfig.FILE)
    }

    @Test
    fun `an empty alias list leaves only the name`() {
        val s = BucketSettings(null, null, null)
        val text = save(s.edit(s.row("errands"), emptyList()), BucketConfig.FILE)
        assertEquals(emptyList<String>(), BucketConfig.parse(text).config.buckets.first { it.name == "errands" }.aliases)
    }

    @Test
    fun `aliases are typed separated by commas or new lines, each kept once`() {
        assertEquals(listOf("errand", "the shop", "shopping"), AliasText.parse(" errand, the shop\nshopping,, Errand , \n"))
        assertEquals(emptyList<String>(), AliasText.parse("  "))
        assertEquals("errand, the shop", AliasText.format(listOf("errand", "the shop")))
    }
}
