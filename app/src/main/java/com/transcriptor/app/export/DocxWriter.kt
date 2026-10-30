package com.transcriptor.app.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Un DOCX es un ZIP con XML: para título y párrafos no hace falta una librería de 10 MB. */
object DocxWriter {
    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    fun write(output: OutputStream, title: String, subtitle: String, lines: List<String>) {
        ZipOutputStream(output).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", RELS)
            zip.entry("word/document.xml", document(title, subtitle, lines))
        }
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun document(title: String, subtitle: String, lines: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        append(paragraph(title, """<w:b/><w:sz w:val="36"/>"""))
        append(paragraph(subtitle, """<w:color w:val="666666"/><w:sz w:val="20"/>"""))
        lines.forEach { append(paragraph(it, """<w:lang w:val="es-419"/>""")) }
        append("</w:body></w:document>")
    }

    private fun paragraph(text: String, properties: String) =
        """<w:p><w:r><w:rPr>$properties</w:rPr><w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r></w:p>"""

    private fun escapeXml(text: String) = buildString(text.length) {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                // XML 1.0 no admite la mayoría de los caracteres de control.
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> append(c)
            }
        }
    }
}
