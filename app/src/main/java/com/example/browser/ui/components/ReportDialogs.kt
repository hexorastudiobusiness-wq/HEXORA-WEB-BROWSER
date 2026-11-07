package com.example.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.util.BugReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The report a shake — or the About screen — prepares.
 *
 * The owner writes what went wrong in their own words; the device details, the
 * app state and the app log are attached around it. Nothing is sent from here:
 * the app hands the finished text to the mail app the owner already has, and
 * "copy" exists for anyone who would rather paste it somewhere else.
 *
 * @param buildReport receives the description typed here and returns the full
 *   text (device + state + log + description). Called when the owner is ready,
 *   so the report reflects the device at the moment of reporting.
 */
@Composable
fun BugReportDialog(
    buildReport: (String) -> String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bug_report_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.bug_report_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.bug_report_hint)) },
                    minLines = 4,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    if (sending) return@TextButton
                    sending = true
                    // Building the report reads the device's own properties,
                    // which is a process call: keep it off the UI thread. And
                    // keep the *whole* launch guarded: this button used to be
                    // one exception away from closing the app.
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching { buildReport(description) }.getOrNull()
                        }
                        if (text == null) {
                            sending = false
                            val fallback = withContext(Dispatchers.IO) {
                                runCatching { BugReport.minimal(description) }.getOrDefault(description)
                            }
                            copyToClipboard(context, fallback)
                            Toast.makeText(
                                context,
                                R.string.bug_report_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                        val opened = BugReport.launch(context, BugReport.mailIntent(text))
                        sending = false
                        if (opened) {
                            onDismiss()
                        } else {
                            // Nothing answered the intent: hand the owner the
                            // text instead of a dead button.
                            copyToClipboard(context, text)
                            Toast.makeText(
                                context,
                                R.string.bug_report_no_mail_app,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.bug_report_send))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    // Off the UI thread: assembling the report spawns `getprop`
                    // for the ROM version, and doing that on the main thread
                    // froze the dialog for a second or more on real devices.
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching { buildReport(description) }
                                .getOrElse { runCatching { BugReport.minimal(description) }.getOrDefault(description) }
                        }
                        copyToClipboard(context, text)
                        Toast.makeText(
                            context,
                            R.string.bug_report_copied,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Text(stringResource(R.string.bug_report_copy))
            }
        },
    )
}

/**
 * First-launch offer to become the default browser.
 *
 * The dialog is only shown when the system says the role can be requested. When
 * it cannot — an older build, or a vendor skin that hides the dialog — the owner
 * gets the settings route instead of a button that does nothing.
 */
@Composable
fun DefaultBrowserDialog(
    canRequestRole: Boolean,
    onRequestRole: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Pre-warm the device details so the first bug report does not stall.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { com.example.browser.util.DeviceInfo.romLabel() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.default_browser_title)) },
        text = {
            Text(
                if (canRequestRole) {
                    stringResource(R.string.default_browser_message)
                } else {
                    stringResource(R.string.default_browser_settings_hint)
                },
            )
        },
        confirmButton = {
            if (canRequestRole) {
                TextButton(onClick = onRequestRole) {
                    Text(stringResource(R.string.default_browser_confirm))
                }
            } else {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.default_browser_settings))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    if (canRequestRole) {
                        stringResource(R.string.default_browser_later)
                    } else {
                        stringResource(R.string.action_close)
                    },
                )
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Hexora report", text))
}
