package dev.kosend

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BookWriter {
    data class Edit(
        val title: String,
        val authors: List<String>,
        val series: String = "",
        val seriesIndex: String = "",
        val tags: List<String> = emptyList(),
        val cover: ByteArray? = null,
    )

    private val EMBEDDABLE = setOf("epub", "fb2", "mobi", "azw", "azw3", "pdf")
    private val COVERS = setOf("epub", "fb2", "mobi", "azw", "azw3")

    fun canEmbed(extension: String) = extension in EMBEDDABLE

    fun canEmbedCover(extension: String) = extension in COVERS

    fun edit(book: Book) = Edit(
        book.title, book.authors, book.details.series, book.details.seriesIndex, book.details.subjects,
        book.customCover?.let { File(it) }?.takeIf { it.isFile }?.readBytes(),
    )

    fun write(book: Book, source: File, dir: File): File? {
        if (!canEmbed(book.extension)) return null
        dir.mkdirs()
        val out = File(dir, "${book.id}.${book.extension}")
        val e = edit(book)
        val ok = runCatching {
            when (book.extension) {
                "epub" -> epub(source, out, e)
                "fb2" -> fb2(source.readBytes(), e)?.let { out.writeBytes(it); true } ?: false
                "mobi", "azw", "azw3" -> mobi(source, out, e)
                "pdf" -> pdf(source, out, e)
                else -> false
            }
        }.getOrDefault(false)
        if (!ok) out.delete()
        return out.takeIf { ok }
    }

    fun fileName(title: String, authors: List<String>, extension: String, fallback: String): String {
        val base = listOf(title, authors.joinToString(" & "))
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim().trim('.', ' ')
            .take(120).trim()
        return if (base.isEmpty()) fallback else "$base.$extension"
    }

    fun version(book: Book): String = if (!book.edited) "" else listOf(
        book.title, book.authors.joinToString("\u0001"), book.details.series, book.details.seriesIndex,
        book.details.subjects.joinToString("\u0001"), book.customCover.orEmpty(),
    ).joinToString("\u0000").hashCode().toUInt().toString(16)

    fun freshPath(base: String, used: List<String>): String {
        if (base !in used) return base
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.', "")
        return generateSequence(2) { it + 1 }
            .map { n -> if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext" }
            .first { it !in used }
    }

    fun normalizeIndex(raw: String): String? {
        val s = raw.trim().replace(',', '.')
        if (s.isEmpty()) return ""
        val n = s.toDoubleOrNull()?.takeIf { it >= 0 && !it.isInfinite() } ?: return null
        return if (n == Math.floor(n) && n < 1e9) n.toLong().toString() else n.toString()
    }

    fun splitList(raw: String): List<String> =
        raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun epub(source: File, out: File, e: Edit): Boolean {
        ZipFile(source).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return false
            val containerXml = zip.getInputStream(container).use { it.readBytes() }.toString(Charsets.UTF_8)
            val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1) ?: return false
            val opfEntry = zip.getEntry(opfPath) ?: return false
            val raw = zip.getInputStream(opfEntry).use { it.readBytes() }
            if (raw.size >= 2 && (raw[0] == 0xFE.toByte() || raw[0] == 0xFF.toByte())) return false
            var text = opf(String(raw, Charsets.UTF_8), e) ?: return false
            var coverPath: String? = null
            if (e.cover != null) {
                val (withCover, href) = opfCover(text) ?: return false
                text = withCover
                coverPath = resolve(opfPath, href)
            }
            val patched = text.toByteArray(Charsets.UTF_8)
            val coverSize = e.cover?.let { jpegSize(it) }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024)).use { zos ->
                zip.getEntry("mimetype")?.let { m ->
                    val data = zip.getInputStream(m).use { it.readBytes() }
                    zos.putNextEntry(ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        crc = CRC32().apply { update(data) }.value
                    })
                    zos.write(data)
                    zos.closeEntry()
                }
                var coverWritten = false
                for (entry in zip.entries()) {
                    if (entry.name == "mimetype") continue
                    zos.putNextEntry(ZipEntry(entry.name).apply { if (entry.time >= 0) time = entry.time })
                    when {
                        entry.name == opfPath -> zos.write(patched)
                        entry.name == coverPath -> { zos.write(e.cover!!); coverWritten = true }
                        coverPath != null && coverSize != null && isPage(entry) -> {
                            val page = zip.getInputStream(entry).use { it.readBytes() }
                            zos.write(fitCoverPage(String(page, Charsets.UTF_8), coverPath.substringAfterLast('/'), coverSize)?.toByteArray(Charsets.UTF_8) ?: page)
                        }
                        !entry.isDirectory -> zip.getInputStream(entry).use { it.copyTo(zos, 64 * 1024) }
                    }
                    zos.closeEntry()
                }
                if (coverPath != null && !coverWritten) {
                    zos.putNextEntry(ZipEntry(coverPath))
                    zos.write(e.cover!!)
                    zos.closeEntry()
                }
            }
        }
        return true
    }

    internal fun opf(opf: String, e: Edit): String? {
        val open = Regex("""<((?:[\w.-]+:)?)metadata\b[^>]*>""").find(opf) ?: return null
        if (open.value.endsWith("/>")) return null
        val prefix = open.groupValues[1]
        val start = open.range.last + 1
        val end = opf.indexOf("</${prefix}metadata", start).takeIf { it >= 0 } ?: return null
        val block = opf.substring(start, end)

        val epub3 = Regex("""<(?:[\w.-]+:)?package\b[^>]*?\bversion\s*=\s*["']\s*3""").containsMatchIn(opf)
        val dcPrefix = Regex("""xmlns:([\w.-]+)\s*=\s*["']http://purl\.org/dc/elements/1\.1/?["']""").find(opf)?.groupValues?.get(1)
        val dc = dcPrefix ?: "dc"
        val dcNs = if (dcPrefix == null) " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" else ""
        val meta = "${prefix}meta"

        val metas = elements(block, "meta")
        val refines = metas.filter { attr(it.value, "refines") != null }
        fun refined(id: String?, property: String): String? = id?.let { i ->
            refines.firstOrNull { attr(it.value, "refines") == "#$i" && attr(it.value, "property") == property }?.let { inner(it.value) }
        }

        val drop = mutableListOf<IntRange>()
        val droppedIds = mutableSetOf<String>()
        fun dropElement(m: MatchResult) {
            drop.add(m.range)
            attr(m.value, "id")?.let { droppedIds.add(it) }
        }
        elements(block, "title").forEach(::dropElement)
        elements(block, "subject").forEach(::dropElement)
        elements(block, "creator").forEach { m ->
            val role = attr(m.value, "role") ?: refined(attr(m.value, "id"), "role")
            if (role == null || role == "aut") dropElement(m)
        }
        metas.forEach { m ->
            val name = attr(m.value, "name")
            val property = attr(m.value, "property")
            val stale = name == "calibre:series" || name == "calibre:series_index" || name == "calibre:title_sort" ||
                (property == "belongs-to-collection" && refined(attr(m.value, "id"), "collection-type") != "set")
            if (stale) dropElement(m)
        }
        refines.forEach { m -> if (attr(m.value, "refines")?.removePrefix("#") in droppedIds) drop.add(m.range) }

        val cleaned = removeRanges(block, drop)
        val indent = Regex("""\n([ \t]*)<""").find(block)?.groupValues?.get(1) ?: "    "
        val lines = mutableListOf<String>()
        lines += "<$dc:title$dcNs id=\"kosend-title\">${xml(e.title)}</$dc:title>"
        e.authors.forEachIndexed { i, a ->
            lines += "<$dc:creator$dcNs id=\"kosend-author-${i + 1}\">${xml(a)}</$dc:creator>"
            if (epub3) lines += "<$meta refines=\"#kosend-author-${i + 1}\" property=\"role\" scheme=\"marc:relators\">aut</$meta>"
        }
        e.tags.forEach { lines += "<$dc:subject$dcNs>${xml(it)}</$dc:subject>" }
        if (e.series.isNotBlank()) {
            lines += "<$meta name=\"calibre:series\" content=\"${xml(e.series)}\"/>"
            if (e.seriesIndex.isNotBlank()) lines += "<$meta name=\"calibre:series_index\" content=\"${xml(e.seriesIndex)}\"/>"
            if (epub3) {
                lines += "<$meta property=\"belongs-to-collection\" id=\"kosend-series\">${xml(e.series)}</$meta>"
                lines += "<$meta refines=\"#kosend-series\" property=\"collection-type\">series</$meta>"
                if (e.seriesIndex.isNotBlank()) lines += "<$meta refines=\"#kosend-series\" property=\"group-position\">${xml(e.seriesIndex)}</$meta>"
            }
        }
        val inserted = lines.joinToString("") { "\n$indent$it" }
        return opf.substring(0, start) + inserted + cleaned + opf.substring(end)
    }

    internal fun opfCover(source: String): Pair<String, String>? {
        val opf = source.replace(Regex("""<((?:[\w.-]+:)?manifest)\b([^>]*?)\s*/>""")) { "<${it.groupValues[1]}${it.groupValues[2]}></${it.groupValues[1]}>" }
        val open = Regex("""<((?:[\w.-]+:)?)metadata\b[^>]*>""").find(opf) ?: return null
        val prefix = open.groupValues[1]
        val epub3 = Regex("""<(?:[\w.-]+:)?package\b[^>]*?\bversion\s*=\s*["']\s*3""").containsMatchIn(opf)
        val manifestOpen = Regex("""<((?:[\w.-]+:)?)manifest\b[^>]*>""").find(opf) ?: return null
        val mPrefix = manifestOpen.groupValues[1]
        val manifestEnd = opf.indexOf("</${mPrefix}manifest", manifestOpen.range.last).takeIf { it >= 0 } ?: return null
        val manifest = opf.substring(manifestOpen.range.last + 1, manifestEnd)

        val items = elements(manifest, "item").filter { attr(it.value, "href") != null }
        val images = items.filter { attr(it.value, "media-type").orEmpty().startsWith("image/") }
        val metaCover = elements(opf, "meta").firstOrNull { attr(it.value, "name") == "cover" }?.let { attr(it.value, "content") }
        val found = images.firstOrNull { "cover-image" in attr(it.value, "properties").orEmpty().split(' ') }
            ?: metaCover?.let { id -> images.firstOrNull { attr(it.value, "id") == id } }
            ?: images.firstOrNull { m -> listOf("id", "href").any { "cover" in attr(m.value, it).orEmpty().lowercase() } }

        val id: String
        val href: String
        var newManifest = manifest
        if (found != null) {
            id = attr(found.value, "id") ?: return null
            href = attr(found.value, "href")!!
            val tag = found.value
            val fixed = tag.replace(Regex("""(\smedia-type\s*=\s*)(["'])[^"']*\2"""), "$1$2image/jpeg$2")
            newManifest = manifest.replaceRange(found.range, fixed)
        } else {
            id = "kosend-cover"
            href = "kosend-cover.jpg"
            val indent = Regex("""\n([ \t]*)<""").find(manifest)?.groupValues?.get(1) ?: "    "
            val props = if (epub3) " properties=\"cover-image\"" else ""
            val item = "\n$indent<${mPrefix}item id=\"$id\" href=\"$href\" media-type=\"image/jpeg\"$props/>"
            val trailing = Regex("""\s*$""").find(manifest)?.range?.first ?: manifest.length
            newManifest = manifest.substring(0, trailing) + item + manifest.substring(trailing)
        }
        var out = opf.substring(0, manifestOpen.range.last + 1) + newManifest + opf.substring(manifestEnd)

        val mOpen = Regex("""<((?:[\w.-]+:)?)metadata\b[^>]*>""").find(out)!!
        val start = mOpen.range.last + 1
        val end = out.indexOf("</${prefix}metadata", start)
        if (end < 0) return null
        val block = out.substring(start, end)
        val cleaned = removeRanges(block, elements(block, "meta").filter { attr(it.value, "name") == "cover" }.map { it.range })
        val indent = Regex("""\n([ \t]*)<""").find(block)?.groupValues?.get(1) ?: "    "
        out = out.substring(0, start) + "\n$indent<${prefix}meta name=\"cover\" content=\"${xml(id)}\"/>" + cleaned + out.substring(end)
        return out to href
    }

    private fun isPage(entry: ZipEntry) = !entry.isDirectory && entry.size in 0..256 * 1024 &&
        entry.name.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm")

    internal fun fitCoverPage(page: String, imageName: String, size: Pair<Int, Int>): String? {
        if ("<svg" !in page || imageName !in page) return null
        val (w, h) = size
        var changed = false
        val out = Regex("""<svg\b[^>]*>[\s\S]*?</svg\s*>""").replace(page) { m ->
            val svg = m.value
            val image = Regex("""<image\b[^>]*>""").find(svg) ?: return@replace svg
            val href = Regex("""href\s*=\s*["']([^"']*)["']""").find(image.value)?.groupValues?.get(1) ?: return@replace svg
            if (percentDecode(href).substringAfterLast('/') != imageName) return@replace svg
            changed = true
            val fixedImage = image.value
                .replace(Regex("""(\swidth\s*=\s*)(["'])[^"']*\2"""), "$1$2$w$2")
                .replace(Regex("""(\sheight\s*=\s*)(["'])[^"']*\2"""), "$1$2$h$2")
            val open = Regex("""<svg\b[^>]*>""").find(svg)!!.value
            val fixedOpen = open
                .replace(Regex("""(\sviewBox\s*=\s*)(["'])[^"']*\2"""), "$1$2" + "0 0 $w $h" + "$2")
                .replace(Regex("""(\spreserveAspectRatio\s*=\s*)(["'])[^"']*\2"""), "$1$2xMidYMid meet$2")
            svg.replaceFirst(open, fixedOpen).replaceFirst(image.value, fixedImage)
        }
        return out.takeIf { changed }
    }

    internal fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xff
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 || marker == 0xFF) { i++; continue }
            val len = u16(b, i + 2)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return u16(b, i + 7) to u16(b, i + 5)
            }
            i += 2 + len
        }
        return null
    }

    private fun resolve(opfPath: String, href: String): String {
        val parts = opfPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in percentDecode(href.substringBefore('#')).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val hex = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 } else { out.write(s[i].toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString("UTF-8")
    }

    internal fun fb2(bytes: ByteArray, e: Edit): ByteArray? {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val declared = Regex("""<\?xml[^>]*?encoding\s*=\s*["']([\w.:-]+)["']""").find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val text = String(bytes, charset)

        val open = Regex("""<((?:[\w.-]+:)?)title-info\b[^>]*>""").find(text) ?: return null
        val p = open.groupValues[1]
        val start = open.range.last + 1
        val end = text.indexOf("</${p}title-info", start).takeIf { it >= 0 } ?: return null
        val block = text.substring(start, end)
        val indent = Regex("""\n([ \t]*)<""").find(block)?.groupValues?.get(1) ?: "  "
        val enc = charset.newEncoder()
        fun t(s: String) = xml(s, enc)

        val drop = listOfNotNull("author", "book-title", "keywords", "sequence", "coverpage".takeIf { e.cover != null }).flatMap { name -> elements(block, name).map { it.range } }
        var cleaned = removeRanges(block, drop)

        val people = e.authors.joinToString("") { a ->
            val parts = a.trim().split(Regex("""\s+"""))
            val first = parts.dropLast(1).joinToString(" ")
            val name = (if (first.isNotEmpty()) "<${p}first-name>${t(first)}</${p}first-name>" else "") +
                "<${p}last-name>${t(parts.last())}</${p}last-name>"
            "\n$indent<${p}author>$name</${p}author>"
        }
        val titleLine = "\n$indent<${p}book-title>${t(e.title)}</${p}book-title>"
        val keywords = if (e.tags.isEmpty()) "" else "\n$indent<${p}keywords>${t(e.tags.joinToString(", "))}</${p}keywords>"
        val sequence = if (e.series.isBlank()) "" else
            "\n$indent<${p}sequence name=\"${t(e.series)}\"" + (if (e.seriesIndex.isNotBlank()) " number=\"${t(e.seriesIndex)}\"" else "") + "/>"

        val xlink = Regex("""xmlns:([\w.-]+)\s*=\s*["']http://www\.w3\.org/1999/xlink["']""").find(text)?.groupValues?.get(1)
        val coverLine = if (e.cover == null) "" else {
            val ns = if (xlink == null) " xmlns:l=\"http://www.w3.org/1999/xlink\"" else ""
            "\n$indent<${p}coverpage><${p}image$ns ${xlink ?: "l"}:href=\"#$FB2_COVER\"/></${p}coverpage>"
        }

        val afterGenres = elements(cleaned, "genre").lastOrNull()?.range?.last?.plus(1) ?: 0
        val annotation = elements(cleaned, "annotation").firstOrNull()?.range?.last?.plus(1)?.takeIf { it > afterGenres }
        val trailing = Regex("""\s*$""").find(cleaned)?.range?.first ?: cleaned.length
        val lang = elements(cleaned, "lang").firstOrNull()?.range?.first
            ?.let { s -> cleaned.lastIndexOf('\n', s).takeIf { it >= afterGenres } ?: s }
            ?.takeIf { it >= (annotation ?: afterGenres) }
        val inserts = listOf(
            trailing to (if (lang == null) coverLine else "") + sequence,
            (lang ?: trailing) to (if (lang != null) coverLine else ""),
            (annotation ?: afterGenres) to keywords,
            afterGenres to people + titleLine,
        )
        val sb = StringBuilder(cleaned)
        inserts.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<Int, String>>> { it.value.first }.thenBy { it.index })
            .forEach { sb.insert(it.value.first, it.value.second) }
        cleaned = sb.toString()

        var rest = text.substring(end)
        if (e.cover != null) {
            val close = Regex("""</(?:[\w.-]+:)?FictionBook\s*>""").findAll(rest).lastOrNull() ?: return null
            val binary = "<${p}binary id=\"$FB2_COVER\" content-type=\"image/jpeg\">" +
                java.util.Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(e.cover) + "</${p}binary>\n"
            rest = rest.substring(0, close.range.first) + binary + rest.substring(close.range.first)
        }
        return (text.substring(0, start) + cleaned + rest).toByteArray(charset)
    }

    private const val FB2_COVER = "kosend-cover.jpg"

    private fun mobi(source: File, out: File, e: Edit): Boolean {
        RandomAccessFile(source, "r").use { f ->
            val length = f.length()
            val head = ByteArray(78).also { f.readFully(it) }
            if (String(head, 60, 8, Charsets.US_ASCII) != "BOOKMOBI") return false
            val count = u16(head, 76)
            if (count < 2) return false
            val table = ByteArray(count * 8).also { f.readFully(it) }
            val offsets = LongArray(count) { u32(table, it * 8).toLong() and 0xffffffffL }
            fun record(i: Int): ByteArray {
                val stop = if (i + 1 < count) offsets[i + 1] else length
                val size = stop - offsets[i]
                if (size < 0 || size > 16 * 1024 * 1024) throw IllegalStateException("bad record")
                return ByteArray(size.toInt()).also { f.seek(offsets[i]); f.readFully(it) }
            }

            val r0 = record(0)
            val patched = mutableMapOf<Int, ByteArray>()
            patched[0] = mobiHeader(r0, e) ?: return false
            e.cover?.let { cover ->
                val firstImage = u32(r0, 108)
                val records = exth(r0).orEmpty()
                listOf(201, 202).mapNotNull { k -> records.firstOrNull { it.first == k }?.second?.takeIf { it.size >= 4 }?.let { u32(it, 0) } }
                    .map { firstImage + it }
                    .filter { firstImage > 0 && it in 1 until count }
                    .forEach { patched[it] = cover }
            }
            kf8Boundary(r0)?.let { b ->
                val i = b + 1
                if (i in 1 until count) {
                    val r = record(i)
                    if (r.size >= 20 && String(r, 16, 4, Charsets.US_ASCII) == "MOBI") mobiHeader(r, e)?.let { patched[i] = it }
                }
            }

            val newOffsets = LongArray(count)
            var shift = 0L
            for (i in 0 until count) {
                newOffsets[i] = offsets[i] + shift
                patched[i]?.let { p -> shift += p.size - ((if (i + 1 < count) offsets[i + 1] else length) - offsets[i]) }
            }
            val newTable = table.copyOf()
            for (i in 0 until count) put32(newTable, i * 8, newOffsets[i])

            FileOutputStream(out).use { os ->
                val ch = os.channel
                os.write(head)
                os.write(newTable)
                f.channel.transfer(78L + count * 8, offsets[0], ch)
                for (i in 0 until count) {
                    val stop = if (i + 1 < count) offsets[i + 1] else length
                    val p = patched[i]
                    if (p != null) os.write(p) else f.channel.transfer(offsets[i], stop, ch)
                }
            }
        }
        return true
    }

    private fun java.nio.channels.FileChannel.transfer(from: Long, to: Long, target: java.nio.channels.WritableByteChannel) {
        var pos = from
        while (pos < to) {
            val n = transferTo(pos, to - pos, target)
            if (n <= 0) throw java.io.IOException("short copy")
            pos += n
        }
    }

    private fun kf8Boundary(r0: ByteArray): Int? {
        val records = exth(r0) ?: return null
        val v = records.firstOrNull { it.first == 121 }?.second ?: return null
        if (v.size < 4) return null
        return u32(v, 0).takeIf { it > 0 && it != -1 }
    }

    private fun exth(r: ByteArray): List<Pair<Int, ByteArray>>? {
        if (r.size < 132) return null
        val start = 16 + u32(r, 20)
        if (u32(r, 128) and 0x40 == 0 || start < 0 || start + 12 > r.size) return null
        if (String(r, start, 4, Charsets.US_ASCII) != "EXTH") return null
        val list = mutableListOf<Pair<Int, ByteArray>>()
        var pos = start + 12
        repeat(u32(r, start + 8)) {
            if (pos + 8 > r.size) return list
            val kind = u32(r, pos)
            val len = u32(r, pos + 4)
            if (len < 8 || pos + len > r.size) return list
            list.add(kind to r.copyOfRange(pos + 8, pos + len))
            pos += len
        }
        return list
    }

    internal fun mobiHeader(r: ByteArray, e: Edit): ByteArray? {
        if (r.size < 132 || String(r, 16, 4, Charsets.US_ASCII) != "MOBI") return null
        if (u16(r, 12) != 0) return null
        val headerEnd = 16 + u32(r, 20)
        if (headerEnd < 132 || headerEnd > r.size) return null
        val charset = if (u32(r, 28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val replaced = setOf(100, 105, 503)
        val records = exth(r).orEmpty().filter { it.first !in replaced }.toMutableList()
        e.authors.forEach { records.add(100 to it.toByteArray(charset)) }
        e.tags.forEach { records.add(105 to it.toByteArray(charset)) }
        records.add(503 to e.title.toByteArray(charset))

        val body = ByteArrayOutputStream()
        records.forEach { (kind, value) ->
            body.write(be32(kind)); body.write(be32(value.size + 8)); body.write(value)
        }
        val exth = ByteArrayOutputStream().apply {
            write("EXTH".toByteArray(Charsets.US_ASCII))
            write(be32(12 + body.size()))
            write(be32(records.size))
            write(body.toByteArray())
            while (size() % 4 != 0) write(0)
        }.toByteArray()
        val name = e.title.toByteArray(charset)
        val out = ByteArrayOutputStream().apply {
            write(r, 0, headerEnd)
            write(exth)
            write(name)
            write(0); write(0)
            while (size() % 4 != 0) write(0)
        }.toByteArray()
        put32(out, 84, (headerEnd + exth.size).toLong())
        put32(out, 88, name.size.toLong())
        put32(out, 128, (u32(r, 128) or 0x40).toLong() and 0xffffffffL)
        return out
    }

    private fun pdf(source: File, out: File, e: Edit): Boolean {
        val length = source.length()
        val (chunk, prev) = RandomAccessFile(source, "r").use { f ->
            val n = minOf(length, 4096L).toInt()
            val tail = ByteArray(n).also { f.seek(length - n); f.readFully(it) }.toString(Charsets.ISO_8859_1)
            val at = tail.lastIndexOf("startxref").takeIf { it >= 0 } ?: return false
            val prev = Regex("""startxref\s+(\d+)""").find(tail, at)?.groupValues?.get(1)?.toLongOrNull() ?: return false
            if (prev <= 0 || prev >= length) return false
            val size = minOf(length - prev, 4L * 1024 * 1024).toInt()
            val chunk = ByteArray(size).also { f.seek(prev); f.readFully(it) }.toString(Charsets.ISO_8859_1)
            chunk to prev
        }
        val trailer = pdfTrailer(chunk) ?: return false
        val (dict, stream) = trailer
        if (Regex("""/Encrypt\b""").containsMatchIn(dict)) return false
        val size = Regex("""/Size\s+(\d+)""").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val root = Regex("""/Root\s+(\d+\s+\d+\s+R)""").find(dict)?.groupValues?.get(1) ?: return false
        val id = Regex("""/ID\s*(\[[^\]]*\])""").find(dict)?.groupValues?.get(1)?.let { " /ID $it" }.orEmpty()

        val buf = ByteArrayOutputStream()
        fun w(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))
        w("\n")
        val infoOffset = length + buf.size()
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        w("$size 0 obj\n<< /Title ${pdfString(e.title)}")
        if (e.authors.isNotEmpty()) w(" /Author ${pdfString(e.authors.joinToString(", "))}")
        if (e.tags.isNotEmpty()) w(" /Keywords ${pdfString(e.tags.joinToString(", "))}")
        w(" /ModDate (D:${stamp}Z) >>\nendobj\n")
        val xrefOffset = length + buf.size()
        if (!stream) {
            w("xref\n$size 1\n${"%010d".format(infoOffset)} 00000 n\r\n")
            w("trailer\n<< /Size ${size + 1} /Root $root /Info $size 0 R /Prev $prev$id >>\n")
        } else {
            val data = ByteArrayOutputStream().apply {
                write(1); write(be32(infoOffset.toInt())); write(0); write(0)
                write(1); write(be32(xrefOffset.toInt())); write(0); write(0)
            }.toByteArray()
            w("${size + 1} 0 obj\n<< /Type /XRef /Size ${size + 2} /W [1 4 2] /Index [$size 2] /Root $root /Info $size 0 R /Prev $prev$id /Length ${data.size} >>\nstream\n")
            buf.write(data)
            w("\nendstream\nendobj\n")
        }
        w("startxref\n$xrefOffset\n%%EOF\n")
        if (xrefOffset > Int.MAX_VALUE) return false

        source.copyTo(out, overwrite = true)
        FileOutputStream(out, true).use { it.write(buf.toByteArray()) }
        return true
    }

    internal fun pdfTrailer(chunk: String): Pair<String, Boolean>? {
        val body = chunk.trimStart()
        return when {
            body.startsWith("xref") -> {
                val t = chunk.indexOf("trailer").takeIf { it >= 0 } ?: return null
                dictAt(chunk, chunk.indexOf("<<", t))?.let { it to false }
            }
            Regex("""^\d+\s+\d+\s+obj""").containsMatchIn(body) -> dictAt(chunk, chunk.indexOf("<<"))?.let { it to true }
            else -> null
        }
    }

    private fun dictAt(s: String, start: Int): String? {
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < s.length) {
            val c = s[i]
            when {
                c == '<' && s.getOrNull(i + 1) == '<' -> { depth++; i += 2; continue }
                c == '>' && s.getOrNull(i + 1) == '>' -> {
                    depth--; i += 2
                    if (depth == 0) return s.substring(start, i)
                    continue
                }
                c == '<' -> { i = s.indexOf('>', i).takeIf { it >= 0 } ?: return null }
                c == '(' -> {
                    var level = 0
                    while (i < s.length) {
                        when (s[i]) {
                            '\\' -> i++
                            '(' -> level++
                            ')' -> { level--; if (level == 0) break }
                        }
                        i++
                    }
                }
            }
            i++
        }
        return null
    }

    private fun pdfString(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_16BE)
        return "<FEFF" + bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) } + ">"
    }

    private fun elements(block: String, local: String): List<MatchResult> {
        val name = """(?:[\w.-]+:)?${Regex.escape(local)}"""
        return Regex("""<$name(?=[\s/>])[^>]*?/>|<$name(?=[\s>])[^>]*>[\s\S]*?</$name\s*>""").findAll(block).toList()
    }

    private fun attr(element: String, name: String): String? {
        val tag = element.substringBefore('>')
        val m = Regex("""\s(?:[\w.-]+:)?${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""").find(tag) ?: return null
        return unxml(m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
    }

    private fun inner(element: String): String =
        unxml(element.substringAfter('>').substringBeforeLast('<')).trim()

    private fun removeRanges(s: String, ranges: List<IntRange>): String {
        val sb = StringBuilder(s)
        var limit = Int.MAX_VALUE
        for (r in ranges.distinct().sortedByDescending { it.first }) {
            if (r.last >= limit) continue
            var from = r.first
            while (from > 0 && sb[from - 1] in " \t") from--
            if (from > 0 && sb[from - 1] == '\n') from--
            if (from > 0 && sb[from - 1] == '\r') from--
            sb.delete(from, r.last + 1)
            limit = from
        }
        return sb.toString()
    }

    private fun xml(s: String, encoder: CharsetEncoder? = null): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = String(Character.toChars(cp))
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D -> {}
                encoder != null && !encoder.canEncode(ch) -> sb.append("&#").append(cp).append(';')
                else -> sb.append(ch)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun unxml(s: String): String = s
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
