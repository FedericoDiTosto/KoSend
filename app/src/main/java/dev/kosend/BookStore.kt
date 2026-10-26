package dev.kosend

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class BookStatus { PENDING, SENDING, SENT, ERROR }

data class BookDetails(
    val publisher: String = "",
    val published: String = "",
    val language: String = "",
    val series: String = "",
    val seriesIndex: String = "",
    val isbn: String = "",
    val subjects: List<String> = emptyList(),
    val description: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("publisher", publisher).put("published", published).put("language", language)
        .put("series", series).put("seriesIndex", seriesIndex).put("isbn", isbn)
        .put("subjects", JSONArray(subjects)).put("description", description)

    companion object {
        fun fromJson(o: JSONObject?): BookDetails {
            o ?: return BookDetails()
            val s = o.optJSONArray("subjects") ?: JSONArray()
            return BookDetails(
                publisher = o.optString("publisher"),
                published = o.optString("published"),
                language = o.optString("language"),
                series = o.optString("series"),
                seriesIndex = o.optString("seriesIndex"),
                isbn = o.optString("isbn"),
                subjects = List(s.length()) { s.getString(it) },
                description = o.optString("description"),
            )
        }
    }
}

data class Book(
    val id: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val title: String,
    val authors: List<String>,
    var status: BookStatus = BookStatus.PENDING,
    var message: String = "",
    var progress: Int = 0,
    val details: BookDetails = BookDetails(),
    val cover: String? = null,
    val addedAt: Long = 0,
    val metaVersion: Int = BookMeta.VERSION,
    val edited: Boolean = false,
    val descriptionEdited: Boolean = false,
    val customFileName: String = "",
    val customCover: String? = null,
    val sentPaths: List<String> = emptyList(),
    val sentVersion: String = "",
    val deviceLpath: String = "",
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    val fileExists: Boolean get() = path.isNotEmpty() && File(path).exists()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("fileName", fileName).put("path", path).put("size", size)
        .put("title", title).put("authors", JSONArray(authors))
        .put("status", status.name).put("message", message)
        .put("details", details.toJson()).put("cover", cover ?: "")
        .put("addedAt", addedAt).put("metaVersion", metaVersion).put("edited", edited)
        .put("descriptionEdited", descriptionEdited).put("customFileName", customFileName)
        .put("customCover", customCover ?: "")
        .put("sentPaths", JSONArray(sentPaths)).put("sentVersion", sentVersion)
        .put("deviceLpath", deviceLpath)

    companion object {
        fun fromJson(o: JSONObject): Book {
            val a = o.optJSONArray("authors") ?: JSONArray()
            val sp = o.optJSONArray("sentPaths") ?: JSONArray()
            val book = Book(
                id = o.getString("id"),
                fileName = o.getString("fileName"),
                path = o.getString("path"),
                size = o.getLong("size"),
                title = o.optString("title"),
                authors = List(a.length()) { a.getString(it) },

                status = BookStatus.valueOf(o.optString("status", "PENDING"))
                    .let { if (it == BookStatus.SENDING) BookStatus.PENDING else it },
                message = o.optString("message"),
                details = BookDetails.fromJson(o.optJSONObject("details")),
                cover = o.optString("cover").ifEmpty { null },
                addedAt = o.optLong("addedAt"),
                metaVersion = o.optInt("metaVersion", 0),
                edited = o.optBoolean("edited"),
                descriptionEdited = o.optBoolean("descriptionEdited"),
                customFileName = o.optString("customFileName"),
                customCover = o.optString("customCover").ifEmpty { null },
                sentPaths = List(sp.length()) { sp.getString(it) },
                sentVersion = o.optString("sentVersion"),
                deviceLpath = o.optString("deviceLpath"),
            )
            if (o.has("sentPaths") || book.status != BookStatus.SENT) return book
            return book.copy(sentPaths = listOf(BookWriter.deviceName(book)), sentVersion = "legacy")
        }
    }
}

object BookStore {
   class Removed(val book: Book, val index: Int)

    private val books = mutableListOf<Book>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dbFile: File
    private lateinit var prefs: SharedPreferences
    lateinit var booksDir: File
        private set
    private lateinit var coversDir: File
    lateinit var outgoingDir: File
        private set

