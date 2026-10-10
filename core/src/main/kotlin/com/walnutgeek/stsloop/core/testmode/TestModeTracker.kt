package com.walnutgeek.stsloop.core.testmode

import com.walnutgeek.stsloop.core.turn.Utterance

/**
 * A Turn's `test` block in `turn.json`: the Session's test [config], the input
 * devices routed while its audio was captured, and how much TTS played
 * during it. [ttsOverlap] (any phrase intersecting the Turn's audio span)
 * marks a self-capture candidate. [ttsPhrases] are the test phrases' ids
 * (`tts-N`, `announce`); "utterance" is kept for the audio of a human Turn.
 * The `turn.json` keys are permanent once a Corpus holds them.
 */
data class TurnTest(
    val config: TestConfig,
    val inputDevices: List<String>,
    val ttsOverlapMs: Long,
    val ttsPhrases: List<String>,
) {
    val ttsOverlap: Boolean get() = ttsPhrases.isNotEmpty()
}

/**
 * Everything a test Session tracks on the sample clock: TTS playback, the
 * routed input device, and the mic level. The capture thread calls
 * [captured] after every read; the platform's callbacks report TTS and route
 * events from their own threads; the STT thread asks for each Turn's
 * [turnTest] when it writes it. Thread-safe.
 */
class TestModeTracker(val config: TestConfig, private val sampleRate: Int) {
    private val clock = SampleClock(sampleRate)
    private val tts = TtsTimeline()
    private val inputs = StepTimeline<String>()
    private val levels = LevelHistory(capacity = LEVEL_CHUNKS)

    /** The latest routed input device, if any was reported. */
    val currentInput: String? get() = inputs.latest

    /** Capture thread, after each read: [samplesCaptured] in total so far, the read returned at [atNs]. */
    fun captured(samplesCaptured: Long, atNs: Long, chunk: ShortArray, count: Int) {
        clock.update(samplesCaptured, atNs)
        levels.add(samplesCaptured, count, Levels.rms(chunk, count))
    }

    /** The sample offset of [atNs], or null before the first read. */
    fun sampleAt(atNs: Long): Long? = clock.sampleAt(atNs)

    /** A phrase began playing; returns where on the sample clock, or null if capture has not started. */
    fun ttsStarted(id: String, atNs: Long): Long? = clock.sampleAt(atNs)?.also { tts.started(id, it) }

    /** A phrase stopped playing (done, error or stopped). */
    fun ttsDone(id: String, atNs: Long): Long? = clock.sampleAt(atNs)?.also { tts.done(id, it) }

    /** The input device in use from [atNs] on. Before the first read it applies from sample 0. */
    fun inputRouted(device: String, atNs: Long) {
        inputs.set(clock.sampleAt(atNs) ?: 0L, device)
    }

    /** The mic level over `[start, end)` in dBFS, or null when those chunks are no longer kept. */
    fun micDbfs(start: Long, end: Long): Double? = levels.rms(start, end)?.let(Levels::dbfs)

    /** The `test` block of the Turn holding [utterance], with phrases still playing counted up to [nowNs]. */
    fun turnTest(utterance: Utterance, nowNs: Long): TurnTest {
        val now = clock.sampleAt(nowNs) ?: utterance.endSample
        val o = tts.overlap(utterance.startSample, utterance.endSample, now)
        // Turns are written in stream order, so no later Turn can reach a phrase that ended before this one.
        tts.forgetBefore(utterance.startSample)
        return TurnTest(
            config = config,
            inputDevices = inputs.during(utterance.startSample, utterance.endSample),
            ttsOverlapMs = o.samples * 1000 / sampleRate,
            ttsPhrases = o.phrases,
        )
    }

    private companion object {
        const val LEVEL_CHUNKS = 600 // 60 s of 100 ms chunks
    }
}
