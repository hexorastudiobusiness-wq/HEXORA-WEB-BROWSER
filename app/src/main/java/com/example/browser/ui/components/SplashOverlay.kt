package com.example.browser.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.browser.R
import kotlinx.coroutines.delay

/**
 * The full-screen splash.
 *
 * It is a designed image rather than a logo in the middle of a colour, and it
 * covers the whole window, which is what removes the two things the owner saw
 * on HyperOS: a black field, and a square artwork with sharp corners floating
 * in it. The system splash before it is set to the same colour the artwork
 * starts with, so the hand-over has no seam.
 *
 * The overlay is painted on top of the browser's first frame and fades out on
 * its own — nothing about it blocks the app, and there is no state to reset if
 * the process is recreated.
 */
@Composable
fun SplashOverlay() {
    var visible by remember { mutableStateOf(true) }
    var fade by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        // Long enough for the first page to have something on it, short enough
        // that it never feels like the app is waiting for itself.
        delay(SPLASH_HOLD_MS)
        while (fade > 0f) {
            fade = (fade - 0.12f).coerceAtLeast(0f)
            delay(FRAME_MS)
        }
        visible = false
    }

    if (!visible) return

    Image(
        painter = painterResource(R.drawable.splash_full),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade },
    )
}

private const val SPLASH_HOLD_MS = 520L
private const val FRAME_MS = 16L
