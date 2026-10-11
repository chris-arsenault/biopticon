package io.ahara.biopticon.probe

import android.content.Context
import android.net.Uri
import io.ahara.biopticon.formats.Csv
import io.ahara.biopticon.formats.Fit

class ArtifactProbes(private val context: Context, private val log: EventLog) {
    fun inspect(uri: Uri) {
        val run = log.begin()
        try {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_000_000) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 2_000_000) { "FILE_TOO_LARGE" }
            if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") {
                val shape = Fit.inspect(bytes)
                log.emit(run, "GS-01", "selected-fit", "garmin", "inspect_format", if (shape.crcValid) "PASS" else "FAIL", "FIT_SHAPE", listOf(
                    Finding("metadata", "protocol", count = shape.protocol.toLong()), Finding("metadata", "profile", count = shape.profile.toLong()),
                    Finding("capability", "crc_valid", flag = shape.crcValid)) + shape.globalMessages.map { Finding("attribute", "global_message", count = it.toLong()) } +
                    shape.fields.map { Finding("attribute", it, text = "encoded_field") })
            } else {
                val shape = Csv.inspect(bytes.toString(Charsets.UTF_8))
                log.emit(run, "EXPORT-CSV", "selected-csv", "selected_export", "inspect_format", "PASS", "CSV_SHAPE",
                    listOf(Finding("counter", "rows", count = shape.rowCount.toLong()), Finding("metadata", "delimiter", text = if (shape.delimiter == '\t') "TAB" else shape.delimiter.toString())) +
                        shape.headers.map { Finding("attribute", it.ifBlank { "unnamed" }.take(160), text = "export_column") } + shape.widths.map { Finding("counter", "row_width", count = it.toLong()) })
            }
            log.finish(run, "FINISHED")
        } catch (error: Exception) {
            log.emit(run, "P-07", "selected-file", "selected_export", "inspect_format", "FAIL", "FILE_INSPECTION_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName)))
            log.finish(run, "INTERRUPTED")
        }
    }
}
