package dev.kosend

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class CoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val placeholder: View
    private val spine: View
    private val title: TextView
    private val author: TextView
    private val format: TextView
    private val image: ImageView

    var onCoverShown: (Boolean) -> Unit = {}

    init {
        LayoutInflater.from(context).inflate(R.layout.view_cover, this, true)
        placeholder = findViewById(R.id.placeholder)
        spine = findViewById(R.id.placeholder_spine)
        title = findViewById(R.id.placeholder_title)
        author = findViewById(R.id.placeholder_author)
        format = findViewById(R.id.placeholder_format)
        image = findViewById(R.id.cover_image)
        val radius = 6 * resources.displayMetrics.density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }

    fun bind(book: Book, heightDp: Int) {
        val density = resources.displayMetrics.density
        placeholder.tint(badgeColor(book.extension))
        val large = heightDp >= 110
        title.visibility = if (large) View.VISIBLE else View.GONE
        author.visibility = if (large) View.VISIBLE else View.GONE
        title.text = book.title
        author.text = book.authors.joinToString()
        title.maxLines = 5
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, (heightDp / 12f).coerceIn(11f, 20f))
        author.setTextSize(TypedValue.COMPLEX_UNIT_SP, (heightDp / 18f).coerceIn(9f, 14f))
        format.text = book.extension.uppercase().take(4)
        format.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        format.setTextColor(if (large) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt())
        val pad = ((if (large) 12 else 2) * density).toInt()
        placeholder.setPadding(pad + (4 * density).toInt(), pad, pad, pad)

        CoverCache.load(image, book.cover, (heightDp * density).toInt()) { shown ->
            image.visibility = if (shown) View.VISIBLE else View.GONE
            placeholder.visibility = if (shown) View.INVISIBLE else View.VISIBLE
            spine.visibility = placeholder.visibility
            onCoverShown(shown)
        }
    }
}
