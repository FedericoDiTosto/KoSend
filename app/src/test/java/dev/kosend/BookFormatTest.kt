package dev.kosend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFormatTest {
    private val epubHead = "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1) + ByteArray(26) +
        "mimetypeapplication/epub+zip".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun extensionInNameWins() {
        assertEquals("epub", BookFormat.detect("Book (2021)_x-y,z.epub", null, ByteArray(0)))
        assertEquals("pdf", BookFormat.detect(" Notes.PDF ", "application/octet-stream", ByteArray(0)))
    }

    @Test
    fun nameWithoutExtensionIsRecognizedFromContent() {
        assertEquals("epub", BookFormat.detect("The Hobbit", "application/octet-stream", epubHead))
        assertEquals("pdf", BookFormat.detect("download", null, "%PDF-1.7".toByteArray()))
        assertEquals("mobi", BookFormat.detect("book", null, ByteArray(60) + "BOOKMOBI".toByteArray()))
        assertEquals("fb2", BookFormat.detect("book", null, "<?xml version=\"1.0\"?>\n<FictionBook xmlns=\"x\">".toByteArray()))
    }

    @Test
    fun declaredTypeIsTheLastResort() {
        assertEquals("epub", BookFormat.detect("The Hobbit", "application/epub+zip", ByteArray(0)))
        assertEquals("pdf", BookFormat.detect("x", "Application/PDF; charset=binary", ByteArray(0)))
        assertNull(BookFormat.detect("photo", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(BookFormat.detect("archive.zip", "application/zip", "PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun extensionIsAddedOnlyWhenMissing() {
        assertEquals("The Hobbit.epub", BookFormat.nameWith("The Hobbit", "epub"))
        assertEquals("The Hobbit.EPUB", BookFormat.nameWith("The Hobbit.EPUB", "epub"))
        assertEquals("v1.2 notes.pdf", BookFormat.nameWith("v1.2 notes", "pdf"))
    }
}
