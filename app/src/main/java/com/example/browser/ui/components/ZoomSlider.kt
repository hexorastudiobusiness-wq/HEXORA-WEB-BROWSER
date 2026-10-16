package com.example.browser.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.browser.R
import kotlin.math.roundToInt

/** Slim 50%..200% text-zoom slider with a one-tap reset to 100%. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoomSlider(
    percent: Int,
    onPercentCommitted: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderState = remember {
        SliderState(
            value = percent.coerceIn(50, 200).toFloat(),
            steps = 14,
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

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Slider(
            state = sliderState,
            modifier = Modifier
                .weight(1f)
                .height(30.dp),
            thumb = {
                Surface(
                    modifier = Modifier.size(14.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 2.dp,
                ) {}
            },
            track = { state ->
                SliderDefaults.Track(
                    colors = SliderDefaults.colors(),
                    enabled = true,
                    sliderState = state,
                    modifier = Modifier.scale(scaleX = 1f, scaleY = 0.55f),
                )
            },
        )
        IconButton(
            onClick = {
                sliderState.value = 100f
                onPercentCommitted(100)
            },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = stringResource(R.string.action_reset_zoom),
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = "${sliderState.value.roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(42.dp),
        )
    }
}
