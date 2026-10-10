package com.walnutgeek.stsloop.core.grammar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BucketConfigTest {
    private fun ok(json: String): BucketConfig = BucketConfig.parse(json).also {
        assertEquals(emptyList<String>(), it.rejected, "unexpected rejections")
    }.config

    /** Parses [json], expecting rejections that start with exactly [rejected], in order. */
    private fun rejecting(json: String, vararg rejected: String): BucketConfig {
        val p = BucketConfig.parse(json)
        assertEquals(rejected.size, p.rejected.size, p.rejected.toString())
        for ((want, got) in rejected.zip(p.rejected)) assertTrue(got.startsWith(want), "expected '$want…', got '$got'")
        return p.config
    }

    private val mvpJson = """
        {
          "buckets": [
            { "name": "errands",      "aliases": ["errands", "errand", "shopping"] },
            { "name": "house-project", "aliases": ["house project", "house", "the house"] },
            { "name": "work",          "aliases": ["work", "worklog", "work log"] },
            { "name": "ideas",         "aliases": ["idea", "ideas", "thought"] }
          ]
        }
    """.trimIndent()

    // --- defaults ---

    @Test
    fun `the defaults are the four mvp Buckets`() {
        assertEquals(listOf("errands", "house-project", "work", "ideas"), BucketConfig.DEFAULT.buckets.map { it.name })
        assertEquals(listOf("house project", "house", "the house"), BucketConfig.DEFAULT.buckets[1].aliases)
    }

    @Test
    fun `the mvp config file parses to the defaults`() = assertEquals(BucketConfig.DEFAULT, ok(mvpJson))

    @Test
    fun `the defaults round-trip through toJson`() = assertEquals(BucketConfig.DEFAULT, ok(BucketConfig.DEFAULT.toJson()))

    @Test
    fun `the file name is buckets json`() = assertEquals("buckets.json", BucketConfig.FILE)

    // --- valid files ---

    @Test
    fun `an empty Bucket list is valid and disables Declarations`() = assertEquals(emptyList<Bucket>(), ok("""{"buckets": []}""").buckets)

    @Test
    fun `aliases are optional`() = assertEquals(listOf(Bucket("garden", emptyList())), ok("""{"buckets":[{"name":"garden"}]}""").buckets)

    @Test
    fun `the same alias twice in one Bucket is fine`() =
        assertEquals(listOf(Bucket("work", listOf("work", "Work"))), ok("""{"buckets":[{"name":"work","aliases":["work","Work"]}]}""").buckets)

    @Test
    fun `an alias equal to another Bucket's alias only after joining is a conflict`() {
        val c = rejecting(
            """{"buckets":[{"name":"a","aliases":["house project"]},{"name":"b","aliases":["houseproject"]}]}""",
            "buckets[1] (b): alias \"houseproject\"",
        )
        // Both aliases are dropped whole: either spelling could mean either Bucket.
        assertEquals(listOf(Bucket("a", emptyList()), Bucket("b", emptyList())), c.buckets)
        assertEquals(null, PhraseGrammar(c).classify("houseproject fix it").bucket)
        assertEquals(null, PhraseGrammar(c).classify("house project fix it").bucket)
    }

    @Test
    fun `string escapes are understood`() =
        assertEquals("café", ok("""{"buckets":[{"name":"café","aliases":["a\"b"]}]}""").buckets[0].name)

    // --- whole-file failures fall back to the defaults ---

    @Test
    fun `a file that is not JSON gives the defaults`() = assertEquals(BucketConfig.DEFAULT, rejecting("{ buckets: ", "file:"))

    @Test
    fun `a top level that is not an object gives the defaults`() = assertEquals(BucketConfig.DEFAULT, rejecting("[]", "file:"))

    @Test
    fun `a missing buckets key gives the defaults`() = assertEquals(BucketConfig.DEFAULT, rejecting("{}", "file:"))

    @Test
    fun `buckets that is not an array gives the defaults`() = assertEquals(BucketConfig.DEFAULT, rejecting("""{"buckets": {}}""", "file:"))

    @Test
    fun `trailing content after the object gives the defaults`() = assertEquals(BucketConfig.DEFAULT, rejecting("""{"buckets": []} x""", "file:"))

    // --- per-Bucket fallback ---

    @Test
    fun `an unknown top-level key is reported and ignored`() =
        assertEquals(emptyList<Bucket>(), rejecting("""{"buckets": [], "fillers": ["um"]}""", "fillers: unknown key").buckets)

    @Test
    fun `a Bucket that is not an object is dropped`() =
        assertEquals(listOf("work"), rejecting("""{"buckets":["errands",{"name":"work"}]}""", "buckets[0]:").buckets.map { it.name })

    @Test
    fun `a Bucket without a name is dropped`() =
        assertEquals(listOf("work"), rejecting("""{"buckets":[{"aliases":["x"]},{"name":"work"}]}""", "buckets[0]:").buckets.map { it.name })

    @Test
    fun `a Bucket whose name has no words is dropped`() =
        assertEquals(listOf("work"), rejecting("""{"buckets":[{"name":" - "},{"name":"work"}]}""", "buckets[0]:").buckets.map { it.name })

    @Test
    fun `a Bucket whose name is not a string is dropped`() =
        assertEquals(emptyList<Bucket>(), rejecting("""{"buckets":[{"name":3}]}""", "buckets[0]:").buckets)

    @Test
    fun `a Bucket name is trimmed`() = assertEquals("work", ok("""{"buckets":[{"name":"  work "}]}""").buckets[0].name)

    @Test
    fun `aliases that are not an array keep the Bucket without them`() =
        assertEquals(listOf(Bucket("work", emptyList())), rejecting("""{"buckets":[{"name":"work","aliases":"job"}]}""", "buckets[0] (work): aliases").buckets)

    @Test
    fun `an alias that is not a string is dropped`() =
        assertEquals(listOf(Bucket("work", listOf("job"))), rejecting("""{"buckets":[{"name":"work","aliases":[1,"job"]}]}""", "buckets[0] (work): alias 1").buckets)

    @Test
    fun `an alias with no words is dropped`() =
        assertEquals(listOf(Bucket("work", listOf("job"))), rejecting("""{"buckets":[{"name":"work","aliases":["--","job"]}]}""", "buckets[0] (work): alias \"--\"").buckets)

    @Test
    fun `an unknown key in a Bucket is reported and the Bucket kept`() =
        assertEquals(listOf(Bucket("work", emptyList())), rejecting("""{"buckets":[{"name":"work","alias":["job"]}]}""", "buckets[0] (work): alias: unknown key").buckets)

    @Test
    fun `a duplicate key anywhere is a whole-file error, as in every Corpus JSON file`() {
        assertEquals(BucketConfig.DEFAULT, rejecting("""{"buckets":[{"name":"work","name":"job"}]}""", "file:"))
        assertEquals(BucketConfig.DEFAULT, rejecting("""{"buckets":[],"buckets":[]}""", "file:"))
    }

    @Test
    fun `a fractional number as an alias is reported as written`() =
        assertEquals(listOf(Bucket("work", emptyList())), rejecting("""{"buckets":[{"name":"work","aliases":[1.5]}]}""", "buckets[0] (work): alias 1.5").buckets)

    @Test
    fun `a second Bucket with the same name is dropped`() =
        assertEquals(listOf(Bucket("work", listOf("job"))), rejecting("""{"buckets":[{"name":"work","aliases":["job"]},{"name":"Work"}]}""", "buckets[1] (Work):").buckets)

    @Test
    fun `a second Bucket whose name is the first's run together is dropped`() =
        assertEquals(listOf("house-project"), rejecting("""{"buckets":[{"name":"house-project"},{"name":"houseproject"}]}""", "buckets[1] (houseproject):").buckets.map { it.name })

    @Test
    fun `an alias claimed by two Buckets is dropped from both and the rest kept`() {
        val c = rejecting(
            """{"buckets":[{"name":"errands","aliases":["shopping","store"]},{"name":"ideas","aliases":["thought","store"]}]}""",
            "buckets[1] (ideas): alias \"store\"",
        )
        assertEquals(listOf(Bucket("errands", listOf("shopping")), Bucket("ideas", listOf("thought"))), c.buckets)
        assertEquals(null, PhraseGrammar(c).classify("store buy milk").bucket)
        assertEquals("errands", PhraseGrammar(c).classify("shopping buy milk").bucket)
    }

    @Test
    fun `an alias equal to another Bucket's name is a conflict, and the name still wins for its own Bucket`() {
        val c = rejecting(
            """{"buckets":[{"name":"work"},{"name":"ideas","aliases":["work"]}]}""",
            "buckets[1] (ideas): alias \"work\"",
        )
        assertEquals(listOf(Bucket("work", emptyList()), Bucket("ideas", emptyList())), c.buckets)
        assertEquals("work", PhraseGrammar(c).classify("work fix the build").bucket)
    }

    @Test
    fun `an alias conflict is matched case- and punctuation-insensitively`() {
        rejecting(
            """{"buckets":[{"name":"a","aliases":["Work-Log"]},{"name":"b","aliases":["work log"]}]}""",
            "buckets[1] (b): alias \"work log\"",
        )
    }

    // --- the constructor guards the invariants parse establishes ---

    @Test
    fun `constructing a config with a shared alias throws`() {
        assertThrows<IllegalArgumentException> { BucketConfig(listOf(Bucket("a", listOf("x")), Bucket("b", listOf("x")))) }
    }

    @Test
    fun `constructing a config with a wordless name throws`() {
        assertThrows<IllegalArgumentException> { BucketConfig(listOf(Bucket("!", emptyList()))) }
    }

    @Test
    fun `toJson writes one Bucket per line`() {
        val json = BucketConfig(listOf(Bucket("a", listOf("x", "y")), Bucket("b", emptyList()))).toJson()
        assertEquals(
            "{\n  \"buckets\": [\n" +
                "    { \"name\": \"a\", \"aliases\": [\"x\", \"y\"] },\n" +
                "    { \"name\": \"b\", \"aliases\": [] }\n" +
                "  ]\n}\n",
            json,
        )
    }
}
