package com.example.browser.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.browser.BuildConfig
import com.example.browser.R
import com.example.browser.data.settings.BrowserSettingsState
import com.example.browser.data.settings.ThemeMode
import com.example.browser.util.SearchEngine

/**
 * One entry of the settings sidebar. Chrome splits Settings the same way: the
 * rail is the map, the pane on its right is the section; a section is reached
 * by tapping its name, never by scrolling past the others.
 */
private enum class SettingsPane(
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    APPEARANCE(R.string.settings_section_appearance, Icons.Filled.Palette),
    SEARCH(R.string.settings_section_search, Icons.Filled.Search),
    PRIVACY(R.string.settings_section_privacy, Icons.Filled.Security),
    TABS(R.string.settings_section_tabs, Icons.Filled.Tab),
    DATA(R.string.settings_section_data, Icons.Filled.Storage),
    APP(R.string.settings_section_app, Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onClearBrowsingData: () -> Unit,
    onOpenPasswords: () -> Unit,
    onOpenSitePermissions: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onSetDefaultBrowser: () -> Unit = {},
    onReportBug: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    var showClearDataConfirm by remember { mutableStateOf(false) }
    // The rail keeps its selection across rotation; the app never has to
    // remember it, because Settings is entered fresh from the browser menu.
    var pane by rememberSaveable { mutableStateOf(SettingsPane.APPEARANCE) }

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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SettingsRail(
                selected = pane,
                onSelect = { pane = it },
            )
            // A hairline instead of a Divider: the rail must read as a
            // sidebar, not as a list of buttons floating over the content.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            // `key(pane)` gives every section its own scroll state, so landing
            // in a section always starts at its top instead of inheriting the
            // previous section's offset.
            key(pane) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    when (pane) {
                        SettingsPane.APPEARANCE -> AppearancePane(settings, viewModel)
                        SettingsPane.SEARCH -> SearchPane(settings, viewModel)
                        SettingsPane.PRIVACY -> PrivacyPane(
                            settings = settings,
                            viewModel = viewModel,
                            onOpenPasswords = onOpenPasswords,
                            onOpenSitePermissions = onOpenSitePermissions,
                        )
                        SettingsPane.TABS -> TabsPane(settings, viewModel)
                        SettingsPane.DATA -> DataPane(
                            onRequestClear = { showClearDataConfirm = true },
                        )
                        SettingsPane.APP -> AppPane(
                            onOpenAbout = onOpenAbout,
                            onSetDefaultBrowser = onSetDefaultBrowser,
                            onReportBug = onReportBug,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showClearDataConfirm) {
        // Destructive and irreversible: confirm first, and only claim success
        // after the clear has actually been issued.
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

/**
 * The left sidebar: every settings section, one tap away, with the selected
 * one highlighted. Labels wrap instead of truncating, because a section name
 * the user cannot read is a section the user will not find.
 */
@Composable
private fun SettingsRail(
    selected: SettingsPane,
    onSelect: (SettingsPane) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsPane.entries.forEach { pane ->
            val isSelected = pane == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .background(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                    .selectable(selected = isSelected, onClick = { onSelect(pane) })
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Icon(
                    imageVector = pane.icon,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(pane.label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AppearancePane(settings: BrowserSettingsState, viewModel: SettingsViewModel) {
    SettingsSectionTitle(stringResource(R.string.settings_theme))
    Text(
        text = stringResource(R.string.settings_appearance_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
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
}

@Composable
private fun SearchPane(settings: BrowserSettingsState, viewModel: SettingsViewModel) {
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
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            onClick = {
                // Same rule the URL resolver applies: an absolute http(s)
                // template containing %s. Invalid input is rejected here with
                // feedback instead of being saved and silently falling back to
                // Google later.
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
                .padding(top = 8.dp)
                .height(36.dp),
        ) {
            Text(
                text = stringResource(R.string.action_save),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PrivacyPane(
    settings: BrowserSettingsState,
    viewModel: SettingsViewModel,
    onOpenPasswords: () -> Unit,
    onOpenSitePermissions: () -> Unit,
) {
    SettingsSectionTitle(stringResource(R.string.settings_section_privacy))
    Text(
        text = stringResource(R.string.settings_privacy_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
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
    SwitchRow(
        label = stringResource(R.string.settings_javascript),
        summary = stringResource(R.string.settings_javascript_summary),
        checked = settings.javaScriptEnabled,
        onCheckedChange = { viewModel.setJavaScriptEnabled(it) },
    )

    SettingsSectionTitle(stringResource(R.string.settings_per_site_title))
    CompactButton(
        text = stringResource(R.string.site_permissions_title),
        onClick = onOpenSitePermissions,
    )

    SettingsSectionTitle(stringResource(R.string.settings_credentials_title))
    SwitchRow(
        label = stringResource(R.string.settings_password_manager),
        summary = stringResource(R.string.settings_password_manager_summary),
        checked = settings.passwordManagerEnabled,
        onCheckedChange = { viewModel.setPasswordManagerEnabled(it) },
    )
    CompactButton(
        text = stringResource(R.string.passwords_manage),
        onClick = onOpenPasswords,
    )
}

@Composable
private fun TabsPane(settings: BrowserSettingsState, viewModel: SettingsViewModel) {
    SettingsSectionTitle(stringResource(R.string.settings_section_tabs))
    SwitchRow(
        label = stringResource(R.string.settings_open_links_new_tab),
        summary = stringResource(R.string.settings_open_links_new_tab_summary),
        checked = settings.openLinksInNewTab,
        onCheckedChange = { viewModel.setOpenLinksInNewTab(it) },
    )
    SwitchRow(
        label = stringResource(R.string.settings_desktop_mode),
        summary = stringResource(R.string.settings_desktop_mode_summary),
        checked = settings.desktopMode,
        onCheckedChange = { viewModel.setDesktopMode(it) },
    )
    Spacer(Modifier.height(12.dp))
    // Zoom is per site and lives in the page's overflow menu, so a global
    // slider here would silently disagree with what the page in front of the
    // user is actually rendering (the old bug).
    Text(
        text = stringResource(R.string.settings_zoom_info_title),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.settings_zoom_info_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DataPane(onRequestClear: () -> Unit) {
    SettingsSectionTitle(stringResource(R.string.settings_section_data))
    Text(
        text = stringResource(R.string.settings_data_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    CompactButton(
        text = stringResource(R.string.menu_clear_data),
        onClick = onRequestClear,
    )
    Text(
        text = stringResource(R.string.settings_clear_data_scope),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun AppPane(
    onOpenAbout: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
    onReportBug: () -> Unit,
) {
    SettingsSectionTitle(stringResource(R.string.settings_section_app))
    Text(
        text = stringResource(R.string.settings_app_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )

    // About is a screen of its own now, not a heading at the bottom of this
    // page: one row that navigates, exactly like every other settings entry
    // that opens something.
    SettingsRow(
        label = stringResource(R.string.about_title),
        summary = stringResource(R.string.settings_about_row_summary),
        onClick = onOpenAbout,
        trailing = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )

    CompactButton(
        text = stringResource(R.string.settings_default_browser),
        onClick = onSetDefaultBrowser,
    )
    Text(
        text = stringResource(R.string.settings_default_browser_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    CompactButton(
        text = stringResource(R.string.about_report_bug),
        onClick = onReportBug,
    )
    Text(
        text = stringResource(R.string.about_report_bug_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(24.dp))
    // Build identity, bottom of Settings: the answer to "is this the build I
    // just downloaded?".
    Text(
        text = stringResource(
            R.string.about_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/**
 * One settings row, compact on purpose.
 *
 * Material's `ListItem` and a stock `Switch` are sized for a full-width list
 * on a tablet; in a phone pane that is ~230 dp wide next to the sidebar they
 * were oversized — 16 sp labels, 52×32 dp switches and 16 dp of padding per
 * row. These helpers keep the same information at 14 sp / 12 sp with a switch
 * scaled to ~40×25 dp, so a section fits on one screen instead of scrolling
 * for three rows.
 */
@Composable
private fun SettingsRow(
    label: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/** The stock switch, one visual notch smaller, inside a box that reserves room for it. */
@Composable
private fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier.size(width = 44.dp, height = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.78f),
        )
    }
}

@Composable
private fun CompactButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
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
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 34.dp, height = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                modifier = Modifier.scale(0.82f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 6.dp),
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
    SettingsRow(
        label = label,
        summary = summary,
        trailing = {
            CompactSwitch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}
