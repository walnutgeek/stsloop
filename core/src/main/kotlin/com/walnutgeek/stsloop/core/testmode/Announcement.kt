package com.walnutgeek.stsloop.core.testmode

/**
 * What a test Session says first, so the driver can confirm the
 * configuration by ear. It names the input the system actually routed
 * (not the one asked for), says so when a Bluetooth request fell back, and
 * flags call mode, since that changes where every sound goes.
 */
object Announcement {
    /**
     * [routedInput] is the routed input's type name (`builtin_mic`,
     * `bluetooth_sco`, ...), or null when none was reported.
     */
    fun text(config: TestConfig, routedInput: String?, bluetoothUnavailable: Boolean): String = buildString {
        append("Test mode. ")
        if (config.label.isNotEmpty()) append(spoken(config.label)).append(". ")
        append(spoken(config.micSource.json)).append(", ")
        if (bluetoothUnavailable) append("bluetooth unavailable, ")
        append(routedInput?.let(::deviceSpoken) ?: "unknown").append(" mic.")
        if (config.audioMode == AudioMode.IN_COMMUNICATION) append(" Call mode.")
    }

    private fun spoken(s: String) = s.replace('-', ' ').replace('_', ' ')

    /** `builtin_mic` → "built in", `bluetooth_sco` → "bluetooth s c o": readable by any TTS voice. */
    private fun deviceSpoken(type: String): String = when (type) {
        "builtin_mic" -> "built in"
        "bluetooth_sco" -> "bluetooth s c o"
        "ble_headset" -> "bluetooth L E"
        else -> spoken(type.removeSuffix("_mic"))
    }
}
