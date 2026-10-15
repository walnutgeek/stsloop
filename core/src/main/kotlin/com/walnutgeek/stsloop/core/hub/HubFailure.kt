package com.walnutgeek.stsloop.core.hub

/**
 * Why the phone cannot use its hub, in words that name the one thing to
 * check (#74). The same [text] goes on the settings hub line, in the
 * reconnect nudge, and in "hub details". [kind] is what the nudge's Ignore
 * compares: a failure of another kind nudges again.
 *
 * A [HubLink] tells them apart by how far a connection got: no WebSocket
 * within [HubLink.CONNECT_TIMEOUT_MS], or a connection error with no HTTP
 * answer, is [Unreachable]; a 401 is [TokenRefused]; a `refusal` or a
 * `welcome` in another major is [ProtocolRefused]; anything else that
 * answered and then did not welcome the phone is [NoWelcome].
 */
sealed interface HubFailure {
    val kind: Kind

    /** Sentences for a person, on the screen. */
    val text: String

    enum class Kind(val json: String) {
        UNREACHABLE("unreachable"),
        TOKEN_REFUSED("token_refused"),
        PROTOCOL_REFUSED("protocol_refused"),
        NO_WELCOME("no_welcome"),
        ;

        companion object {
            fun of(json: String?): Kind? = entries.firstOrNull { it.json == json }
        }
    }

    /** Which side a [ProtocolRefused] says to update. */
    enum class Update(val json: String) {
        /** The hub needs a newer app. */
        APP("app"),

        /** This app needs a newer hub. */
        HUB("hub"),
        ;

        companion object {
            fun of(json: String?): Update? = entries.firstOrNull { it.json == json }
        }
    }

    /** Nothing answered at [address] (the pairing's host and port). */
    data class Unreachable(val address: String) : HubFailure {
        override val kind get() = Kind.UNREACHABLE
        override val text
            get() = "Can't reach the hub at $address. Is Tailscale on and is this phone in your tailnet? Is stsloop-hub serve running?"
    }

    /** The hub answered 401: it does not know this phone's token. */
    data object TokenRefused : HubFailure {
        override val kind get() = Kind.TOKEN_REFUSED
        override val text get() = "The hub doesn't know this phone. Run stsloop-hub init and scan again."
    }

    /**
     * The hub refused the handshake; [update] names the side to update, null
     * when the refusal does not say (then the hub's own [message] is shown).
     */
    data class ProtocolRefused(val update: Update?, val message: String) : HubFailure {
        override val kind get() = Kind.PROTOCOL_REFUSED
        override val text
            get() = when (update) {
                Update.APP -> "The hub needs a newer app. Update stsloop on this phone."
                Update.HUB -> "This app needs a newer hub. Upgrade stsloop-hub."
                null -> "The hub refused this phone: $message"
            }
    }

    /** Something answered at the hub's address but did not welcome the phone. */
    data object NoWelcome : HubFailure {
        override val kind get() = Kind.NO_WELCOME
        override val text get() = "The hub answered but didn't finish the handshake."
    }

    /** The fields [fromJson] reads back. */
    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(KIND to kind.json).apply {
        when (val f = this@HubFailure) {
            is Unreachable -> put(ADDRESS, f.address)
            is ProtocolRefused -> {
                put(UPDATE, f.update?.json)
                put(MESSAGE, f.message)
            }
            TokenRefused, NoWelcome -> Unit
        }
    }

    companion object {
        private const val KIND = "kind"
        private const val ADDRESS = "address"
        private const val UPDATE = "update"
        private const val MESSAGE = "message"

        /** The failure [toJson] wrote, or null when [json] is not one this phone knows. */
        fun fromJson(json: Map<*, *>): HubFailure? = when (Kind.of(json[KIND] as? String)) {
            Kind.UNREACHABLE -> (json[ADDRESS] as? String)?.let(::Unreachable)
            Kind.TOKEN_REFUSED -> TokenRefused
            Kind.PROTOCOL_REFUSED -> (json[MESSAGE] as? String)?.let { ProtocolRefused(Update.of(json[UPDATE] as? String), it) }
            Kind.NO_WELCOME -> NoWelcome
            null -> null
        }

        /**
         * What [refusal] says to update. `unsupported_protocol` decides by the
         * majors the hub supports: all older than the phone's, the hub; any
         * newer, the app. `bad_hello` means the hub cannot read this app's
         * hello, so the app. Any other reason does not say.
         */
        fun of(refusal: Refusal): HubFailure {
            val mine = Protocol.VERSION.major
            val update = when (refusal.reason) {
                "unsupported_protocol" -> when {
                    refusal.supported.any { it.major > mine } -> Update.APP
                    refusal.supported.isNotEmpty() && refusal.supported.all { it.major < mine } -> Update.HUB
                    else -> null
                }
                "bad_hello" -> Update.APP
                else -> null
            }
            return ProtocolRefused(update, refusal.message)
        }

        /** The host and port of a pairing's [url], as a person would check it: `100.101.102.103:7717`. */
        fun address(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')
    }
}
