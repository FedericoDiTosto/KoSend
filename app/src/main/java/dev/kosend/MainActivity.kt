package dev.kosend

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: QueueAdapter
    private lateinit var list: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton
    private var dragging = false
    private lateinit var swipe: SwipeToRemove
    private val io = Executors.newSingleThreadExecutor()

    private val storeListener = { refresh() }
    private val stateListener: (CalibreServer.State) -> Unit = { refresh() }

    private val pickBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        ServerController.pickerOpen = false
        importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        BookStore.init(this)

        val prefs = getSharedPreferences("kosend", MODE_PRIVATE)
        adapter = QueueAdapter(
            context = this,
            infoExpanded = prefs.getBoolean("info_expanded", true),
            onPowerToggled = { on -> if (on) ServerController.start(this) else ServerController.stop() },
            onEject = { ServerController.eject() },
            onClearSent = { BookStore.removeSent() },
            onInfoToggled = { expanded -> prefs.edit().putBoolean("info_expanded", expanded).apply() },
            onBookTapped = ::onBookTapped,
            onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            onRetry = ::sendAgain,
            onStartDrag = { swipe.close(); touchHelper.startDrag(it) },
            onBindBook = { holder, book -> swipe.bind(holder, book) },
        )
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator?.changeDuration = 0
        touchHelper.attachToRecyclerView(list)
        swipe = SwipeToRemove(list, adapter, isDragging = { dragging }, onRemove = ::removeWithUndo)

        fab = findViewById(R.id.add)
        fab.setOnClickListener {
            ServerController.pickerOpen = true
            pickBooks.launch(arrayOf("*/*"))
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) fab.shrink() else if (dy < -8) fab.extend()
            }
        })

        val fabMargin = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            list.updatePadding(top = bars.top, bottom = bars.bottom + (96 * resources.displayMetrics.density).toInt())
            fab.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
                bottomMargin = bars.bottom + fabMargin
                rightMargin = bars.right + fabMargin
            }
            insets
        }

        if (savedInstanceState == null) handleIncoming(intent)
        readOutdatedMeta()
    }

    private val touchHelper by lazy {
        ItemTouchHelper(QueueGestures(
            adapter,
            onDragging = { dragging = it },
            onDropped = { ids -> BookStore.reorder(ids); refresh() },
        ))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        BookStore.addListener(storeListener)
        ServerController.addListener(stateListener)
        refresh()
    }

    override fun onStop() {
        BookStore.removeListener(storeListener)
        ServerController.removeListener(stateListener)
        super.onStop()
    }

    private fun refresh() {
        if (dragging) return
        val state = ServerController.state
        adapter.update(BookStore.all(), state, ServerController.localIp(this))

        if (state is CalibreServer.State.Stopped) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
        }
        importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        io.execute {
            var added = 0
            val skipped = mutableListOf<String>()
            for (uri in uris) {
                val name = displayName(uri)
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in CalibreServer.SUPPORTED_EXTENSIONS) {
                    skipped.add(name); continue
                }
                val id = BookStore.newId()
                val dest = File(BookStore.booksDir, "$id.$ext")
                val ok = runCatching {
                    contentResolver.openInputStream(uri)!!.use { inp ->
                        java.io.FileOutputStream(dest).use { out ->
                            inp.copyTo(out)
                            out.fd.sync()
                        }
                    }
                }.isSuccess
                if (!ok) { dest.delete(); skipped.add(name); continue }
                val meta = BookMeta.read(dest, name)
                val cover = meta.cover?.let { BookStore.saveCover(id, it) }
                BookStore.add(Book(
                    id, safeFileName(name), dest.path, dest.length(), meta.title, meta.authors,
                    details = meta.details, cover = cover, addedAt = System.currentTimeMillis(),
                ))
                added++
            }
            runOnUiThread {
                if (added > 0) {
                    ServerController.sendPending()
                    list.post { list.smoothScrollToPosition(adapter.itemCount - 1) }
                    Toast.makeText(this, resources.getQuantityString(R.plurals.toast_added, added, added), Toast.LENGTH_SHORT).show()
                }
                if (skipped.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_skipped, skipped.joinToString()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000]"""), "_").trim().trimStart('.')
        return cleaned.ifEmpty { "book.epub" }
    }

    private fun readOutdatedMeta() {
        io.execute {
            for (book in BookStore.outdated()) {
                val meta = runCatching { BookMeta.read(File(book.path), book.fileName) }.getOrNull() ?: continue
                val cover = meta.cover?.let { BookStore.saveCover(book.id, it) }
                BookStore.setMeta(book.id, meta, cover)
            }
        }
    }

    private fun onBookTapped(book: Book) {
        BookDetail.show(this, book.id, onSendAgain = ::sendAgain, onRemove = ::removeWithUndo)
    }

    private fun sendAgain(book: Book) {
        BookStore.retry(book.id)
        ServerController.sendPending()
    }

    private fun removeWithUndo(book: Book) {
        val removed = BookStore.remove(book.id) ?: run {
            adapter.notifyItemChanged(adapter.bookIds().indexOf(book.id) + 1)
            return
        }
        Snackbar.make(findViewById(R.id.root), getString(R.string.snackbar_removed, book.title), Snackbar.LENGTH_LONG)
            .setAnchorView(fab)
            .setAction(R.string.undo) { BookStore.restore(removed) }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) BookStore.discard(removed)
                }
            })
            .show()
    }
}

internal fun badgeColor(ext: String): Int = when (ext) {
    "epub" -> R.color.badge_epub
    "pdf" -> R.color.badge_pdf
    "mobi", "azw", "azw3" -> R.color.badge_mobi
    else -> R.color.badge_other
}

internal fun formatSize(context: android.content.Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes)

internal fun View.tint(colorRes: Int) {
    backgroundTintList = ColorStateList.valueOf(context.getColor(colorRes))
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra(key)
