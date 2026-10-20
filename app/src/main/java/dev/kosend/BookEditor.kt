package dev.kosend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

object BookEditor {
    private val io = Executors.newSingleThreadExecutor()

    private val JUNK = Regex(
        """\b(final|eng|english|ita|italian|ebook|e-book|retail|epub|mobi|pdf|azw3|ocr|v\d+(\.\d+)*|z-?library|z-?lib|1lib)\b""",
        RegexOption.IGNORE_CASE,
    )

    private const val COVER_MAX = 1600
    private const val SOURCE_MAX = 2800

    fun show(context: Context, bookId: String, pickImage: ((Uri?) -> Unit) -> Unit) {
        val book = BookStore.get(bookId) ?: return
        val dialog = AppCompatDialog(context, R.style.BookDetailDialog)
        val v = LayoutInflater.from(context).inflate(R.layout.dialog_edit_book, null)
        dialog.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            val light = context.resources.getBoolean(R.bool.light_bars)
            WindowCompat.getInsetsController(w, v).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }

        val bar = v.findViewById<View>(R.id.edit_bar)
        val scroll = v.findViewById<View>(R.id.edit_scroll)
        val barTop = bar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            bar.updatePadding(top = bars.top + barTop)
            scroll.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }

        val titleLayout = v.findViewById<TextInputLayout>(R.id.edit_title_layout)
        val indexLayout = v.findViewById<TextInputLayout>(R.id.edit_index_layout)
        val title = v.findViewById<TextInputEditText>(R.id.edit_title_input)
        val authors = v.findViewById<TextInputEditText>(R.id.edit_authors_input)
        val series = v.findViewById<TextInputEditText>(R.id.edit_series_input)
        val index = v.findViewById<TextInputEditText>(R.id.edit_index_input)
        val tags = v.findViewById<TextInputEditText>(R.id.edit_tags_input)
        val tidy = v.findViewById<View>(R.id.edit_tidy)
        val fileName = v.findViewById<TextView>(R.id.edit_file_name)
        val revert = v.findViewById<View>(R.id.edit_revert)

        val coverRow = v.findViewById<View>(R.id.edit_cover_row)
        val coverView = v.findViewById<CoverView>(R.id.edit_cover)
        val preview = v.findViewById<ImageView>(R.id.edit_cover_preview)
        val coverProgress = v.findViewById<View>(R.id.edit_cover_progress)
        val coverChange = v.findViewById<View>(R.id.edit_cover_change)
        val pendingFile = BookStore.pendingCoverFile(bookId).apply { delete() }
        var pending: Bitmap? = null
        val save = v.findViewById<View>(R.id.edit_save)
        coverRow.visibility = if (BookWriter.canEmbedCover(book.extension)) View.VISIBLE else View.GONE
        coverView.bind(book, 120)
        val warning = v.findViewById<View>(R.id.edit_resend_warning)
        val warningText = v.findViewById<TextView>(R.id.edit_resend_text)
        val warningTip = v.findViewById<View>(R.id.edit_resend_tip)
        val chevron = v.findViewById<View>(R.id.edit_resend_chevron)
        warning.setOnClickListener {
            val expand = warningTip.visibility != View.VISIBLE
            TransitionManager.beginDelayedTransition(warning.parent as ViewGroup, AutoTransition().setDuration(200))
            warningText.maxLines = if (expand) Int.MAX_VALUE else 2
            warningTip.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expand) 180f else 0f).setDuration(200).start()
        }
        val coverCrop = v.findViewById<View>(R.id.edit_cover_crop)
        var source: Bitmap? = null
        fun busy(on: Boolean) {
            coverChange.isEnabled = !on
            coverCrop.isEnabled = !on
            save.isEnabled = !on
            coverProgress.visibility = if (on) View.VISIBLE else View.GONE
        }
        fun background(work: () -> Bitmap?, done: (Bitmap) -> Unit) {
            busy(true)
            io.execute {
                val result = runCatching { work() }.getOrNull()
                v.post {
                    busy(false)
                    if (result == null) Toast.makeText(context, R.string.edit_cover_failed, Toast.LENGTH_SHORT).show()
                    else done(result)
                }
            }
        }
        fun crop(from: Bitmap) {
            CoverCropper.show(context, from) { cropped ->
                background({ finishCover(cropped, pendingFile) }) { small ->
                    pending = small
                    preview.setImageBitmap(small)
                    preview.visibility = View.VISIBLE
                    if (warning.visibility != View.VISIBLE) {
                        warning.alpha = 0f
                        warning.visibility = View.VISIBLE
                        warning.animate().alpha(1f).setDuration(250).start()
                    }
                }
            }
        }
        book.customCover?.let { File(it) }?.takeIf { it.isFile }?.let { coverCrop.visibility = View.VISIBLE }
        val pick = View.OnClickListener {
            pickImage { uri ->
                uri ?: return@pickImage
                background({ decodeCover(context, uri) }) { full ->
                    source = full
                    coverCrop.visibility = View.VISIBLE
                    crop(full)
                }
            }
        }
        coverCrop.setOnClickListener {
            source?.let { crop(it) } ?: book.customCover?.let { path ->
                background({ BitmapFactory.decodeFile(path) }) { full -> source = full; crop(full) }
            }
        }
        coverChange.setOnClickListener(pick)
        v.findViewById<View>(R.id.edit_cover_card).setOnClickListener(pick)
        dialog.setOnDismissListener { pendingFile.delete() }

        title.setText(book.title)
        authors.setText(book.authors.joinToString(", "))
        series.setText(book.details.series)
        index.setText(book.details.seriesIndex)
        tags.setText(book.details.subjects.joinToString(", "))

        v.findViewById<TextView>(R.id.edit_note).text =
            if (BookWriter.canEmbed(book.extension)) context.getString(R.string.edit_note_embed)
            else context.getString(R.string.edit_note_plain, book.extension)

        val refresh = {
            val t = title.text.toString().trim()
            tidy.visibility = if (tidyTitle(t) != t && t.isNotEmpty()) View.VISIBLE else View.GONE
            fileName.text = context.getString(
                R.string.edit_file_name,
                BookWriter.fileName(t, BookWriter.splitList(authors.text.toString()), book.extension, book.fileName),
            )
            titleLayout.error = null
            indexLayout.error = null
        }
        title.doAfterTextChanged { refresh() }
        authors.doAfterTextChanged { refresh() }
        index.doAfterTextChanged { indexLayout.error = null }
        refresh()

        tidy.setOnClickListener {
            title.setText(tidyTitle(title.text.toString()))
            title.setSelection(title.length())
        }

        revert.visibility = if (book.edited) View.VISIBLE else View.GONE
        revert.setOnClickListener {
            revert.isEnabled = false
            io.execute {
                val current = BookStore.get(bookId)
                val meta = current?.let { runCatching { BookMeta.read(File(it.path), it.fileName) }.getOrNull() }
                if (meta != null) BookStore.revertEdits(bookId, meta)
                v.post { dialog.dismiss() }
            }
        }

        v.findViewById<View>(R.id.edit_close).setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val t = title.text.toString().trim().replace(Regex("""\s+"""), " ")
            val i = BookWriter.normalizeIndex(index.text.toString())
            if (t.isEmpty()) { titleLayout.error = context.getString(R.string.edit_error_title); title.requestFocus(); return@setOnClickListener }
            if (i == null) { indexLayout.error = context.getString(R.string.edit_error_index); index.requestFocus(); return@setOnClickListener }
            val s = series.text.toString().trim().replace(Regex("""\s+"""), " ")
            BookStore.edit(bookId, BookWriter.Edit(
                title = t,
                authors = BookWriter.splitList(authors.text.toString()),
                series = s,
                seriesIndex = if (s.isEmpty()) "" else i,
                tags = BookWriter.splitList(tags.text.toString()),
            ), pending?.takeIf { pendingFile.isFile }?.let { pendingFile to it })
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun decodeCover(context: Context, uri: Uri): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > SOURCE_MAX) decoder.setTargetSize(info.size.width * SOURCE_MAX / long, info.size.height * SOURCE_MAX / long)
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SOURCE_MAX) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } ?: return null
        return Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
        }
    }

    private fun finishCover(cropped: Bitmap, out: File): Bitmap {
        val flat = if (cropped.height <= COVER_MAX) cropped
            else Bitmap.createScaledBitmap(cropped, (cropped.width.toLong() * COVER_MAX / cropped.height).toInt().coerceAtLeast(1), COVER_MAX, true)
        FileOutputStream(out).use { flat.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val h = 600
        return if (flat.height <= h) flat
        else Bitmap.createScaledBitmap(flat, (flat.width.toLong() * h / flat.height).toInt().coerceAtLeast(1), h, true)
    }

    internal fun tidyTitle(raw: String): String {
        var s = raw.trim()
            .replace(Regex("""\.(epub|pdf|mobi|azw3?|fb2)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[\[(][^\])]*(z-?lib|1lib|libgen|anna)[^\])]*[\])]""", RegexOption.IGNORE_CASE), "")
        if (' ' !in s || '_' in s) s = s.replace(Regex("""[-_.]+"""), " ").replace(JUNK, "")
        s = s.replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_', '.')
        if (s.isEmpty()) return raw.trim()
        if (s == s.lowercase() || s == s.uppercase()) {
            s = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        }
        return s
    }
}
