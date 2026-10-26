package dev.kosend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BookWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val edit = BookWriter.Edit(
        title = "Harry Potter & the Philosopher's Stone",
        authors = listOf("J.K. Rowling", "Mary GrandPré"),
        series = "Harry Potter",
        seriesIndex = "1",
        tags = listOf("Fantasy", "Magic"),
    )

    private val epub2Opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>harry-potter-1-eng_final</dc:title>
    <dc:creator opf:role="aut" opf:file-as="Unknown">Unknown</dc:creator>
    <dc:creator opf:role="ill">Some Illustrator</dc:creator>
    <dc:subject>Old</dc:subject>
    <dc:identifier id="uid">urn:uuid:123</dc:identifier>
    <dc:language>en</dc:language>
    <meta name="calibre:series" content="Wrong"/>
    <meta name="calibre:series_index" content="9"/>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest><item id="cover-img" href="cover.jpg" media-type="image/jpeg"/></manifest>
  <spine/>
</package>"""

    private val epub3Opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title id="t1">Old title</dc:title>
    <meta refines="#t1" property="title-type">main</meta>
    <dc:creator id="c1">Old Author</dc:creator>
    <meta refines="#c1" property="role" scheme="marc:relators">aut</meta>
    <meta refines="#c1" property="file-as">Author, Old</meta>
    <dc:creator id="c2">Editor Person</dc:creator>
    <meta refines="#c2" property="role" scheme="marc:relators">edt</meta>
    <meta property="belongs-to-collection" id="s1">Old Series</meta>
    <meta refines="#s1" property="collection-type">series</meta>
    <meta refines="#s1" property="group-position">4</meta>
    <dc:identifier id="uid">urn:uuid:abc</dc:identifier>
    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
  </metadata>
  <manifest/>
  <spine/>
</package>"""

    @Test
    fun epub2OpfIsRewritten() {
        val out = BookWriter.opf(epub2Opf, edit)!!
        assertFalse("harry-potter-1-eng_final" in out)
        assertFalse("Unknown" in out)
        assertFalse("Wrong" in out)
        assertFalse(">Old<" in out)
        assertTrue("<dc:title id=\"kosend-title\">Harry Potter &amp; the Philosopher's Stone</dc:title>" in out)
        assertTrue("<dc:creator id=\"kosend-author-1\">J.K. Rowling</dc:creator>" in out)
        assertTrue("<dc:creator id=\"kosend-author-2\">Mary GrandPré</dc:creator>" in out)
        assertTrue("Some Illustrator" in out)
        assertTrue("<meta name=\"calibre:series\" content=\"Harry Potter\"/>" in out)
        assertTrue("<meta name=\"calibre:series_index\" content=\"1\"/>" in out)
        assertTrue("<dc:subject>Fantasy</dc:subject>" in out)
        assertTrue("<meta name=\"cover\" content=\"cover-img\"/>" in out)
        assertTrue("urn:uuid:123" in out)
        assertFalse("belongs-to-collection" in out)
        assertWellFormed(out)
    }

    @Test
    fun epub3OpfIsRewritten() {
        val out = BookWriter.opf(epub3Opf, edit)!!
        assertFalse("Old title" in out)
        assertFalse("title-type" in out)
        assertFalse("Old Author" in out)
        assertFalse("Author, Old" in out)
        assertFalse("Old Series" in out)
        assertFalse(">4<" in out)
        assertTrue("Editor Person" in out)
        assertTrue("<meta refines=\"#c2\" property=\"role\" scheme=\"marc:relators\">edt</meta>" in out)
        assertTrue("<meta refines=\"#kosend-author-1\" property=\"role\" scheme=\"marc:relators\">aut</meta>" in out)
        assertTrue("<meta property=\"belongs-to-collection\" id=\"kosend-series\">Harry Potter</meta>" in out)
        assertTrue("<meta refines=\"#kosend-series\" property=\"collection-type\">series</meta>" in out)
        assertTrue("<meta refines=\"#kosend-series\" property=\"group-position\">1</meta>" in out)
        assertTrue("dcterms:modified" in out)
        assertWellFormed(out)
    }

    @Test
    fun opfWithoutDcPrefixDeclaresIt() {
        val opf = """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><opf:metadata xmlns:opf="http://www.idpf.org/2007/opf"><title xmlns="http://purl.org/dc/elements/1.1/">x</title></opf:metadata></package>"""
        val out = BookWriter.opf(opf, edit.copy(series = ""))!!
        assertTrue("<dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\" id=\"kosend-title\">" in out)
        assertFalse(">x<" in out)
        assertWellFormed(out)
    }

    @Test
    fun epubFileKeepsMimetypeFirstAndStored() {
        val src = tmp.newFile("book.epub")
        ZipOutputStream(src.outputStream()).use { z ->
            val mime = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            z.write(mime); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("OEBPS/content.opf")); z.write(epub2Opf.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("OEBPS/ch1.xhtml")); z.write("<html>hello</html>".toByteArray()); z.closeEntry()
        }
        val out = BookWriter.write(book("epub"), src, tmp.newFolder())!!
        ZipFile(out).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().toString(Charsets.UTF_8)
            assertTrue("calibre:series\" content=\"Harry Potter\"" in opf)
            assertEquals("<html>hello</html>", zip.getInputStream(zip.getEntry("OEBPS/ch1.xhtml")).readBytes().toString(Charsets.UTF_8))
        }
        val raw = out.readBytes()
        assertEquals("mimetypeapplication/epub+zip", String(raw, 30, 28, Charsets.US_ASCII))
    }

    @Test
    fun brokenEpubFallsBack() {
        val src = tmp.newFile("bad.epub").apply { writeText("not a zip") }
        assertNull(BookWriter.write(book("epub"), src, tmp.newFolder()))
    }

    @Test
    fun fb2TitleInfoIsRewritten() {
        val fb2 = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
 <description>
  <title-info>
   <genre>sf</genre>
   <author><first-name>Old</first-name><last-name>Name</last-name></author>
   <book-title>old_title</book-title>
   <annotation><p>About</p></annotation>
   <lang>en</lang>
   <sequence name="Old" number="3"/>
  </title-info>
  <document-info><author><nickname>maker</nickname></author></document-info>
 </description>
 <body><p>Text</p></body>
</FictionBook>""".toByteArray(charset("windows-1251"))
        val out = String(BookWriter.fb2(fb2, edit)!!, charset("windows-1251"))
        assertFalse("old_title" in out)
        assertFalse("<last-name>Name" in out)
        assertFalse("name=\"Old\"" in out)
        assertTrue("<author><first-name>J.K.</first-name><last-name>Rowling</last-name></author>" in out)
        assertTrue("<last-name>GrandPr&#233;</last-name>" in out || "<last-name>GrandPré</last-name>" in out)
        assertTrue("<book-title>Harry Potter &amp; the Philosopher's Stone</book-title>" in out)
        assertTrue("<sequence name=\"Harry Potter\" number=\"1\"/>" in out)
        assertTrue(out.indexOf("<keywords>") > out.indexOf("</annotation>"))
        assertTrue(out.indexOf("<author>") > out.indexOf("</genre>"))
        assertTrue("<nickname>maker</nickname>" in out)
        assertWellFormed(out.replace("windows-1251", "UTF-8"))
    }

    @Test
    fun mobiRecordsAreRewritten() {
        val src = tmp.newFile("book.mobi")
        src.writeBytes(mobiFile("old_title", "Unknown"))
        val out = BookWriter.write(book("mobi"), src, tmp.newFolder())!!
        val bytes = out.readBytes()
        val count = u16(bytes, 76)
        assertEquals(3, count)
        val r0 = u32(bytes, 78)
        val r1 = u32(bytes, 86)
        val r2 = u32(bytes, 94)
        assertEquals("MOBI", String(bytes, r0 + 16, 4, Charsets.US_ASCII))
        val nameOffset = u32(bytes, r0 + 84)
        val nameLength = u32(bytes, r0 + 88)
        assertEquals(edit.title, String(bytes, r0 + nameOffset, nameLength, Charsets.UTF_8))
        assertEquals("RECORD-ONE", String(bytes, r1, 10, Charsets.US_ASCII))
        assertEquals("RECORD-TWO", String(bytes, r2, 10, Charsets.US_ASCII))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("J.K. Rowling" in text)
        assertFalse("Unknown" in text)
        assertTrue("KEEP-ME" in text)
    }

    @Test
    fun pdfWithClassicXrefGetsIncrementalInfo() {
        val src = tmp.newFile("book.pdf")
        val body = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
        val xref = body.length
        src.writeText(body + "xref\n0 3\n0000000000 65535 f\r\n0000000009 00000 n\r\n0000000058 00000 n\r\n" +
            "trailer\n<< /Size 3 /Root 1 0 R /ID [<AB12><CD34>] >>\nstartxref\n$xref\n%%EOF\n", Charsets.ISO_8859_1)
        val out = BookWriter.write(book("pdf"), src, tmp.newFolder())!!
        val text = out.readText(Charsets.ISO_8859_1)
        assertTrue(text.startsWith(src.readText(Charsets.ISO_8859_1)))
        val start = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n3 1\n", start))
        val infoOffset = text.substring(start + 9, start + 19).toInt()
        assertTrue(text.startsWith("3 0 obj", infoOffset))
        assertTrue("/Info 3 0 R /Prev $xref /ID [<AB12><CD34>]" in text)
        assertTrue("/Size 4 /Root 1 0 R" in text)
        assertTrue("/Title <FEFF0048" in text)
    }

    @Test
    fun pdfWithXrefStreamGetsXrefStream() {
        val src = tmp.newFile("book.pdf")
        val body = "%PDF-1.5\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        val xref = body.length
        src.writeText(body + "5 0 obj\n<< /Type /XRef /Size 6 /Root 1 0 R /W [1 2 1] /Filter /FlateDecode /DecodeParms << /Columns 4 /Predictor 12 >> /Length 0 >>\nstream\n\nendstream\nendobj\nstartxref\n$xref\n%%EOF", Charsets.ISO_8859_1)
        val out = BookWriter.write(book("pdf"), src, tmp.newFolder())!!
        val text = out.readText(Charsets.ISO_8859_1)
        val start = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("7 0 obj\n<< /Type /XRef /Size 8 /W [1 4 2] /Index [6 2] /Root 1 0 R /Info 6 0 R /Prev $xref", start))
        val data = text.substring(text.lastIndexOf(">>\nstream\n") + 10).toByteArray(Charsets.ISO_8859_1)
        assertEquals(1, data[0].toInt())
        val infoOffset = u32(data, 1)
        assertTrue(text.startsWith("6 0 obj", infoOffset))
        assertEquals(start, u32(data, 8))
    }

    @Test
    fun encryptedPdfIsLeftAlone() {
        val src = tmp.newFile("book.pdf")
        src.writeText("%PDF-1.4\nxref\n0 1\n0000000000 65535 f\r\ntrailer\n<< /Size 1 /Root 1 0 R /Encrypt 4 0 R >>\nstartxref\n9\n%%EOF\n", Charsets.ISO_8859_1)
        assertNull(BookWriter.write(book("pdf"), src, tmp.newFolder()))
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    @Test
    fun epubCoverReplacesExistingImage() {
        val out = BookWriter.opfCover(epub2Opf.replace("cover.jpg\" media-type=\"image/jpeg\"", "img/cover.png\" media-type=\"image/png\""))!!
        assertEquals("img/cover.png", out.second)
        assertTrue("<item id=\"cover-img\" href=\"img/cover.png\" media-type=\"image/jpeg\"/>" in out.first)
        assertEquals(1, Regex("""name="cover"""").findAll(out.first).count())
        assertWellFormed(out.first)
    }

    @Test
    fun epubCoverIsAddedWhenMissing() {
        val out = BookWriter.opfCover(epub3Opf)!!
        assertEquals("kosend-cover.jpg", out.second)
        assertTrue("<item id=\"kosend-cover\" href=\"kosend-cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>" in out.first)
        assertTrue("<meta name=\"cover\" content=\"kosend-cover\"/>" in out.first)
        assertWellFormed(out.first)
    }

    @Test
    fun epubFileGetsNewCoverEntry() {
        val src = tmp.newFile("c.epub")
        ZipOutputStream(src.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("OEBPS/content.opf")); z.write(epub3Opf.toByteArray()); z.closeEntry()
        }
        val out = BookWriter.write(withCover(book("epub")), src, tmp.newFolder())!!
        ZipFile(out).use { zip ->
            assertTrue(zip.getInputStream(zip.getEntry("OEBPS/kosend-cover.jpg")).readBytes().contentEquals(jpeg))
        }
    }

    @Test
    fun fb2CoverIsEmbedded() {
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
 <description>
  <title-info>
   <genre>sf</genre>
   <book-title>x</book-title>
   <coverpage><image l:href="#old.jpg"/></coverpage>
   <lang>en</lang>
  </title-info>
 </description>
 <body><p>Text</p></body>
 <binary id="old.jpg" content-type="image/jpeg">AAAA</binary>
</FictionBook>""".toByteArray()
        val out = String(BookWriter.fb2(fb2, edit.copy(cover = jpeg))!!)
        assertFalse("#old.jpg" in out)
        assertTrue("<coverpage><image l:href=\"#kosend-cover.jpg\"/></coverpage>" in out)
        assertTrue(out.indexOf("<coverpage>") < out.indexOf("<lang>"))
        assertTrue("<binary id=\"kosend-cover.jpg\" content-type=\"image/jpeg\">/9j/4AECAwQ=</binary>" in out)
        assertWellFormed(out)
    }

    @Test
    fun mobiCoverRecordIsReplaced() {
        val src = tmp.newFile("cover.mobi")
        src.writeBytes(mobiFile("t", "a", coverIndex = 0))
        val out = BookWriter.write(withCover(book("mobi")), src, tmp.newFolder())!!
        val bytes = out.readBytes()
        val r1 = u32(bytes, 86)
        val r2 = u32(bytes, 94)
        assertTrue(bytes.copyOfRange(r1, r2).contentEquals(jpeg))
        assertEquals("RECORD-TWO", String(bytes, r2, 10, Charsets.US_ASCII))
    }

    @Test
    fun resentFilesGetFreshNames() {
        assertEquals("A.epub", BookWriter.freshPath("A.epub", emptyList()))
        assertEquals("A (2).epub", BookWriter.freshPath("A.epub", listOf("A.epub")))
        assertEquals("A (3).epub", BookWriter.freshPath("A.epub", listOf("A.epub", "A (2).epub")))
        val b = book("epub")
        assertEquals(BookWriter.version(b), BookWriter.version(b.copy()))
        assertFalse(BookWriter.version(b) == BookWriter.version(b.copy(customCover = "/x/new.jpg")))
        assertEquals("", BookWriter.version(b.copy(edited = false)))
    }

    @Test
    fun coverPageIsFittedToNewImage() {
        val page = """<html><body><svg xmlns="http://www.w3.org/2000/svg" version="1.1" width="100%" height="100%" viewBox="0 0 499 706" preserveAspectRatio="none"><image width="499" height="706" xlink:href="cover.jpeg"/></svg></body></html>"""
        val out = BookWriter.fitCoverPage(page, "cover.jpeg", 1067 to 1600)!!
        assertTrue("viewBox=\"0 0 1067 1600\"" in out)
        assertTrue("<image width=\"1067\" height=\"1600\" xlink:href=\"cover.jpeg\"/>" in out)
        assertTrue("width=\"100%\" height=\"100%\"" in out)
        assertNull(BookWriter.fitCoverPage(page, "other.jpg", 1 to 1))
    }

    @Test
    fun jpegSizeIsRead() {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(14)
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0, 50, 0, 30, 3) + ByteArray(9)
        val out = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(30 to 50, BookWriter.jpegSize(out))
        assertNull(BookWriter.jpegSize(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun fileNamesAreClean() {
        assertEquals("Dune - Frank Herbert.epub", BookWriter.fileName("Dune", listOf("Frank Herbert"), "epub", "x.epub"))
        assertEquals("A B - C & D.pdf", BookWriter.fileName("A/B", listOf("C", "D"), "pdf", "x.pdf"))
        assertEquals("x.txt", BookWriter.fileName("  ", emptyList(), "txt", "x.txt"))
    }

    @Test
    fun seriesIndexIsNormalized() {
        assertEquals("1", BookWriter.normalizeIndex("1"))
        assertEquals("1", BookWriter.normalizeIndex("1.0"))
        assertEquals("2.5", BookWriter.normalizeIndex("2,5"))
        assertEquals("", BookWriter.normalizeIndex(" "))
        assertNull(BookWriter.normalizeIndex("one"))
    }

    @Test
    fun opfDescriptionIsKeptUnlessEdited() {
        val opf = epub2Opf.replace("<dc:language>", "<dc:description>&lt;p&gt;Original&lt;/p&gt;</dc:description>\n    <dc:language>")
        assertTrue("&lt;p&gt;Original&lt;/p&gt;" in BookWriter.opf(opf, edit)!!)

        val out = BookWriter.opf(opf, edit.copy(description = "A boy & a wizard.\n\nSecond part."))!!
        assertFalse("Original" in out)
        assertTrue("<dc:description>A boy &amp; a wizard.\n\nSecond part.</dc:description>" in out)
        assertWellFormed(out)

        val cleared = BookWriter.opf(opf, edit.copy(description = ""))!!
        assertFalse("description" in cleared)
        assertWellFormed(cleared)
    }

    @Test
    fun fb2AnnotationIsReplaced() {
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
 <description>
  <title-info>
   <genre>sf</genre>
   <book-title>old</book-title>
   <annotation><p>About</p></annotation>
   <lang>en</lang>
  </title-info>
 </description>
 <body><p>Text</p></body>
</FictionBook>""".toByteArray()
        val out = String(BookWriter.fb2(fb2, edit.copy(description = "First <one>.\nSecond."))!!)
        assertFalse("About" in out)
        assertTrue("<annotation><p>First &lt;one&gt;.</p><p>Second.</p></annotation>" in out)
        assertTrue(out.indexOf("<annotation>") > out.indexOf("</book-title>"))
        assertTrue(out.indexOf("<keywords>") > out.indexOf("</annotation>"))
        assertTrue(out.indexOf("<lang>") > out.indexOf("<keywords>"))
        assertWellFormed(out)

        assertTrue("<p>About</p>" in String(BookWriter.fb2(fb2, edit)!!))
    }

    @Test
    fun mobiDescriptionIsWritten() {
        val src = tmp.newFile("book.mobi")
        src.writeBytes(mobiFile("old_title", "Unknown"))
        val b = book("mobi").let { it.copy(details = it.details.copy(description = "New blurb"), descriptionEdited = true) }
        val text = String(BookWriter.write(b, src, tmp.newFolder())!!.readBytes(), Charsets.UTF_8)
        assertTrue("New blurb" in text)
        assertTrue("KEEP-ME" in text)
    }

    @Test
    fun descriptionOnlyChangesVersionWhenEdited() {
        val b = book("epub")
        val other = b.copy(details = b.details.copy(description = "From the file"))
        assertEquals(BookWriter.version(b), BookWriter.version(other))
        assertFalse(BookWriter.version(other) == BookWriter.version(other.copy(descriptionEdited = true)))
        assertNull(BookWriter.edit(other).description)
        assertEquals("From the file", BookWriter.edit(other.copy(descriptionEdited = true)).description)
    }

    @Test
    fun customFileNameWinsOnDevice() {
        val b = book("epub")
        assertEquals("Dune - Frank Herbert.epub", BookWriter.deviceName(b.copy(title = "Dune", authors = listOf("Frank Herbert"))))
        assertEquals("file.epub", BookWriter.deviceName(b.copy(edited = false)))
        assertEquals("My copy.epub", BookWriter.deviceName(b.copy(customFileName = "My copy")))
        assertEquals("a b.epub", BookWriter.deviceName(b.copy(customFileName = " a/b.EPUB ")))
        assertEquals("notes.epub", BookWriter.deviceName(b.copy(customFileName = "notes.pdf")))
        assertEquals("v1.2.epub", BookWriter.deviceName(b.copy(customFileName = "v1.2")))
        assertEquals(BookWriter.deviceName(b), BookWriter.deviceName(b.copy(customFileName = " .. ")))
        assertEquals(BookWriter.version(b), BookWriter.version(b.copy(customFileName = "")))
        assertFalse(BookWriter.version(b) == BookWriter.version(b.copy(customFileName = "My copy")))
    }

    @Test
    fun messyTitlesAreTidied() {
        assertEquals("Harry Potter 1", BookEditor.tidyTitle("harry-potter-1-eng_final"))
        assertEquals("The Hobbit", BookEditor.tidyTitle("The_Hobbit (z-library.sk)"))
        assertEquals("English Grammar in Use", BookEditor.tidyTitle("English Grammar in Use"))
    }

    private fun withCover(b: Book) = b.copy(customCover = tmp.newFile().apply { writeBytes(jpeg) }.path)

    private fun book(ext: String) = Book(
        id = "id", fileName = "file.$ext", path = "", size = 0, title = edit.title, authors = edit.authors,
        details = BookDetails(series = edit.series, seriesIndex = edit.seriesIndex, subjects = edit.tags), edited = true,
    )

    private fun mobiFile(title: String, author: String, coverIndex: Int? = null): ByteArray {
        val header = ByteArray(232)
        "MOBI".toByteArray().copyInto(header, 16)
        put32(header, 20, 216)
        put32(header, 28, 65001)
        put32(header, 128, 0x40)
        put32(header, 108, if (coverIndex != null) 1 else -1)
        val exth = ByteArrayOutputStream().apply {
            val recs = listOf(100 to author.toByteArray(), 503 to title.toByteArray(), 101 to "KEEP-ME".toByteArray()) +
                listOfNotNull(coverIndex?.let { 201 to be32(it) })
            val body = ByteArrayOutputStream()
            recs.forEach { (k, b) -> body.write(be32(k)); body.write(be32(b.size + 8)); body.write(b) }
            write("EXTH".toByteArray()); write(be32(12 + body.size())); write(be32(recs.size)); write(body.toByteArray())
            while (size() % 4 != 0) write(0)
        }.toByteArray()
        val name = title.toByteArray()
        val r0 = header + exth + name + ByteArray(4)
        put32(r0, 84, header.size + exth.size)
        put32(r0, 88, name.size)
        val r1 = "RECORD-ONE".toByteArray()
        val r2 = "RECORD-TWO".toByteArray()
        val pdb = ByteArray(78)
        "BOOKMOBI".toByteArray().copyInto(pdb, 60)
        pdb[77] = 3
        val start = 78 + 3 * 8 + 2
        val table = ByteArray(24)
        put32(table, 0, start)
        put32(table, 8, start + r0.size)
        put32(table, 16, start + r0.size + r1.size)
        return pdb + table + ByteArray(2) + r0 + r1 + r2
    }

    private fun assertWellFormed(xml: String) {
        assertNotNull(javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream()))
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun put32(b: ByteArray, o: Int, v: Int) = be32(v).copyInto(b, o)
}
