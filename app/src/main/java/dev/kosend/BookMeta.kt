package dev.kosend

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

object BookMeta {
    fun read(file: File, fileName: String): Pair<String, List<String>> {
        val fallback = fromFileName(fileName)
        if (!fileName.lowercase().endsWith(".epub")) return fallback
        return runCatching { fromEpub(file) }.getOrNull()
            ?.let { (t, a) -> (t.ifBlank { fallback.first }) to a.ifEmpty { fallback.second } }
            ?: fallback
    }

    private fun fromFileName(fileName: String): Pair<String, List<String>> {
        val base = fileName.substringBeforeLast('.')
            .replace(Regex("""\s*\((z-library|z-lib|1lib)[^)]*\)"""), "")
            .replace('_', ' ').trim()
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].length < 60) parts[1].trim() to listOf(parts[0].trim())
        else base to emptyList()
    }

    private fun fromEpub(file: File): Pair<String, List<String>> {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return "" to emptyList()
            val opfPath = zip.getInputStream(container).use { findRootFile(it) } ?: return "" to emptyList()
            val opf = zip.getEntry(opfPath) ?: return "" to emptyList()
            return zip.getInputStream(opf).use { parseOpf(it) }
        }
    }

    private fun findRootFile(input: InputStream): String? {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.endsWith("rootfile")) {
                return p.getAttributeValue(null, "full-path")
            }
        }
        return null
    }

    private fun parseOpf(input: InputStream): Pair<String, List<String>> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var title = ""
        val authors = mutableListOf<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.substringAfter(':')) {
                "title" -> if (title.isEmpty()) title = p.nextText().trim()
                "creator" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { authors.add(it) }
                "manifest" -> break
            }
        }
        return title to authors
    }
}
