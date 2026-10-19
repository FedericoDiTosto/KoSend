package dev.kosend

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class SwipeToRemove(
    private val list: RecyclerView,
    private val adapter: QueueAdapter,
    private val isDragging: () -> Boolean,
    private val onRemove: (Book) -> Unit,
) : RecyclerView.OnItemTouchListener {
    private val slop = ViewConfiguration.get(list.context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(list.context).scaledMinimumFlingVelocity * 6
    private val actionWidth = list.resources.getDimensionPixelSize(R.dimen.swipe_action_width).toFloat()

    var openId: String? = null
        private set

    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var swiping: RecyclerView.ViewHolder? = null
    private var candidate: RecyclerView.ViewHolder? = null
    private var swallowTap = false
    private var velocity: VelocityTracker? = null

    init {
        list.addOnItemTouchListener(this)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) close()
            }
        })
    }

    fun bind(holder: RecyclerView.ViewHolder, book: Book) {
        val card = holder.card() ?: return
        card.animate().cancel()
        val open = book.id == openId && book.status != BookStatus.SENDING
        card.translationX = if (open) -actionWidth else 0f
        holder.removeLayer()?.visibility = if (open) View.VISIBLE else View.INVISIBLE
        holder.itemView.findViewById<View>(R.id.remove_action)?.setOnClickListener { remove(holder) }
    }

    fun close() {
        val id = openId ?: return
        openId = null
        holderFor(id)?.let { settle(it, 0f) }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                swallowTap = false
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                velocity?.addMovement(e)
                val holder = rv.findChildViewUnder(e.x, e.y)?.let { rv.getChildViewHolder(it) }
                candidate = holder?.takeIf { swipeable(it) }
                val open = openId?.let { holderFor(it) }
                if (open != null) {
                    val onAction = holder === open && e.x >= open.itemView.right - actionWidth
                    if (!onAction) {
                        close()
                        swallowTap = true
                        if (holder !== open) candidate = holder?.takeIf { swipeable(it) }
                    }
                }
                return swallowTap
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val holder = candidate ?: return false
                val dx = e.x - downX
                val dy = e.y - downY
                if (!isDragging() && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    swiping = holder
                    startTx = holder.card()?.translationX ?: 0f
                    if (openId != null && adapter.bookAt(holder.bindingAdapterPosition)?.id != openId) close()
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                    holder.removeLayer()?.visibility = View.VISIBLE
                    downX = e.x
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> candidate = null
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        velocity?.addMovement(e)
        val holder = swiping ?: return
        val card = holder.card() ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                card.translationX = (startTx + e.x - downX).coerceIn(-card.width.toFloat(), 0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val tx = card.translationX
                swiping = null
                candidate = null
                when {
                    e.actionMasked == MotionEvent.ACTION_CANCEL -> settle(holder, if (tx < -actionWidth / 2) -actionWidth else 0f)
                    tx < -card.width * 0.55f || (vx < -flingVelocity && tx < -actionWidth) -> remove(holder)
                    vx > flingVelocity || tx > -actionWidth / 2 -> {
                        if (openId == idOf(holder)) openId = null
                        settle(holder, 0f)
                    }
                    else -> {
                        openId = idOf(holder)
                        settle(holder, -actionWidth)
                    }
                }
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun remove(holder: RecyclerView.ViewHolder) {
        val book = adapter.bookAt(holder.bindingAdapterPosition) ?: return
        val card = holder.card() ?: return
        openId = null
        card.animate().translationX(-card.width.toFloat()).setDuration(160).withEndAction { onRemove(book) }.start()
    }

    private fun settle(holder: RecyclerView.ViewHolder, target: Float) {
        val card = holder.card() ?: return
        card.animate().translationX(target).setDuration(180).withEndAction {
            if (target == 0f) holder.removeLayer()?.visibility = View.INVISIBLE
        }.start()
    }

    private fun swipeable(holder: RecyclerView.ViewHolder): Boolean =
        holder.card() != null && adapter.bookAt(holder.bindingAdapterPosition)?.status?.let { it != BookStatus.SENDING } == true

    private fun idOf(holder: RecyclerView.ViewHolder) = adapter.bookAt(holder.bindingAdapterPosition)?.id

    private fun holderFor(id: String): RecyclerView.ViewHolder? {
        val position = adapter.bookIds().indexOf(id).takeIf { it >= 0 } ?: return null
        return list.findViewHolderForAdapterPosition(position + 1)
    }

    private fun RecyclerView.ViewHolder.card(): View? = itemView.findViewById(R.id.card)

    private fun RecyclerView.ViewHolder.removeLayer(): View? = itemView.findViewById(R.id.remove_layer)
}
