package com.walnutgeek.stsloop.core.summary

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import com.walnutgeek.stsloop.core.parseTurnDirectoryName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where each capture Bucket's last summary got to (#76): the directory name
 * of the newest Note it covered, by Bucket name. The next summary covers
 * the Notes after it. Kept on the phone, in `files/summaries.json`
 * (`{"marks": {"errands": "<started_at>-<id>"}}`), or in memory when [files]
 * is null. A mark is moved only once a summary has been spoken, and only
 * forward.
 *
 * An unreadable file, or a value that is not a Turn directory name, reads as
 * no mark: that Bucket's next summary is a first one again (the last day),
 * which loses nothing. Thread-safe.
 */
class SummaryMarks(files: File?) {
    private val file = files?.let { File(it, FILE) }
    private var marks: MutableMap<String, String>? = null

    /** The directory name of the newest Note [bucket]'s last summary covered, or null when it has had none. */
    @Synchronized
    fun since(bucket: String): String? = loaded()[bucket]

    /** [bucket]'s summary covering Notes up to the Turn [through] has been spoken. Writes the file. */
    @Synchronized
    fun move(bucket: String, through: String) {
        val m = loaded()
        val was = m[bucket]
        if (was != null && was >= through) return
        m[bucket] = through
        val f = file ?: return
        f.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot create $it") }
        val tmp = File(f.parentFile, "${f.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(JsonWriter.pretty(mapOf("marks" to m.toSortedMap())).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) throw IOException("cannot replace $f")
    }

    private fun loaded(): MutableMap<String, String> = marks ?: read().also { marks = it }

    private fun read(): MutableMap<String, String> {
        val text = try {
            file?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        } ?: return mutableMapOf()
        val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: return mutableMapOf()
        val out = mutableMapOf<String, String>()
        for ((k, v) in root["marks"] as? Map<*, *> ?: return out) {
            if (k is String && v is String && parseTurnDirectoryName(v) != null) out[k] = v
        }
        return out
    }

    companion object {
        const val FILE = "summaries.json"
    }
}
