package com.example.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Tiny on-disk cache of tab preview thumbnails (one JPEG per tab id).
 * Keeps the tab switcher visual without holding bitmaps in memory.
 */
class TabThumbnailStore(context: Context) {

    private val dir: File =
        File(context.applicationContext.filesDir, "tab_thumbs").apply { mkdirs() }

    fun save(tabId: String, bitmap: Bitmap) {
        runCatching {
            fileFor(tabId).outputStream().use { out ->
                scaleDown(bitmap).compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
        }
    }

    fun load(tabId: String): Bitmap? {
        val file = fileFor(tabId)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun delete(tabId: String) {
        runCatching { fileFor(tabId).delete() }
    }

    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(tabId: String): File = File(dir, "$tabId.jpg")

    private fun scaleDown(bitmap: Bitmap, maxWidth: Int = 400): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap,
            maxWidth,
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
