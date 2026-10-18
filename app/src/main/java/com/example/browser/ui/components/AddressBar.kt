package com.example.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.ui.browser.AddressSuggestion

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
    suggestions: List<AddressSuggestion> = emptyList(),
    onQueryChange: (String) -> Unit = {},
    onSuggestionSelected: (String) -> Unit = {},
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
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 34.dp else 38.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (isSecure) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = stringResource(
                                if (isSecure) R.string.secure_connection
                                else R.string.insecure_connection,
                            ),
                            modifier = Modifier.size(18.dp),
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.address_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { newValue ->
                                    if (newValue.text != text) {
                                        onQueryChange(newValue.text)
                                    }
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
                                    .fillMaxWidth()
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
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
                        }
                        if (isEditing && text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    fieldValue = TextFieldValue("")
                                    onQueryChange("")
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_clear_input),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onOpenDownloads,
                    modifier = Modifier.size(if (compact) 32.dp else 36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = stringResource(R.string.menu_downloads),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(if (compact) 32.dp else 36.dp),
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = stringResource(
                            if (isBookmarked) R.string.action_remove_bookmark
                            else R.string.action_add_bookmark,
                        ),
                        modifier = Modifier.size(20.dp),
                        tint = if (isBookmarked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // History suggestions while typing: tap one to open it directly
            // instead of retyping a previously visited address.
            if (isEditing && suggestions.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    focusManager.clearFocus()
                                    onSuggestionSelected(suggestion.url)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = suggestion.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = suggestion.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
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
