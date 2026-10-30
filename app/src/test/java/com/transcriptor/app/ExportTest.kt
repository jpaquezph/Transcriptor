package com.transcriptor.app

import com.transcriptor.app.asr.TextCleaner
import com.transcriptor.app.data.Segment
import com.transcriptor.app.export.DocxWriter
import com.transcriptor.app.export.formatClock
import com.transcriptor.app.export.paragraphs
import com.transcriptor.app.export.safeFileName
import com.transcriptor.app.export.timestampedLines
import com.transcriptor.app.export.toSrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportTest {
    private val segments = listOf(
        Segment(1_000, 3_500, "Hola, ¿cómo estás?"),
        Segment(3_800, 5_000, "Muy bien."),
        Segment(9_000, 3_661_250, "Después de una pausa."),
    )

    @Test
    fun `formatea el reloj`() {
        assertEquals("0:05", formatClock(5_000))
        assertEquals("12:03", formatClock(723_000))
        assertEquals("1:01:01", formatClock(3_661_000))
    }

    @Test
    fun `agrupa en parrafos separando por pausas largas`() {
        assertEquals(
            listOf("Hola, ¿cómo estás? Muy bien.", "Después de una pausa."),
            paragraphs(segments),
        )
    }

    @Test
    fun `corta parrafos demasiado largos`() {
        val long = List(8) { Segment(it * 1000L, it * 1000L + 900, "x".repeat(100)) }
        assertEquals(2, paragraphs(long).size)
    }

    @Test
    fun `genera marcas de tiempo`() {
        assertEquals("[0:01] Hola, ¿cómo estás?", timestampedLines(segments).first())
    }

    @Test
    fun `genera SRT valido`() {
        val srt = toSrt(segments)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:03,500\nHola, ¿cómo estás?\n\n2\n"))
        assertTrue(srt.contains("00:00:09,000 --> 01:01:01,250"))
    }

    @Test
    fun `genera un DOCX con XML bien formado`() {
        val output = ByteArrayOutputStream()
        DocxWriter.write(output, "Reunión <A&B>", "1 ene", listOf("Año \"nuevo\" \u0001", "Niño"))

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), entries.keys)

        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        entries.values.forEach { parser.parse(ByteArrayInputStream(it)) }
        val text = parser.parse(ByteArrayInputStream(entries.getValue("word/document.xml")))
            .documentElement.textContent
        assertTrue(text.contains("Reunión <A&B>"))
        assertTrue(text.contains("Año \"nuevo\" "))
    }

    @Test
    fun `limpia nombres de archivo`() {
        assertEquals("audio_ de_ hoy", safeFileName("audio: de/ hoy"))
        assertEquals("transcripcion", safeFileName("   "))
    }

    @Test
    fun `descarta alucinaciones de Whisper`() {
        assertNull(TextCleaner.clean(" Subtítulos realizados por la comunidad de Amara.org "))
        assertNull(TextCleaner.clean("Subtitulos por la comunidad de Amara.org"))
        assertNull(TextCleaner.clean("[Música]"))
        assertNull(TextCleaner.clean("(aplausos)"))
        assertNull(TextCleaner.clean("♪"))
        assertNull(TextCleaner.clean("   "))
    }

    @Test
    fun `conserva el habla normal`() {
        assertEquals("Hablemos de subtítulos.", TextCleaner.clean(" Hablemos de subtítulos. "))
        assertEquals("(Risas) y seguimos.", TextCleaner.clean("(Risas) y seguimos."))
        assertFalse(TextCleaner.clean("Hola").isNullOrEmpty())
    }
}
