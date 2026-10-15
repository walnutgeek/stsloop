package com.walnutgeek.stsloop.core.hub

/**
 * One try at the hub for a screen (the nudge's Retry, a fresh pairing): fed a
 * [HubLink]'s statuses and Descriptors, it calls [done] once, with the first
 * failure or with the first Descriptors after a welcome. Thread-safe.
 */
class HubProbe(private val done: (Outcome) -> Unit) {
    sealed interface Outcome {
        /** What the screen says. */
        val said: String

        data class Connected(val descriptors: Descriptors) : Outcome {
            override val said: String
                get() = when (val n = descriptors.buckets.count { it.type == "agent" }) {
                    1 -> "connected, 1 agent."
                    else -> "connected, $n agents."
                }
        }

        data class Failed(val failure: HubFailure) : Outcome {
            override val said: String get() = failure.text
        }
    }

    private var over = false

    fun status(status: HubStatus) {
        status.failure?.let { finish(Outcome.Failed(it)) }
    }

    fun descriptors(descriptors: Descriptors) = finish(Outcome.Connected(descriptors))

    private fun finish(outcome: Outcome) {
        synchronized(this) {
            if (over) return
            over = true
        }
        done(outcome)
    }
}

/**
 * The one sentence a Session says about its hub (#74): [CANT_REACH], into
 * the first Silence after the Session's first failure to use the hub, if the
 * hub has not welcomed the phone before it. Said at most once per Session;
 * after that, hub Buckets say "hub offline" as they always have. Fed a
 * [HubLink]'s statuses; [say] must return at once. Thread-safe.
 */
class HubStartNotice(private val say: (String) -> Unit) {
    private var over = false

    fun status(status: HubStatus) {
        val failure = status.failure
        synchronized(this) {
            if (over || (status !is HubStatus.Online && failure == null)) return
            over = true
        }
        if (failure != null) say(CANT_REACH)
    }

    companion object {
        const val CANT_REACH = "can't reach the hub."
    }
}
