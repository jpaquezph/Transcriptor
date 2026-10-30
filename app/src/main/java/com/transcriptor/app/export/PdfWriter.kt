package com.transcriptor.app.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

object PdfWriter {
    private const val PAGE_WIDTH = 595 // A4 en puntos
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 56
    private const val FOOTER_SPACE = 28

    fun write(output: OutputStream, title: String, subtitle: String, lines: List<String>) {
        val width = PAGE_WIDTH - MARGIN * 2
        val titleLayout = layout(title, width, 20f, Typeface.DEFAULT_BOLD, Color.BLACK)
        val subtitleLayout = layout(subtitle, width, 10f, Typeface.DEFAULT, Color.DKGRAY)
        val body = layout(lines.joinToString("\n\n"), width, 11.5f, Typeface.DEFAULT, Color.BLACK)
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
        }

        val document = PdfDocument()
        try {
            val bottom = PAGE_HEIGHT - MARGIN - FOOTER_SPACE
            var pageNumber = 1
            var line = 0
            while (line < body.lineCount || pageNumber == 1) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                val canvas = page.canvas
                var y = MARGIN
                if (pageNumber == 1) {
                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), y.toFloat())
                    titleLayout.draw(canvas)
                    canvas.translate(0f, titleLayout.height + 6f)
                    subtitleLayout.draw(canvas)
                    canvas.restore()
                    y += titleLayout.height + subtitleLayout.height + 24
                }
                // Las páginas se cortan entre renglones, nunca por la mitad de uno.
                val first = line
                val top = body.getLineTop(first)
                while (line < body.lineCount && y + body.getLineBottom(line) - top <= bottom) line++
                if (line > first) {
                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), (y - top).toFloat())
                    canvas.clipRect(0, top, width, body.getLineBottom(line - 1))
                    body.draw(canvas)
                    canvas.restore()
                }
                canvas.drawText("$pageNumber", PAGE_WIDTH / 2f, PAGE_HEIGHT - MARGIN / 2f, footer)
                document.finishPage(page)
                pageNumber++
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun layout(text: String, width: Int, size: Float, face: Typeface, color: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = face
            this.color = color
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .build()
    }
}
