package com.walnutgeek.stsloop.core.testmode

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionLogTest {
    // --- JsonWriter: compact JSON for any plain value ---

    @Test
    fun `plain values`() {
        assertEquals("null", JsonWriter.write(null))
        assertEquals("true", JsonWriter.write(true))
        assertEquals("42", JsonWriter.write(42))
        assertEquals("-7", JsonWriter.write(-7L))
        assertEquals("1.5", JsonWriter.write(1.5))
        assertEquals("\"a\\\"b\\n\"", JsonWriter.write("a\"b\n"))
    }

    @Test
    fun `a non-finite number is null, since JSON has no NaN`() {
        assertEquals("null", JsonWriter.write(Double.NaN))
        assertEquals("null", JsonWriter.write(Double.NEGATIVE_INFINITY))
        assertEquals("null", JsonWriter.write(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `objects keep their key order and nest`() {
        val v = linkedMapOf("b" to 1, "a" to listOf("x", null, mapOf("k" to false)))
        assertEquals("""{"b":1,"a":["x",null,{"k":false}]}""", JsonWriter.write(v))
        assertEquals(v, Json.parse(JsonWriter.write(v)).let { normalise(it) })
    }

    @Test
    fun `anything else is refused rather than written as toString`() {
        assertThrows<IllegalArgumentException> { JsonWriter.write(Any()) }
        assertThrows<IllegalArgumentException> { JsonWriter.write(mapOf(1 to 2)) }
    }

    private fun normalise(v: Any?): Any? = when (v) {
        is Long -> v.toInt()
        is Map<*, *> -> v.mapValues { normalise(it.value) }
        is List<*> -> v.map(::normalise)
        else -> v
    }

    // --- SessionLog: one JSON object per line ---

    @Test
    fun `a line is one JSON object with at and event first`() {
        val line = SessionLog.line(1_791_296_527_431, "tts_start", linkedMapOf("utterance" to "tts-1", "sample" to 16000L))
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
        assertEquals(
            """{"at":"2026-10-06T14:22:07.431Z","event":"tts_start","utterance":"tts-1","sample":16000}""" + "\n",
            line,
        )
    }

    @Test
    fun `a newline inside a value stays escaped on its one line`() {
        val line = SessionLog.line(0, "error", mapOf("message" to "two\nlines"))
        assertEquals(1, line.count { it == '\n' })
        assertEquals("two\nlines", Json.parseObject(line)["message"])
    }

    @Test
    fun `fields may not shadow at or event`() {
        assertThrows<IllegalArgumentException> { SessionLog.line(0, "x", mapOf("at" to 1)) }
        assertThrows<IllegalArgumentException> { SessionLog.line(0, "x", mapOf("event" to 1)) }
    }

    @Test
    fun `the log lives beside the Turns, named like them`() {
        assertEquals("sessions", SESSIONS_DIR)
        assertEquals("2026-10-06T14:22:07.431Z-0f22ab.jsonl", SessionLog.fileName(1_791_296_527_431, "0f22ab"))
    }

    // --- Levels ---

    @Test
    fun `rms of a chunk`() {
        assertEquals(0.0, Levels.rms(ShortArray(4), 4))
        assertEquals(100.0, Levels.rms(shortArrayOf(100, -100, 100, -100), 4), 1e-9)
        assertEquals(100.0, Levels.rms(shortArrayOf(100, -100, 9999), 2), 1e-9) // only the first count samples
    }

    @Test
    fun `dBFS is relative to full scale and floored for digital silence`() {
        assertEquals(0.0, Levels.dbfs(32768.0), 1e-9)
        assertEquals(-20.0, Levels.dbfs(3276.8), 1e-9)
        assertEquals(Levels.FLOOR_DBFS, Levels.dbfs(0.0))
    }

    @Test
    fun `level history gives the power-mean rms over the chunks touching a span`() {
        val h = LevelHistory(capacity = 4)
        assertNull(h.rms(0, 100))
        h.add(endSample = 100, samples = 100, rms = 10.0) // [0, 100)
        h.add(endSample = 200, samples = 100, rms = 30.0) // [100, 200)
        assertEquals(10.0, h.rms(0, 100)!!, 1e-9)
        assertEquals(30.0, h.rms(150, 160)!!, 1e-9)
        assertEquals(Math.sqrt((100.0 + 900.0) / 2), h.rms(50, 150)!!, 1e-9)
        assertNull(h.rms(200, 300))
    }

    @Test
    fun `level history keeps only its newest chunks`() {
        val h = LevelHistory(capacity = 2)
        h.add(100, 100, 1.0)
        h.add(200, 100, 2.0)
        h.add(300, 100, 3.0)
        assertNull(h.rms(0, 100))
        assertEquals(3.0, h.rms(200, 300)!!, 1e-9)
        assertFalse(h.rms(0, 300) == null)
    }
}
