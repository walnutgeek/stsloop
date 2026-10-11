package com.walnutgeek.stsloop.audio

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Handler

/**
 * Watches whether Android has silenced [record] in favour of another capture
 * client (`AudioRecordingConfiguration.isClientSilenced()`): the platform
 * then keeps handing it buffers of zeros, with no error. [changed] hears
 * each change of that state, the first one included, on [handler]'s thread.
 *
 * Every Session runs one, test mode included; test mode's own recording
 * callback only adds the full configuration to its route log.
 */
class SilencedWatch(
    private val am: AudioManager,
    private val record: AudioRecord,
    private val handler: Handler,
    private val changed: (silenced: Boolean) -> Unit,
) {
    private var last: Boolean? = null // handler thread only

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = check(configs)
    }

    /** After `startRecording()`: our configuration exists only while recording. */
    fun start() {
        am.registerAudioRecordingCallback(callback, handler)
        handler.post { check(am.activeRecordingConfigurations) }
    }

    fun close() {
        am.unregisterAudioRecordingCallback(callback)
    }

    private fun check(configs: List<AudioRecordingConfiguration>) {
        val session = record.audioSessionId
        val ours = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
        val silenced = ours.isClientSilenced
        if (silenced == last) return
        last = silenced
        changed(silenced)
    }
}
