package com.walnutgeek.stsloop.core.summary

import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.ListedTurn
import com.walnutgeek.stsloop.core.corpus.TranscriptList
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.grammar.Words
import com.walnutgeek.stsloop.core.hub.SummaryNote
import com.walnutgeek.stsloop.core.parseTurnDirectoryName
import com.walnutgeek.stsloop.core.turn.EchoText
import java.io.File

/**
 * The Notes one summary of the capture Bucket [bucket] covers (#76), oldest
 * first: those said since [since], the directory name of the newest Note its
 * last summary covered, or, on its first summary (null), those of the last
 * [FIRST_WINDOW_MS]. Only the newest [CAP] are covered; [more] says older
 * ones in the range were left out.
 */
data class NoteRange(val bucket: String, val since: String?, val notes: List<SummaryNote>, val more: Boolean) {
    /** The directory name of the newest Note covered: where the next summary starts, once this one is spoken. */
    val newest: String? get() = notes.lastOrNull()?.turn

    companion object {
        /** The most Notes one summary covers: the newest ones. */
        const val CAP = 50

        /** How far back a Bucket's first summary reaches. */
        const val FIRST_WINDOW_MS = 24 * 3_600_000L

        /**
         * The range of [bucket] in [newestFirst] (the Corpus's Turns, newest
         * first by directory name), read only as far back as the range starts.
         *
         * A Note of a capture Bucket is a `note` Turn filed under it, by
         * Declaration or by Focus; its words are its `content` (its
         * transcript, for one written without). The Inbox's Notes are the
         * unlabelled Turns: `unclassified`, with words other than fillers. A
         * Note that a "scratch that" tombstones is left out: the command Turn
         * always comes after its target, so reading newest first meets it
         * before the Note it drops.
         */
        fun of(newestFirst: Sequence<ListedTurn>, bucket: String, since: String?, nowMs: Long, cap: Int = CAP): NoteRange {
            // Directory names start with their UTC started_at, so they compare as times.
            val after: (String) -> Boolean =
                if (since != null) { name -> name > since } else UtcTimestamp.format(nowMs - FIRST_WINDOW_MS).let { from -> { name -> name >= from } }
            val inbox = bucket == Descriptor.INBOX.name
            val tombstoned = HashSet<String>()
            val notes = ArrayList<SummaryNote>()
            var more = false
            for (t in newestFirst) {
                if (!after(t.directoryName)) break
                if (t.kind == TurnKind.COMMAND.json) t.tombstones?.let { tombstoned += it }
                if (t.problem != null || t.directoryName in tombstoned) continue
                val text = when {
                    inbox && t.kind == TurnKind.UNCLASSIFIED.json -> t.transcript
                    !inbox && t.kind == TurnKind.NOTE.json && t.bucket == bucket -> t.content ?: t.transcript
                    else -> null
                }?.let(::words) ?: continue
                if (notes.size == cap) {
                    more = true
                    break
                }
                notes += SummaryNote(t.directoryName, text)
            }
            return NoteRange(bucket, since, notes.asReversed().toList(), more)
        }

        /** [text] as a Note's words to be read (lower-cased when all upper case), or null when it has none but fillers. */
        private fun words(text: String): String? {
            val said = EchoText.of(text) ?: return null
            return said.takeIf { Words.normalise(it).any { w -> w !in PhraseGrammar.FILLERS } }
        }
    }
}

/** Where a summary reads Notes from: the Corpus's Turns, newest first, read lazily. */
fun interface NoteSource {
    fun newestFirst(): Sequence<ListedTurn>

    companion object {
        /** No Corpus at all. */
        val NONE = NoteSource { emptySequence() }
    }
}

/**
 * The Corpus directory as a [NoteSource]: its Turn directories newest first,
 * each `turn.json` read only when the range gets to it. Read-only: a Turn
 * directory never changes once published.
 */
class CorpusNotes(private val dir: File) : NoteSource {
    override fun newestFirst(): Sequence<ListedTurn> {
        val names = dir.list().orEmpty().filter { parseTurnDirectoryName(it) != null }.sortedDescending()
        return names.asSequence().map { name ->
            val file = File(File(dir, name), TURN_FILE)
            val text = try {
                if (file.isFile) file.readText(Charsets.UTF_8) else null
            } catch (e: java.io.IOException) {
                return@map ListedTurn(name, problem = "unreadable turn.json: ${e.message}")
            }
            TranscriptList.read(name, text)
        }
    }
}
