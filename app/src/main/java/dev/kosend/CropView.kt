package dev.kosend

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val margin = 28 * density
    private val touchSlop = 28 * density
    private val minSize = 56 * density

    private val image = RectF()
    private val crop = RectF()
    private var bitmap: Bitmap? = null
    private var ratio: Float? = null

    private val dim = Paint().apply { color = 0xA6000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f * density; strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outside = Path()

    private enum class Drag { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    var onChanged: () -> Unit = {}

    val bitmapAspect: Float get() = bitmap?.let { it.width.toFloat() / it.height } ?: 1f

    fun setBitmap(b: Bitmap) {
        bitmap = b
        layoutImage()
        resetCrop()
    }

    fun setRatio(r: Float?) {
        ratio = r
        resetCrop()
    }

    fun cropRect(): Rect? {
        val b = bitmap ?: return null
        if (image.width() <= 0) return null
        val scale = b.width / image.width()
        return Rect(
            ((crop.left - image.left) * scale).toInt().coerceIn(0, b.width - 1),
            ((crop.top - image.top) * scale).toInt().coerceIn(0, b.height - 1),
            ((crop.right - image.left) * scale).toInt().coerceIn(1, b.width),
            ((crop.bottom - image.top) * scale).toInt().coerceIn(1, b.height),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val old = RectF(image)
        val rel = if (old.width() > 0) RectF(
            (crop.left - old.left) / old.width(), (crop.top - old.top) / old.height(),
            (crop.right - old.left) / old.width(), (crop.bottom - old.top) / old.height(),
        ) else null
        layoutImage()
        if (rel == null) resetCrop() else {
            crop.set(
                image.left + rel.left * image.width(), image.top + rel.top * image.height(),
                image.left + rel.right * image.width(), image.top + rel.bottom * image.height(),
            )
            invalidate()
        }
    }

    private fun layoutImage() {
        val b = bitmap ?: return
        val aw = width - 2 * margin
        val ah = height - 2 * margin
        if (aw <= 0 || ah <= 0) return
        val scale = min(aw / b.width, ah / b.height)
        val w = b.width * scale
        val h = b.height * scale
        image.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun resetCrop() {
        if (image.width() <= 0) { invalidate(); return }
        val r = ratio
        if (r == null) {
            crop.set(image)
        } else {
            var w = image.width()
            var h = w / r
            if (h > image.height()) { h = image.height(); w = h * r }
            crop.set(image.centerX() - w / 2, image.centerY() - h / 2, image.centerX() + w / 2, image.centerY() + h / 2)
        }
        invalidate()
        onChanged()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, image, bitmapPaint)
        outside.reset()
        outside.fillType = Path.FillType.EVEN_ODD
        outside.addRect(image, Path.Direction.CW)
        outside.addRect(crop, Path.Direction.CW)
        canvas.drawPath(outside, dim)

        for (i in 1..2) {
            val x = crop.left + crop.width() * i / 3
            val y = crop.top + crop.height() * i / 3
            canvas.drawLine(x, crop.top, x, crop.bottom, grid)
            canvas.drawLine(crop.left, y, crop.right, y, grid)
        }
        canvas.drawRect(crop, frame)

        val l = min(22 * density, min(crop.width(), crop.height()) / 3)
        val o = handle.strokeWidth / 2
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            canvas.drawLine(x - dx * o, y - dy * o, x + dx * l, y - dy * o, handle)
            canvas.drawLine(x - dx * o, y - dy * o, x - dx * o, y + dy * l, handle)
        }
        corner(crop.left, crop.top, 1f, 1f)
        corner(crop.right, crop.top, -1f, 1f)
        corner(crop.left, crop.bottom, 1f, -1f)
        corner(crop.right, crop.bottom, -1f, -1f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = target(e.x, e.y)
                lastX = e.x; lastY = e.y
                if (drag != Drag.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return false
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (drag == Drag.MOVE) move(dx, dy) else resize(e.x, e.y)
                invalidate()
                onChanged()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun target(x: Float, y: Float): Drag {
        val nearL = abs(x - crop.left) < touchSlop
        val nearR = abs(x - crop.right) < touchSlop
        val nearT = abs(y - crop.top) < touchSlop
        val nearB = abs(y - crop.bottom) < touchSlop
        val inX = x > crop.left - touchSlop && x < crop.right + touchSlop
        val inY = y > crop.top - touchSlop && y < crop.bottom + touchSlop
        return when {
            nearL && nearT -> Drag.TOP_LEFT
            nearR && nearT -> Drag.TOP_RIGHT
            nearL && nearB -> Drag.BOTTOM_LEFT
            nearR && nearB -> Drag.BOTTOM_RIGHT
            ratio == null && nearL && inY -> Drag.LEFT
            ratio == null && nearR && inY -> Drag.RIGHT
            ratio == null && nearT && inX -> Drag.TOP
            ratio == null && nearB && inX -> Drag.BOTTOM
            crop.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun move(dx: Float, dy: Float) {
        val ox = dx.coerceIn(image.left - crop.left, image.right - crop.right)
        val oy = dy.coerceIn(image.top - crop.top, image.bottom - crop.bottom)
        crop.offset(ox, oy)
    }

    private fun resize(x: Float, y: Float) {
        val r = ratio
        if (r == null) {
            when (drag) {
                Drag.LEFT, Drag.TOP_LEFT, Drag.BOTTOM_LEFT -> crop.left = x.coerceIn(image.left, crop.right - minSize)
                Drag.RIGHT, Drag.TOP_RIGHT, Drag.BOTTOM_RIGHT -> crop.right = x.coerceIn(crop.left + minSize, image.right)
                else -> {}
            }
            when (drag) {
                Drag.TOP, Drag.TOP_LEFT, Drag.TOP_RIGHT -> crop.top = y.coerceIn(image.top, crop.bottom - minSize)
                Drag.BOTTOM, Drag.BOTTOM_LEFT, Drag.BOTTOM_RIGHT -> crop.bottom = y.coerceIn(crop.top + minSize, image.bottom)
                else -> {}
            }
            return
        }
        val left = drag == Drag.TOP_LEFT || drag == Drag.BOTTOM_LEFT
        val top = drag == Drag.TOP_LEFT || drag == Drag.TOP_RIGHT
        val ax = if (left) crop.right else crop.left
        val ay = if (top) crop.bottom else crop.top
        val maxW = min(if (left) ax - image.left else image.right - ax, (if (top) ay - image.top else image.bottom - ay) * r)
        val wanted = max(abs(x - ax), abs(y - ay) * r)
        val w = wanted.coerceIn(min(minSize, maxW), maxW)
        val h = w / r
        crop.set(
            if (left) ax - w else ax, if (top) ay - h else ay,
            if (left) ax else ax + w, if (top) ay else ay + h,
        )
    }
}
