package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * PDF/A-2b, best effort: adds the sRGB output intent and the PDF/A metadata, removes encryption,
 * JavaScript and other actions, makes annotations printable with appearances, and checks what
 * cannot be fixed here (fonts that are not embedded, attachments).
 */
object PdfA {
    enum class Problem { FONTS_NOT_EMBEDDED, ATTACHMENTS }

    data class Report(val problems: Map<Problem, List<String>>) {
        val conformant get() = problems.isEmpty()
    }

    suspend fun convert(context: Context, input: File, output: File, password: String?): Report = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            doc.version = 1.7f
            val catalog = doc.documentCatalog
            val problems = HashMap<Problem, MutableList<String>>()

            // No JavaScript or automatic actions.
            catalog.cosObject.removeItem(COSName.OPEN_ACTION)
            catalog.cosObject.removeItem(COSName.AA)
            catalog.names?.let { names ->
                names.cosObject.removeItem(COSName.getPDFName("JavaScript"))
                if (names.embeddedFiles != null) problems.getOrPut(Problem.ATTACHMENTS) { mutableListOf() }
            }

            // Fonts must be embedded; annotations must print and have an appearance.
            val missing = sortedSetOf<String>()
            val seen = HashSet<COSBase>()
            fun fonts(res: PDResources?) {
                if (res == null) return
                for (name in res.fontNames) {
                    val f = runCatching { res.getFont(name) }.getOrNull() ?: continue
                    if (!seen.add(f.cosObject)) continue
                    if (!f.isEmbedded && f !is com.tom_roush.pdfbox.pdmodel.font.PDType3Font) missing += f.name ?: name.name
                }
                for (name in res.xObjectNames) {
                    val xo = runCatching { res.getXObject(name) }.getOrNull()
                    if (xo is PDFormXObject && seen.add(xo.cosObject)) fonts(xo.resources)
                }
            }
            for (page in doc.pages) {
                page.cosObject.removeItem(COSName.AA)
                fonts(page.resources)
                for (a in runCatching { page.annotations }.getOrDefault(emptyList())) {
                    if (a.subtype == "Popup") continue
                    a.cosObject.removeItem(COSName.AA)
                    a.cosObject.removeItem(COSName.A)
                    a.setHidden(false)
                    a.setPrinted(true)
                    if (a.appearance == null) runCatching { a.constructAppearances(doc) }
                    a.appearance?.normalAppearance?.let { ap ->
                        if (ap.isStream) fonts(ap.appearanceStream.resources)
                    }
                }
            }
            if (missing.isNotEmpty()) problems[Problem.FONTS_NOT_EMBEDDED] = missing.toMutableList()

            // sRGB output intent.
            if (catalog.outputIntents.isEmpty()) {
                context.assets.open("icc/sRGB.icc").use { icc ->
                    val intent = PDOutputIntent(doc, icc).apply {
                        info = "sRGB IEC61966-2.1"
                        outputCondition = "sRGB IEC61966-2.1"
                        outputConditionIdentifier = "sRGB IEC61966-2.1"
                        registryName = "http://www.color.org"
                    }
                    catalog.addOutputIntent(intent)
                }
            }

            // Matching document information and XMP metadata.
            val info = doc.documentInformation
            val now = Calendar.getInstance()
            info.producer = "Open PDF Viewer"
            info.modificationDate = now
            if (info.creationDate == null) info.creationDate = now
            val xmp = xmp(info.title, info.author, info.subject, info.keywords, info.producer, info.creationDate, info.modificationDate)
            val meta = PDMetadata(doc)
            meta.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
            catalog.metadata = meta

            doc.save(output)
            Report(problems)
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun date(c: Calendar): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = c.timeZone ?: TimeZone.getDefault()
        return f.format(c.time)
    }

    /** The XMP packet PDF/A asks for, mirroring the document information. */
    fun xmp(title: String?, author: String?, subject: String?, keywords: String?, producer: String?, created: Calendar, modified: Calendar): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"><pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description>\n")
        append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        append("<dc:format>application/pdf</dc:format>")
        if (!title.isNullOrEmpty()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(title)}</rdf:li></rdf:Alt></dc:title>")
        if (!author.isNullOrEmpty()) append("<dc:creator><rdf:Seq><rdf:li>${esc(author)}</rdf:li></rdf:Seq></dc:creator>")
        if (!subject.isNullOrEmpty()) append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(subject)}</rdf:li></rdf:Alt></dc:description>")
        append("</rdf:Description>\n")
        append("<rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">")
        if (!producer.isNullOrEmpty()) append("<pdf:Producer>${esc(producer)}</pdf:Producer>")
        if (!keywords.isNullOrEmpty()) append("<pdf:Keywords>${esc(keywords)}</pdf:Keywords>")
        append("</rdf:Description>\n")
        append("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">")
        append("<xmp:CreateDate>${date(created)}</xmp:CreateDate><xmp:ModifyDate>${date(modified)}</xmp:ModifyDate>")
        append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>")
    }
}
