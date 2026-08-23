package works.resolve.amanuensis.ui.setup

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.amanuensis.R
import works.resolve.amanuensis.ui.theme.AmanuensisTheme

/** Setup state of the speech-model download, driven by [MainActivity]. */
enum class ModelState { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED }

@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is @ExperimentalMaterial3Api in stable 1.4.0; stable from 1.5.0-alpha23.
@Composable
fun SetupScreen(
    imeEnabled: Boolean,
    micGranted: Boolean,
    modelState: ModelState,
    downloadProgress: Float?,
    onOpenImeSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onDownloadModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.setup_title),
                        maxLines = 1,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Scaffold supplies the edge-to-edge insets (target 37) and the
                // top-bar height.
                .padding(innerPadding)
                // Stay usable on short/landscape displays.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SetupStep(
                headline = stringResource(R.string.setup_enable_ime),
                status = stringResource(
                    R.string.setup_ime_status,
                    stringResource(if (imeEnabled) R.string.setup_status_yes else R.string.setup_status_no),
                ),
                iconRes = R.drawable.ic_ime_keyboard,
                done = imeEnabled,
            )

            SetupStep(
                headline = stringResource(R.string.setup_grant_mic),
                status = stringResource(
                    R.string.setup_permission_status,
                    stringResource(if (micGranted) R.string.setup_status_yes else R.string.setup_status_no),
                ),
                iconRes = R.drawable.ic_ime_mic,
                done = micGranted,
            )

            SetupStep(
                headline = stringResource(R.string.setup_download_model),
                status = when (modelState) {
                    ModelState.DOWNLOADED -> stringResource(
                        R.string.setup_model_status,
                        stringResource(R.string.setup_status_yes),
                    )
                    ModelState.DOWNLOADING -> stringResource(R.string.setup_model_downloading)
                    ModelState.FAILED -> stringResource(R.string.setup_download_failed)
                    ModelState.NOT_DOWNLOADED -> stringResource(
                        R.string.setup_model_status,
                        stringResource(R.string.setup_status_no),
                    )
                },
                iconRes = R.drawable.ic_download,
                done = modelState == ModelState.DOWNLOADED,
            )

            Spacer(Modifier.height(8.dp))

            if (!imeEnabled) {
                Button(
                    onClick = onOpenImeSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.setup_enable_ime))
                }
            }
            if (!micGranted) {
                Button(
                    onClick = onRequestMicPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.setup_grant_mic))
                }
            }
            if (modelState == ModelState.DOWNLOADING) {
                val progress = downloadProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            } else if (modelState != ModelState.DOWNLOADED) {
                Button(
                    onClick = onDownloadModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.setup_download_model))
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    headline: String,
    status: String,
    @DrawableRes iconRes: Int,
    done: Boolean,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(status) },
        leadingContent = {
            Icon(
                painter = painterResource(iconRes),
                // Decorative: the row text carries the meaning.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (done) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    // Status is already stated by the supporting text.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    AmanuensisTheme {
        SetupScreen(
            imeEnabled = false,
            micGranted = false,
            modelState = ModelState.NOT_DOWNLOADED,
            downloadProgress = null,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onDownloadModel = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenDownloadingPreview() {
    AmanuensisTheme {
        SetupScreen(
            imeEnabled = true,
            micGranted = true,
            modelState = ModelState.DOWNLOADING,
            downloadProgress = 0.4f,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onDownloadModel = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenAllSetPreview() {
    AmanuensisTheme {
        SetupScreen(
            imeEnabled = true,
            micGranted = true,
            modelState = ModelState.DOWNLOADED,
            downloadProgress = null,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onDownloadModel = {},
        )
    }
}
