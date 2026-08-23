package com.amanuensis.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.amanuensis.R
import com.amanuensis.ui.theme.AmanuensisTheme

@Composable
fun SetupScreen(
    imeEnabled: Boolean,
    micGranted: Boolean,
    onOpenImeSettings: () -> Unit,
    onShowPicker: () -> Unit,
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Edge-to-edge (target 37): stay clear of system bars...
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // ...and stay usable on short/landscape displays.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        // Centered when content fits; scrolls gracefully when it does not.
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(
            label = stringResource(
                R.string.setup_ime_status,
                stringResource(if (imeEnabled) R.string.setup_status_yes else R.string.setup_status_no),
            ),
            positive = imeEnabled,
        )
        StatusCard(
            label = stringResource(
                R.string.setup_permission_status,
                stringResource(if (micGranted) R.string.setup_status_yes else R.string.setup_status_no),
            ),
            positive = micGranted,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!imeEnabled) {
                Button(onClick = onOpenImeSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_enable_ime))
                }
            }
            OutlinedButton(onClick = onShowPicker, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_open_picker))
            }
            if (!micGranted) {
                Button(onClick = onRequestMicPermission, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_grant_mic))
                }
            }
        }

        if (imeEnabled && micGranted) {
            Text(
                text = stringResource(R.string.setup_all_set),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusCard(label: String, positive: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (positive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    AmanuensisTheme {
        SetupScreen(
            imeEnabled = false,
            micGranted = false,
            onOpenImeSettings = {},
            onShowPicker = {},
            onRequestMicPermission = {},
        )
    }
}
