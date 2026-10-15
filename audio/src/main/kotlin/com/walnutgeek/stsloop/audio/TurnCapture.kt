package com.walnutgeek.stsloop.audio

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.failure.FailureAnnouncer
import com.walnutgeek.stsloop.core.failure.DeadMic
import com.walnutgeek.stsloop.core.failure.SilentFailure
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Drafts
import com.walnutgeek.stsloop.core.hub.HubFacts
import com.walnutgeek.stsloop.core.hub.HubReach
import com.walnutgeek.stsloop.core.hub.StatusQueries
import com.walnutgeek.stsloop.core.hub.HubRequests
import com.walnutgeek.stsloop.core.hub.HubStatus
import com.walnutgeek.stsloop.core.hub.StatusReport
import com.walnutgeek.stsloop.core.hub.StatusRequest
import com.walnutgeek.stsloop.core.hub.Send
import com.walnutgeek.stsloop.core.hub.Sent
import com.walnutgeek.stsloop.core.hub.Item
import com.walnutgeek.stsloop.core.hub.ItemSpoken
import com.walnutgeek.stsloop.core.hub.Message
import com.walnutgeek.stsloop.core.hub.Reply
import com.walnutgeek.stsloop.core.hub.Request
import com.walnutgeek.stsloop.core.hub.Summarize
import com.walnutgeek.stsloop.core.summary.BucketSummaries
import com.walnutgeek.stsloop.core.summary.NoteSource
import com.walnutgeek.stsloop.core.summary.SummaryMarks
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import com.walnutgeek.stsloop.core.testmode.TestConfig
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turn.AttentionQueue
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Focus
import com.walnutgeek.stsloop.core.turn.HalfDuplex
import com.walnutgeek.stsloop.core.turn.LoopState
import com.walnutgeek.stsloop.core.turn.Segmenter
import com.walnutgeek.stsloop.core.turn.Speaker
import com.walnutgeek.stsloop.core.turn.SpeechProbability
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turn.Transcriber
import com.walnutgeek.stsloop.core.turn.TurnEvent
import com.walnutgeek.stsloop.core.turn.Utterance
import com.walnutgeek.stsloop.core.turn.Worker
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One Session's capture pipeline: the mic's int16 chunks go in, and each
 * utterance the VAD finds comes out as its own transcribed Turn in the Corpus
 * ([Segmenter] cuts, [Transcriber] streams it into the recognizer while it is
 * captured, [CorpusSink] classifies it with the [PhraseGrammar] over
 * [buckets] and writes it), and each published Turn's transcript is echoed
 * through [speaker], strictly Half-duplex ([HalfDuplex]: the mic is closed
 * while the echo plays and for `guard_ms` after).
 *
 * The Session keeps a [Focus] (#40), starting in the Inbox. A lone Bucket
 * alias focuses that Bucket; [accept] ticks it on the stream's clock, and
 * when `focus_timeout_ms` pass with no speech it returns to the Inbox and
 * the loop says so, into the next Silence like any reply. A command a hub
 * Bucket declares ("status") goes to the hub through `hubSend` ([HubRequests]),
 * and its reply, handed to [replied], is said the same way. Under an agent
 * Bucket's Focus, Turns make its Draft ([Drafts], #45); "send" reads it back
 * and then sends it through `hubSend`, and the hub's answer, handed to
 * [sent], is said; [welcomed] resends whatever the hub has not answered.
 * "Summarize bucket" (#76) reads the focused capture Bucket's Notes from
 * `summaryNotes` and sends them to the hub when it offers `summaries`
 * (`hubSummarises`); the summary, handed to [replied] like a request's
 * reply, is said, and once it has been heard the Bucket's mark in
 * `summaryMarks` moves. Without the hub the loop reads the Notes raw itself.
 * An Item the hub raises (a blocked agent), handed to [item], waits in the
 * Session's [AttentionQueue] and is said after an earcon in the next
 * Silence; once it has been said, the hub is told (`item_spoken`).
 *
 * It also notices the loop's silent failures (#15) and says them the same
 * way, once each ([FailureAnnouncer]): a mic stream that stays digitally
 * silent ([DeadMic]), a Turn whose Recording is silent (`audio.silent`),
 * and what the platform reports through [clientSilenced] and [played].
 * Sample 0 of the stream is wall time
 * [sessionStartedAtMs]. A Turn that fails to write is logged and counted in
 * [failedTurns]; the Session carries on.
 *
 * Decoding and Corpus writes run on this Session's own STT thread, so
 * [accept] never waits on them and the mic is never starved. The recognizer
 * is loaded there too, as soon as the capture is created. If decoding falls
 * more than [maxQueuedMs] of audio behind, the Turn being captured stops
 * being fed and is written without a transcript; its audio is always kept.
 *
 * Not thread-safe: call [accept] and [finish] from the capture thread;
 * [hurry], [spoken], [item], [clientSilenced] and [played] may be called from any thread.
 */
class TurnCapture(
    writer: CorpusWriter,
    private val sessionId: String,
    sessionStartedAtMs: Long,
    appVersion: String,
    timings: Timings,
    vad: SpeechProbability,
    windowSamples: Int,
    /** Loads the Session's one recognizer; called once, on the STT thread. */
    recognizer: () -> StreamingRecognizer,
    maxQueuedMs: Long = MAX_QUEUED_MS,
    /** The Buckets a Turn may be Declared into or focus; [bucketsChanged] replaces them. */
    buckets: BucketRegistry = BucketRegistry.capture(BucketConfig.DEFAULT),
    /** Whether the hub is online now (a volatile read): the loop says "hub offline" for hub Buckets when not. */
    hubOnline: () -> Boolean = { false },
    /** Sends a message to the hub, false when it cannot; must return at once. Replies come back through [replied]. */
    hubSend: (Message) -> Boolean = { false },
    /** Whether the hub is welcomed now with the `summaries` capability (a volatile read). */
    hubSummarises: () -> Boolean = { false },
    /** Where "summarize bucket" reads Notes: the Corpus. */
    summaryNotes: NoteSource = NoteSource.NONE,
    /** Where each capture Bucket's last summary got to. */
    summaryMarks: SummaryMarks = SummaryMarks(null),
    /**
     * What the phone knows of its hub for "hub status" and "hub details"
     * (#75); called on the STT thread, so it may read files.
     */
    hubFacts: (StatusQueries.Detail) -> HubFacts = { HubFacts(HubStatus.Unpaired, true, HubReach.EMPTY, null, null, System.currentTimeMillis()) },
    /** Test mode (#27): each Turn's `test` block, computed on the STT thread; null outside test mode. */
    testOf: ((Utterance) -> TurnTest?)? = null,
    /** Speaks each Turn's echo, reporting its end through [spoken]; null means no echo, and the mic never closes. */
    speaker: Speaker? = null,
    /**
     * "Stop listening" (#14): [turn] is in the Corpus and its reply has been
     * said (or never will be: no speaker, or the Session ended first). End the
     * Session. Called on the capture or the STT thread.
     */
    private val endSession: (turn: Turn) -> Unit = {},
    /** Called on the STT thread after each Turn is published, with the stream range it holds. */
    private val onTurn: (Turn, Utterance, SttTiming?) -> Unit = { _, _, _ -> },
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "stt-$sessionId") }
    private val grammar = PhraseGrammar(buckets)
    private val focus = Focus({ grammar.buckets }, timings.focusTimeoutMs.toLong())
    private val requests = HubRequests(
        send = hubSend,
        say = { text -> if (!ending) loop.announce(text) },
        clockMs = SystemClock::elapsedRealtime,
        listener = object : HubRequests.Listener {
            override fun sent(request: Request, bucket: BucketRequest) {
                Log.i(TAG, "Session $sessionId: asked the hub ${bucket.command} of ${bucket.bucket} (${request.id})")
            }

            override fun replied(request: Request, reply: Reply, afterMs: Long) {
                Log.i(TAG, "Session $sessionId: the hub answered ${request.id} in $afterMs ms${reply.reason?.let { " ($it)" } ?: ""}: ${reply.say}")
            }

            override fun timedOut(request: Request, afterMs: Long) {
                Log.w(TAG, "Session $sessionId: no answer from the hub to ${request.id} in $afterMs ms")
            }

            override fun ignored(reply: Reply) {
                Log.w(TAG, "Session $sessionId: ignored a reply to ${reply.id}, which nothing is waiting for")
            }
        },
    )
    private val drafts = Drafts(
        send = hubSend,
        say = { text -> if (!ending) loop.announce(text) },
        clockMs = SystemClock::elapsedRealtime,
        listener = object : Drafts.Listener {
            override fun sent(send: Send, again: Boolean) {
                Log.i(TAG, "Session $sessionId: ${if (again) "sent again" else "sent"} the Draft for ${send.bucket} (${send.key}, ${send.text.length} chars)")
            }

            override fun answered(send: Send, sent: Sent, afterMs: Long) {
                Log.i(TAG, "Session $sessionId: the hub answered the send ${send.key} in $afterMs ms${sent.reason?.let { " ($it)" } ?: ""}: ${sent.say}")
            }

            override fun ignored(sent: Sent) {
                Log.w(TAG, "Session $sessionId: ignored an answer to the send ${sent.key}, which nothing is waiting for")
            }
        },
    )

    private val summaries = BucketSummaries(
        notes = summaryNotes,
        marks = summaryMarks,
        hubSummarises = hubSummarises,
        send = hubSend,
        say = { text, heard -> if (ending) heard(false) else loop.announce(text, heard) },
        clockMs = SystemClock::elapsedRealtime,
        // The mark is a file write, and a summary is heard on the capture thread: written on the STT thread.
        io = { task ->
            try {
                executor.execute(task)
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "Session $sessionId: a summary was heard as the Session ended; its mark stays", e)
            }
        },
        listener = object : BucketSummaries.Listener {
            override fun sent(summarize: Summarize) {
                Log.i(TAG, "Session $sessionId: asked the hub to summarise ${summarize.notes.size} notes of ${summarize.bucket} (${summarize.id})")
            }

            override fun replied(summarize: Summarize, reply: Reply, afterMs: Long) {
                Log.i(TAG, "Session $sessionId: the hub summarised ${summarize.id} in $afterMs ms${reply.reason?.let { " ($it)" } ?: ""}")
            }

            override fun timedOut(summarize: Summarize, afterMs: Long) {
                Log.w(TAG, "Session $sessionId: no summary from the hub for ${summarize.id} in $afterMs ms; reading the notes raw")
            }

            override fun moved(bucket: String, through: String) {
                Log.i(TAG, "Session $sessionId: $bucket summarised through $through")
            }

            override fun failed(what: String, error: Exception) {
                Log.e(TAG, "Session $sessionId: $what failed", error)
            }
        },
    )

    private val reports = StatusQueries(
        send = hubSend,
        say = { text -> if (!ending) loop.announce(text) },
        facts = hubFacts,
        clockMs = SystemClock::elapsedRealtime,
        listener = object : StatusQueries.Listener {
            override fun asked(request: StatusRequest, detail: StatusQueries.Detail) {
                Log.i(TAG, "Session $sessionId: asked the hub how it is (${request.id}, ${detail.name.lowercase()})")
            }

            override fun answered(request: StatusRequest, report: StatusReport, afterMs: Long) {
                Log.i(TAG, "Session $sessionId: the hub answered ${request.id} in $afterMs ms: $report")
            }

            override fun timedOut(request: StatusRequest, afterMs: Long) {
                Log.w(TAG, "Session $sessionId: no status report from the hub to ${request.id} in $afterMs ms")
            }

            override fun ignored(report: StatusReport) {
                Log.w(TAG, "Session $sessionId: ignored a status report ${report.id}, which nothing is waiting for")
            }
        },
    )

    private val attention = AttentionQueue()
    private val hubSend = hubSend
    private val hasSpeaker = speaker != null
    private val sink: CorpusSink = CorpusSink(
        writer, sessionId, sessionStartedAtMs, SAMPLE_RATE_HZ, appVersion, grammar, focus,
        testOf = testOf,
        hubOnline = hubOnline,
        request = requests::request,
        drafts = drafts,
        summaries = summaries,
        hubReport = reports::ask,
        listener = object : CorpusSink.Listener {
            override fun readBack(turn: Turn, say: String, send: () -> Unit, withdraw: () -> Unit) {
                // Read back, then sent: a readback the Session ends before is never heard, so nothing is sent,
                // and neither is anything in a Session with no echo (test mode), where nothing is read back.
                if (speaker == null) {
                    Log.w(TAG, "Session $sessionId: a Draft is never sent without its readback, and this Session speaks nothing; kept")
                    withdraw()
                    return
                }
                loop.echo(say) { if (ending) withdraw() else send() }
            }

            override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {
                // Persist, then speak (phase0.md's loop): a Turn that failed to write is not echoed. A command
                // Turn says its reply ("dropped.") instead of its transcript.
                loop.echo(say)
                turn.audio.silent?.let { failures.set(SilentFailure.SILENT_RECORDING, it) }
                onTurn(turn, utterance, timing)
            }

            override fun endSession(turn: Turn, say: String) {
                // Persisted, then said, then ended: the Session ends only once the reply is over.
                ending = true // and no failure is said after "stopped listening."
                loop.echo(say) { endSession(turn) }
            }

            override fun failed(utterance: Utterance, error: Exception) {
                Log.e(TAG, "Session $sessionId lost the Turn at samples ${utterance.startSample}..${utterance.endSample}", error)
            }

            override fun discarded(event: TurnEvent.Discarded) {
                Log.d(TAG, "discarded ${event.speechSamples * 1000 / SAMPLE_RATE_HZ} ms of speech at sample ${event.startSample}")
            }
        },
    )
    private val transcriber = Transcriber(
        Worker { task -> executor.execute(task) },
        load = {
            val t0 = SystemClock.elapsedRealtime()
            recognizer().also { Log.i(TAG, "Session $sessionId loaded ${it.model} in ${SystemClock.elapsedRealtime() - t0} ms") }
        },
        sink,
        wallClock = System::currentTimeMillis,
        nanoTime = SystemClock::elapsedRealtimeNanos, // the clock accept() stamps samples with
        maxQueuedSamples = maxQueuedMs * SAMPLE_RATE_HZ / 1000,
        listener = object : Transcriber.Listener {
            override fun unavailable(error: Throwable) {
                Log.e(TAG, "Session $sessionId has no recognizer; Turns are written without transcripts", error)
            }

            override fun failed(startSample: Long, error: Throwable) {
                Log.e(TAG, "Session $sessionId lost the transcript of the Turn at sample $startSample", error)
            }

            override fun fellBehind(startSample: Long, queuedSamples: Long) {
                Log.w(
                    TAG,
                    "Session $sessionId: decoding ${queuedSamples * 1000 / SAMPLE_RATE_HZ} ms behind; " +
                        "the Turn at sample $startSample is written without a transcript",
                )
            }
        },
    ).apply { start() }
    private val segmenter = Segmenter(timings, SAMPLE_RATE_HZ, windowSamples, vad, transcriber)
    private val loop: HalfDuplex = HalfDuplex(
        segmenter, timings, SAMPLE_RATE_HZ, speaker,
        object : HalfDuplex.Listener {
            override fun speaking(id: Long, text: String, atSample: Long) {
                Log.i(TAG, "Session $sessionId echo $id: mic closed at sample $atSample")
            }

            override fun reopened(atSample: Long, closedSamples: Long) {
                Log.i(TAG, "Session $sessionId: mic open again at sample $atSample after ${closedSamples * 1000 / SAMPLE_RATE_HZ} ms closed")
            }

            override fun lost(id: Long) {
                Log.w(TAG, "Session $sessionId echo $id never reported an end; stopped it, reopening the mic after the guard")
            }

            override fun unspoken(count: Int) {
                Log.i(TAG, "Session $sessionId: $count echoes never spoken, the Session ended first")
            }

            override fun itemsSpoken(items: List<Item>) {
                for (i in items) tellSpoken(i, "said")
            }
        },
        attention,
    )
    private val failures = FailureAnnouncer(
        clockMs = SystemClock::elapsedRealtime,
        say = { f -> if (!ending) loop.announce(f.spoken) },
        listener = object : FailureAnnouncer.Listener {
            override fun announced(failure: SilentFailure) {
                announced++
                val how = if (speaker == null) "not spoken: no echo in this Session" else "saying \"${failure.spoken}\""
                Log.w(TAG, "Session $sessionId: $failure ($how)")
            }

            override fun suppressed(failure: SilentFailure, because: String) {
                Log.w(TAG, "Session $sessionId: $failure, not said: $because")
            }

            override fun cleared(failure: SilentFailure) {
                Log.i(TAG, "Session $sessionId: $failure is over")
            }
        },
    )
    private val deadMic = DeadMic(SAMPLE_RATE_HZ) { silent, atSample ->
        Log.w(TAG, "Session $sessionId: the mic stream is ${if (silent) "effectively silent" else "live again"} at sample $atSample")
        failures.set(SilentFailure.MIC_SILENT, silent)
    }
    private var finished = false

    /** Samples accepted so far: the stream's clock, which [focus] runs on. Capture thread only. */
    private var position = 0L

    /**
     * Set once the Session is ending ("stop listening" asked to end it, or
     * [finish] started): a failure reported after that (a late platform
     * callback) is not said, so nothing follows the last reply.
     */
    @Volatile
    private var ending = false

    /** Silent failures said (or, with no echo, that would have been); final once [finish] returns. */
    @Volatile
    var announced = 0
        private set

    /** Published so far; final once [finish] returns. */
    val publishedTurns: Int get() = sink.published
    val failedTurns: Int get() = sink.failed

    /** Published without a transcript; final once [finish] returns. */
    val untranscribedTurns: Int get() = transcriber.untranscribed

    /**
     * Tees the first [count] samples of [samples] in. [capturedAtNs] is when
     * the last of them was captured, on `SystemClock.elapsedRealtimeNanos`:
     * call this straight after `AudioRecord.read`. Returns without waiting for
     * decoding or writing.
     */
    fun accept(samples: ShortArray, count: Int, capturedAtNs: Long = SystemClock.elapsedRealtimeNanos()) {
        // Only what the loop listens to: while the mic is closed some routes may zero it during our own speech.
        val open = loop.state.let { it == LoopState.LISTENING || it == LoopState.CAPTURING }
        if (open) deadMic.accept(samples, count)
        loop.accept(samples, count, capturedAtNs)
        position += count
        if (focus.tick(position * 1000 / SAMPLE_RATE_HZ, capturing = loop.state == LoopState.CAPTURING) && !ending) {
            Log.i(TAG, "Session $sessionId: Focus timed out back to the Inbox")
            loop.announce(drafts.timedOut(focus.left))
        }
        requests.tick()
        drafts.tick()
        summaries.tick()
        reports.tick()
    }

    /** Any thread: the hub answered a send; its answer is said into the next Silence. */
    fun sent(sent: Sent) = drafts.sent(sent)

    /** Any thread: the hub has welcomed the phone; every send it has not answered goes again, with its key. */
    fun welcomed() = drafts.welcomed()

    /**
     * Any thread: the hub raised [item]; it is said after an earcon in the
     * next Silence, unless it has been already. Without a speaker nothing is
     * said, and the hub is not told: it sends the Item again next time.
     */
    fun item(item: Item) {
        if (!hasSpeaker || ending) {
            val why = if (ending) "the Session is ending" else "no echo in this Session"
            Log.i(TAG, "Session $sessionId: item ${item.id} not said, $why: ${item.say}")
            return
        }
        when (attention.offer(item)) {
            AttentionQueue.Offered.QUEUED -> Log.i(TAG, "Session $sessionId: item ${item.id} waits for Silence: ${item.say}")
            AttentionQueue.Offered.DUPLICATE -> Log.i(TAG, "Session $sessionId: item ${item.id} is already waiting")
            AttentionQueue.Offered.SPOKEN -> tellSpoken(item, "already said")
        }
    }

    private fun tellSpoken(item: Item, why: String) {
        val told = hubSend(ItemSpoken(item.id))
        Log.i(TAG, "Session $sessionId: item ${item.id} $why; " + if (told) "told the hub" else "the hub is offline, it will send it again")
    }

    /** Any thread: the hub answered "hub status" or "hub details"; the answer is said into the next Silence. */
    fun statusReport(report: StatusReport) = reports.answered(report)

    /** Any thread: the hub answered a request or a summary; its reply is said into the next Silence. */
    fun replied(reply: Reply) {
        if (!summaries.replied(reply)) requests.replied(reply)
    }

    /**
     * Any thread: say [text] into the next Silence, as the loop's own machine
     * Turn (the name-recognisability check's warning at Session start). Not
     * said once the Session is ending, nor with no echo.
     */
    fun announce(text: String) {
        if (!ending) loop.announce(text)
    }

    /** Any thread: from the next Turn on, the Buckets are [buckets] (the hub sent a new snapshot). */
    fun bucketsChanged(buckets: BucketRegistry) = grammar.replace(buckets)

    /** Any thread: Android has (or no longer has) silenced this Session's recording for another capture client. */
    fun clientSilenced(silenced: Boolean) = failures.set(SilentFailure.MIC_SILENCED, silenced)

    /**
     * Any thread: a machine Turn the engine reported done was [heard] (it
     * plausibly played) or not ([EchoSpeaker]'s checks). Not heard is a
     * failure until one is heard again.
     */
    fun played(heard: Boolean) = failures.set(SilentFailure.SPEECH_UNHEARD, !heard)

    /** Any thread: the echo [id] ended at [atNs] (`elapsedRealtimeNanos`); the guard interval starts there. */
    fun spoken(id: Long, atNs: Long) = loop.spoken(id, atNs)

    /**
     * Skip all remaining recognition: every queued Turn is written at once,
     * without a transcript. For a teardown that cannot wait for decoding.
     */
    fun hurry() = transcriber.abandon()

    /**
     * Ends the Session: an utterance still being captured becomes a Turn, and
     * this waits until every Turn is written and the recognizer is released.
     * Decoding gets [TRANSCRIBE_DRAIN_MS]; after that the rest of the queue is
     * written without transcripts. The queue is never dropped: if a native call
     * is stuck past [WRITE_DRAIN_MS] more, this returns and the STT thread
     * still writes those Turns, and then releases the recognizer, when the call
     * returns. Safe to call twice.
     */
    fun finish() {
        ending = true
        try {
            loop.finish()
        } finally {
            if (!finished) {
                finished = true
                drain()
                for (s in drafts.unanswered()) {
                    Log.w(TAG, "Session $sessionId: the hub never answered the send ${s.key} for ${s.bucket}; it may or may not have arrived")
                }
            }
        }
    }

    private fun drain() {
        transcriber.close()
        executor.shutdown()
        val t0 = SystemClock.elapsedRealtime()
        if (!executor.awaitTermination(TRANSCRIBE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Session $sessionId: STT still decoding after $TRANSCRIBE_DRAIN_MS ms; writing the rest without transcripts")
            transcriber.abandon()
            if (!executor.awaitTermination(WRITE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Session $sessionId: a native call is stuck; queued Turns will be written when it returns")
                return
            }
        }
        Log.i(TAG, "Session $sessionId: STT drained ${SystemClock.elapsedRealtime() - t0} ms after the last sample")
    }

    companion object {
        private const val TAG = "stsloop.TurnCapture"

        /** Decoding may fall this far behind capture before a Turn stops being fed. */
        const val MAX_QUEUED_MS = 10_000L

        /**
         * A normal Stop waits this long for decoding. The service stays in the
         * foreground until the capture thread ends, so a long wait is safe there;
         * a teardown calls [hurry] first and waits only for the writes.
         */
        const val TRANSCRIBE_DRAIN_MS = 30_000L
        const val WRITE_DRAIN_MS = 3_000L

        /** Silero VAD as a [SpeechProbability]: the raw per-window probability, no sherpa-side segmenting. */
        fun silero(vad: Vad) = SpeechProbability { window -> vad.compute(window) }

        /**
         * The [Timings] in `<dir>/timings.json`, read at Session start so they can
         * be tuned without a rebuild. A missing file means defaults; each
         * rejected key is logged and keeps its default, the rest still apply.
         */
        fun loadTimings(dir: File): Timings =
            loadConfig(File(dir, Timings.FILE), Timings()) { Timings.parse(it).run { timings to rejected } }

        /**
         * The [BucketConfig] in `<dir>/buckets.json`, read at Session start so
         * Buckets and their aliases can be edited without a rebuild. A missing
         * or unreadable file means the phase0.md defaults; otherwise each rejected
         * Bucket or alias is logged and the rest still apply.
         */
        fun loadBuckets(dir: File): BucketConfig =
            loadConfig(File(dir, BucketConfig.FILE), BucketConfig.DEFAULT) { BucketConfig.parse(it).run { config to rejected } }

        /**
         * The phone's own aliases for hub Buckets ([HubAliases]) in
         * `<dir>/hub/aliases.json`, set on the settings screen and read at
         * Session start. A missing or unreadable file means the hub's aliases.
         */
        fun loadHubAliases(dir: File): HubAliases =
            loadConfig(File(dir, HubAliases.FILE), HubAliases.NONE) { HubAliases.parse(it).run { aliases to rejected } }

        /**
         * The Buckets the phone can address: the capture Provider over
         * [loadBuckets], then the hub's Buckets of [hub] (the cached or a live
         * snapshot), if any, with the phone's [aliases] for them. Each conflict is logged.
         */
        fun registry(config: BucketConfig, hub: Descriptors?, aliases: HubAliases = HubAliases.NONE): BucketRegistry =
            HubProvider.registry(config, hub, aliases).also { r -> for (p in r.rejected) Log.e(TAG, "Buckets: ignoring $p") }

        /**
         * The Bluetooth test mode [TestConfig] in `<dir>/testmode.json`, read at
         * Session start. A missing or unreadable file means test mode is off;
         * each rejected key is logged and keeps its default.
         */
        fun loadTestConfig(dir: File): TestConfig =
            loadConfig(File(dir, TestConfig.FILE), TestConfig()) { TestConfig.parse(it).run { config to rejected } }

        /** Reads a runtime-editable config [file]: [default] when missing or unreadable, each rejection logged. */
        private fun <T> loadConfig(file: File, default: T, parse: (String) -> Pair<T, List<String>>): T {
            if (!file.exists()) return default
            val text = try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "cannot read $file, using the defaults", e)
                return default
            }
            val (value, rejected) = parse(text)
            for (r in rejected) Log.e(TAG, "$file: ignoring $r")
            return value
        }
    }
}
