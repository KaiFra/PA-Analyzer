package de.flkaiser.paanalyzer.audio

import android.media.AudioDeviceInfo

/** An audio device as shown in the UI. Plain data so the selection logic is unit-testable. */
data class DeviceOption(val id: Int, val type: Int, val name: String) {
    val isBluetooth: Boolean get() = type in BLUETOOTH_OUTPUT_TYPES
    val label: String get() = "$name (${typeLabel(type)})"
}

private val BLUETOOTH_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
)

/** Outputs that make no sense for a PA measurement. SCO would drag the mic onto the headset. */
private val HIDDEN_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_TELEPHONY,
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    AudioDeviceInfo.TYPE_FM,
)

private val HIDDEN_INPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_TELEPHONY,
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    AudioDeviceInfo.TYPE_FM_TUNER,
    AudioDeviceInfo.TYPE_TV_TUNER,
)

private val PREFERRED_INPUT_ORDER = listOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
)

object DeviceSelection {

    fun visibleOutputs(all: List<DeviceOption>): List<DeviceOption> =
        all.filter { it.type !in HIDDEN_OUTPUT_TYPES }.distinctBy { it.id }

    fun visibleInputs(all: List<DeviceOption>): List<DeviceOption> =
        all.filter { it.type !in HIDDEN_INPUT_TYPES }.distinctBy { it.id }

    /** Keeps the current choice while it is connected, otherwise picks the first Bluetooth output. */
    fun chooseOutput(options: List<DeviceOption>, currentId: Int?): DeviceOption? =
        options.firstOrNull { it.id == currentId } ?: options.firstOrNull { it.isBluetooth }

    /** Keeps the current choice, otherwise prefers a USB measurement mic over the built-in mic. */
    fun chooseInput(options: List<DeviceOption>, currentId: Int?): DeviceOption? {
        options.firstOrNull { it.id == currentId }?.let { return it }
        for (type in PREFERRED_INPUT_ORDER) options.firstOrNull { it.type == type }?.let { return it }
        return options.firstOrNull()
    }
}

fun typeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "line"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    else -> "type $type"
}
