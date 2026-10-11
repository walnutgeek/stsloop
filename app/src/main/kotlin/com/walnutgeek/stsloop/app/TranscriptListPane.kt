package com.walnutgeek.stsloop.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.corpus.ListedTurn
import com.walnutgeek.stsloop.core.corpus.TranscriptList
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors

/**
 * The transcript list: every Turn in the Corpus, newest first, for
 * sanity-checking the loop after a drive. Read-only: it lists [corpusDir]
 * and reads each Turn's `turn.json`, nothing else. The staging directory sits
 * beside the Corpus, not in it, so it is never seen.
 *
 * Reloads on [start] (the activity's resume) and whenever a Turn directory
 * appears in or leaves the Corpus while the screen is shown.
 */
class TranscriptListPane(context: Context, private val corpusDir: File) {
    /** The list itself, to be placed in the activity's layout. */
    val view = ListView(context)
    private val adapter = TurnAdapter(context)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var observer: FileObserver? = null
    private val reload = Runnable { load() }

    /** Called with the number of Turns listed after each load. */
    var onLoaded: (Int) -> Unit = {}

    init {
        view.adapter = adapter
        view.divider = null
    }

    fun start() {
        // A Turn is published by renaming its staged directory into the Corpus.
        observer = object : FileObserver(corpusDir, MOVED_TO or MOVED_FROM or CREATE or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                main.removeCallbacks(reload)
                main.postDelayed(reload, 200)
            }
        }.also { it.startWatching() }
        load()
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
        main.removeCallbacks(reload)
    }

    fun load() {
        val gen = ++generation
        io.execute {
            val turns = readCorpus(corpusDir)
            main.post {
                if (gen != generation) return@post
                adapter.turns = turns
                adapter.notifyDataSetChanged()
                onLoaded(turns.size)
            }
        }
    }

    private class TurnAdapter(private val context: Context) : BaseAdapter() {
        var turns: List<ListedTurn> = emptyList()

        override fun getCount() = turns.size
        override fun getItem(position: Int) = turns[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? TurnRow ?: TurnRow(context)
            row.bind(turns[position])
            return row
        }
    }

    private class TurnRow(context: Context) : LinearLayout(context) {
        private val header = TextView(context).apply { textSize = 13f; alpha = 0.7f }
        private val text = TextView(context).apply { textSize = 18f }
        private val meta = TextView(context).apply { textSize = 13f; alpha = 0.7f }

        init {
            orientation = VERTICAL
            setPadding(0, 24, 0, 24)
            addView(header)
            addView(text)
            addView(meta)
        }

        fun bind(t: ListedTurn) {
            header.text = buildString {
                append(t.startedAt?.let(::localTime) ?: t.directoryName)
                t.durationMs?.let { append(" · %.1f s".format(it / 1000.0)) }
                t.id?.let { append(" · ").append(it) }
            }
            val problem = t.problem
            val transcript = t.transcript
            text.text = when {
                problem != null -> problem
                transcript == null -> "(no transcript)"
                transcript.isEmpty() -> "(empty transcript)"
                else -> transcript
            }
            val marker = problem != null || transcript.isNullOrEmpty()
            text.setTypeface(null, if (marker) Typeface.ITALIC else Typeface.NORMAL)
            text.alpha = if (marker) 0.6f else 1f
            text.paintFlags = if (t.tombstoned) {
                text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            meta.text = if (t.problem != null) {
                t.directoryName
            } else {
                buildString {
                    append("Bucket: ").append(t.bucket ?: "none")
                    append(" · kind: ").append(t.kind ?: "none")
                    t.command?.let { append(" (").append(it).append(")") }
                    if (t.command == Command.SCRATCH_THAT.json) append(t.tombstones?.let { " · dropped the Turn of ${dirTime(it)}" } ?: " · nothing to drop")
                    t.tombstonedBy?.let { append(" · DROPPED by scratch that at ").append(dirTime(it)) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "stsloop.Transcripts"
        private const val MAX_TURN_JSON_BYTES = 256 * 1024
        // One reader thread for the process, so a recreated activity does not leak one.
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stsloop-transcripts").apply { isDaemon = true } }
        private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss").withZone(ZoneId.systemDefault())

        /** The local time of a Turn directory name (`<started_at>-<id>`), or the name itself. */
        private fun dirTime(directoryName: String): String = localTime(TranscriptList.startedAtOf(directoryName))

        private fun localTime(utc: String): String =
            try {
                TIME.format(Instant.parse(utc))
            } catch (e: DateTimeParseException) {
                utc
            }

        /**
         * Reads every Turn directory in the Corpus, skipping the test-mode Session logs.
         * Never writes; tolerates anything it finds.
         */
        fun readCorpus(corpusDir: File): List<ListedTurn> {
            val dirs = corpusDir.listFiles { f -> f.isDirectory && TranscriptList.isTurnEntry(f.name) }.orEmpty()
            // Tombstones are derived from the command Turns read here (TranscriptList.tombstonedBy).
            return TranscriptList.of(dirs.map(::readTurn))
        }

        private fun readTurn(dir: File): ListedTurn {
            val file = File(dir, TURN_FILE)
            if (!file.isFile) return TranscriptList.read(dir.name, null)
            if (file.length() > MAX_TURN_JSON_BYTES) {
                return ListedTurn(dir.name, problem = "turn.json too large (${file.length()} bytes)")
            }
            return try {
                TranscriptList.read(dir.name, file.readText())
            } catch (e: IOException) {
                Log.w(TAG, "cannot read $file", e)
                ListedTurn(dir.name, problem = "cannot read turn.json: ${e.message}")
            }
        }
    }
}
