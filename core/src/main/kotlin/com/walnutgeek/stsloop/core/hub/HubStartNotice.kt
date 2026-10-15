package com.walnutgeek.stsloop.core.hub

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
