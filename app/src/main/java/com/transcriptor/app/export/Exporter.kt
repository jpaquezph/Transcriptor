package com.transcriptor.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.transcriptor.app.R
import com.transcriptor.app.data.Transcript
import java.io.File
import java.io.OutputStream

enum class ExportFormat(val extension: String, val mime: String, @StringRes val label: Int) {
    PDF("pdf", "application/pdf", R.string.format_pdf),
    DOCX(
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        R.string.format_docx,
    ),
    TXT("txt", "text/plain", R.string.format_txt),
    SRT("srt", "application/x-subrip", R.string.format_srt),
}

object Exporter {
    /** WhatsApp corta los mensajes muy largos y un Intent grande puede directamente fallar. */
    private const val MAX_SHARED_TEXT = 60_000

    fun fileName(transcript: Transcript, format: ExportFormat) =
        "${safeFileName(transcript.title)}.${format.extension}"

    fun write(
        context: Context,
        transcript: Transcript,
        format: ExportFormat,
        timestamps: Boolean,
        output: OutputStream,
    ) {
        val lines = if (timestamps) timestampedLines(transcript.segments) else paragraphs(transcript.segments)
        val subtitle = buildString {
            append(DateFormat.getMediumDateFormat(context).format(transcript.createdAt))
            if (transcript.durationMs > 0) append(" · ").append(formatClock(transcript.durationMs))
        }
        when (format) {
            ExportFormat.PDF -> PdfWriter.write(output, transcript.title, subtitle, lines)
            ExportFormat.DOCX -> DocxWriter.write(output, transcript.title, subtitle, lines)
            ExportFormat.TXT -> output.write(lines.joinToString("\n\n").toByteArray())
            ExportFormat.SRT -> output.write(toSrt(transcript.segments).toByteArray())
        }
    }

    fun shareFileIntent(
        context: Context,
        transcript: Transcript,
        format: ExportFormat,
        timestamps: Boolean,
    ): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(transcript, format))
        file.outputStream().use { write(context, transcript, format, timestamps, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return streamIntent(uri, format.mime, transcript.title)
    }

    fun shareTextIntent(context: Context, transcript: Transcript): Intent {
        val text = plainText(transcript.segments)
        if (text.length > MAX_SHARED_TEXT) {
            return shareFileIntent(context, transcript, ExportFormat.TXT, timestamps = false)
        }
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, transcript.title)
            .putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun streamIntent(uri: Uri, mime: String, subject: String) =
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun chooser(context: Context, intent: Intent): Intent =
        Intent.createChooser(intent, context.getString(R.string.share_chooser))
}
