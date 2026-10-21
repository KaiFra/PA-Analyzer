package de.flkaiser.paanalyzer.data

import android.content.Context
import de.flkaiser.paanalyzer.dsp.MicCalibration
import java.io.File

/** Keeps the active microphone calibration in the app's private storage. */
class CalibrationStore(context: Context) {

    private val file = File(context.filesDir, "mic-calibration.txt")
    private val nameFile = File(context.filesDir, "mic-calibration.name")

    fun load(): MicCalibration? = runCatching {
        if (!file.exists()) return null
        MicCalibration.parse(file.readText(), nameFile.takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "Calibration" })
    }.getOrNull()

    fun save(calibration: MicCalibration) {
        file.writeText(MicCalibration.write(calibration))
        nameFile.writeText(calibration.name)
    }

    fun clear() {
        file.delete()
        nameFile.delete()
    }
}
