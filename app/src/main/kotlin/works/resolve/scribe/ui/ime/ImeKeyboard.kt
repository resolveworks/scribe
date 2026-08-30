package works.resolve.scribe.ui.ime

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.scribe.R
import works.resolve.scribe.ui.theme.ScribeTheme

/** Dictation status, shared by the engine logic and the input view. */
internal enum class DictationState { IDLE, LOADING, LISTENING, FAILED }

/** The complete, deliberately small input view hosted by InputMethodService. */
@Composable
internal fun ImeKeyboard(
    state: DictationState,
    level: Float,
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
                MicControl(state, level, onMicClick)
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

/**
 * The keyboard is always listening while shown, so the mic is a status
 * indicator while the engine works — faded while the model loads, white
 * once actively listening, not clickable either way (there is no stop) —
 * and a retry or setup affordance otherwise.
 *
 * A translucent halo behind the button reflects the live input level
 * while listening (0..1); the engine drives it to 0 otherwise, so the
 * halo shrinks back to barely peeking past the button.
 */
@Composable
private fun MicControl(state: DictationState, level: Float, onClick: () -> Unit) {
    val listening = state == DictationState.LISTENING
    val loading = state == DictationState.LOADING
    val description = stringResource(
        when {
            listening -> R.string.ime_cd_mic_listening
            loading -> R.string.ime_cd_loading
            state == DictationState.FAILED -> R.string.ime_cd_mic_retry
            else -> R.string.ime_cd_mic_start
        }
    )
    val haloColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val haloLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 100),
        label = "micHaloLevel",
    )
    val haloRadius = (40 + 28 * haloLevel).dp
    Box {
        Canvas(modifier = Modifier.size(72.dp)) {
            drawCircle(color = haloColor, radius = haloRadius.toPx())
        }
        FilledIconButton(
            onClick = onClick,
            enabled = !listening && !loading,
            // Disabled yet white while listening: the full tone marks the live
            // mic, while fading stays reserved for the loading state.
            colors =
                if (listening) {
                    IconButtonDefaults.filledIconButtonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (state == DictationState.FAILED) R.drawable.mic_alert_24 else R.drawable.mic_24
                ),
                contentDescription = description,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ImeKeyboardPreview() {
    ScribeTheme {
        ImeKeyboard(
            state = DictationState.LISTENING,
            level = 0.7f,
            onBack = {},
            onDelete = {},
            onMicClick = {},
            onEnter = {},
        )
    }
}