    val deviceSynced: Boolean
        get() = prefs.getBoolean("device_synced", false)

    fun onDevice(b: Book): Boolean =
        if (deviceSynced) b.deviceLpath.isNotEmpty() else b.sentPaths.isNotEmpty()

    var deleteAfterSend: Boolean
        get() = prefs.getBoolean("delete_after_send", false)
        set(value) = prefs.edit().putBoolean("delete_after_send", value).apply()

    @Synchronized
    fun init(context: Context) {
        if (::dbFile.isInitialized) return
        dbFile = File(context.filesDir, "queue.json")
        prefs = context.getSharedPreferences("kosend", Context.MODE_PRIVATE)
        booksDir = File(context.filesDir, "books").apply { mkdirs() }
        coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        outgoingDir = File(context.cacheDir, "outgoing").apply { deleteRecursively(); mkdirs() }
        val loaded = !dbFile.exists() || runCatching {
            val arr = JSONArray(dbFile.readText())
            for (i in 0 until arr.length()) {
                val b = Book.fromJson(arr.getJSONObject(i))
                val file = File(b.path)
                if (b.status != BookStatus.SENT && file.exists() && file.length() != b.size) {
                   file.delete()
                    b.status = BookStatus.ERROR; b.message = Messages.MISSING_FILE
                    books.add(b)
                } else if (b.status == BookStatus.SENT || file.exists()) {
                    books.add(b)
                }
            }
        }.isSuccess
        if (loaded) {
            val known = books.map { File(it.path).name }.toSet()
            booksDir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
            val knownCovers = books.flatMap { listOfNotNull(it.cover, it.customCover) }.map { File(it).name }.toSet()
            coversDir.listFiles()?.filter { it.name !in knownCovers }?.forEach { it.delete() }
        }
    }

    fun storageUsed(): Pair<Long, Int> {
        val files = booksDir.listFiles().orEmpty()
        return files.sumOf { it.length() } to files.size
    }

    fun pendingCoverFile(id: String) = File(coversDir, "$id.pending.jpg")

