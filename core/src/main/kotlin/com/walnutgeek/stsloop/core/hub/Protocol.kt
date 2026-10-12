package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter

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
 * Focus when [anywhere]; [primitive] runs it.
 */
data class HubCommand(val id: String, val phrases: List<String>, val primitive: String, val anywhere: Boolean = false)

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

    /** The capabilities the phone can use. */
    val CAPABILITIES: List<String> = listOf("descriptors")

    /** What the phone says first on every connection. */
    val HELLO = Hello(VERSION, CAPABILITIES)

    private val URL = Regex("^wss?://.+")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{32,}$")

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
                "pairing" -> {
                    val url = o.string("url")
                    val token = o.string("token")
                    if (!URL.matches(url)) throw BadMessage("pairing needs a ws:// or wss:// url")
                    if (!TOKEN.matches(token)) throw BadMessage("pairing needs a token of at least 32 URL-safe base64 characters")
                    Pairing(o.version("protocol"), url, token)
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
                HubCommand(co.string("id"), co.strings("phrases", minItems = 1), co.string("primitive"), co.boolean("anywhere"))
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
