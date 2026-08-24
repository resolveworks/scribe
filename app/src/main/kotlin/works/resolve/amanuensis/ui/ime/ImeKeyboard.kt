package works.resolve.amanuensis.ui.ime

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.amanuensis.R
import works.resolve.amanuensis.ui.theme.AmanuensisTheme

internal enum class MicVisualState { IDLE, LOADING, LISTENING, FAILED }

/** One-line hint; rendered only while non-blank. */
@Immutable
internal data class ImeUiState(
    val status: String = "",
    val micState: MicVisualState = MicVisualState.IDLE,
    val micEnabled: Boolean = false,
)

/** The complete, deliberately small input view hosted by InputMethodService. */
@Composable
internal fun ImeKeyboard(
    state: ImeUiState,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onMicClick: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        // Wrap-content layout: the IME window sizes itself to this view's
        // measured height, so nothing here fixes geometry. The framework
        // owns the only structural spacing — navigationBarsPadding lifts the
        // content above the edge-to-edge nav bar (the surface color still
        // paints behind it) — and the controls get modest vertical padding.
        // The back key anchors the top-left; a status line, when shown, fills
        // the rest of that header row to its right.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 48.dp) // mirror the header row's height below the keys
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_24),
                        contentDescription = stringResource(R.string.ime_cd_back),
                    )
                }
                if (state.status.isNotBlank()) {
                    Text(
                        text = state.status,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The delete–mic–enter cluster is centered as one group, with
            // wide gaps so the side keys reach toward thumb range without
            // pinning to the screen edges.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyboardKey(
                    iconRes = R.drawable.backspace_24,
                    description = stringResource(R.string.ime_cd_delete),
                    onClick = onDelete,
                )
                MicControl(state, onMicClick)
                KeyboardKey(
                    iconRes = R.drawable.keyboard_return_24,
                    description = stringResource(R.string.ime_cd_enter),
                    onClick = onEnter,
                )
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    @DrawableRes iconRes: Int,
    description: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
        )
    }
}

@Composable
private fun MicControl(state: ImeUiState, onClick: () -> Unit) {
    if (state.micState == MicVisualState.LOADING) {
        val loadingDescription = stringResource(R.string.ime_cd_loading)
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = loadingDescription },
            )
        }
        return
    }

    val listening = state.micState == MicVisualState.LISTENING
    val description = stringResource(
        when {
            listening -> R.string.ime_cd_mic_stop
            state.micState == MicVisualState.FAILED -> R.string.ime_cd_mic_retry
            else -> R.string.ime_cd_mic_start
        }
    )
    FilledIconButton(
        onClick = onClick,
        enabled = state.micEnabled,
        modifier = Modifier.size(72.dp),
    ) {
        Icon(
            painter = painterResource(
                if (listening) R.drawable.mic_off_24 else R.drawable.mic_24
            ),
            contentDescription = description,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ImeKeyboardPreview() {
    AmanuensisTheme {
        ImeKeyboard(
            state = ImeUiState(
                status = "Something went wrong. Tap to retry.",
                micState = MicVisualState.FAILED,
                micEnabled = true,
            ),
            onBack = {},
            onDelete = {},
            onMicClick = {},
            onEnter = {},
        )
    }
}
