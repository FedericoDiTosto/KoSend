package dev.kosend

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

class QueueAdapter(
    private val context: Context,
    private var infoExpanded: Boolean,
    private val onPowerToggled: (Boolean) -> Unit,
    private val onEject: () -> Unit,
    private val onClearSent: () -> Unit,
    private val onInfoToggled: (Boolean) -> Unit,
    private val onBookTapped: (Book) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onRetry: (Book) -> Unit = {},
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
    private val onBindBook: (RecyclerView.ViewHolder, Book) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var books: List<Book> = emptyList()
    private var state: CalibreServer.State = CalibreServer.State.Stopped
    private var ip: String? = null

    fun update(books: List<Book>, state: CalibreServer.State, ip: String?) {
        val old = this.books
        this.books = books
        this.state = state
        this.ip = ip
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = 1 + maxOf(old.size, 1)
            override fun getNewListSize() = 1 + maxOf(books.size, 1)

            override fun areItemsTheSame(o: Int, n: Int) = when {
                o == 0 || n == 0 -> o == n
                old.isEmpty() || books.isEmpty() -> old.isEmpty() && books.isEmpty()
                else -> old[o - 1].id == books[n - 1].id
            }

            override fun areContentsTheSame(o: Int, n: Int) = o != 0 && old.getOrNull(o - 1) == books.getOrNull(n - 1)
        }).dispatchUpdatesTo(this)
    }

    fun bookAt(position: Int): Book? = if (position > 0) books.getOrNull(position - 1) else null

    fun bookIds(): List<String> = books.map { it.id }

    fun moveItem(from: Int, to: Int) {
        if (bookAt(from) == null || bookAt(to) == null) return
        books = books.toMutableList().apply { add(to - 1, removeAt(from - 1)) }
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = 1 + maxOf(books.size, 1)

    override fun getItemViewType(position: Int) = when {
        position == 0 -> TYPE_HEADER
        books.isEmpty() -> TYPE_EMPTY
        else -> TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
            TYPE_EMPTY -> object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_empty, parent, false)) {}
            else -> BookHolder(inflater.inflate(R.layout.item_book, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind()
            is BookHolder -> holder.bind(books[position - 1])
        }
    }

    private inner class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val iconBg: View = v.findViewById(R.id.status_icon_bg)
        private val icon: ImageView = v.findViewById(R.id.status_icon)
        private val title: TextView = v.findViewById(R.id.status_title)
        private val detail: TextView = v.findViewById(R.id.status_detail)
        private val power: MaterialSwitch = v.findViewById(R.id.power)
        private val eject: MaterialButton = v.findViewById(R.id.eject)
        private val infoHeader: View = v.findViewById(R.id.info_header)
        private val infoBody: View = v.findViewById(R.id.info_body)
        private val chevron: ImageView = v.findViewById(R.id.info_chevron)
        private val note: TextView = v.findViewById(R.id.info_note)
        private val count: TextView = v.findViewById(R.id.queue_count)
        private val clearSent: MaterialButton = v.findViewById(R.id.clear_sent)

        init {
            val steps = listOf(R.id.step1 to R.string.info_step1, R.id.step2 to R.string.info_step2, R.id.step3 to R.string.info_step3)
            steps.forEachIndexed { i, (id, text) ->
                val step = v.findViewById<View>(id)
                step.findViewById<TextView>(R.id.step_number).text = (i + 1).toString()
                step.findViewById<TextView>(R.id.step_text).setText(text)
            }
            eject.setOnClickListener { onEject() }
            v.findViewById<View>(R.id.settings).setOnClickListener { onOpenSettings() }
            clearSent.setOnClickListener { onClearSent() }
            infoHeader.setOnClickListener {
                infoExpanded = !infoExpanded
                onInfoToggled(infoExpanded)
                bindInfo(animate = true)
            }
        }

        fun bind() {
            val s = state
            val running = s !is CalibreServer.State.Stopped
            power.setOnCheckedChangeListener(null)
            power.isChecked = running
            power.setOnCheckedChangeListener { _, on -> onPowerToggled(on) }

            val surfaceHigh = MaterialColors.getColor(iconBg, com.google.android.material.R.attr.colorSurfaceContainerHighest)
            val onVariant = MaterialColors.getColor(icon, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val primary = MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorPrimary)
            val primaryContainer = MaterialColors.getColor(iconBg, com.google.android.material.R.attr.colorPrimaryContainer)
            val (iconRes, bg, fg) = when (s) {
                CalibreServer.State.Stopped -> Triple(R.drawable.ic_power_settings_new, surfaceHigh, onVariant)
                is CalibreServer.State.Waiting -> Triple(R.drawable.ic_wifi, primaryContainer, primary)
                is CalibreServer.State.Connected -> Triple(R.drawable.ic_check_circle, context.getColor(R.color.success_container), context.getColor(R.color.success))
                is CalibreServer.State.Error -> Triple(R.drawable.ic_error, context.getColor(R.color.error_container), context.getColor(R.color.error))
            }
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(fg)
            iconBg.backgroundTintList = ColorStateList.valueOf(bg)

            when (s) {
                CalibreServer.State.Stopped -> {
                    title.setText(R.string.status_off_title)
                    detail.setText(R.string.status_off_detail)
                }
                is CalibreServer.State.Waiting -> {
                    title.setText(R.string.status_waiting_title)
                    detail.text = ip?.let { context.getString(R.string.status_waiting_detail, "$it:${s.port}") }
                        ?: context.getString(R.string.status_waiting_detail_noip, s.port)
                }
                is CalibreServer.State.Connected -> {
                    title.setText(R.string.status_connected_title)
                    detail.text = s.deviceName
                }
                is CalibreServer.State.Error -> {
                    title.setText(R.string.status_error_title)
                    detail.text = Messages.localize(context, s.message)
                }
            }
            eject.visibility = if (s is CalibreServer.State.Connected) View.VISIBLE else View.GONE

            val port = (s as? CalibreServer.State.Waiting)?.port
            note.text = if (ip != null && port != null) context.getString(R.string.info_note_ip, ip, port)
            else context.getString(R.string.info_note_noip)
            bindInfo()

            val pending = books.count { it.status == BookStatus.PENDING || it.status == BookStatus.SENDING }
            count.text = when {
                books.isEmpty() -> ""
                pending > 0 -> context.resources.getQuantityString(R.plurals.queue_pending, pending, pending)
                else -> context.resources.getQuantityString(R.plurals.queue_total, books.size, books.size)
            }
            clearSent.visibility = if (books.any { it.status == BookStatus.SENT }) View.VISIBLE else View.GONE
        }

        private fun bindInfo(animate: Boolean = false) {
            infoBody.visibility = if (infoExpanded) View.VISIBLE else View.GONE
            val angle = if (infoExpanded) 180f else 0f
            if (animate) chevron.animate().rotation(angle).setDuration(150).start() else chevron.rotation = angle
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class BookHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = v.findViewById(R.id.card)
        private val cover: CoverView = v.findViewById(R.id.cover)
        private val title: TextView = v.findViewById(R.id.title)
        private val subtitle: TextView = v.findViewById(R.id.subtitle)
        private val statusIcon: ImageView = v.findViewById(R.id.status_icon)
        private val status: TextView = v.findViewById(R.id.status)
        private val retry: MaterialButton = v.findViewById(R.id.retry)
        private val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
        private val handle: View = v.findViewById(R.id.drag_handle)

        init {
            handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }
        }

        fun bind(b: Book) {
            card.setOnClickListener { onBookTapped(b) }
            onBindBook(this, b)
            retry.setOnClickListener { onRetry(b) }
            title.text = b.title
            cover.onCoverShown = { shown ->
               val authors = b.authors.joinToString().ifEmpty { null }
                subtitle.text = listOfNotNull(authors, b.extension.uppercase().takeIf { shown }, formatSize(context, b.size))
                    .joinToString("  ·  ")
            }
            cover.bind(b, 64)

            val (iconRes, color, text) = statusDisplay(context, status, b)
            statusIcon.setImageResource(iconRes)
            statusIcon.imageTintList = ColorStateList.valueOf(color)
            status.setTextColor(color)
            status.text = text
            retry.visibility = if (b.status == BookStatus.ERROR && b.fileExists) View.VISIBLE else View.GONE
            handle.visibility = if (b.status == BookStatus.SENDING) View.INVISIBLE else View.VISIBLE

            if (b.status == BookStatus.SENDING) {
                progress.visibility = View.VISIBLE
                progress.setProgressCompat(b.progress, true)
            } else {
                progress.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_BOOK = 1
        private const val TYPE_EMPTY = 2
    }
}

internal fun statusDisplay(context: Context, view: View, b: Book): Triple<Int, Int, String> {
    val onVariant = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
    val primary = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
    return when (b.status) {
        BookStatus.PENDING -> Triple(R.drawable.ic_schedule, onVariant, context.getString(R.string.book_pending))
        BookStatus.SENDING -> Triple(R.drawable.ic_upload, primary, context.getString(R.string.book_sending, b.progress))
        BookStatus.SENT -> Triple(R.drawable.ic_check_circle, context.getColor(R.color.success), context.getString(R.string.book_sent))
        BookStatus.ERROR -> Triple(R.drawable.ic_error, context.getColor(R.color.error), b.message.ifEmpty { context.getString(R.string.book_error) }.let { Messages.localize(context, it) })
    }
}
