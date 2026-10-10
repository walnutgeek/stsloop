package com.walnutgeek.stsloop.core.testmode

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.jsonString
import kotlin.math.floor

/** `mic_source`: the `MediaRecorder.AudioSource` the Session records with. */
enum class MicSource(val json: String) {
    VOICE_RECOGNITION("voice_recognition"),
    MIC("mic"),
    UNPROCESSED("unprocessed"),
    VOICE_COMMUNICATION("voice_communication"),
}

/**
 * `mic_input`: which microphone is asked for. [DEFAULT] asks for nothing
 * (what the first drive did); [BUILTIN] prefers the phone's mic; [BLUETOOTH]
 * makes the car's hands-free (SCO / LE audio) device the communication device
 * and prefers its mic.
 */
enum class MicInput(val json: String) {
    DEFAULT("default"),
    BUILTIN("builtin"),
    BLUETOOTH("bluetooth"),
}

/** `audio_mode`: the `AudioManager` mode held for the Session. */
enum class AudioMode(val json: String) {
    NORMAL("normal"),
    IN_COMMUNICATION("in_communication"),
}

/**
 * `tts_usage`: the `AudioAttributes` usage the test phrase is spoken with.
 * The usage decides the route: [ASSISTANT], [MEDIA] and [NAVIGATION] normally
 * go out over A2DP in a car; [VOICE_COMMUNICATION] follows the communication
 * device (SCO).
 */
enum class TtsUsage(val json: String) {
    ASSISTANT("assistant"),
    MEDIA("media"),
    NAVIGATION("navigation"),
    VOICE_COMMUNICATION("voice_communication"),
}

/** One microphone path, as the debug UI cycles through them. */
data class MicPreset(val source: MicSource, val input: MicInput, val mode: AudioMode) {
    override fun toString() = "${source.json}/${input.json}" + if (mode == AudioMode.NORMAL) "" else " (${mode.json})"
}

/** The preset after [current], wrapping around; a combination that is no preset goes to the first. */
fun List<MicPreset>.next(current: MicPreset): MicPreset = this[(indexOf(current) + 1) % size]

/**
 * Bluetooth test mode (#27): a debug configuration that turns a Session into
 * an instrumented experiment. While the Session records Turns as usual, a
 * fixed phrase is spoken every [ttsIntervalMs] over the current output route,
 * the mic is captured through the chosen path, and every route event is
 * logged. Read at Session start from `files/testmode.json`, like the timings
 * and Buckets, and written by the debug UI.
 *
 * - [enabled]: off means a normal Session; the rest of the file is ignored.
 * - [label]: free tag for the conditions, e.g. `parked-ac-off`, `driving`.
 * - [micSource], [micInput], [audioMode]: the microphone path.
 * - [ttsIntervalMs]: 0 turns the phrase off.
 * - [ttsUsage]: the phrase's audio usage, which picks its output route.
 * - [ttsPhrase]: `{n}` is replaced by the phrase's number in the Session.
 */
