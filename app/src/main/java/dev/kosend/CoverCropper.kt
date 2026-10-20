package dev.kosend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup

object CoverCropper {
    private class Ratio(val label: Int, val value: (Bitmap) -> Float?)

    private val RATIOS = listOf(
        Ratio(R.string.crop_book) { 2f / 3f },
        Ratio(R.string.crop_kindle) { 5f / 8f },
        Ratio(R.string.crop_kobo) { 3f / 4f },
        Ratio(R.string.crop_square) { 1f },
        Ratio(R.string.crop_original) { it.width.toFloat() / it.height },
        Ratio(R.string.crop_free) { null },
    )

    fun show(context: Context, source: Bitmap, onDone: (Bitmap) -> Unit) {
        val dialog = AppCompatDialog(context, R.style.BookDetailDialog)
        val v = LayoutInflater.from(context).inflate(R.layout.dialog_crop, null)
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
        val bar = v.findViewById<View>(R.id.crop_bar)
        val ratios = v.findViewById<View>(R.id.crop_ratios_scroll)
        val barTop = bar.paddingTop
        val ratiosBottom = ratios.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bar.updatePadding(top = bars.top + barTop)
            ratios.updatePadding(bottom = bars.bottom + ratiosBottom)
            insets
        }

        var bitmap = source
        var selected = RATIOS.first()
        val crop = v.findViewById<CropView>(R.id.crop_view)
        val size = v.findViewById<TextView>(R.id.crop_size)
        crop.onChanged = {
            crop.cropRect()?.let { size.text = context.getString(R.string.crop_size, it.width(), it.height()) }
        }
        crop.setBitmap(bitmap)
        crop.setRatio(selected.value(bitmap))

        val group = v.findViewById<ChipGroup>(R.id.crop_ratios)
        RATIOS.forEach { ratio ->
            group.addView(Chip(context).apply {
                setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter))
                setText(ratio.label)
                isCheckable = true
                isChecked = ratio === selected
                setOnClickListener {
                    selected = ratio
                    crop.setRatio(ratio.value(bitmap))
                }
            })
        }

        v.findViewById<View>(R.id.crop_rotate).setOnClickListener {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
            crop.setBitmap(bitmap)
            crop.setRatio(selected.value(bitmap))
        }
        v.findViewById<View>(R.id.crop_close).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.crop_done).setOnClickListener {
            val r = crop.cropRect() ?: return@setOnClickListener
            onDone(Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height()))
            dialog.dismiss()
        }
        dialog.show()
    }
}
