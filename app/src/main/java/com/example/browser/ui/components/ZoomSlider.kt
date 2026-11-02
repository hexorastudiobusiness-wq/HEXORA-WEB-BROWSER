package com.example.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.browser.R
import kotlin.math.roundToInt

/** Smooth 50%..200% text-zoom slider with a one-tap reset to 100%. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoomSlider(
    percent: Int,
    onPercentCommitted: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderState = remember {
        SliderState(
            // No steps: the thumb follows the finger continuously instead of
            // snapping in coarse 10% jumps.
            value = percent.coerceIn(50, 200).toFloat(),
            steps = 0,
            valueRange = 50f..200f,
        )
    }
    sliderState.onValueChangeFinished = {
        onPercentCommitted(sliderState.value.roundToInt())
    }

    // Keep the thumb in sync when the persisted value changes elsewhere
    // (for example via the reset button in the other zoom UI).
    LaunchedEffect(percent) {
        val target = percent.coerceIn(50, 200).toFloat()
        if (sliderState.value != target) sliderState.value = target
    }

    val displayed = sliderState.value.roundToInt()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Slider(
            state = sliderState,
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
            thumb = {
                Surface(
                    modifier = Modifier.size(20.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 5.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary),
                        )
                    }
                }
            },
            track = { state ->
                val fraction = (
                    (state.value - state.valueRange.start) /
                        (state.valueRange.endInclusive - state.valueRange.start)
                    ).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            },
        )
        IconButton(
            onClick = {
                sliderState.value = 100f
                onPercentCommitted(100)
            },
            enabled = displayed != 100,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = stringResource(R.string.action_reset_zoom),
                modifier = Modifier.size(18.dp),
                tint = if (displayed == 100) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Text(
            text = "$displayed%",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}
