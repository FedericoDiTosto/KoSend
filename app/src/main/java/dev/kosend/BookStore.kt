package dev.kosend

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class BookStatus { PENDING, SENDING, SENT, ERROR }

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
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("fileName", fileName).put("path", path).put("size", size)
        .put("title", title).put("authors", JSONArray(authors))
        .put("status", status.name).put("message", message)

    companion object {
        fun fromJson(o: JSONObject): Book {
            val a = o.optJSONArray("authors") ?: JSONArray()
            return Book(
                id = o.getString("id"),
                fileName = o.getString("fileName"),
                path = o.getString("path"),
                size = o.getLong("size"),
                title = o.optString("title"),
                authors = List(a.length()) { a.getString(it) },

                status = BookStatus.valueOf(o.optString("status", "PENDING"))
                    .let { if (it == BookStatus.SENDING) BookStatus.PENDING else it },
                message = o.optString("message"),
            )
        }
    }
}

object BookStore {
    private val books = mutableListOf<Book>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dbFile: File
    private lateinit var prefs: SharedPreferences
    lateinit var booksDir: File
        private set

    var deleteAfterSend: Boolean
        get() = prefs.getBoolean("delete_after_send", false)
        set(value) = prefs.edit().putBoolean("delete_after_send", value).apply()

    @Synchronized
    fun init(context: Context) {
        if (::dbFile.isInitialized) return
        dbFile = File(context.filesDir, "queue.json")
        prefs = context.getSharedPreferences("kosend", Context.MODE_PRIVATE)
        booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val loaded = !dbFile.exists() || runCatching {
            val arr = JSONArray(dbFile.readText())
            for (i in 0 until arr.length()) {
                val b = Book.fromJson(arr.getJSONObject(i))
                if (b.status == BookStatus.SENT || File(b.path).exists()) books.add(b)
            }
        }.isSuccess
        if (loaded) {
            val known = books.map { File(it.path).name }.toSet()
            booksDir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
        }
    }

    fun storageUsed(): Pair<Long, Int> {
        val files = booksDir.listFiles().orEmpty()
        return files.sumOf { it.length() } to files.size
    }

    @Synchronized
    fun all(): List<Book> = books.map { it.copy() }

    @Synchronized
    fun pending(): List<Book> = books.filter { it.status == BookStatus.PENDING }.map { it.copy() }

    @Synchronized
    fun add(book: Book) {
        books.add(book)
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
    fun resetSending() {
        val stuck = books.filter { it.status == BookStatus.SENDING }
        if (stuck.isEmpty()) return
        stuck.forEach { it.status = BookStatus.PENDING; it.progress = 0 }
        save(); notifyChanged()
    }

    @Synchronized
    fun retry(id: String) {
        val b = books.find { it.id == id } ?: return
        b.status = BookStatus.PENDING; b.message = ""; b.progress = 0
        save(); notifyChanged()
    }

    @Synchronized
    fun remove(id: String) {
        val b = books.find { it.id == id } ?: return
        if (b.status == BookStatus.SENDING) return
        books.remove(b)
        File(b.path).delete()
        save(); notifyChanged()
    }

    @Synchronized
    fun removeSent() {
        books.filter { it.status == BookStatus.SENT }.forEach { File(it.path).delete() }
        books.removeAll { it.status == BookStatus.SENT }
        save(); notifyChanged()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun save() {
        val arr = JSONArray()
        books.forEach { arr.put(it.toJson()) }
        val tmp = File(dbFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(dbFile)
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
