package com.example.browser.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.browser.BuildConfig
import com.example.browser.R
import com.example.browser.ui.theme.logoColorFilter
import com.example.browser.util.BrowserRole

/**
 * About — its own screen, not a heading at the bottom of Settings.
 *
 * The content is deliberately specific: which engine renders the pages, which
 * protections are on before the user touches anything, where each kind of data
 * is stored, and how a bug report actually leaves the phone. A description
 * that says "fast and secure" without naming the mechanism tells the reader
 * nothing, so every claim here corresponds to code in this repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onReportBug: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_screen_title)) },
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
                .padding(horizontal = 18.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.hexora_logo),
                    contentDescription = stringResource(
                        R.string.start_logo_content_description,
                    ),
                    contentScale = ContentScale.Fit,
                    // Paper prints the mark in ink, like the start page does.
                    colorFilter = logoColorFilter(),
                    modifier = Modifier
                        .size(124.dp)
                        .clip(RoundedCornerShape(28.dp)),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.about_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            AboutCard(R.string.about_what_title) {
                AboutParagraph(R.string.about_what_body)
            }

            AboutCard(R.string.about_protection_title) {
                AboutBullet(R.string.about_protection_trackers)
                AboutBullet(R.string.about_protection_https)
                AboutBullet(R.string.about_protection_cookies)
                AboutBullet(R.string.about_protection_permissions)
                AboutBullet(R.string.about_protection_private)
            }

            AboutCard(R.string.about_data_title) {
                AboutBullet(R.string.about_data_passwords)
                AboutBullet(R.string.about_data_local)
                AboutBullet(R.string.about_data_reports)
            }

            AboutCard(R.string.about_browsing_title) {
                AboutBullet(R.string.about_browsing_tabs)
                AboutBullet(R.string.about_browsing_tray)
                AboutBullet(R.string.about_browsing_site)
                AboutBullet(R.string.about_browsing_default)
            }

            AboutCard(R.string.about_limits_title) {
                AboutParagraph(R.string.about_limits_body)
            }

            AboutCard(R.string.about_engine_title) {
                AboutParagraph(R.string.about_engine_body)
                OutlinedButton(
                    onClick = onOpenLicences,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.about_licences_button))
                }
            }

            AboutCard(R.string.about_developer_title) {
                Text(
                    text = stringResource(R.string.about_made_by),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // The address is shown, not only linked: it must be readable
                // on a device with no mail app installed.
                Text(
                    text = BrowserRole.DEVELOPER_EMAIL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(
                    onClick = {
                        // Explicit contact, no report payload: the address is
                        // prefilled and the composer opened, so nothing is
                        // sent without the owner writing and pressing send.
                        val intent = com.example.browser.util.BugReport.contactIntent()
                        try {
                            context.startActivity(intent)
                        } catch (error: android.content.ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                BrowserRole.DEVELOPER_EMAIL,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.about_contact))
                }
                OutlinedButton(
                    onClick = onReportBug,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.about_report_bug))
                }
                Text(
                    text = stringResource(R.string.about_report_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(
                    R.string.about_build_footer,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AboutCard(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    10.dp,
                ),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun AboutParagraph(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AboutBullet(@StringRes text: Int) {
    Row {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
