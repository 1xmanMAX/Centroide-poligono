package com.scannerpromax.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generador mínimo de documentos Word (.docx) sin dependencias (lógica pura, probada en JVM).
 * Un .docx es un ZIP con [Content_Types].xml, _rels/.rels y word/document.xml (WordprocessingML).
 * Cada sección lleva un título (negrita, 14 pt) y sus párrafos; las secciones después de la primera empiezan en
 * página nueva.
 */
object DocxWriter {

    data class Section(val title: String?, val paragraphs: List<String>)

    fun write(documentTitle: String?, sections: List<Section>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        zip.setLevel(6)
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", CONTENT_TYPES)
        entry("_rels/.rels", RELS)
        entry("docProps/core.xml", core(documentTitle))
        entry("word/document.xml", document(documentTitle, sections))
        zip.finish()
        zip.flush()
    }

    internal fun document(documentTitle: String?, sections: List<Section>): String {
        val sb = StringBuilder(4096)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        documentTitle?.takeIf { it.isNotBlank() }?.let { paragraph(sb, it, bold = true, sizeHalfPt = 36, pageBreakBefore = false) }
        sections.forEachIndexed { i, s ->
            s.title?.let { paragraph(sb, it, bold = true, sizeHalfPt = 28, pageBreakBefore = i > 0) }
            if (s.paragraphs.isEmpty()) paragraph(sb, "", bold = false, sizeHalfPt = 22, pageBreakBefore = false)
            s.paragraphs.forEach { p -> paragraph(sb, p, bold = false, sizeHalfPt = 22, pageBreakBefore = false) }
        }
        sb.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""")
        sb.append("""<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun paragraph(sb: StringBuilder, text: String, bold: Boolean, sizeHalfPt: Int, pageBreakBefore: Boolean) {
        sb.append("<w:p><w:pPr>")
        if (pageBreakBefore) sb.append("<w:pageBreakBefore/>")
        sb.append("""<w:spacing w:after="120"/></w:pPr>""")
        val lines = text.split('\n')
        sb.append("<w:r><w:rPr>")
        if (bold) sb.append("<w:b/>")
        sb.append("""<w:sz w:val="$sizeHalfPt"/></w:rPr>""")
        lines.forEachIndexed { i, line ->
            if (i > 0) sb.append("<w:br/>")
            sb.append("""<w:t xml:space="preserve">""").append(escape(line)).append("</w:t>")
        }
        sb.append("</w:r></w:p>")
    }

    private fun core(title: String?): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${escape(title.orEmpty())}</dc:title>""" +
            "<dc:creator>ESCÁNER PRO MAX</dc:creator></cp:coreProperties>"

    /** Escapa XML y elimina caracteres no permitidos en XML 1.0 (controles, sustitutos sueltos). */
    internal fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' -> sb.append(' ')
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { sb.append(c).append(s[i + 1]); i++ }
                c.isSurrogate() -> Unit
                c < ' ' || c == '￾' || c == '￿' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
            "</Types>"

    private const val RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
            "</Relationships>"

    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
}
