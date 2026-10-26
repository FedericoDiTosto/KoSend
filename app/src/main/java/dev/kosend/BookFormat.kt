package dev.kosend

object BookFormat {
    const val HEAD_SIZE = 1024

    private val MIME = mapOf(
        "application/epub+zip" to "epub",
        "application/pdf" to "pdf",
        "application/x-mobipocket-ebook" to "mobi",
        "application/vnd.amazon.ebook" to "azw",
        "application/vnd.amazon.mobi8-ebook" to "azw3",
        "application/x-mobi8-ebook" to "azw3",
        "application/x-fictionbook+xml" to "fb2",
        "application/x-fictionbook" to "fb2",
        "image/vnd.djvu" to "djvu",
        "image/x-djvu" to "djvu",
        "application/vnd.comicbook+zip" to "cbz",
        "application/x-cbz" to "cbz",
        "text/plain" to "txt",
        "application/rtf" to "rtf",
        "text/rtf" to "rtf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
    )

    fun detect(name: String, mime: String?, head: ByteArray): String? =
        name.trim().substringAfterLast('.', "").lowercase().takeIf { it in CalibreServer.SUPPORTED_EXTENSIONS }
            ?: sniff(head)
            ?: mime?.substringBefore(';')?.trim()?.lowercase()?.let { MIME[it] }

    fun nameWith(name: String, extension: String): String {
        val trimmed = name.trim()
        return if (trimmed.substringAfterLast('.', "").lowercase() == extension) trimmed else "$trimmed.$extension"
    }

    internal fun sniff(head: ByteArray): String? {
        fun at(offset: Int, text: String) = head.size >= offset + text.length &&
            String(head, offset, text.length, Charsets.ISO_8859_1) == text
        return when {
            at(0, "%PDF") -> "pdf"
            at(0, "PK\u0003\u0004") && at(30, "mimetypeapplication/epub+zip") -> "epub"
            at(60, "BOOKMOBI") -> "mobi"
            at(0, "AT&TFORM") -> "djvu"
            at(0, "{\\rtf") -> "rtf"
            "<FictionBook" in String(head, Charsets.ISO_8859_1) -> "fb2"
            else -> null
        }
    }
}
