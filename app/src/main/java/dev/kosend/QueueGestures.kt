package dev.kosend

import android.view.HapticFeedbackConstants
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class QueueGestures(
    private val adapter: QueueAdapter,
    private val onDragging: (Boolean) -> Unit,
    private val onDropped: (List<String>) -> Unit,
) : ItemTouchHelper.Callback() {
    private var dragging = false

    override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
        val book = adapter.bookAt(vh.bindingAdapterPosition) ?: return 0
        if (book.status == BookStatus.SENDING) return 0
        return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
        if (adapter.bookAt(to.bindingAdapterPosition) == null) return false
        adapter.moveItem(from.bindingAdapterPosition, to.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(vh, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
            dragging = true
            onDragging(true)
            vh.itemView.findViewById<MaterialCardView>(R.id.card)?.isDragged = true
            vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        super.clearView(rv, vh)
        vh.itemView.findViewById<MaterialCardView>(R.id.card)?.isDragged = false
        if (dragging) {
            dragging = false
            onDragging(false)
            onDropped(adapter.bookIds())
        }
    }
}
