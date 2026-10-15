package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter

/**
 * What the phone last learnt about reaching its paired hub, from any
 * connection (a Session's, the pairing screen's, the nudge's Retry), kept in
 * `files/hub/reach.json` ([HubStore.reach]) so the app can say it when it
 * next opens (#74):
 * ```json
 * { "connected_at": "2026-10-14T09:00:00.000Z",
 *   "failure": { "kind": "unreachable", "address": "100.101.102.103:7717" },
 *   "failed_at": "2026-10-14T10:00:00.000Z",
 *   "ignored": "unreachable" }
 * ```
 * [failure] is the outcome of the last try when it failed, and null once one
 * connects. The reconnect nudge shows while there is a [failure] the person
 * has not [ignored][ignore]. Ignore lasts until the failure kind changes, a
 * connection succeeds, or the hub is paired again (which drops the file).
 * Immutable; each change returns a new value.
 */
data class HubReach(
    /** When the hub last welcomed the phone. */
    val connectedAtMs: Long?,
    /** Why the last try failed; null when it connected, or none was made. */
    val failure: HubFailure?,
    /** When [failure] last happened. */
    val failedAtMs: Long?,
    /** The kind of failure the person chose to ignore. */
    val ignored: HubFailure.Kind?,
) {
    /** Whether to nudge: the last try failed, and not in a way the person has ignored. */
    val nudge: Boolean get() = failure != null && failure.kind != ignored

    /** The hub welcomed the phone at [atMs]: no failure, and Ignore ends. */
    fun connected(atMs: Long) = HubReach(atMs, null, null, null)

    /** A try failed with [failure] at [atMs]; Ignore holds only while the kind is the ignored one. */
    fun failed(failure: HubFailure, atMs: Long) =
        HubReach(connectedAtMs, failure, atMs, ignored?.takeIf { it == failure.kind })

    /** The person chose Ignore for the current failure. */
    fun ignore(): HubReach = if (failure == null) this else copy(ignored = failure.kind)

    /** With [status] recorded at [atMs]: a welcome or a failure; anything else changes nothing. */
    fun record(status: HubStatus, atMs: Long): HubReach {
        val failure = status.failure
        return when {
            status is HubStatus.Online -> connected(atMs)
            failure != null -> failed(failure, atMs)
            else -> this
        }
    }

    /** The settings screen's hub line: connected, or the last failure and when; [time] formats a moment. */
    fun line(time: (Long) -> String): String = when {
        failure != null -> "Could not connect (${time(checkNotNull(failedAtMs))}). ${failure.text}"
        connectedAtMs != null -> "Connected (${time(connectedAtMs)})."
        else -> "Not connected yet."
    }

    /** The file format [parse] reads. */
    fun toJson(): String = JsonWriter.pretty(
        linkedMapOf(
            CONNECTED_AT to connectedAtMs?.let(UtcTimestamp::format),
            FAILURE to failure?.toJson(),
            FAILED_AT to failedAtMs?.let(UtcTimestamp::format),
            IGNORED to ignored?.json,
        ),
    )

    companion object {
        val EMPTY = HubReach(null, null, null, null)

        private const val CONNECTED_AT = "connected_at"
        private const val FAILURE = "failure"
        private const val FAILED_AT = "failed_at"
        private const val IGNORED = "ignored"

        /**
         * Reads [toJson]'s format. A file that is not a JSON object reads as
         * [EMPTY]; a failure this phone cannot read, or without its time, is
         * dropped with its Ignore, so a broken file never nudges.
         */
        fun parse(json: String): HubReach {
            val root = try {
                Json.parseObject(json)
            } catch (e: JsonException) {
                return EMPTY
            }
            val connectedAt = (root[CONNECTED_AT] as? String)?.let(UtcTimestamp::parse)
            val failure = (root[FAILURE] as? Map<*, *>)?.let(HubFailure::fromJson)
            val failedAt = (root[FAILED_AT] as? String)?.let(UtcTimestamp::parse)
            if (failure == null || failedAt == null) return HubReach(connectedAt, null, null, null)
            return HubReach(connectedAt, failure, failedAt, HubFailure.Kind.of(root[IGNORED] as? String))
        }
    }
}
