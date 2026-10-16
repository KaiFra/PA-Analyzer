package de.flkaiser.paanalyzer.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes a measurement file to the cache and opens the share sheet. The file can be imported again. */
fun shareCsv(context: Context, name: String, csv: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val safeName = name.replace(Regex("[^\\p{L}\\p{N} ._-]+"), "_").trim().ifEmpty { "measurement" }.take(80)
    val file = File(dir, "$safeName.csv")
    file.writeText(csv)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share measurement"))
}
