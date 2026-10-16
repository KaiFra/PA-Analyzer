package de.flkaiser.paanalyzer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes the measurement (CSV, plus the recording as WAV when available) to the cache
 * and opens the share sheet. Both files can be imported again together.
 */
fun shareMeasurement(context: Context, data: ExportData) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val safeName = data.name.replace(Regex("[^\\p{L}\\p{N} ._-]+"), "_").trim().ifEmpty { "measurement" }.take(80)
    val uris = ArrayList<Uri>()
    File(dir, "$safeName.csv").also { it.writeText(data.csv) }.let { uris += uriFor(context, it) }
    data.wav?.let { wav -> File(dir, "$safeName.wav").also { it.writeBytes(wav) }.let { uris += uriFor(context, it) } }

    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }.apply {
        putExtra(Intent.EXTRA_SUBJECT, safeName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share measurement"))
}

private fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
