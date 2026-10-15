package com.example.browser.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.browser.R

/**
 * Address/search bar (ARCHITECTURE.md §6): URL or search input, security
 * indicator, clear action while editing, recent-downloads shortcut and
 * bookmark toggle, plus the loading progress line. Reload/stop deliberately
 * lives only in the bottom browser bar.
 */
@Composable
fun AddressBar(
    url: String,
    isSecure: Boolean,
    isLoading: Boolean,
    progress: Float,
    isBookmarked: Boolean,
    onSubmit: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    glass: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isEditing by remember { mutableStateOf(false) }
    var selectAllOnFocus by remember { mutableStateOf(false) }
    val text = fieldValue.text

    fun selectAll(value: TextFieldValue): TextFieldValue =
        value.copy(selection = TextRange(0, value.text.length))

    // Keep the field in sync with the loaded page while the user isn't typing.
    LaunchedEffect(url, isEditing) {
        if (!isEditing) {
            fieldValue = TextFieldValue(url)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (glass) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shadowElevation = if (glass) 0.dp else 2.dp,
        border = if (glass) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
        } else {
            null
        },
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        fieldValue = if (selectAllOnFocus && newValue.text == text) {
                            // The first tap after focus may move the cursor
                            // as part of the same click. Convert that first
                            // interaction into select-all, like other browsers.
                            selectAllOnFocus = false
                            selectAll(newValue)
                        } else {
                            if (newValue.text != text) selectAllOnFocus = false
                            newValue
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            val wasEditing = isEditing
                            isEditing = focusState.isFocused
                            if (focusState.isFocused && !wasEditing) {
                                selectAllOnFocus = true
                                if (fieldValue.text.isNotEmpty()) {
                                    fieldValue = selectAll(fieldValue)
                                }
                            }
                            if (!focusState.isFocused) {
                                selectAllOnFocus = false
                            }
                        },
                    placeholder = { Text(stringResource(R.string.address_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isSecure) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = stringResource(
                                if (isSecure) R.string.secure_connection
                                else R.string.insecure_connection,
                            ),
                        )
                    },
                    trailingIcon = {
                        if (isEditing && text.isNotEmpty()) {
                            IconButton(
                                onClick = { fieldValue = TextFieldValue("") },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_clear_input),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = if (glass) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        unfocusedContainerColor = if (glass) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            onSubmit(text)
                            focusManager.clearFocus()
                        },
                    ),
                )

                IconButton(onClick = onOpenDownloads) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = stringResource(R.string.menu_downloads),
                    )
                }
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = stringResource(
                            if (isBookmarked) R.string.action_remove_bookmark
                            else R.string.action_add_bookmark,
                        ),
                        tint = if (isBookmarked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
