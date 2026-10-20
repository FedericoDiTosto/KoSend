package dev.kosend

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object BookDetail {
    fun show(
        context: Context, bookId: String,
        onSendAgain: (Book) -> Unit, onRemove: (Book) -> Unit, pickImage: ((Uri?) -> Unit) -> Unit,
    ) {
        val book = BookStore.get(bookId) ?: return
        val dialog = AppCompatDialog(context, R.style.BookDetailDialog)
        val v = LayoutInflater.from(context).inflate(R.layout.dialog_book, null)
        dialog.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            val light = context.resources.getBoolean(R.bool.light_bars)
            WindowCompat.getInsetsController(w, v).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }

        val topBarContent = v.findViewById<View>(R.id.top_bar_content)
        val scroll = v.findViewById<NestedScrollView>(R.id.scroll)
        val content = scroll.getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBarContent.updatePadding(top = bars.top + dp(context, 8))
            content.updatePadding(bottom = bars.bottom)
            insets
        }
        v.findViewById<View>(R.id.close).setOnClickListener { dialog.dismiss() }

        val glass = android.graphics.drawable.ColorDrawable(
            MaterialColors.getColor(v, com.google.android.material.R.attr.colorSurfaceContainerLow)
        ).apply { alpha = 0 }
        v.findViewById<View>(R.id.top_bar).background = glass
        val line = v.findViewById<View>(R.id.top_bar_line)
        val barTitle = v.findViewById<TextView>(R.id.top_bar_title)
        val title = v.findViewById<View>(R.id.title)
        barTitle.text = book.title
        scroll.setOnScrollChangeListener { _: NestedScrollView, _: Int, y: Int, _: Int, _: Int ->
            val start = v.findViewById<View>(R.id.cover_card).top.coerceAtLeast(1)
            val fade = (y.toFloat() / start).coerceIn(0f, 1f)
            glass.alpha = (fade * 0.82f * 255).toInt()
            line.alpha = fade
            val titleBottom = title.bottom + (title.parent as View).top
            barTitle.alpha = ((y + topBarContent.height - titleBottom).toFloat() / dp(context, 24)).coerceIn(0f, 1f)
        }

        bind(context, v, book)
        v.findViewById<View>(R.id.edit).setOnClickListener { BookEditor.show(context, bookId, pickImage) }

        var shown = book
        val bindStatus = {
            val b = BookStore.get(bookId)
            if (b == null) dialog.dismiss() else {
                if (b.title != shown.title || b.authors != shown.authors || b.details != shown.details ||
                    b.edited != shown.edited || b.cover != shown.cover || b.customCover != shown.customCover) {
                    shown = b
                    barTitle.text = b.title
                    bindHeader(context, v, b)
                    bindAbout(context, v, b)
                    bindDetails(context, v, b)
                }
                bindStatus(context, v, b, onSendAgain) { dialog.dismiss(); onRemove(b) }
            }
        }
        bindStatus()
        BookStore.addListener(bindStatus)
        dialog.setOnDismissListener { BookStore.removeListener(bindStatus) }
        dialog.show()
    }

   internal fun bind(context: Context, v: View, book: Book) {
        bindHeader(context, v, book)
        bindAbout(context, v, book)
        bindDetails(context, v, book)
        bindStatus(context, v, book, {}) {}
    }

    private fun bindHeader(context: Context, v: View, book: Book) {
        v.findViewById<CoverView>(R.id.cover).apply {
            val backdrop = v.findViewById<ImageView>(R.id.backdrop)
            onCoverShown = { shown -> if (shown) showBackdrop(backdrop, book) }
            bind(book, 234)
        }
        v.findViewById<TextView>(R.id.title).text = book.title
        v.findViewById<TextView>(R.id.authors).showText(book.authors.joinToString())
        val d = book.details
        v.findViewById<TextView>(R.id.series).showText(when {
            d.series.isEmpty() -> ""
            d.seriesIndex.isEmpty() -> d.series
            else -> context.getString(R.string.meta_series, d.series, d.seriesIndex)
        })

        val facts = v.findViewById<ChipGroup>(R.id.facts)
        facts.removeAllViews()
        listOf(book.extension.uppercase(), formatSize(context, book.size), languageName(d.language), year(d.published))
            .filter { it.isNotBlank() }
            .forEach { facts.addView(chip(context, it)) }
    }

    private fun showBackdrop(backdrop: ImageView, book: Book) {
        CoverCache.load(backdrop, book.cover, 200) {}
        if (Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
        } else {
            backdrop.alpha = 0.2f
        }
    }

    private fun bindAbout(context: Context, v: View, book: Book) {
        val d = book.details
        v.findViewById<View>(R.id.about_section).visibility =
            if (d.description.isBlank() && d.subjects.isEmpty()) View.GONE else View.VISIBLE

        val description = v.findViewById<TextView>(R.id.description)
        val more = v.findViewById<MaterialButton>(R.id.description_more)
        description.showText(d.description)
        more.visibility = View.GONE
        if (d.description.isNotBlank()) {
            description.post {
                val cut = description.layout?.let { it.lineCount > 0 && it.getEllipsisCount(it.lineCount - 1) > 0 } == true
                more.visibility = if (cut) View.VISIBLE else View.GONE
            }
            val toggle = View.OnClickListener {
                val expanded = description.maxLines == Int.MAX_VALUE
                description.maxLines = if (expanded) 5 else Int.MAX_VALUE
                more.setText(if (expanded) R.string.show_more else R.string.show_less)
            }
            more.setOnClickListener(toggle)
            description.setOnClickListener(toggle)
        }

        val subjects = v.findViewById<ChipGroup>(R.id.subjects)
        subjects.removeAllViews()
        d.subjects.forEach { subjects.addView(chip(context, it)) }
        subjects.visibility = if (d.subjects.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindDetails(context: Context, v: View, book: Book) {
        val d = book.details
        val publication = listOf(
            R.string.meta_publisher to d.publisher,
            R.string.meta_published to formatPublished(d.published),
            R.string.meta_language to languageName(d.language),
            R.string.meta_isbn to d.isbn,
        )
        val shown = rows(context, v.findViewById(R.id.publication), publication)
        v.findViewById<View>(R.id.publication_section).visibility = if (shown) View.VISIBLE else View.GONE

        rows(context, v.findViewById(R.id.file), listOf(
            R.string.meta_file_name to book.fileName,
            R.string.meta_format to book.extension.uppercase(),
            R.string.meta_size to formatSize(context, book.size),
            R.string.meta_added to if (book.addedAt > 0) DateUtils.formatDateTime(
                context, book.addedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
            ) else "",
        ))
    }

    private fun bindStatus(context: Context, v: View, b: Book, onSendAgain: (Book) -> Unit, onRemove: () -> Unit) {
        val iconBg = v.findViewById<View>(R.id.status_icon_bg)
        val icon = v.findViewById<ImageView>(R.id.status_icon)
        val status = v.findViewById<TextView>(R.id.status)
        val (iconRes, color, text) = statusDisplay(context, status, b)
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(color)
        iconBg.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x26))
        status.setTextColor(color)
        status.text = text

        val canResend = (b.status == BookStatus.ERROR || b.status == BookStatus.SENT) && b.fileExists
        v.findViewById<View>(R.id.edit).visibility =
            if (b.status != BookStatus.SENDING && b.fileExists) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.status_detail).showText(when (b.status) {
            BookStatus.PENDING -> context.getString(R.string.detail_pending) +
                if (b.edited) " " + context.getString(R.string.detail_edited) else ""
            BookStatus.SENDING -> context.getString(R.string.detail_sending)
            BookStatus.SENT -> if (b.fileExists) context.getString(R.string.detail_sent_to, b.message.ifEmpty { "KOReader" })
                else context.getString(R.string.detail_sent_deleted)
            BookStatus.ERROR -> if (b.fileExists) context.getString(R.string.detail_error) else ""
        })

        val progress = v.findViewById<LinearProgressIndicator>(R.id.progress)
        progress.visibility = if (b.status == BookStatus.SENDING) View.VISIBLE else View.GONE
        if (b.status == BookStatus.SENDING) progress.setProgressCompat(b.progress, true)

        val sendAgain = v.findViewById<View>(R.id.send_again)
        val remove = v.findViewById<View>(R.id.remove)
        sendAgain.visibility = if (canResend) View.VISIBLE else View.GONE
        remove.visibility = if (b.status != BookStatus.SENDING) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.actions).visibility =
            if (canResend || b.status != BookStatus.SENDING) View.VISIBLE else View.GONE
        sendAgain.setOnClickListener { onSendAgain(b) }
        remove.setOnClickListener { onRemove() }
    }

    private fun rows(context: Context, container: LinearLayout, items: List<Pair<Int, String>>): Boolean {
        val inflater = LayoutInflater.from(context)
        container.removeAllViews()
        val visible = items.filter { it.second.isNotBlank() }
        visible.forEachIndexed { i, (label, value) ->
            if (i > 0) container.addView(View(context).apply {
                setBackgroundColor(MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutlineVariant))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(context, 20); marginEnd = dp(context, 20)
                }
            })
            val row = inflater.inflate(R.layout.item_detail, container, false)
            row.findViewById<TextView>(R.id.label).setText(label)
            row.findViewById<TextView>(R.id.value).text = value
            container.addView(row)
        }
        return visible.isNotEmpty()
    }

    private fun chip(context: Context, text: String) = Chip(context).apply {
        this.text = text
        isClickable = false
        isCheckable = false
        isFocusable = false
        chipBackgroundColor = ColorStateList.valueOf(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0)
        )
        chipStrokeWidth = 0f
        setEnsureMinTouchTargetSize(false)
    }

    private fun TextView.showText(value: String) {
        text = value
        visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun formatPublished(raw: String): String {
        if (raw.isBlank() || raw.startsWith("0")) return ""
        return runCatching {
            LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        }.getOrElse { year(raw).ifEmpty { raw } }
    }

    private fun year(raw: String): String =
        raw.take(4).takeIf { y -> y.length == 4 && y.all { it.isDigit() } && !y.startsWith("0") }.orEmpty()

    private fun languageName(code: String): String {
        if (code.isBlank() || code == "und") return ""
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        return name.ifBlank { code }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
