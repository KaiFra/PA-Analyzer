package de.flkaiser.paanalyzer

import android.media.AudioDeviceInfo
import de.flkaiser.paanalyzer.audio.DeviceOption
import de.flkaiser.paanalyzer.audio.DeviceSelection
import de.flkaiser.paanalyzer.audio.OutputChannel
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSelectionTest {

    private val speaker = DeviceOption(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone")
    private val earpiece = DeviceOption(2, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Phone")
    private val sco = DeviceOption(3, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "PA receiver")
    private val a2dp = DeviceOption(4, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "PA receiver")
    private val bleSpeaker = DeviceOption(5, AudioDeviceInfo.TYPE_BLE_SPEAKER, "LE box")
    private val builtinMic = DeviceOption(10, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Mic")
    private val usbMic = DeviceOption(11, AudioDeviceInfo.TYPE_USB_DEVICE, "UMIK-1")
    private val scoMic = DeviceOption(12, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Headset mic")

    @Test
    fun hidesEarpieceAndScoOutputs() {
        assertEquals(listOf(speaker, a2dp, bleSpeaker), DeviceSelection.visibleOutputs(listOf(speaker, earpiece, sco, a2dp, bleSpeaker)))
    }

    @Test
    fun prefersBluetoothOutput() {
        val outputs = listOf(speaker, a2dp, bleSpeaker)
        assertEquals(a2dp, DeviceSelection.chooseOutput(outputs, currentId = null))
        assertTrue(a2dp.isBluetooth && bleSpeaker.isBluetooth && !speaker.isBluetooth)
    }

    @Test
    fun keepsChosenOutputWhileConnected() {
        assertEquals(speaker, DeviceSelection.chooseOutput(listOf(speaker, a2dp), currentId = speaker.id))
        // The chosen device disappeared: fall back to Bluetooth.
        assertEquals(a2dp, DeviceSelection.chooseOutput(listOf(a2dp), currentId = 99))
    }

    @Test
    fun noBluetoothMeansSystemDefault() {
        assertNull(DeviceSelection.chooseOutput(listOf(speaker), currentId = null))
    }

    @Test
    fun prefersUsbMeasurementMicAndNeverSco() {
        val inputs = DeviceSelection.visibleInputs(listOf(scoMic, builtinMic, usbMic))
        assertEquals(listOf(builtinMic, usbMic), inputs)
        assertEquals(usbMic, DeviceSelection.chooseInput(inputs, currentId = null))
        assertEquals(builtinMic, DeviceSelection.chooseInput(inputs, currentId = builtinMic.id))
    }

    @Test
    fun labelsIncludeType() {
        assertEquals("PA receiver (Bluetooth)", a2dp.label)
    }

    @Test
    fun interleavesChannels() {
        val mono = floatArrayOf(0f, 1f, 2f, 3f)
        val out = FloatArray(4)
        OutputChannel.BOTH.interleave(mono, 1, 2, out)
        assertContentEquals(floatArrayOf(1f, 1f, 2f, 2f), out)
        OutputChannel.LEFT.interleave(mono, 1, 2, out)
        assertContentEquals(floatArrayOf(1f, 0f, 2f, 0f), out)
        OutputChannel.RIGHT.interleave(mono, 1, 2, out)
        assertContentEquals(floatArrayOf(0f, 1f, 0f, 2f), out)
    }
}
