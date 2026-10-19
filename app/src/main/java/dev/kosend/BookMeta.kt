package dev.kosend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Xml
import androidx.core.text.HtmlCompat
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.ZipFile

object BookMeta {
    const val VERSION = 1

    private const val COVER_HEIGHT = 600

    class Result(
        val title: String,
        val authors: List<String>,
        val details: BookDetails = BookDetails(),
        val cover: Bitmap? = null,
    )

    fun read(file: File, fileName: String): Result {
        val fallback = fromFileName(fileName)
        val parsed = runCatching {
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "epub" -> fromEpub(file)
                "mobi", "azw", "azw3" -> fromMobi(file)
                "pdf" -> Result("", emptyList(), cover = pdfCover(file))
                else -> null
            }
        }.getOrNull() ?: return fallback
        return Result(
            title = parsed.title.ifBlank { fallback.title },
            authors = parsed.authors.ifEmpty { fallback.authors },
            details = parsed.details,
            cover = parsed.cover,
        )
    }

    private fun fromFileName(fileName: String): Result {
        val base = fileName.substringBeforeLast('.')
            .replace(Regex("""\s*\((z-library|z-lib|1lib)[^)]*\)"""), "")
            .replace('_', ' ').trim()
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].length < 60) Result(parts[1].trim(), listOf(parts[0].trim()))
        else Result(base, emptyList())
    }


    private class Item(val id: String, val href: String, val mediaType: String, val properties: String)

    private fun fromEpub(file: File): Result? {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val opfPath = zip.getInputStream(container).use { findRootFile(it) } ?: return null
            val opf = zip.getEntry(opfPath) ?: return null
            val (meta, coverHref) = zip.getInputStream(opf).use { parseOpf(it) }
            val cover = coverHref?.let { zip.getEntry(resolve(opfPath, it)) }
                ?.let { entry -> runCatching { zip.getInputStream(entry).use { decodeScaled(it.readBytes()) } }.getOrNull() }
            return Result(meta.title, meta.authors, meta.details, cover)
        }
    }

    private fun findRootFile(input: InputStream): String? {
        val p = parser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.endsWith("rootfile")) {
                return p.getAttributeValue(null, "full-path")
            }
        }
        return null
    }

    private fun parseOpf(input: InputStream): Pair<Result, String?> {
        val p = parser(input)
        var title = ""
        val authors = mutableListOf<String>()
        var d = BookDetails()
        val subjects = mutableListOf<String>()
        var coverId: String? = null
        val items = mutableListOf<Item>()
        loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.substringAfter(':')) {
                "title" -> text(p)?.let { if (title.isEmpty()) title = it }
                "creator" -> {
                    val role = attr(p, "role")
                    text(p)?.let { if (role == null || role == "aut") authors.add(it) }
                }
                "publisher" -> text(p)?.let { if (d.publisher.isEmpty()) d = d.copy(publisher = it) }
                "date" -> text(p)?.let { if (d.published.isEmpty()) d = d.copy(published = it) }
                "language" -> text(p)?.let { if (d.language.isEmpty()) d = d.copy(language = it) }
                "description" -> text(p)?.let { if (d.description.isEmpty()) d = d.copy(description = plain(it)) }
                "subject" -> text(p)?.let { subjects.add(it) }
                "identifier" -> {
                    val scheme = attr(p, "scheme")
                    text(p)?.let { v -> if (d.isbn.isEmpty()) isbn(v, scheme)?.let { d = d.copy(isbn = it) } }
                }
                "meta" -> {
                    val content = attr(p, "content").orEmpty()
                    when (attr(p, "name")) {
                        "cover" -> coverId = content
                        "calibre:series" -> d = d.copy(series = content)
                        "calibre:series_index" -> d = d.copy(seriesIndex = content.removeSuffix(".0"))
                    }
                    when (attr(p, "property")) {
                        "belongs-to-collection" -> text(p)?.let { if (d.series.isEmpty()) d = d.copy(series = it) }
                        "group-position" -> text(p)?.let { if (d.seriesIndex.isEmpty()) d = d.copy(seriesIndex = it) }
                    }
                }
                "item" -> items.add(Item(
                    attr(p, "id").orEmpty(), attr(p, "href").orEmpty(),
                    attr(p, "media-type").orEmpty(), attr(p, "properties").orEmpty(),
                ))
                "spine" -> break@loop
            }
        }
        val images = items.filter { it.mediaType.startsWith("image/") && it.href.isNotEmpty() }
        val cover = images.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: coverId?.let { id -> images.firstOrNull { it.id == id } }
            ?: images.firstOrNull { "cover" in it.id.lowercase() || "cover" in it.href.lowercase() }
        return Result(title, authors, d.copy(subjects = subjects)) to cover?.href
    }

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    private fun attr(p: XmlPullParser, name: String): String? =
        (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).substringAfter(':') == name }
            ?.let { p.getAttributeValue(it) }

    private fun text(p: XmlPullParser): String? =
        runCatching { p.nextText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun resolve(opfPath: String, href: String): String {
        val parts = opfPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in Uri.decode(href.substringBefore('#')).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun fromMobi(file: File): Result? {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(78).also { f.readFully(it) }
            val type = String(head, 60, 8, Charsets.US_ASCII)
            if (type != "BOOKMOBI") return null
            val count = u16(head, 76)
            val table = ByteArray(count * 8).also { f.readFully(it) }
            val offsets = LongArray(count) { u32(table, it * 8).toLong() }
            fun record(i: Int): ByteArray {
                val end = if (i + 1 < count) offsets[i + 1] else f.length()
                val bytes = ByteArray((end - offsets[i]).toInt().coerceIn(0, 16 * 1024 * 1024))
                f.seek(offsets[i]); f.readFully(bytes)
                return bytes
            }

            val r0 = record(0)
            if (r0.size < 132 || String(r0, 16, 4, Charsets.US_ASCII) != "MOBI") return null
            val headerLength = u32(r0, 20)
            val charset = if (u32(r0, 28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
            val nameOffset = u32(r0, 84)
            val nameLength = u32(r0, 88)
            var title = if (nameOffset > 0 && nameOffset + nameLength <= r0.size) String(r0, nameOffset, nameLength, charset).trim() else ""
            val firstImage = u32(r0, 108)

            val authors = mutableListOf<String>()
            val subjects = mutableListOf<String>()
            var d = BookDetails()
            var coverIndex = -1
            var thumbIndex = -1
            val exth = 16 + headerLength
            if (u32(r0, 128) and 0x40 != 0 && exth + 12 <= r0.size && String(r0, exth, 4, Charsets.US_ASCII) == "EXTH") {
                var pos = exth + 12
                repeat(u32(r0, exth + 8)) {
                    if (pos + 8 > r0.size) return@repeat
                    val kind = u32(r0, pos)
                    val len = u32(r0, pos + 4)
                    if (len < 8 || pos + len > r0.size) return@repeat
                    val value = { String(r0, pos + 8, len - 8, charset).trim() }
                    when (kind) {
                        100 -> value().takeIf { it.isNotEmpty() }?.let { authors.add(it) }
                        101 -> d = d.copy(publisher = value())
                        103 -> d = d.copy(description = plain(value()))
                        104 -> isbn(value(), "isbn")?.let { d = d.copy(isbn = it) }
                        105 -> value().takeIf { it.isNotEmpty() }?.let { subjects.add(it) }
                        106 -> d = d.copy(published = value())
                        201 -> if (len >= 12) coverIndex = u32(r0, pos + 8)
                        202 -> if (len >= 12) thumbIndex = u32(r0, pos + 8)
                        503 -> value().takeIf { it.isNotEmpty() }?.let { title = it }
                        524 -> d = d.copy(language = value())
                    }
                    pos += len
                }
            }

            val cover = listOf(coverIndex, thumbIndex)
                .filter { it >= 0 && firstImage >= 0 && firstImage + it < count }
                .firstNotNullOfOrNull { runCatching { decodeScaled(record(firstImage + it)) }.getOrNull() }
            return Result(title, authors, d.copy(subjects = subjects), cover)
        }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)


    private fun pdfCover(file: File): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(0).use { page ->
                    val height = COVER_HEIGHT
                    val width = (height.toFloat() * page.width / page.height).toInt().coerceIn(1, height * 2)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }


    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= COVER_HEIGHT) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (bmp.height <= COVER_HEIGHT) return bmp
        val width = (bmp.width.toFloat() * COVER_HEIGHT / bmp.height).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, width, COVER_HEIGHT, true)
    }

    private fun plain(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            .replace(Regex("""\n{3,}"""), "\n\n").trim()

    private fun isbn(value: String, scheme: String?): String? {
        val digits = value.removePrefix("urn:isbn:").filter { it.isDigit() || it == 'X' || it == 'x' }
        val looksLike = scheme.equals("isbn", ignoreCase = true) || value.startsWith("urn:isbn:") ||
            value.none { it.isLetter() && it != 'X' && it != 'x' }
        return digits.takeIf { looksLike && (it.length == 10 || it.length == 13) }
    }
}
