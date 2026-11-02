package com.example.browser.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.browser.MainActivity
import com.example.browser.R
import java.security.MessageDigest
import java.util.Locale

/**
 * "Add to Home screen": pins a shortcut that reopens the page in Hexora.
 *
 * GeckoView does not expose installable-PWA manifests, so this is a classic
 * launcher shortcut (own label + app icon), not a standalone web app.
 */
object HomeScreenShortcut {

    fun add(context: Context, title: String, url: String): Boolean {
        if (!UrlUtils.isHttpUrl(url)) return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val label = title.ifBlank { url }.take(30)
        val icon = appIconBitmap(context)
            ?.let(IconCompat::createWithBitmap)
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(url))
            .setShortLabel(label)
            .setLongLabel(title.ifBlank { url })
            .setIcon(icon)
            .setIntent(intent)
            .build()

        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
    }

    /**
     * Renders the launcher icon through the package manager. Decoding
     * [R.mipmap.ic_launcher] with BitmapFactory cannot follow the
     * adaptive-icon XML used on API 26+, so the old path silently fell back
     * to a 1×1 blank bitmap.
     */
    private fun appIconBitmap(context: Context): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val width = drawable.intrinsicWidth
            .takeIf { it > 0 } ?: 192
        val height = drawable.intrinsicHeight
            .takeIf { it > 0 } ?: 192
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()

    /** Stable, effectively collision-free per-URL shortcut id. */
    private fun shortcutId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString(separator = "") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF)
        }
        return "hexora-$hex"
    }
}
