package com.walnutgeek.stsloop.core.corpus

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnVad
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptListTest {
    // As written on the phone before VAD (#9): one Turn per Session, no vad/transcript/kind.
    private val beforeVad = """
        {
          "schema": 1,
          "id": "7d192e",
          "session_id": "372987",
          "started_at": "2026-10-10T14:46:47.904Z",
          "ended_at": "2026-10-10T14:47:05.797Z",
          "audio": { "file": "audio.wav", "sha256": "2b89", "sample_rate": 16000, "duration_ms": 17700 },
          "app_version": "0.1.0",
          "tombstoned_by": null
        }
    """.trimIndent()

    // As written after VAD (#9) but before STT (#10).
    private val beforeStt = """
        {
          "schema": 1,
          "id": "ff5611",
          "session_id": "8216a8",
          "started_at": "2026-10-10T15:55:37.651Z",
          "ended_at": "2026-10-10T15:55:47.835Z",
          "audio": { "file": "audio.wav", "sha256": "57cf", "sample_rate": 16000, "duration_ms": 10184 },
          "vad": { "speech_ms": 8384, "trailing_silence_ms": 1500 },
          "app_version": "0.1.0",
          "tombstoned_by": null
        }
    """.trimIndent()

    // The full MVP example (docs/mvp.md, Corpus format), with the Declaration fields of #12.
    private val declared = """
        {
          "schema": 1,
          "id": "a3f1c9",
          "session_id": "0f22ab",
          "started_at": "2026-10-06T14:22:07.431Z",
          "ended_at": "2026-10-06T14:22:12.411Z",
          "audio": { "file": "audio.wav", "sha256": "x", "sample_rate": 16000, "duration_ms": 4980 },
          "vad": { "speech_ms": 3180, "trailing_silence_ms": 1500 },
          "transcript": {
            "text": "ERRANDS ORDER ROOFING SCREWS",
            "engine": "sherpa-onnx",
            "model": "m",
            "finished_at": "2026-10-06T14:22:12.650Z",
            "latency_ms": 239
          },
          "kind": "note",
          "declaration": { "bucket": "errands", "position": "leading", "matched": "errands" },
          "bucket": "errands",
          "bucket_source": "declaration",
          "content": "order roofing screws",
          "app_version": "0.1.0",
          "tombstoned_by": null
        }
    """.trimIndent()

    private val beforeVadDir = "2026-10-10T14:46:47.904Z-7d192e"
    private val beforeSttDir = "2026-10-10T15:55:37.651Z-ff5611"
    private val declaredDir = "2026-10-06T14:22:07.431Z-a3f1c9"

    @Test
    fun `a Turn written before VAD lists with no transcript, Bucket or kind`() {
        val t = TranscriptList.read(beforeVadDir, beforeVad)
        assertEquals(
            ListedTurn(
                directoryName = beforeVadDir,
                id = "7d192e",
                startedAt = "2026-10-10T14:46:47.904Z",
                durationMs = 17_700,
            ),
            t,
        )
        assertNull(t.transcript)
        assertNull(t.problem)
        assertFalse(t.tombstoned)
    }

    @Test
    fun `a Turn written before STT lists with no transcript`() {
        val t = TranscriptList.read(beforeSttDir, beforeStt)
        assertNull(t.transcript)
        assertNull(t.kind)
        assertEquals(10_184L, t.durationMs)
        assertNull(t.problem)
    }

    @Test
    fun `a Turn written by the current writer reads back its transcript and kind`() {
        val turn = Turn(
            id = "b4e2d0",
            sessionId = "0f22ab",
            startedAtMs = 1_791_296_527_431,
            audio = TurnAudio("audio.wav", "x", 16_000, 4471),
            appVersion = "0.1.0",
            vad = TurnVad(3000, 1500),
            transcript = Transcript("SAY \"HI\"\n", "sherpa-onnx", "m", 1_791_296_532_000, 300),
            classification = Classification.UNCLASSIFIED,
        )
        val t = TranscriptList.read(turn.directoryName, TurnJson.encode(turn))
        assertEquals("SAY \"HI\"\n", t.transcript)
        assertEquals("unclassified", t.kind)
        assertEquals("b4e2d0", t.id)
        assertEquals("2026-10-06T14:22:07.431Z", t.startedAt)
        assertNull(t.bucket)
        assertNull(t.problem)
    }

    @Test
    fun `a declared Note shows its Bucket, kind and transcript`() {
        val t = TranscriptList.read(declaredDir, declared)
        assertEquals("ERRANDS ORDER ROOFING SCREWS", t.transcript)
        assertEquals("errands", t.bucket)
        assertEquals("note", t.kind)
        assertFalse(t.tombstoned)
    }

    @Test
    fun `an empty transcript is kept apart from a missing one`() {
        val t = TranscriptList.read(declaredDir, declared.replace("ERRANDS ORDER ROOFING SCREWS", ""))
        assertEquals("", t.transcript)
    }

    @Test
    fun `an explicit null Bucket is no Bucket`() {
        val t = TranscriptList.read(declaredDir, declared.replace("\"bucket\": \"errands\",", "\"bucket\": null,"))
        assertNull(t.bucket)
    }

    @Test
    fun `an unknown kind is shown as written`() {
        val t = TranscriptList.read(declaredDir, declared.replace("\"kind\": \"note\"", "\"kind\": \"query\""))
        assertEquals("query", t.kind)
    }

    @Test
    fun `a Turn's own tombstoned_by marks it tombstoned`() {
        val t = TranscriptList.read(declaredDir, declared.replace("\"tombstoned_by\": null", "\"tombstoned_by\": \"c0ffee\""))
        assertTrue(t.tombstoned)
        assertEquals("c0ffee", t.tombstonedBy)
    }

    @Test
    fun `fields of the wrong type are treated as absent, not as a broken Turn`() {
        val odd = """
            { "id": 7, "started_at": "2026-10-06T14:22:07.431Z", "audio": "audio.wav",
              "transcript": { "text": 42 }, "kind": ["note"], "bucket": {}, "tombstoned_by": false,
              "future_field": { "x": [1, 2, 3] } }
        """.trimIndent()
        val t = TranscriptList.read(declaredDir, odd)
        assertEquals(ListedTurn(directoryName = declaredDir, startedAt = "2026-10-06T14:22:07.431Z"), t)
        assertNull(t.problem)
    }

    @Test
    fun `a transcript block without text is no transcript`() {
        val t = TranscriptList.read(declaredDir, """{ "transcript": { "engine": "sherpa-onnx" } }""")
        assertNull(t.transcript)
    }

    @Test
    fun `a missing turn json is listed with a problem`() {
        val t = TranscriptList.read(declaredDir, null)
        assertEquals(declaredDir, t.directoryName)
        assertEquals("no turn.json", t.problem)
        assertNull(t.transcript)
    }

    @Test
    fun `malformed json is listed with a problem instead of failing the list`() {
        val t = TranscriptList.read(declaredDir, declared.substring(0, 100))
        assertNotNull(t.problem)
        assertTrue(t.problem!!.startsWith("unreadable turn.json"), t.problem)
    }

    @Test
    fun `a json document that is not an object is a problem`() {
        assertEquals("turn.json is not an object", TranscriptList.read(declaredDir, "[1, 2]").problem)
    }

    @Test
    fun `lists newest first by directory name`() {
        val listed = TranscriptList.of(
            listOf(
                TranscriptList.read(beforeVadDir, beforeVad),
                TranscriptList.read(declaredDir, declared),
                TranscriptList.read(beforeSttDir, beforeStt),
            ),
        )
        assertEquals(listOf(beforeSttDir, beforeVadDir, declaredDir), listed.map { it.directoryName })
    }

    @Test
    fun `a tombstone mapping marks the Turn by directory name`() {
        val command = "2026-10-06T14:22:20.000Z-dead01"
        val listed = TranscriptList.of(
            listOf(
                TranscriptList.read(declaredDir, declared),
                TranscriptList.read(command, """{ "id": "dead01", "kind": "command" }"""),
                TranscriptList.read(beforeVadDir, beforeVad),
            ),
            tombstonedBy = mapOf(declaredDir to command),
        )
        val byDir = listed.associateBy { it.directoryName }
        assertEquals(command, byDir.getValue(declaredDir).tombstonedBy)
        assertFalse(byDir.getValue(command).tombstoned)
        assertFalse(byDir.getValue(beforeVadDir).tombstoned)
    }

    @Test
    fun `Turns sharing an id are told apart by directory name`() {
        val sameIdDir = "2026-10-11T09:00:00.000Z-a3f1c9"
        val listed = TranscriptList.of(
            listOf(TranscriptList.read(declaredDir, declared), TranscriptList.read(sameIdDir, declared)),
            tombstonedBy = mapOf(sameIdDir to "2026-10-11T09:00:05.000Z-dead01"),
        )
        assertEquals(listOf(true, false), listed.map { it.tombstoned })
        assertEquals(listOf(sameIdDir, declaredDir), listed.map { it.directoryName })
    }

    @Test
    fun `without a tombstone mapping nothing is tombstoned unless its own turn json says so`() {
        val listed = TranscriptList.of(listOf(TranscriptList.read(declaredDir, declared), TranscriptList.read(beforeVadDir, beforeVad)))
        assertTrue(listed.none { it.tombstoned })
    }

    @Test
    fun `a Turn's own tombstoned_by wins over the mapping`() {
        val listed = TranscriptList.of(
            listOf(TranscriptList.read(declaredDir, declared.replace("\"tombstoned_by\": null", "\"tombstoned_by\": \"first1\""))),
            tombstonedBy = mapOf(declaredDir to "2026-10-06T14:22:20.000Z-dead01"),
        )
        assertEquals("first1", listed.single().tombstonedBy)
    }

    @Test
    fun `a duplicate key makes turn json a problem`() {
        val t = TranscriptList.read(declaredDir, """{ "kind": "note", "kind": "command" }""")
        assertTrue(t.problem!!.contains("duplicate key"), t.problem)
    }
}
