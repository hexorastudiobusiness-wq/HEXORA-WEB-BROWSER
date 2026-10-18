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

    /** Recently shown previews, kept decoded so opening the tab grid is instant. */
    private val memoryCache = object : LinkedHashMap<String, Bitmap>(
        MAX_MEMORY_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?,
        ): Boolean = size > MAX_MEMORY_ENTRIES
    }

    fun save(tabId: String, bitmap: Bitmap) {
        val scaled = scaleDown(bitmap)
        synchronized(memoryCache) { memoryCache[tabId] = scaled }
        runCatching {
            fileFor(tabId).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
        }
    }

    fun load(tabId: String): Bitmap? {
        synchronized(memoryCache) { memoryCache[tabId] }?.let { return it }
        val file = fileFor(tabId)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?.also { bitmap ->
                synchronized(memoryCache) { memoryCache[tabId] = bitmap }
            }
    }

    fun delete(tabId: String) {
        synchronized(memoryCache) { memoryCache.remove(tabId) }
        runCatching { fileFor(tabId).delete() }
    }

    fun clearAll() {
        synchronized(memoryCache) { memoryCache.clear() }
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(tabId: String): File = File(dir, "$tabId.jpg")

    private companion object {
        const val MAX_MEMORY_ENTRIES = 24
    }

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
