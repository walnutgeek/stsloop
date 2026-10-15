package com.walnutgeek.stsloop.audio

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The config files the settings screen and the name-recognisability check
 * both change (`files/buckets.json`, `files/hub/aliases.json`): each change
 * re-reads its file and writes it whole under one lock for the process
 * ([LOCK]), so the check learning an alias (#81) and a save on the screen
 * never lose each other's write.
 */
object SettingsFiles {
    private const val TAG = "stsloop.SettingsFiles"

    /** Held around every read-change-write of these files. */
    val LOCK = Any()

    /** The text of `<files>/<name>`, or null when there is none. Throws [IOException] when it exists but cannot be read. */
    fun read(files: File, name: String): String? {
        val file = File(files, name)
        if (!file.exists()) return null
        return file.readText()
    }

    /** Replaces `<files>/<name>` atomically, so a Session starting meanwhile reads the old file or the new, never half of one. */
    fun write(files: File, name: String, text: String) {
        val file = File(files, name)
        val dir = file.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
        Log.i(TAG, "saved $file")
    }
}
