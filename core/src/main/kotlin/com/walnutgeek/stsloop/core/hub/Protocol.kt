package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import java.security.SecureRandom
import java.util.Base64

/** A protocol version, `{major, minor}` on the wire. */
data class Version(val major: Int, val minor: Int)

/** One message of the phone ↔ hub protocol, v1 (`protocol/`, ADR-0006). */
sealed interface Message

/** Phone to hub, first on every connection. */
data class Hello(val protocol: Version, val capabilities: List<String>) : Message

/** Hub to phone: the version and the capabilities both sides will use. */
data class Welcome(val protocol: Version, val capabilities: List<String>) : Message

/** Hub to phone, instead of [Welcome]; the hub closes with 1008 right after. [message] is fit to be spoken. */
data class Refusal(val reason: String, val message: String, val supported: List<Version> = emptyList()) : Message

/**
 * A phrase that is a command while its Bucket has Focus, or whatever has
 * Focus when [anywhere]; [primitive] runs it. [replyWithinMs], when set, is
 * how long the phone waits for a `request` command's reply ("what does it
 * say" asks a model).
 */
data class HubCommand(
    val id: String,
    val phrases: List<String>,
    val primitive: String,
    val anywhere: Boolean = false,
    val replyWithinMs: Long? = null,
)

/**
 * A hub Bucket, as its Provider describes it (`descriptors.schema.json`). The
 * [type] and each Primitive are open strings: a phone that does not know one
 * still keeps the Bucket.
 */
data class HubDescriptor(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val type: String,
    val primitives: List<String>,
    val commands: List<HubCommand>,
)

/** Hub to phone: every hub Bucket, a full snapshot that replaces the last. */
data class Descriptors(val buckets: List<HubDescriptor>) : Message

/** Phone to hub: run the `request` command [command] of the Bucket whose Descriptor id is [bucket]. */
data class Request(val id: String, val bucket: String, val command: String) : Message

/** Hub to phone: the answer to the [Request] [id], to be spoken as it is. [reason] is null when the command ran. */
data class Reply(val id: String, val say: String, val reason: String? = null) : Message

/** What the hub's QR code holds: where the hub is, and the token to connect with. Never sent over the WebSocket. */
data class Pairing(val protocol: Version, val url: String, val token: String) : Message {
    override fun toString() = "Pairing(protocol=$protocol, url=$url, token=…)" // keeps the token out of logs
}

/**
 * Phone to hub, with the `corpus` capability: one Turn of the Corpus offered
 * for upload. [turn] is its directory name, [turnJson] its `turn.json` text as
 * written, and [sha256] and [bytes] describe the Recording the phone will send.
 */
data class TurnOffer(val turn: String, val turnJson: String, val sha256: String, val bytes: Long) : Message {
    override fun toString() = "TurnOffer(turn=$turn, sha256=$sha256, bytes=$bytes)"
}

/** Phone to hub: one piece of the Recording on offer, from [offset]; [data] is standard base64. */
data class TurnAudio(val turn: String, val offset: Long, val data: String, val last: Boolean) : Message {
    override fun toString() = "TurnAudio(turn=$turn, offset=$offset, ${data.length} base64 chars, last=$last)"

    companion object {
        fun of(turn: String, offset: Long, bytes: ByteArray, last: Boolean) =
            TurnAudio(turn, offset, Base64.getEncoder().encodeToString(bytes), last)
    }
}

/**
 * Hub to phone: the answer to each [TurnOffer] and [TurnAudio]. [status] is an
 * open string: [SEND] (from [offset]), [STORED], [REJECTED] (with a [reason]),
 * and anything else reads as a rejection.
 */
data class TurnAck(
    val turn: String,
    val status: String,
    val offset: Long? = null,
    val reason: String? = null,
    val message: String? = null,
) : Message {
    companion object {
        const val SEND = "send"
        const val STORED = "stored"
        const val REJECTED = "rejected"
    }
}

