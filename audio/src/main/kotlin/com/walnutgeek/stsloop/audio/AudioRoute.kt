package com.walnutgeek.stsloop.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * The phone-wide audio settings a Session changes, and puts back: the audio
 * mode and the communication device. Both outlive the app, so every change
 * goes through here and [restore] undoes it, each step attempted even if
 * another throws. Used by production Sessions (the hands-free route) and by
 * Bluetooth test mode (#27), which may also hold call mode.
 *
 * The hands-free route is the #8 spike's GO: in normal mode,
 * `setCommunicationDevice` on the car's Bluetooth SCO (or LE audio) headset
 * moves both capture (`voice_recognition` with the headset preferred) and TTS
 * (`USAGE_ASSISTANT`) onto it. Without one, nothing is changed and the
 * Session records from the phone mic, with TTS on A2DP or the speaker.
 */
class AudioRoute(private val am: AudioManager, private val sessionId: String) {
    private var previousMode: Int? = null

    /** True while a communication device set here has not been cleared. */
    var communicationSet = false
        private set

    /** The hands-free headset chosen by [useHandsFree]: [ok] if the platform accepted it, and its mic. */
    class HandsFree(val device: AudioDeviceInfo, val ok: Boolean, val input: AudioDeviceInfo?)

    /** Holds [mode] until [restore]. */
    fun holdMode(mode: Int) {
        if (previousMode == null) previousMode = am.mode
        am.mode = mode
    }

    /**
     * Makes the first Bluetooth SCO / LE headset the communication device, so
     * capture and TTS both use the car's hands-free path. Null when no such
     * headset is connected; nothing is changed then.
     */
    fun useHandsFree(): HandsFree? {
        val comm = am.availableCommunicationDevices.firstOrNull { it.type in HANDS_FREE_TYPES } ?: return null
        val ok = am.setCommunicationDevice(comm)
        communicationSet = communicationSet || ok
        val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == comm.type }
        return HandsFree(comm, ok, input)
    }

    /** Puts back the communication device, then the audio mode. Idempotent; never throws. */
    fun restore() {
        if (communicationSet) {
            step("clear the communication device") { am.clearCommunicationDevice() }
            communicationSet = false
        }
        previousMode?.let { mode ->
            step("restore the audio mode") { am.mode = mode }
            previousMode = null
        }
    }

    private inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Session $sessionId: could not $what", t)
        }
    }

    companion object {
        private const val TAG = "stsloop.Route"

        val HANDS_FREE_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

        fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "in_call"
            AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
            AudioManager.MODE_CALL_SCREENING -> "call_screening"
            else -> "mode_$mode"
        }

        /** A stable short name for an `AudioDeviceInfo` type, as written to `turn.json`. */
        fun typeName(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "builtin_speaker_safe"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
            AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
            AudioDeviceInfo.TYPE_BUS -> "bus"
            else -> "type_$type"
        }

        fun describe(d: AudioDeviceInfo): Map<String, Any?> =
            linkedMapOf("type" to typeName(d.type), "name" to d.productName?.toString(), "id" to d.id)
    }
}
