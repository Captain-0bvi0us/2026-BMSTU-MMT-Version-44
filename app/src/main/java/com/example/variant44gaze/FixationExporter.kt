package com.example.variant44gaze

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт списка фиксаций в CSV и подготовка интента для шеринга через FileProvider.
 */
object FixationExporter {

    fun saveToCache(context: Context, fixations: List<Fixation>): File {
        val cacheDir = File(context.cacheDir, "fixations").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "fixations_$ts.csv")
        file.writer(Charsets.UTF_8).use { w ->
            w.appendLine("index,start_time_ms,duration_ms,screen_x_px,screen_y_px,norm_x,norm_y,samples")
            for (f in fixations) {
                w.append(f.index.toString()).append(',')
                w.append(f.startTimeMs.toString()).append(',')
                w.append(f.durationMs.toString()).append(',')
                w.append(fmt(f.screenPoint.x)).append(',')
                w.append(fmt(f.screenPoint.y)).append(',')
                w.append(fmt(f.normalizedPoint.x)).append(',')
                w.append(fmt(f.normalizedPoint.y)).append(',')
                w.append(f.sampleCount.toString()).append('\n')
            }
        }
        return file
    }

    fun buildShareIntent(context: Context, file: File): Intent {
        val authority = context.packageName + ".fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Точки фиксации взгляда")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
}
