package com.walnutgeek.stsloop.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `fields owned by later tickets are absent, not null`() {
        val json = TurnJson.encode(turn)
        for (key in listOf("vad", "transcript", "kind", "declaration", "bucket", "bucket_source", "content")) {
            assertFalse(json.contains("\"$key\""), "unexpected $key in $json")
        }
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
