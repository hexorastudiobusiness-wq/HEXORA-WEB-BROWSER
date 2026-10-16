package com.example.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Small private on-disk cache for Start Page shortcut site icons. */
class ShortcutIconStore(context: Context) {

    private val dir: File =
        File(context.applicationContext.filesDir, "start_shortcut_icons").apply { mkdirs() }

    fun save(shortcutId: Long, bitmap: Bitmap) {
        runCatching {
            fileFor(shortcutId).outputStream().use { out ->
                scaleDown(bitmap).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    fun load(shortcutId: Long): Bitmap? {
        val file = fileFor(shortcutId)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun delete(shortcutId: Long) {
        runCatching { fileFor(shortcutId).delete() }
    }

    private fun fileFor(shortcutId: Long): File = File(dir, "$shortcutId.png")

    private fun scaleDown(bitmap: Bitmap, maxSize: Int = 128): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
