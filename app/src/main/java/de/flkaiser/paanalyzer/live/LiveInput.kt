package de.flkaiser.paanalyzer.live

import android.media.AudioRecord
import de.flkaiser.paanalyzer.audio.AudioException
import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.LiveSpectrum

/** Where the live analyzer listens. */
enum class LiveSource(val label: String) {
    MICROPHONE("Microphone"),

    /** What the phone plays (other apps' media audio), captured digitally. */
    PHONE("Phone audio"),
}

/** One update of the live analyzer for the UI. */
class LiveFrame(
    val spectrum: LiveSpectrum,
    val waterfall: WaterfallHistory,
    val settings: LiveSettings,
    val source: LiveSource,
    /** Name of the microphone calibration applied, null if none. */
    val calibration: String?,
)

/** A started recording that delivers mono samples (−1..1). */
class LiveInput(private val record: AudioRecord, private val float: Boolean) : AutoCloseable {
    private var shorts: ShortArray? = null

    init {
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            throw AudioException("The audio input could not be started.")
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw AudioException("The audio input could not be started. Is another app recording?")
        }
    }

    /** Blocks until samples arrive; returns how many were read into [buffer]. */
    fun read(buffer: FloatArray): Int {
        val n = if (float) {
            record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        } else {
            val s = shorts?.takeIf { it.size >= buffer.size } ?: ShortArray(buffer.size).also { shorts = it }
            val count = record.read(s, 0, buffer.size, AudioRecord.READ_BLOCKING)
            for (i in 0 until count) buffer[i] = s[i] / 32_768f
            count
        }
        if (n < 0) throw AudioException("Recording failed (error $n).")
        return n
    }

    override fun close() {
        runCatching { record.stop() }
        record.release()
    }
}
