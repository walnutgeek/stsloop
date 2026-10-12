package com.walnutgeek.stsloop.core.settings

/**
 * What the settings screen does with one edit: [Save] the whole new [text]
 * of [file] (a path under the app's `files/`, written atomically), write
 * nothing because nothing changed ([Unchanged]), or show why it was
 * [Refused] and write nothing. Every saved text is input the Session's own
 * parser reads back as the edit asked, and keeps whatever else an
 * adb-written file held. A file a Session cannot read as a whole is never
 * overwritten: the person who wrote it over adb fixes or deletes it.
 */
sealed interface Edit {
    data class Save(val file: String, val text: String) : Edit

    data object Unchanged : Edit

    data class Refused(val problem: String) : Edit

    companion object {
        /** The refusal for an existing [file] that is not one a Session can use as a whole. */
        internal fun unusable(file: String, why: String?) =
            Refused("$file cannot be read ($why), so it is left as it is. Fix it or delete it over adb.")
    }
}
