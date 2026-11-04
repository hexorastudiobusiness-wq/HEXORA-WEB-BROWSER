package com.example.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.browser.data.settings.SitePreferencesStore
import kotlin.math.roundToInt

/**
 * Floating, translucent zoom control for the page in front of the user.
 *
 * Why this shape:
 * - It is docked to the **right edge** and only ~320dp tall, vertically
 *   centred, so it sits under the right thumb and can be dragged one-handed
 *   without stretching or reaching to the bottom of the screen.
 * - The scrim is barely tinted: the page stays visible and reflows live while
 *   the slider moves, which is how Chrome's quick zoom behaves.
 * - The value is per site (see [SitePreferencesStore]); "100%" resets it.
 *
 * Nothing here writes to the page: it reports a percent and the ViewModel
 * applies it to the engine.
 */
@Composable
fun SiteZoomPanel(
    percent: Int,
    onPercentChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val min = SitePreferencesStore.MIN_ZOOM
    val step = 5
    val max = SitePreferencesStore.MAX_ZOOM
    val clamped = percent.coerceIn(min, max)

    Box(Modifier.fillMaxSize()) {
        // Tap outside to dismiss. Almost transparent on purpose.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
                .width(76.dp)
                .height(320.dp)
                // Consume taps on the panel so the scrim does not dismiss it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "$clamped%",
                    style = MaterialTheme.typography.labelLarge,
                )
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .width(48.dp)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumbSize = 30.dp
                    val travel = (maxHeight - thumbSize).coerceAtLeast(1.dp)
                    val ratio = (max - clamped).toFloat() / (max - min)
                    // Pixel height of the drag area, straight from the layout
                    // constraints: no Density scope needed inside gestures.
                    val trackPx = constraints.maxHeight.toFloat()

                    fun percentAt(y: Float): Int {
                        if (trackPx <= 0f) return clamped
                        val fraction = (y / trackPx).coerceIn(0f, 1f)
                        val raw = max - fraction * (max - min)
                        // 5% steps: every change reflows the page, so a
                        // pixel-precise value behind Gecko's back for every
                        // drag event would only make the drag stutter. 75%,
                        // 100% and 125% are all still reachable.
                        return ((raw / step).roundToInt() * step).coerceIn(min, max)
                    }

                    // Track
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                    )
                    // Filled part above the thumb hints at "bigger text up".
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .width(6.dp)
                            .height(travel * ratio)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    )
                    // Thumb
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = travel * ratio)
                            .size(thumbSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    // Gesture surface on top of all of it.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(min, max) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        onPercentChange(percentAt(offset.y))
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        onPercentChange(percentAt(change.position.y))
                                    },
                                )
                            }
                            .pointerInput(min, max) {
                                detectTapGestures { offset ->
                                    onPercentChange(percentAt(offset.y))
                                }
                            },
                    )
                }
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = "100%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