/**
 * Phone to hub: deliver the Draft [text] to the agent Bucket whose Descriptor
 * id is [bucket], at most once for its idempotency [key]. The same [Send],
 * key and all, goes again after a reconnect until a [Sent] answers it.
 */
data class Send(val key: String, val bucket: String, val text: String) : Message {
    override fun toString() = "Send(key=$key, bucket=$bucket, ${text.length} chars)" // keeps the Draft out of logs
}

/**
 * Hub to phone: the answer to the [Send] [key], to be spoken as it is.
 * [reason] is null when the Draft was delivered; with any reason it was not,
 * or may not have been, and the phone keeps the Draft.
 */
data class Sent(val key: String, val say: String, val reason: String? = null) : Message

/**
 * A frame that is not a message, or a message of a known type that breaks its
 * schema. [messageType] is the frame's `type` when it had one.
 */
class BadMessage(problem: String, val messageType: String? = null) : IllegalArgumentException(problem)

/**
 * The phone's reading of the protocol, v1: the same rules as the hub's
 * `stsloop_hub.protocol`, checked by the same fixtures.
 *
 * - [decode] turns a frame into a [Message]. Unknown fields are ignored at any
 *   depth, and a message of a type the phone does not know decodes to null, so
 *   the caller ignores it. A known type that breaks its schema throws [BadMessage].
 * - [encode] gives compact JSON, one message per text frame.
 */
object Protocol {
    /** The one version the phone speaks (ADR-0006: the phone supports only its own major). */
    val VERSION = Version(1, 0)

    /** The capabilities the phone can use: Descriptors from the hub, and Corpus upload to it. */
    val CAPABILITIES: List<String> = listOf("descriptors", CORPUS)

    const val CORPUS = "corpus"

    /** What the phone says first on every connection. */
    val HELLO = Hello(VERSION, CAPABILITIES)

    /** The shortest idempotency key a [Send] may carry; the phone's are 32 hex digits ([newKey]). */
    const val MIN_KEY = 16

    private val keys = SecureRandom()

