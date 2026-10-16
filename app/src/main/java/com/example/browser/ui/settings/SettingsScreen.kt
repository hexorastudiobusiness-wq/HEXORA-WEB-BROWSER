package com.example.browser.ui.settings

import android.widget.Toast
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.data.settings.ThemeMode
import com.example.browser.ui.components.ZoomSlider
import com.example.browser.util.SearchEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onClearBrowsingData: () -> Unit,
    onOpenPasswords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

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
                    label = engine.displayName,
                    selected = settings.searchEngine == engine,
                    onClick = { viewModel.setSearchEngine(engine) },
                )
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
                label = stringResource(R.string.settings_cookies),
                summary = stringResource(R.string.settings_cookies_summary),
                checked = settings.cookiesEnabled,
                onCheckedChange = { viewModel.setCookiesEnabled(it) },
            )
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
            Text(
                text = stringResource(R.string.settings_text_zoom),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_text_zoom_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZoomSlider(
                percent = settings.textZoomPercent,
                onPercentCommitted = { percent ->
                    viewModel.setTextZoomPercent(percent)
                },
                modifier = Modifier.fillMaxWidth(),
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
                onClick = {
                    onClearBrowsingData()
                    Toast.makeText(
                        context,
                        R.string.browsing_data_cleared,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.menu_clear_data))
            }
            Spacer(Modifier.height(24.dp))
        }
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
