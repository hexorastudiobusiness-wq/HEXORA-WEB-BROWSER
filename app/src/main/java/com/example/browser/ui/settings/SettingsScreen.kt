package com.example.browser.ui.settings

import android.widget.Toast
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.browser.BuildConfig
import com.example.browser.R
import com.example.browser.data.settings.ThemeMode
import com.example.browser.util.SearchEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onClearBrowsingData: () -> Unit,
    onOpenPasswords: () -> Unit,
    onOpenSitePermissions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    var showClearDataConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_search_engine))
            SearchEngine.entries.forEach { engine ->
                RadioRow(
                    label = if (engine == SearchEngine.CUSTOM &&
                        settings.customSearchName.isNotBlank()
                    ) {
                        settings.customSearchName
                    } else {
                        engine.displayName
                    },
                    selected = settings.searchEngine == engine,
                    onClick = { viewModel.setSearchEngine(engine) },
                )
            }
            if (settings.searchEngine == SearchEngine.CUSTOM) {
                var customName by remember(settings.customSearchName) {
                    mutableStateOf(settings.customSearchName)
                }
                var customTemplate by remember(settings.customSearchTemplate) {
                    mutableStateOf(settings.customSearchTemplate)
                }
                var showTemplateError by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text(stringResource(R.string.settings_custom_search_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = customTemplate,
                    onValueChange = {
                        customTemplate = it
                        showTemplateError = false
                    },
                    label = {
                        Text(stringResource(R.string.settings_custom_search_template))
                    },
                    placeholder = {
                        Text(stringResource(R.string.settings_custom_search_template_example))
                    },
                    isError = showTemplateError,
                    supportingText = if (showTemplateError) {
                        {
                            Text(
                                stringResource(
                                    R.string.settings_custom_search_template_invalid,
                                ),
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        // Same rule the URL resolver applies: an absolute
                        // http(s) template containing %s. Invalid input is
                        // rejected here with feedback instead of being
                        // saved and silently falling back to Google later.
                        val candidate = customTemplate.trim()
                        val valid = "%s" in candidate &&
                            (candidate.startsWith("https://", true) ||
                                candidate.startsWith("http://", true))
                        if (valid) {
                            viewModel.setCustomSearch(customName, customTemplate)
                        } else {
                            showTemplateError = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionTitle(stringResource(R.string.settings_theme))
            RadioRow(
                label = stringResource(R.string.theme_system),
                selected = settings.themeMode == ThemeMode.SYSTEM,
                onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
            )
            RadioRow(
                label = stringResource(R.string.theme_light),
                selected = settings.themeMode == ThemeMode.LIGHT,
                onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
            )
            RadioRow(
                label = stringResource(R.string.theme_dark),
                selected = settings.themeMode == ThemeMode.DARK,
                onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
            )
            RadioRow(
                label = stringResource(R.string.theme_hexora),
                selected = settings.themeMode == ThemeMode.HEXORA,
                onClick = { viewModel.setThemeMode(ThemeMode.HEXORA) },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            SwitchRow(
                label = stringResource(R.string.settings_javascript),
                summary = stringResource(R.string.settings_javascript_summary),
                checked = settings.javaScriptEnabled,
                onCheckedChange = { viewModel.setJavaScriptEnabled(it) },
            )
            SwitchRow(
                label = stringResource(R.string.settings_tracking_protection),
                summary = stringResource(R.string.settings_tracking_protection_summary),
                checked = settings.trackingProtectionEnabled,
                onCheckedChange = { viewModel.setTrackingProtectionEnabled(it) },
            )
            SwitchRow(
                label = stringResource(R.string.settings_https_only),
                summary = stringResource(R.string.settings_https_only_summary),
                checked = settings.httpsOnlyMode,
                onCheckedChange = { viewModel.setHttpsOnlyMode(it) },
            )
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_total_cookie_protection))
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_total_cookie_protection_summary))
                },
            )
            OutlinedButton(
                onClick = onOpenSitePermissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.site_permissions_title))
            }
            SwitchRow(
                label = stringResource(R.string.settings_password_manager),
                summary = stringResource(R.string.settings_password_manager_summary),
                checked = settings.passwordManagerEnabled,
                onCheckedChange = { viewModel.setPasswordManagerEnabled(it) },
            )
            OutlinedButton(
                onClick = onOpenPasswords,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.passwords_manage))
            }
            SwitchRow(
                label = stringResource(R.string.settings_desktop_mode),
                summary = stringResource(R.string.settings_desktop_mode_summary),
                checked = settings.desktopMode,
                onCheckedChange = { viewModel.setDesktopMode(it) },
            )
            Spacer(Modifier.height(12.dp))
            // Zoom is per site and lives in the page's overflow menu, so a
            // global slider here would silently disagree with what the page
            // in front of the user is actually rendering (the old bug).
            Text(
                text = stringResource(R.string.settings_zoom_info_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_zoom_info_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                label = stringResource(R.string.settings_open_links_new_tab),
                summary = stringResource(R.string.settings_open_links_new_tab_summary),
                checked = settings.openLinksInNewTab,
                onCheckedChange = { viewModel.setOpenLinksInNewTab(it) },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showClearDataConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.menu_clear_data))
            }
            Spacer(Modifier.height(24.dp))
            // Build identity, bottom of Settings: the answer to "is this the
            // build I just downloaded?".
            Text(
                text = "Hexora ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearDataConfirm) {
        // Destructive and irreversible: confirm first, and only claim
        // success after the clear has actually been issued.
        AlertDialog(
            onDismissRequest = { showClearDataConfirm = false },
            title = {
                Text(stringResource(R.string.settings_clear_data_confirm_title))
            },
            text = {
                Text(stringResource(R.string.settings_clear_data_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDataConfirm = false
                        onClearBrowsingData()
                        Toast.makeText(
                            context,
                            R.string.browsing_data_cleared,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Text(stringResource(R.string.menu_clear_data))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}
