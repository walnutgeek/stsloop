package com.walnutgeek.stsloop.core

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.TranscriptList
import com.walnutgeek.stsloop.core.testmode.MicInput
import com.walnutgeek.stsloop.core.testmode.MicSource
import com.walnutgeek.stsloop.core.testmode.TestConfig
import com.walnutgeek.stsloop.core.testmode.TurnTest
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
    fun `a measured Recording says whether it is silent, at the end of the audio block`() {
        val silent = TurnJson.encode(turn.copy(audio = turn.audio.copy(silent = true)))
        assertTrue(silent.contains("\"sample_rate\": 16000, \"duration_ms\": 4471, \"silent\": true },\n"), silent)
        val heard = TurnJson.encode(turn.copy(audio = turn.audio.copy(silent = false)))
        assertTrue(heard.contains("\"duration_ms\": 4471, \"silent\": false },\n"), heard)
        assertEquals(true, (Json.parseObject(silent)["audio"] as Map<*, *>)["silent"])
    }

    @Test
    fun `an unmeasured Recording has no silent key`() {
        assertFalse(TurnJson.encode(turn).contains("silent"))
    }

    @Test
    fun `a recovered Turn says so just before app_version, and others do not`() {
        val json = TurnJson.encode(turn.copy(recovered = true))
        assertTrue(json.contains("  \"recovered\": true,\n  \"app_version\": \"0.1.0\",\n"), json)
        assertFalse(TurnJson.encode(turn).contains("recovered"))
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
                    "  \"declaration\": null,\n" +
                    "  \"bucket\": null,\n" +
                    "  \"bucket_source\": null,\n" +
                    "  \"content\": null,\n" +
                    "  \"app_version\"",
            ),
            json,
        )
    }

    @Test
    fun `kind and null labels are written without a transcript, and each kind has its spec name`() {
        assertEquals(listOf("note", "command", "unclassified"), TurnKind.entries.map { it.json })
        val json = TurnJson.encode(turn.copy(classification = Classification.UNCLASSIFIED))
        assertTrue(
            json.contains(
                "\"duration_ms\": 4471 },\n" +
                    "  \"kind\": \"unclassified\",\n" +
                    "  \"declaration\": null,\n" +
                    "  \"bucket\": null,\n" +
                    "  \"bucket_source\": null,\n" +
                    "  \"content\": null,\n" +
                    "  \"app_version\"",
            ),
            json,
        )
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
    fun `a Turn never classified has no label fields at all`() {
        val json = TurnJson.encode(turn.copy(transcript = transcript))
        for (key in listOf("kind", "declaration", "bucket", "bucket_source", "content")) {
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
    fun `a Classification refuses inconsistent fields`() {
        val d = Declaration("errands", DeclarationPosition.LEADING, "errands")
        assertThrows<IllegalArgumentException> { Classification(TurnKind.NOTE, d, "work", BucketSource.DECLARATION, "x") }
        assertThrows<IllegalArgumentException> { Classification(TurnKind.NOTE, null, "work", null, "x") }
        assertThrows<IllegalArgumentException> { Classification(TurnKind.UNCLASSIFIED, content = "x") }
        // A Note always has a Bucket: an unlabelled transcript is unclassified, not a Note.
        assertThrows<IllegalArgumentException> { Classification(TurnKind.NOTE, content = "x") }
    }

    @Test
    fun `the transcript list reads back the kind and Bucket written`() {
        val note = Classification.declared(Declaration("errands", DeclarationPosition.LEADING, "errands"), "order roofing screws")
        val declared = TranscriptList.read(turn.directoryName, TurnJson.encode(turn.copy(transcript = transcript, classification = note)))
        assertEquals("note" to "errands", declared.kind to declared.bucket)
        assertEquals(null, declared.problem)
        val plain = TranscriptList.read(turn.directoryName, TurnJson.encode(turn.copy(transcript = transcript, classification = Classification.UNCLASSIFIED)))
        assertEquals("unclassified" to null, plain.kind to plain.bucket)
        assertEquals(transcript.text, plain.transcript)
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

    private val test = TurnTest(
        config = TestConfig(enabled = true, label = "desk", micSource = MicSource.MIC, micInput = MicInput.BUILTIN),
        inputDevices = listOf("builtin_mic"),
        ttsOverlapMs = 820,
        ttsPhrases = listOf("tts-3"),
    )

    @Test
    fun `a test-mode Turn carries its test block after the labels, before app_version`() {
        val json = TurnJson.encode(turn.copy(transcript = transcript, classification = Classification.UNCLASSIFIED, test = test))
        assertTrue(
            json.contains(
                "  \"content\": null,\n" +
                    "  \"test\": {\n" +
                    "    \"label\": \"desk\",\n" +
                    "    \"mic_source\": \"mic\",\n" +
                    "    \"mic_input\": \"builtin\",\n" +
                    "    \"audio_mode\": \"normal\",\n" +
                    "    \"input_devices\": [\"builtin_mic\"],\n" +
                    "    \"tts_interval_ms\": 5000,\n" +
                    "    \"tts_usage\": \"assistant\",\n" +
                    "    \"tts_overlap\": true,\n" +
                    "    \"tts_overlap_ms\": 820,\n" +
                    "    \"tts_phrases\": [\"tts-3\"]\n" +
                    "  },\n" +
                    "  \"app_version\"",
            ),
            json,
        )
        val parsed = Json.parseObject(json)["test"] as Map<*, *>
        assertEquals(listOf("builtin_mic"), parsed["input_devices"])
    }

    @Test
    fun `a Turn with no TTS playing has tts_overlap false and no phrases`() {
        val json = TurnJson.encode(turn.copy(test = test.copy(ttsOverlapMs = 0, ttsPhrases = emptyList(), inputDevices = emptyList())))
        val t = Json.parseObject(json)["test"] as Map<*, *>
        assertEquals(false, t["tts_overlap"])
        assertEquals(0L, t["tts_overlap_ms"])
        assertEquals(emptyList<String>(), t["tts_phrases"])
        assertEquals(emptyList<String>(), t["input_devices"])
    }

    @Test
    fun `outside test mode there is no test block`() {
        assertFalse(TurnJson.encode(turn).contains("\"test\""))
    }

    @Test
    fun `the transcript list still reads a test-mode Turn`() {
        val listed = TranscriptList.read(turn.directoryName, TurnJson.encode(turn.copy(transcript = transcript, test = test)))
        assertEquals(null, listed.problem)
        assertEquals(transcript.text, listed.transcript)
    }
}
