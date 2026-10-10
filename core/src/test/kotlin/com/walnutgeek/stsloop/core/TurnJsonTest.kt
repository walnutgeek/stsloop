package com.walnutgeek.stsloop.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TurnJsonTest {
    private val turn = Turn(
        id = "a3f1c9",
        sessionId = "0f22ab",
        startedAtMs = 1_791_296_527_431,
        audio = TurnAudio(
            file = "audio.wav",
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            sampleRate = 16_000,
            durationMs = 4471,
        ),
        appVersion = "0.1.0",
    )

    @Test
    fun `serialises the schema 1 fields in MVP order`() {
        assertEquals(
            """
            {
              "schema": 1,
              "id": "a3f1c9",
              "session_id": "0f22ab",
              "started_at": "2026-10-06T14:22:07.431Z",
              "ended_at": "2026-10-06T14:22:11.902Z",
              "audio": { "file": "audio.wav", "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "sample_rate": 16000, "duration_ms": 4471 },
              "app_version": "0.1.0",
              "tombstoned_by": null
            }
            """.trimIndent() + "\n",
            TurnJson.encode(turn),
        )
    }

    @Test
    fun `ended_at is started_at plus the sample-derived duration`() {
        assertEquals(1_791_296_531_902, turn.endedAtMs)
        val longer = turn.copy(audio = turn.audio.copy(durationMs = 60_000))
        assertEquals(turn.startedAtMs + 60_000, longer.endedAtMs)
        assertTrue(TurnJson.encode(longer).contains("\"ended_at\": \"2026-10-06T14:23:07.431Z\""))
    }

    @Test
    fun `a tombstoned Turn names the Turn that tombstoned it`() {
        val json = TurnJson.encode(turn.copy(tombstonedBy = "b4e2d0"))
        assertTrue(json.contains("\"tombstoned_by\": \"b4e2d0\""), json)
    }

    @Test
    fun `a VAD-cut Turn carries the vad block right after audio`() {
        val json = TurnJson.encode(turn.copy(vad = TurnVad(speechMs = 3180, trailingSilenceMs = 1500)))
        assertTrue(
            json.contains(
                "\"duration_ms\": 4471 },\n" +
                    "  \"vad\": { \"speech_ms\": 3180, \"trailing_silence_ms\": 1500 },\n" +
                    "  \"app_version\"",
            ),
            json,
        )
    }

    @Test
    fun `the vad block does not change ended_at`() {
        val json = TurnJson.encode(turn.copy(vad = TurnVad(3180, 1500)))
        assertTrue(json.contains("\"ended_at\": \"2026-10-06T14:22:11.902Z\""), json)
    }

    @Test
    fun `without a VAD cut the vad block is absent`() {
        assertFalse(TurnJson.encode(turn).contains("\"vad\""))
    }

    private val transcript = Transcript(
        text = "ERRANDS ORDER ROOFING SCREWS",
        engine = "sherpa-onnx",
        model = "zipformer/int8+modified_beam_search",
        finishedAtMs = 1_791_296_532_141,
        latencyMs = 239,
    )

    @Test
    fun `a transcribed Turn carries the transcript block and kind after vad, before app_version`() {
        val json = TurnJson.encode(
            turn.copy(vad = TurnVad(3180, 1500), transcript = transcript, classification = Classification.UNCLASSIFIED),
        )
        assertTrue(
            json.contains(
                "  \"vad\": { \"speech_ms\": 3180, \"trailing_silence_ms\": 1500 },\n" +
                    "  \"transcript\": {\n" +
                    "    \"text\": \"ERRANDS ORDER ROOFING SCREWS\",\n" +
                    "    \"engine\": \"sherpa-onnx\",\n" +
                    "    \"model\": \"zipformer/int8+modified_beam_search\",\n" +
                    "    \"finished_at\": \"2026-10-06T14:22:12.141Z\",\n" +
                    "    \"latency_ms\": 239\n" +
                    "  },\n" +
                    "  \"kind\": \"unclassified\",\n" +
                    "  \"app_version\"",
            ),
            json,
        )
    }

    @Test
    fun `kind is written without a transcript, and each kind has its spec name`() {
        assertEquals(listOf("note", "command", "unclassified"), TurnKind.entries.map { it.json })
        val json = TurnJson.encode(turn.copy(classification = Classification.UNCLASSIFIED))
        assertTrue(json.contains("\"duration_ms\": 4471 },\n  \"kind\": \"unclassified\",\n  \"app_version\""), json)
        assertFalse(json.contains("\"transcript\""))
    }

    @Test
    fun `transcript text is stored exactly as the engine produced it`() {
        val json = TurnJson.encode(turn.copy(transcript = transcript.copy(text = " Hello,  World ")))
        assertTrue(json.contains("\"text\": \" Hello,  World \",\n"), json)
    }

    @Test
    fun `transcript text is JSON-escaped`() {
        val json = TurnJson.encode(turn.copy(transcript = transcript.copy(text = "say \"hi\"\\")))
        assertTrue(json.contains("\"text\": \"say \\\"hi\\\"\\\\\""), json)
    }

    @Test
    fun `an unclassified Turn has no Note fields, not even null ones`() {
        val json = TurnJson.encode(turn.copy(transcript = transcript, classification = Classification.UNCLASSIFIED))
        for (key in listOf("declaration", "bucket", "bucket_source", "content")) {
            assertFalse(json.contains("\"$key\""), "unexpected $key in $json")
        }
    }

    @Test
    fun `a declared Note is written as in the mvp Corpus example`() {
        val note = Classification.declared(Declaration("errands", DeclarationPosition.LEADING, "errands"), "order roofing screws")
        val json = TurnJson.encode(turn.copy(transcript = transcript, classification = note))
        assertTrue(
            json.contains(
                "  },\n" +
                    "  \"kind\": \"note\",\n" +
                    "  \"declaration\": { \"bucket\": \"errands\", \"position\": \"leading\", \"matched\": \"errands\" },\n" +
                    "  \"bucket\": \"errands\",\n" +
                    "  \"bucket_source\": \"declaration\",\n" +
                    "  \"content\": \"order roofing screws\",\n" +
                    "  \"app_version\": \"0.1.0\",\n",
            ),
            json,
        )
    }

    @Test
    fun `a trailing Declaration says so`() {
        val note = Classification.declared(Declaration("house-project", DeclarationPosition.TRAILING, "houseproject"), "x")
        val json = TurnJson.encode(turn.copy(transcript = transcript, classification = note))
        assertTrue(json.contains("{ \"bucket\": \"house-project\", \"position\": \"trailing\", \"matched\": \"houseproject\" }"), json)
    }

    @Test
    fun `an undeclared Note writes the Bucket fields as null and keeps its content`() {
        val json = TurnJson.encode(turn.copy(transcript = transcript, classification = Classification.undeclared("order \"roofing\" screws")))
        assertTrue(
            json.contains(
                "  \"kind\": \"note\",\n" +
                    "  \"declaration\": null,\n" +
                    "  \"bucket\": null,\n" +
                    "  \"bucket_source\": null,\n" +
                    "  \"content\": \"order \\\"roofing\\\" screws\",\n" +
                    "  \"app_version\"",
            ),
            json,
        )
    }

    @Test
    fun `a Classification refuses inconsistent fields`() {
        val d = Declaration("errands", DeclarationPosition.LEADING, "errands")
        assertThrows<IllegalArgumentException> { Classification(TurnKind.NOTE, d, "work", BucketSource.DECLARATION, "x") }
        assertThrows<IllegalArgumentException> { Classification(TurnKind.NOTE, null, "work", null, "x") }
        assertThrows<IllegalArgumentException> { Classification(TurnKind.UNCLASSIFIED, content = "x") }
    }

    @Test
    fun `position and bucket_source have their spec names`() {
        assertEquals(listOf("leading", "trailing"), DeclarationPosition.entries.map { it.json })
        assertEquals(listOf("declaration"), BucketSource.entries.map { it.json })
    }

    @Test
    fun `strings are JSON-escaped`() {
        val json = TurnJson.encode(turn.copy(appVersion = "0.1.0 \"dev\"\\\n\u0001"))
        assertTrue(json.contains("\"app_version\": \"0.1.0 \\\"dev\\\"\\\\\\n\\u0001\""), json)
    }

    @Test
    fun `Turn directory is named started_at dash id`() {
        assertEquals("2026-10-06T14:22:07.431Z-a3f1c9", turn.directoryName)
    }
}