    fun saveCover(id: String, bitmap: Bitmap): String? = runCatching {
        val file = File(coversDir, "$id.jpg")
        writeAtomically(file) { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        CoverCache.evict(file.path)
        file.path
    }.getOrNull()

    @Synchronized
    fun all(): List<Book> = books.map { it.copy() }

    @Synchronized
    fun get(id: String): Book? = books.find { it.id == id }?.copy()

    @Synchronized
    fun nextPending(): Book? = books.firstOrNull { it.status == BookStatus.PENDING }?.copy()

    @Synchronized
    fun pendingCount(): Int = books.count { it.status == BookStatus.PENDING }

    @Synchronized
    fun outdated(): List<Book> = books.filter {
        File(it.path).length() == it.size && (it.metaVersion < BookMeta.VERSION || it.cover?.let { c -> File(c).length() == 0L } == true)
    }.map { it.copy() }

    @Synchronized
    fun add(book: Book) {
        books.add(book)
        save(); notifyChanged()
    }

    @Synchronized
    fun setMeta(id: String, meta: BookMeta.Result, cover: String?) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0) return
        val b = books[i]
        books[i] = if (b.edited) b.copy(
            details = meta.details.copy(
                series = b.details.series, seriesIndex = b.details.seriesIndex, subjects = b.details.subjects,
                description = if (b.descriptionEdited) b.details.description else meta.details.description,
            ),
            cover = cover ?: b.cover, metaVersion = BookMeta.VERSION,
        ) else b.copy(
            title = meta.title, authors = meta.authors, details = meta.details,
            cover = cover ?: b.cover, metaVersion = BookMeta.VERSION,
        )
        save(); notifyChanged()
    }

    @Synchronized
    fun edit(id: String, edit: BookWriter.Edit, cover: Pair<File, Bitmap>? = null) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0 || books[i].status == BookStatus.SENDING) return
        val b = books[i]
        var shown = b.cover
        var custom = b.customCover
        if (cover != null) {
            val target = File(coversDir, "$id.custom-${System.currentTimeMillis()}.jpg")
            if (cover.first.renameTo(target)) {
                b.customCover?.let { File(it).delete() }
                custom = target.path
                shown = saveCover(id, cover.second) ?: shown
            }
        }
        books[i] = b.copy(
            title = edit.title, authors = edit.authors,
            details = b.details.copy(
                series = edit.series, seriesIndex = edit.seriesIndex, subjects = edit.tags,
                description = edit.description ?: b.details.description,
            ),
            edited = true, descriptionEdited = b.descriptionEdited || edit.description != null,
            customFileName = edit.fileName, cover = shown, customCover = custom,
        )
        save(); notifyChanged()
    }

    @Synchronized
    fun revertEdits(id: String, meta: BookMeta.Result) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0 || books[i].status == BookStatus.SENDING) return
        val b = books[i]
        var shown = b.cover
        if (b.customCover != null) {
            File(b.customCover).delete()
            shown = meta.cover?.let { saveCover(id, it) }
            if (shown == null) b.cover?.let { File(it).delete(); CoverCache.evict(it) }
        }
        books[i] = b.copy(
            title = meta.title, authors = meta.authors, details = meta.details,
            edited = false, descriptionEdited = false, customFileName = "", cover = shown, customCover = null,
        )
        save(); notifyChanged()
    }

    @Synchronized
    fun update(id: String, status: BookStatus, message: String = "", progress: Int = 0) {
        val b = books.find { it.id == id } ?: return
        b.status = status; b.message = message; b.progress = progress
        if (status == BookStatus.SENT && deleteAfterSend) File(b.path).delete()
        if (status != BookStatus.SENDING) save()
        notifyChanged()
    }

    @Synchronized
    fun recordSent(id: String, lpath: String, version: String) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0) return
        val b = books[i]
        books[i] = b.copy(sentPaths = (b.sentPaths - lpath) + lpath, sentVersion = version, deviceLpath = lpath)
        save()
    }

    @Synchronized
    fun syncDevice(present: Map<String, String>) {
        for (i in books.indices) books[i] = books[i].copy(deviceLpath = present[books[i].id].orEmpty())
        prefs.edit().putBoolean("device_synced", true).apply()
        save(); notifyChanged()
    }

    @Synchronized
    fun resetSending() {
        val stuck = books.filter { it.status == BookStatus.SENDING }
        if (stuck.isEmpty()) return
        stuck.forEach { it.status = BookStatus.PENDING; it.progress = 0 }
        save(); notifyChanged()
    }

    @Synchronized
    fun retry(id: String) {
        val b = books.find { it.id == id } ?: return
        if (b.status == BookStatus.SENDING || !File(b.path).exists()) return
        b.status = BookStatus.PENDING; b.message = ""; b.progress = 0
        save(); notifyChanged()
    }

    @Synchronized
    fun reorder(ids: List<String>) {
        val rank = ids.withIndex().associate { (i, id) -> id to i }
        val sorted = books.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        if (sorted.map { it.id } == books.map { it.id }) return
        books.clear(); books.addAll(sorted)
        save(); notifyChanged()
    }

    @Synchronized
    fun remove(id: String): Removed? {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0 || books[i].status == BookStatus.SENDING) return null
        val b = books.removeAt(i)
        save(); notifyChanged()
        return Removed(b, i)
    }

    @Synchronized
    fun restore(removed: Removed) {
        if (books.any { it.id == removed.book.id }) return
        books.add(removed.index.coerceIn(0, books.size), removed.book)
        save(); notifyChanged()
    }

    @Synchronized
    fun discard(removed: Removed) {
        if (books.any { it.id == removed.book.id }) return
        deleteFiles(removed.book)
    }

    @Synchronized
    fun removeSent() {
        books.filter { it.status == BookStatus.SENT }.forEach { deleteFiles(it) }
        books.removeAll { it.status == BookStatus.SENT }
        save(); notifyChanged()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun deleteFiles(b: Book) {
        File(b.path).delete()
        b.cover?.let { File(it).delete(); CoverCache.evict(it) }
        b.customCover?.let { File(it).delete() }
    }

    private fun save() {
        val arr = JSONArray()
        books.forEach { arr.put(it.toJson()) }
        writeAtomically(dbFile) { it.write(arr.toString().toByteArray()) }
    }

    private fun writeAtomically(file: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(file.path + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        tmp.renameTo(file)
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
