package works.resolve.scribe.ui.ime

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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.scribe.R
import works.resolve.scribe.ui.theme.ScribeTheme

internal enum class MicVisualState { IDLE, LOADING, LISTENING, FAILED }

/** Visual state of the IME controls, hosted by the service. */
@Immutable
internal data class ImeUiState(
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
        // The back key anchors the top-left.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 48.dp) // mirror the back key's height below the keys
                .padding(horizontal = 16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24),
                    contentDescription = stringResource(R.string.ime_cd_back),
                )
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
                when {
                    listening -> R.drawable.mic_off_24
                    state.micState == MicVisualState.FAILED -> R.drawable.mic_alert_24
                    else -> R.drawable.mic_24
                }
            ),
            contentDescription = description,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ImeKeyboardPreview() {
    ScribeTheme {
        ImeKeyboard(
            state = ImeUiState(
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