    /** A new idempotency key for a [Send]: 128 random bits, as 32 lowercase hex digits. */
    fun newKey(): String = ByteArray(16).also { keys.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private val URL = Regex("^wss?://.+")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{32,}$")
    private val TURN_NAME = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z-[0-9a-f]{1,32}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun decode(text: String): Message? {
        val obj = try {
            Json.parse(text)
        } catch (e: JsonException) {
            throw BadMessage("not JSON: ${e.message}")
        }
        return fromJson(obj)
    }

    fun fromJson(obj: Any?): Message? {
        val type = (obj as? Map<*, *>)?.get("type")
        if (type !is String || type.isEmpty()) throw BadMessage("not a message: an object with a string type")
        val o = Obj(obj, type)
        return try {
            when (type) {
                "hello" -> Hello(o.version("protocol"), o.strings("capabilities"))
                "welcome" -> Welcome(o.version("protocol"), o.strings("capabilities"))
                "refusal" -> Refusal(
                    o.string("reason"),
                    o.string("message"),
                    if ("supported" in obj) o.list("supported").map { Obj(mapOf("v" to it), "refusal's supported").version("v") } else emptyList(),
                )
                "descriptors" -> Descriptors(o.list("buckets").map(::descriptor))
                "request" -> Request(o.string("id"), o.string("bucket"), o.string("command"))
                "reply" -> Reply(o.string("id"), o.string("say"), if ("reason" in obj) o.string("reason") else null)
                "send" -> {
                    val key = o.string("key")
                    if (key.length < MIN_KEY) throw BadMessage("send needs a key of at least $MIN_KEY characters")
                    Send(key, o.string("bucket"), o.string("text"))
                }
                "sent" -> Sent(o.string("key"), o.string("say"), if ("reason" in obj) o.string("reason") else null)
                "pairing" -> {
                    val url = o.string("url")
                    val token = o.string("token")
                    if (!URL.matches(url)) throw BadMessage("pairing needs a ws:// or wss:// url")
                    if (!TOKEN.matches(token)) throw BadMessage("pairing needs a token of at least 32 URL-safe base64 characters")
                    Pairing(o.version("protocol"), url, token)
                }
                "turn_offer" -> {
                    val turn = o.string("turn")
                    if (!TURN_NAME.matches(turn)) throw BadMessage("turn_offer's turn \"$turn\" is not a Turn directory name")
                    val audio = Obj(o.map["audio"] as? Map<*, *> ?: throw BadMessage("turn_offer needs audio as an object"), "turn_offer's audio")
                    val sha256 = audio.string("sha256")
                    if (!SHA256.matches(sha256)) throw BadMessage("turn_offer's audio needs sha256 as 64 lowercase hex digits")
                    val turnJson = o.map["turn_json"] as? String ?: throw BadMessage("turn_offer needs turn_json as a string")
                    if (turnJson.length < 2) throw BadMessage("turn_offer has an empty turn_json")
                    TurnOffer(turn, turnJson, sha256, audio.count("bytes"))
                }
                "turn_audio" -> {
                    val data = o.map["data"] as? String ?: throw BadMessage("turn_audio needs data as a string")
                    try {
                        Base64.getDecoder().decode(data)
                    } catch (e: IllegalArgumentException) {
                        throw BadMessage("turn_audio's data is not base64: ${e.message}")
                    }
                    TurnAudio(o.string("turn"), o.count("offset"), data, o.map["last"] as? Boolean ?: throw BadMessage("turn_audio needs last as a boolean"))
                }
                "turn_ack" -> {
                    val status = o.string("status")
                    TurnAck(
                        o.string("turn"),
                        status,
                        offset = if ("offset" in obj || status == TurnAck.SEND) o.count("offset") else null,
                        reason = if ("reason" in obj || status == TurnAck.REJECTED) o.string("reason") else null,
                        message = if ("message" in obj) o.string("message") else null,
                    )
                }
                else -> null
            }
        } catch (e: BadMessage) {
            throw BadMessage(e.message ?: "bad $type", type)
        }
    }

    fun encode(message: Message): String = JsonWriter.write(toJson(message))

    fun toJson(message: Message): Map<String, Any?> = when (message) {
        is Hello -> mapOf("type" to "hello", "protocol" to v(message.protocol), "capabilities" to message.capabilities)
        is Welcome -> mapOf("type" to "welcome", "protocol" to v(message.protocol), "capabilities" to message.capabilities)
        is Refusal -> buildMap {
            put("type", "refusal")
            put("reason", message.reason)
            put("message", message.message)
            if (message.supported.isNotEmpty()) put("supported", message.supported.map(::v))
        }
        is Descriptors -> mapOf("type" to "descriptors", "buckets" to message.buckets.map(::descriptorJson))
        is Pairing -> mapOf("type" to "pairing", "protocol" to v(message.protocol), "url" to message.url, "token" to message.token)
        is Request -> mapOf("type" to "request", "id" to message.id, "bucket" to message.bucket, "command" to message.command)
        is Reply -> buildMap {
            put("type", "reply")
            put("id", message.id)
            message.reason?.let { put("reason", it) }
            put("say", message.say)
        }
        is Send -> mapOf("type" to "send", "key" to message.key, "bucket" to message.bucket, "text" to message.text)
        is Sent -> buildMap {
            put("type", "sent")
            put("key", message.key)
            message.reason?.let { put("reason", it) }
            put("say", message.say)
        }
        is TurnOffer -> mapOf(
            "type" to "turn_offer",
            "turn" to message.turn,
            "turn_json" to message.turnJson,
            "audio" to mapOf("sha256" to message.sha256, "bytes" to message.bytes),
        )
        is TurnAudio -> mapOf("type" to "turn_audio", "turn" to message.turn, "offset" to message.offset, "data" to message.data, "last" to message.last)
        is TurnAck -> buildMap {
            put("type", "turn_ack")
            put("turn", message.turn)
            put("status", message.status)
            message.offset?.let { put("offset", it) }
            message.reason?.let { put("reason", it) }
            message.message?.let { put("message", it) }
        }
    }

    private fun v(version: Version) = mapOf("major" to version.major, "minor" to version.minor)

    private fun descriptorJson(d: HubDescriptor) = mapOf(
        "id" to d.id,
        "name" to d.name,
        "aliases" to d.aliases,
        "type" to d.type,
        "primitives" to d.primitives,
        "commands" to d.commands.map { c ->
            buildMap {
                put("id", c.id)
                put("phrases", c.phrases)
                put("primitive", c.primitive)
                if (c.anywhere) put("anywhere", true)
                c.replyWithinMs?.let { put("reply_within_ms", it) }
            }
        },
    )

    private fun descriptor(value: Any?): HubDescriptor {
        val map = value as? Map<*, *> ?: throw BadMessage("a Descriptor is not an object")
        val o = Obj(map, "Descriptor \"${map["id"]}\"")
        val d = HubDescriptor(
            id = o.string("id"),
            name = o.string("name"),
            aliases = o.strings("aliases"),
            type = o.string("type"),
            primitives = o.strings("primitives"),
            commands = o.list("commands").map { c ->
                val cm = c as? Map<*, *> ?: throw BadMessage("${o.what}'s command is not an object")
                val co = Obj(cm, "${o.what}'s command")
                HubCommand(
                    co.string("id"), co.strings("phrases", minItems = 1), co.string("primitive"), co.boolean("anywhere"),
                    co.positiveOrNull("reply_within_ms"),
                )
            },
        )
        for (c in d.commands) {
            if (c.primitive !in d.primitives) throw BadMessage("${o.what}'s command \"${c.id}\" uses \"${c.primitive}\", not in its primitives")
        }
        return d
    }

    /** Typed reads of one JSON object, naming [what] in each problem. */
    private class Obj(val map: Map<*, *>, val what: String) {
        fun string(key: String): String {
            val s = map[key] as? String ?: throw BadMessage("$what needs $key as a string")
            if (s.isEmpty()) throw BadMessage("$what has an empty $key")
            return s
        }

        /** An optional boolean: false when absent. */
        fun boolean(key: String): Boolean {
            if (key !in map) return false
            return map[key] as? Boolean ?: throw BadMessage("$what needs $key as a boolean")
        }

        fun list(key: String): List<*> = map[key] as? List<*> ?: throw BadMessage("$what needs $key as a list")

        fun strings(key: String, minItems: Int = 0): List<String> {
            val l = list(key)
            if (l.size < minItems || !l.all { it is String && it.isNotEmpty() }) throw BadMessage("$what needs $key as a list of non-empty strings")
            return l.map { it as String }
        }

        /** An optional positive integer: null when absent. */
        fun positiveOrNull(key: String): Long? {
            if (key !in map) return null
            val n = map[key] as? Long ?: throw BadMessage("$what needs $key as an integer")
            if (n < 1) throw BadMessage("$what has a $key that is not positive")
            return n
        }

        /** A non-negative integer. */
        fun count(key: String): Long {
            val n = map[key] as? Long ?: throw BadMessage("$what needs $key as an integer")
            if (n < 0) throw BadMessage("$what has a negative $key")
            return n
        }

        fun version(key: String): Version {
            val m = map[key] as? Map<*, *> ?: throw BadMessage("$what needs $key as an object")
            fun part(k: String): Int {
                val n = m[k] as? Long ?: throw BadMessage("$what's $key needs $k as an integer")
                if (n < 0 || n > Int.MAX_VALUE) throw BadMessage("$what's $key has $k out of range")
                return n.toInt()
            }
            return Version(part("major"), part("minor"))
        }
    }
}
