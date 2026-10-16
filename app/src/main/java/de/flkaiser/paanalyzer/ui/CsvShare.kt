package de.flkaiser.paanalyzer.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.flkaiser.paanalyzer.dsp.CsvExporter
import de.flkaiser.paanalyzer.dsp.MeasurementResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes the result to the cache and opens the share sheet. */
fun shareCsv(context: Context, result: MeasurementResult) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    val file = File(dir, "pa-measurement-$stamp.csv")
    file.writeText(CsvExporter.export(result))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share measurement"))
}
