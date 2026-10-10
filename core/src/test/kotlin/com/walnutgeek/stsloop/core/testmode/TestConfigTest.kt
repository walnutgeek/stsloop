package com.walnutgeek.stsloop.core.testmode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TestConfigTest {
    private fun ok(json: String): TestConfig = TestConfig.parse(json).also {
        assertEquals(emptyList<String>(), it.rejected, "unexpected rejections")
    }.config

    private fun rejecting(json: String, vararg keys: String): TestConfig {
        val p = TestConfig.parse(json)
        assertEquals(keys.toList(), p.rejected.map { it.substringBefore(':') }, p.rejected.toString())
        return p.config
    }

    @Test
    fun `defaults are off, the first drive's mic path, and a phrase every 5 s over the assistant usage`() {
        val c = TestConfig()
        assertFalse(c.enabled)
        assertEquals(MicSource.VOICE_RECOGNITION, c.micSource)
        assertEquals(MicInput.DEFAULT, c.micInput)
        assertEquals(AudioMode.NORMAL, c.audioMode)
        assertEquals(5000, c.ttsIntervalMs)
        assertEquals(TtsUsage.ASSISTANT, c.ttsUsage)
        assertEquals("", c.label)
    }

    @Test
    fun `an empty object means all defaults, so test mode stays off`() {
        assertEquals(TestConfig(), ok("{}"))
    }

    @Test
    fun `every key is read`() {
        val c = ok(
            """
            { "enabled": true, "label": "parked-ac-off", "mic_source": "unprocessed", "mic_input": "bluetooth",
              "audio_mode": "in_communication", "tts_interval_ms": 10000, "tts_usage": "media",
              "tts_phrase": "hello {n}" }
            """,
        )
        assertEquals(
            TestConfig(
                enabled = true, label = "parked-ac-off", micSource = MicSource.UNPROCESSED, micInput = MicInput.BLUETOOTH,
                audioMode = AudioMode.IN_COMMUNICATION, ttsIntervalMs = 10_000, ttsUsage = TtsUsage.MEDIA, ttsPhrase = "hello {n}",
            ),
            c,
        )
    }

    @Test
    fun `every enum value is spelled as in the file`() {
        assertEquals(listOf("voice_recognition", "mic", "unprocessed", "voice_communication"), MicSource.entries.map { it.json })
        assertEquals(listOf("default", "builtin", "bluetooth"), MicInput.entries.map { it.json })
        assertEquals(listOf("normal", "in_communication"), AudioMode.entries.map { it.json })
        assertEquals(listOf("assistant", "media", "navigation", "voice_communication"), TtsUsage.entries.map { it.json })
    }

    @Test
    fun `tts_interval_ms 0 turns the phrase off`() {
        val c = ok("""{"tts_interval_ms": 0}""")
        assertEquals(0, c.ttsIntervalMs)
        assertFalse(c.ttsOn)
        assertTrue(TestConfig().ttsOn)
    }

    @Test
    fun `a bad key keeps its default and the rest still apply`() {
        val c = rejecting(
            """{"enabled": true, "mic_source": "telepathy", "tts_interval_ms": 500, "tts_usage": 3, "colour": "red"}""",
            "mic_source", "tts_interval_ms", "tts_usage", "colour",
        )
        assertEquals(TestConfig(enabled = true), c)
    }

    @Test
    fun `interval bounds`() {
        assertEquals(TestConfig.MIN_TTS_INTERVAL_MS, ok("""{"tts_interval_ms": ${TestConfig.MIN_TTS_INTERVAL_MS}}""").ttsIntervalMs)
        assertEquals(TestConfig.MAX_TTS_INTERVAL_MS, ok("""{"tts_interval_ms": ${TestConfig.MAX_TTS_INTERVAL_MS}}""").ttsIntervalMs)
        rejecting("""{"tts_interval_ms": ${TestConfig.MAX_TTS_INTERVAL_MS + 1}}""", "tts_interval_ms")
        rejecting("""{"tts_interval_ms": -1}""", "tts_interval_ms")
        rejecting("""{"tts_interval_ms": 5000.5}""", "tts_interval_ms")
    }

    @Test
    fun `enabled must be a boolean`() {
        assertFalse(rejecting("""{"enabled": "yes"}""", "enabled").enabled)
        assertFalse(rejecting("""{"enabled": 1}""", "enabled").enabled)
    }

    @Test
    fun `label is a short plain tag`() {
        assertEquals("driving 2", ok("""{"label": "driving 2"}""").label)
        rejecting("""{"label": "a/b"}""", "label")
        rejecting("""{"label": "${"x".repeat(TestConfig.MAX_LABEL + 1)}"}""", "label")
        rejecting("""{"label": null}""", "label")
    }

    @Test
    fun `phrase must have words and be short`() {
        rejecting("""{"tts_phrase": "   "}""", "tts_phrase")
        rejecting("""{"tts_phrase": "${"a".repeat(TestConfig.MAX_PHRASE + 1)}"}""", "tts_phrase")
    }

    @Test
    fun `a file that is not strict JSON means all defaults`() {
        val p = TestConfig.parse("""{"enabled": true, "enabled": false}""")
        assertEquals(TestConfig(), p.config)
        assertEquals(listOf("file"), p.rejected.map { it.substringBefore(':') })
        assertEquals(TestConfig(), TestConfig.parse("[]").config)
        assertEquals(TestConfig(), TestConfig.parse("").config)
    }

    @Test
    fun `toJson round-trips through parse`() {
        val c = TestConfig(
            enabled = true, label = "desk", micSource = MicSource.MIC, micInput = MicInput.BUILTIN,
            audioMode = AudioMode.IN_COMMUNICATION, ttsIntervalMs = 0, ttsUsage = TtsUsage.NAVIGATION,
            ttsPhrase = "say \"hi\" {n}",
        )
        assertEquals(c, ok(c.toJson()))
        assertEquals(TestConfig(), ok(TestConfig().toJson()))
    }

    @Test
    fun `the constructor refuses what parse would reject`() {
        assertThrows<IllegalArgumentException> { TestConfig(ttsIntervalMs = 1) }
        assertThrows<IllegalArgumentException> { TestConfig(label = "no/slash") }
        assertThrows<IllegalArgumentException> { TestConfig(ttsPhrase = "") }
    }

    @Test
    fun `the phrase numbers each utterance`() {
        assertEquals("test 7 done", TestConfig(ttsPhrase = "test {n} done").phrase(7))
        assertEquals("no number", TestConfig(ttsPhrase = "no number").phrase(3))
        assertTrue(TestConfig().phrase(12).contains("12"))
    }

    @Test
    fun `the default phrase names no Bucket, so a self-captured phrase is never Declared`() {
        val words = TestConfig().phrase(1).lowercase().split(Regex("[^a-z0-9]+")).toSet()
        for (alias in listOf("errands", "errand", "shopping", "house", "work", "worklog", "idea", "ideas", "thought")) {
            assertFalse(alias in words, alias)
        }
    }

    @Test
    fun `mic presets cycle through every acceptance-criteria path`() {
        val presets = TestConfig.MIC_PRESETS
        assertEquals(MicPreset(MicSource.VOICE_RECOGNITION, MicInput.DEFAULT, AudioMode.NORMAL), presets.first())
        for (s in listOf(MicSource.VOICE_RECOGNITION, MicSource.MIC, MicSource.UNPROCESSED)) {
            assertTrue(presets.any { it.source == s && it.input == MicInput.BUILTIN }, s.json)
        }
        assertTrue(presets.any { it.input == MicInput.BLUETOOTH })
        val c = TestConfig().withPreset(presets[1])
        assertEquals(presets[1], c.preset)
        assertEquals(presets[2], TestConfig.MIC_PRESETS.next(c.preset))
        assertEquals(presets[0], TestConfig.MIC_PRESETS.next(presets.last()))
        // A hand-written combination that is no preset cycles to the first.
        assertEquals(presets[0], presets.next(MicPreset(MicSource.MIC, MicInput.BLUETOOTH, AudioMode.NORMAL)))
    }

    @Test
    fun `next cycles any list`() {
        assertEquals(10_000, TestConfig.TTS_INTERVALS_MS.next(5_000))
        assertEquals(5_000, TestConfig.TTS_INTERVALS_MS.next(0))
        assertEquals("desk", TestConfig.LABELS.next("hand-typed"))
    }

    @Test
    fun `the announcement names the mic actually routed, not the one asked for`() {
        val c = TestConfig(enabled = true, label = "parked-ac-off", micSource = MicSource.VOICE_RECOGNITION, micInput = MicInput.BUILTIN)
        assertEquals(
            "Test mode. parked ac off. voice recognition, built in mic.",
            Announcement.text(c, routedInput = "builtin_mic", bluetoothUnavailable = false),
        )
        assertEquals(
            "Test mode. voice recognition, bluetooth s c o mic.",
            Announcement.text(TestConfig(micInput = MicInput.BLUETOOTH), routedInput = "bluetooth_sco", bluetoothUnavailable = false),
        )
    }

    @Test
    fun `a Bluetooth fallback is said aloud`() {
        val c = TestConfig(micSource = MicSource.VOICE_COMMUNICATION, micInput = MicInput.BLUETOOTH, audioMode = AudioMode.IN_COMMUNICATION)
        assertEquals(
            "Test mode. voice communication, bluetooth unavailable, built in mic. Call mode.",
            Announcement.text(c, routedInput = "builtin_mic", bluetoothUnavailable = true),
        )
    }

    @Test
    fun `an unknown route is said as unknown`() {
        assertEquals("Test mode. mic, unknown mic.", Announcement.text(TestConfig(micSource = MicSource.MIC), null, false))
    }

    @Test
    fun `summary names the configuration in one line`() {
        assertEquals(
            "desk: mic/builtin, normal mode, TTS every 5 s (assistant)",
            TestConfig(label = "desk", micSource = MicSource.MIC, micInput = MicInput.BUILTIN).summary(),
        )
        assertEquals("voice_recognition/default, normal mode, TTS off", TestConfig(ttsIntervalMs = 0).summary())
    }
}
