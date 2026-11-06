package com.example.browser.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.browser.R

/**
 * Licences — the third-party notices, kept off the About page so that page can
 * stay readable.
 *
 * GeckoView ships under the Mozilla Public License 2.0, which asks a
 * distributor to say where the covered source can be obtained. Linking the
 * exact tree and the licence text is how this screen answers that, instead of
 * a one-line "Powered by Mozilla" that says nothing a reader can check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences_title)) },
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
            Text(
                text = stringResource(R.string.licences_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )

            LicenceCard(
                title = R.string.licences_engine_title,
                body = R.string.licences_engine_body,
                links = listOf(
                    R.string.licences_source_button to GECKOVIEW_SOURCE,
                    R.string.licences_mpl_button to MPL_2_0,
                ),
                onOpenUrl = onOpenUrl,
            )

            LicenceCard(
                title = R.string.licences_libs_title,
                body = R.string.licences_libs_body,
                links = listOf(R.string.licences_apache_button to APACHE_2_0),
                onOpenUrl = onOpenUrl,
            )

            LicenceCard(
                title = R.string.licences_app_title,
                body = R.string.licences_app_body,
                links = emptyList(),
                onOpenUrl = onOpenUrl,
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LicenceCard(
    @StringRes title: Int,
    @StringRes body: Int,
    links: List<Pair<Int, String>>,
    onOpenUrl: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                links.forEach { (label, url) ->
                    OutlinedButton(
                        onClick = { onOpenUrl(url) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    ) {
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}

/** The GeckoView tree these builds compile against. */
private const val GECKOVIEW_SOURCE =
    "https://github.com/mozilla/gecko-dev/tree/master/mobile/android/geckoview"

private const val MPL_2_0 = "https://www.mozilla.org/MPL/2.0/"

private const val APACHE_2_0 = "https://www.apache.org/licenses/LICENSE-2.0"
