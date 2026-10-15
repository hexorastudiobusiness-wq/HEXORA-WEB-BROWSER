package com.example.browser.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.browser.MainActivity
import com.example.browser.R

/**
 * "Add to Home screen": pins a shortcut that reopens the page in Hexora.
 *
 * GeckoView does not expose installable-PWA manifests, so this is a classic
 * launcher shortcut (own label + app icon), not a standalone web app.
 */
object HomeScreenShortcut {

    fun add(context: Context, title: String, url: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val label = title.ifBlank { url }.take(30)
        val shortcut = ShortcutInfoCompat.Builder(context, "hexora-${url.hashCode()}")
            .setShortLabel(label)
            .setLongLabel(title.ifBlank { url })
            .setIcon(IconCompat.createWithBitmap(appIconBitmap(context)))
            .setIntent(intent)
            .build()

        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
    }

    private fun appIconBitmap(context: Context): Bitmap =
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