data class TestConfig(
    val enabled: Boolean = false,
    val label: String = "",
    val micSource: MicSource = MicSource.VOICE_RECOGNITION,
    val micInput: MicInput = MicInput.DEFAULT,
    val audioMode: AudioMode = AudioMode.NORMAL,
    val ttsIntervalMs: Int = 5_000,
    val ttsUsage: TtsUsage = TtsUsage.ASSISTANT,
    val ttsPhrase: String = DEFAULT_PHRASE,
) {
    init {
        checkLabel(label)?.let { throw IllegalArgumentException("label: $it") }
        checkInterval(ttsIntervalMs.toDouble())?.let { throw IllegalArgumentException("tts_interval_ms: $it") }
        checkPhrase(ttsPhrase)?.let { throw IllegalArgumentException("tts_phrase: $it") }
    }

    val ttsOn: Boolean get() = ttsIntervalMs > 0

    val preset: MicPreset get() = MicPreset(micSource, micInput, audioMode)

    fun withPreset(p: MicPreset) = copy(micSource = p.source, micInput = p.input, audioMode = p.mode)

    /** The [n]th phrase of a Session. */
    fun phrase(n: Int): String = ttsPhrase.replace("{n}", n.toString())

    /** One line for the UI and the log. */
    fun summary(): String = buildString {
        if (label.isNotEmpty()) append(label).append(": ")
        append(micSource.json).append('/').append(micInput.json).append(", ").append(audioMode.json).append(" mode, ")
        if (ttsOn) append("TTS every ${ttsIntervalMs / 1000.0}".removeSuffix(".0") + " s (${ttsUsage.json})") else append("TTS off")
    }

    /** The file format [parse] reads, every key present. */
    fun toJson(): String = listOf(
        "enabled" to enabled.toString(),
        "label" to jsonString(label),
        "mic_source" to jsonString(micSource.json),
        "mic_input" to jsonString(micInput.json),
        "audio_mode" to jsonString(audioMode.json),
        "tts_interval_ms" to ttsIntervalMs.toString(),
        "tts_usage" to jsonString(ttsUsage.json),
        "tts_phrase" to jsonString(ttsPhrase),
    ).joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { (k, v) -> "  \"$k\": $v" }

    /** The result of [parse]: the config to use, and each rejected key as `"key: why"`. */
    data class Parsed(val config: TestConfig, val rejected: List<String>)

    companion object {
        /** File name in app storage (`files/testmode.json`). */
        const val FILE = "testmode.json"
        const val MIN_TTS_INTERVAL_MS = 2_000
        const val MAX_TTS_INTERVAL_MS = 600_000
        const val MAX_LABEL = 40
        const val MAX_PHRASE = 200

        /** Names no Bucket, so a self-captured phrase can never be Declared into one. */
        const val DEFAULT_PHRASE = "This is the machine speaking, test number {n}."

        /** The microphone paths the debug UI cycles through; the first is what the first drive used. */
        val MIC_PRESETS = listOf(
            MicPreset(MicSource.VOICE_RECOGNITION, MicInput.DEFAULT, AudioMode.NORMAL),
            MicPreset(MicSource.VOICE_RECOGNITION, MicInput.BUILTIN, AudioMode.NORMAL),
            MicPreset(MicSource.MIC, MicInput.BUILTIN, AudioMode.NORMAL),
            MicPreset(MicSource.UNPROCESSED, MicInput.BUILTIN, AudioMode.NORMAL),
            MicPreset(MicSource.VOICE_RECOGNITION, MicInput.BLUETOOTH, AudioMode.NORMAL),
            MicPreset(MicSource.VOICE_COMMUNICATION, MicInput.BLUETOOTH, AudioMode.IN_COMMUNICATION),
        )

        /** The intervals the debug UI cycles through. */
        val TTS_INTERVALS_MS = listOf(5_000, 10_000, 20_000, 0)

        /** The condition labels the debug UI cycles through (docs/test-drive.md). */
        val LABELS = listOf("desk", "parked-ac-off", "parked-ac-on", "driving")

        private val LABEL_CHARS = Regex("[A-Za-z0-9 _.-]*")

        private fun checkLabel(v: String): String? = when {
            v.length > MAX_LABEL -> "must be at most $MAX_LABEL characters"
            !LABEL_CHARS.matches(v) -> "only letters, digits, space, '-', '_' and '.', was ${jsonString(v)}"
            else -> null
        }

        private fun checkInterval(v: Double): String? = when {
            v != floor(v) -> "must be a whole number of milliseconds, was $v"
            v == 0.0 -> null
            v < MIN_TTS_INTERVAL_MS || v > MAX_TTS_INTERVAL_MS ->
                "must be 0 (off) or in $MIN_TTS_INTERVAL_MS..$MAX_TTS_INTERVAL_MS, was ${v.toLong()}"
            else -> null
        }

        private fun checkPhrase(v: String): String? = when {
            v.isBlank() -> "must have words"
            v.length > MAX_PHRASE -> "must be at most $MAX_PHRASE characters"
            else -> null
        }

        private inline fun <reified E : Enum<E>> enumOf(raw: Any?, json: (E) -> String): Pair<E?, String?> {
            val all = enumValues<E>()
            val hit = all.firstOrNull { json(it) == raw }
            return if (hit != null) hit to null else null to "must be one of ${all.map(json)}, was ${show(raw)}"
        }

        private fun show(v: Any?): String = if (v is String) jsonString(v) else v.toString()

        val KEYS = listOf("enabled", "label", "mic_source", "mic_input", "audio_mode", "tts_interval_ms", "tts_usage", "tts_phrase")

        /**
         * Parses the file, falling back **per key** like `timings.json`: an
         * unknown key or a bad value is rejected and keeps its default, the
         * rest still apply. A file that is not a strict JSON object (a
         * duplicate key included) gives all defaults, so test mode stays off.
         */
        fun parse(json: String): Parsed {
            val entries = try {
                Json.parseObject(json)
            } catch (e: JsonException) {
                return Parsed(TestConfig(), listOf("file: ${e.message}"))
            }
            val rejected = mutableListOf<String>()
            var c = TestConfig()
            for ((key, raw) in entries) {
                val problem: String? = when (key) {
                    "enabled" -> if (raw is Boolean) { c = c.copy(enabled = raw); null } else "must be true or false, was ${show(raw)}"
                    "label" -> if (raw !is String) "must be a string, was ${show(raw)}" else checkLabel(raw) ?: run { c = c.copy(label = raw); null }
                    "mic_source" -> enumOf<MicSource>(raw) { it.json }.let { (v, why) -> v?.let { c = c.copy(micSource = it) }; why }
                    "mic_input" -> enumOf<MicInput>(raw) { it.json }.let { (v, why) -> v?.let { c = c.copy(micInput = it) }; why }
                    "audio_mode" -> enumOf<AudioMode>(raw) { it.json }.let { (v, why) -> v?.let { c = c.copy(audioMode = it) }; why }
                    "tts_usage" -> enumOf<TtsUsage>(raw) { it.json }.let { (v, why) -> v?.let { c = c.copy(ttsUsage = it) }; why }
                    "tts_interval_ms" -> {
                        val v = (raw as? Number)?.toDouble()
                        if (v == null) "must be a number, was ${show(raw)}" else checkInterval(v) ?: run { c = c.copy(ttsIntervalMs = v.toInt()); null }
                    }
                    "tts_phrase" -> if (raw !is String) "must be a string, was ${show(raw)}" else checkPhrase(raw) ?: run { c = c.copy(ttsPhrase = raw); null }
                    else -> "unknown key; known keys: $KEYS"
                }
                if (problem != null) rejected += "$key: $problem"
            }
            return Parsed(c, rejected)
        }
    }
}
