package com.walnutgeek.stsloop.core.recognisability

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * `files/recognisability.json` ([NameCheckResults.FILE]), read and changed
 * under one lock for the process: the check files results while a Session,
 * starting meanwhile, marks phrases announced, and neither may lose the
 * other's write. A missing or unreadable file reads as no results.
 */
class NameCheckStore(files: File) {
    private val file = File(files, NameCheckResults.FILE)

    fun read(): NameCheckResults = synchronized(LOCK) { load() }

    /** Re-reads the file, applies [change] and writes the result atomically; returns it. */
    fun update(change: (NameCheckResults) -> NameCheckResults): NameCheckResults = synchronized(LOCK) {
        val before = load()
        val after = change(before)
        if (after != before) write(after)
        after
    }

    private fun load(): NameCheckResults {
        if (!file.exists()) return NameCheckResults.EMPTY
        return try {
            NameCheckResults.parse(file.readText()).results
        } catch (_: IOException) {
            NameCheckResults.EMPTY
        }
    }

    private fun write(results: NameCheckResults) {
        val dir = file.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(results.toJson().toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
    }

    private companion object {
        val LOCK = Any()
    }
}
