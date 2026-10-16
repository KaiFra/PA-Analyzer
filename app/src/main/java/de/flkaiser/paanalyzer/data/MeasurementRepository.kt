package de.flkaiser.paanalyzer.data

import android.content.Context
import de.flkaiser.paanalyzer.dsp.MeasurementDocument
import de.flkaiser.paanalyzer.dsp.MeasurementFile
import de.flkaiser.paanalyzer.dsp.MeasurementFileException
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import de.flkaiser.paanalyzer.dsp.WavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A saved measurement as listed in the library. */
data class SavedEntry(val id: String, val name: String, val createdAtMillis: Long)

/**
 * Stores measurements as CSV files (see [MeasurementFile]) in the app's private
 * storage, each with its raw recording as a 32-bit float WAV file of the same name,
 * so the analysis can be repeated with other settings later.
 */
class MeasurementRepository(context: Context) {

    private val dir = File(context.filesDir, "measurements").apply { mkdirs() }

    suspend fun list(): List<SavedEntry> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val head = file.bufferedReader().useLines { lines -> lines.takeWhile { it.startsWith("#") }.joinToString("\n") }
                    val (name, created) = MeasurementFile.readHeader(head)
                    SavedEntry(file.name, name.ifBlank { file.nameWithoutExtension }, created)
                }.getOrNull()
            }
            .sortedByDescending { it.createdAtMillis }
    }

    suspend fun save(
        result: MeasurementResult,
        name: String,
        createdAtMillis: Long = System.currentTimeMillis(),
        recording: FloatArray? = null,
    ): SavedEntry {
        val id = newId(createdAtMillis)
        if (recording != null) writeRecording(id, recording, result.settings.sampleRate)
        return write(MeasurementDocument(name, createdAtMillis, result), id)
    }

    /** Replaces the stored analysis (e.g. after changing analysis settings); the recording stays. */
    suspend fun update(id: String, doc: MeasurementDocument): SavedEntry = write(doc, id)

    /** The raw recording of a saved measurement, or null for measurements saved without one. */
    suspend fun loadRecording(id: String): FloatArray? = withContext(Dispatchers.IO) {
        val wav = recordingFile(id)
        if (!wav.exists()) null else WavFile.read(wav.readBytes()).samples
    }

    fun recordingFile(id: String): File = File(dir, file(id).nameWithoutExtension + WAV_EXTENSION)

    suspend fun load(id: String): MeasurementDocument = withContext(Dispatchers.IO) {
        MeasurementFile.read(file(id).readText())
    }

    suspend fun rename(id: String, name: String): SavedEntry {
        val doc = load(id)
        return write(MeasurementDocument(name, doc.createdAtMillis, doc.result), id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        recordingFile(id).delete()
        file(id).delete()
    }

    /**
     * Imports a measurement file (e.g. one shared from another phone), optionally with its
     * recording, and returns the stored copy.
     */
    suspend fun import(text: String, fallbackName: String, wav: ByteArray? = null): Pair<SavedEntry, MeasurementDocument> {
        val doc = MeasurementFile.read(text)
        val created = doc.createdAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val stored = MeasurementDocument(doc.name.ifBlank { fallbackName }, created, doc.result)
        val id = newId(System.currentTimeMillis())
        if (wav != null) {
            val audio = WavFile.read(wav)
            if (audio.sampleRate != doc.result.settings.sampleRate) {
                throw MeasurementFileException("The recording's sample rate (${audio.sampleRate} Hz) does not match the measurement.")
            }
            writeRecording(id, audio.samples, audio.sampleRate)
        }
        return write(stored, id) to stored
    }

    private suspend fun writeRecording(id: String, samples: FloatArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        val target = recordingFile(id)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(WavFile.write(samples, sampleRate))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** The CSV text of a measurement, for sharing. */
    fun export(doc: MeasurementDocument): String = MeasurementFile.write(doc)

    private suspend fun write(doc: MeasurementDocument, id: String): SavedEntry = withContext(Dispatchers.IO) {
        val target = file(id)
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(MeasurementFile.write(doc))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        SavedEntry(id, doc.name, doc.createdAtMillis)
    }

    private fun newId(millis: Long): String {
        var n = 0
        while (true) {
            val id = "m-$millis${if (n == 0) "" else "-$n"}$EXTENSION"
            if (!File(dir, id).exists() && !recordingFile(id).exists()) return id
            n++
        }
    }

    private fun file(id: String): File {
        require(id.endsWith(EXTENSION) && '/' !in id && '\\' !in id) { "Invalid measurement id" }
        return File(dir, id)
    }

    private companion object {
        const val EXTENSION = ".csv"
        const val WAV_EXTENSION = ".wav"
    }
}
