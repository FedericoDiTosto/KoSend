package dev.kosend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

object CoverCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, path: String?, maxHeight: Int, onShown: (Boolean) -> Unit) {
        val key = "$path@$maxHeight"
        view.tag = key
        view.animate().cancel()
        view.alpha = 1f
        val cached = path?.let { cache.get(key) }
        view.setImageBitmap(cached)
        onShown(cached != null)
        if (path == null || cached != null) return
        io.execute {
            val bmp = runCatching { decode(path, maxHeight) }.getOrNull()
            main.post {
                if (bmp != null) cache.put(key, bmp)
                if (view.tag == key && bmp != null) {
                    view.alpha = 0f
                    view.setImageBitmap(bmp)
                    view.animate().alpha(1f).setDuration(150).start()
                    onShown(true)
                }
            }
        }
    }

    fun evict(path: String) {
        cache.snapshot().keys.filter { it.startsWith("$path@") }.forEach { cache.remove(it) }
    }

    private fun decode(path: String, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
