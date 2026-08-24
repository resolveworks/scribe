package works.resolve.amanuensis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.TranscriptLine
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import works.resolve.amanuensis.ime.EditorActions
import works.resolve.amanuensis.ime.TextJoining
import works.resolve.amanuensis.ui.ime.ImeKeyboard
import works.resolve.amanuensis.ui.ime.ImeUiState
import works.resolve.amanuensis.ui.ime.MicVisualState
import works.resolve.amanuensis.ui.theme.AmanuensisTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Amanuensis voice IME.
 *
 * Moonshine usage follows the binding's contract: construction is cheap, the
 * blocking [MicTranscriber.load] / [MicTranscriber.start] run on one
 * serialized background executor, `onText` is treated as a changing partial
 * (composing text), `onLine` as a finished line (committed text), and the
 * model stays loaded and reusable while the service lives.
 */
class AmanuensisInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private enum class EngineState { IDLE, LOADING, STOPPING, LISTENING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService

    private var mic: MicTranscriber? = null

    /** Set in [onDestroy]; all Moonshine callbacks are gated on it. */
    @Volatile private var destroyed = false

    @Volatile private var engineState = EngineState.IDLE

    /** Separator for the line being dictated, until it is committed. */
    private var pendingSeparator: String? = null

    /** Null until the first async cache check; false re-checks, so a model downloaded in setup mid-process is picked up. */
    private var modelPresent: Boolean? = null

    /** Guards against repeatedly pushing the setup screen over the host app. */
    private var setupPromptShown = false

    // ComposeView needs owners when it is hosted outside an Activity or
    // Fragment. Their lifetime follows the service/window callbacks.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private var inputComposeView: ComposeView? = null
    private var uiState by mutableStateOf(ImeUiState())

    // -- Lifecycle -----------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        worker = Executors.newSingleThreadExecutor()
        mic = MicTranscriber(this)
            .onText(::onPartialText)
            .onLine(::onFinalLine)
            .onError { onEngineError() }
    }

    override fun onDestroy() {
        destroyed = true
        val m = mic
        mic = null
        // close() interrupts threads and joins; keep it off the main thread,
        // serialized after any in-flight load()/start().
        worker.execute { runCatching { m?.close() } }
        worker.shutdown()
        inputComposeView?.disposeComposition()
        inputComposeView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        inputComposeView?.disposeComposition()
        refreshStatus()
        refreshMicButton()
        // A window Recomposer looks up its owners from the IME window's root,
        // not only from the returned input view, so install them on both.
        window?.window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@AmanuensisInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@AmanuensisInputMethodService)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AmanuensisInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@AmanuensisInputMethodService)
            setContent {
                AmanuensisTheme {
                    ImeKeyboard(
                        state = uiState,
                        onBack = ::switchToPreviousKeyboard,
                        onDelete = ::deleteBackwards,
                        onMicClick = ::onMicClicked,
                        onEnter = ::performEnter,
                    )
                }
            }
            inputComposeView = this
        }
    }

    // Never take over the whole screen, in either orientation.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (modelPresent != true) {
            // The model may have been downloaded in setup since the last look.
            checkModel()
        }
        refreshStatus()
        refreshMicButton()
        maybeAutoStartDictation()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDictation()
        super.onFinishInputView(finishingInput)
        // isAuxiliary keeps this voice subtype out of IME history, but does
        // not restore the previous keyboard by itself. Return while this
        // service's IME token is still valid, before the window is hidden.
        switchToPreviousInputMethod()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        maybeAutoStartDictation()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    // -- Field handling ------------------------------------------------------

    private fun refreshStatus() {
        when {
            modelPresent == false ->
                setStatus(getString(R.string.ime_status_model_missing))
            !micPermissionGranted() ->
                setStatus(getString(R.string.ime_status_permission_missing))
            engineState == EngineState.FAILED ->
                setStatus(getString(R.string.ime_status_failed))
            // LOADING/STOPPING show no status; the mic button's loader covers them.
            else -> setStatus("")
        }
    }

    private fun micEnabled(): Boolean =
        engineState != EngineState.LOADING &&
            engineState != EngineState.STOPPING

    /** The IME never requests the permission itself — setup owns requests, so the SDK dialog can't pop over the host app. */
    private fun micPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // -- Auto-start ----------------------------------------------------------

    /** Starts dictation when the keyboard is visible and the engine is ready. */
    private fun maybeAutoStartDictation() {
        if (!isInputViewShown || modelPresent != true || !micPermissionGranted()) return
        if (engineState != EngineState.IDLE && engineState != EngineState.FAILED) return
        startDictation()
    }

    // -- Model cache --------------------------------------------------------

    private fun checkModel() {
        worker.execute {
            val present = MoonshineModel.isDownloaded(this)
            main.post {
                if (destroyed) return@post
                modelPresent = present
                if (!present && !setupPromptShown) {
                    setupPromptShown = true
                    openSetupScreen()
                }
                refreshStatus()
                refreshMicButton()
                maybeAutoStartDictation()
            }
        }
    }

    private fun openSetupScreen() {
        // Bring setup forward at the point of use, like the system permission prompt.
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // -- Dictation -----------------------------------------------------------

    private fun onMicClicked() {
        when {
            modelPresent == false -> openSetupScreen()
            modelPresent == null -> Unit // Model-cache check still running.
            !micPermissionGranted() -> openSetupScreen() // Setup owns permission requests.
            !micEnabled() -> return
            engineState == EngineState.LISTENING -> stopDictation()
            else -> startDictation()
        }
    }

    private fun startDictation() {
        if (currentInputConnection == null) return
        pendingSeparator = null
        engineState = EngineState.LOADING
        setStatus("") // Any previous status line is cleared; the loader shows loading.
        refreshMicButton()
        // load()/start() block; keep them off the main thread, serialized on the worker.
        worker.execute {
            try {
                mic?.load()
            } catch (_: Exception) {
                failStart()
                return@execute
            }
            // Cancelled mid-load: skip start() so the mic/permission dialog
            // can never appear after the keyboard hid.
            if (engineState != EngineState.LOADING) return@execute
            try {
                mic?.start()
            } catch (_: Exception) {
                failStart()
                return@execute
            }
            main.post {
                if (!destroyed && engineState == EngineState.LOADING) {
                    engineState = EngineState.LISTENING
                    setStatus("")
                    refreshMicButton()
                }
                // Else a queued mic.stop() already follows on the worker.
            }
        }
    }

    private fun failStart() {
        main.post {
            if (destroyed || engineState != EngineState.LOADING) {
                return@post
            }
            engineState = EngineState.FAILED
            setStatus(getString(R.string.ime_status_failed))
            refreshMicButton()
        }
    }

    private fun stopDictation() {
        when (engineState) {
            EngineState.LISTENING -> {
                engineState = EngineState.IDLE
                setStatus("")
                refreshMicButton()
                // onFinalLine still accepts the trailing final posted after stop().
                mic?.stop()
            }
            EngineState.LOADING -> {
                // start() sets its running flag last, so stop must run after
                // the in-flight worker job or start would undo it.
                engineState = EngineState.STOPPING
                pendingSeparator = null
                setStatus("")
                refreshMicButton()
                worker.execute {
                    mic?.stop()
                    main.post {
                        if (!destroyed && engineState == EngineState.STOPPING) {
                            engineState = EngineState.IDLE
                            refreshMicButton()
                            refreshStatus()
                            maybeAutoStartDictation()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun onPartialText(text: String) {
        if (destroyed || engineState != EngineState.LISTENING) return
        val connection = currentInputConnection ?: return
        connection.setComposingText(separatorForCurrentField() + text, 1)
    }

    private fun onFinalLine(line: TranscriptLine) {
        if (destroyed) return
        val connection = currentInputConnection ?: return
        val text = line.text.orEmpty()
        if (text.isNotEmpty()) {
            // Replaces the composing (partial) region and commits the final line.
            connection.commitText(separatorForCurrentField() + text, 1)
        } else {
            connection.finishComposingText()
        }
        pendingSeparator = null
    }

    private fun separatorForCurrentField(): String {
        pendingSeparator?.let { return it }
        val connection = currentInputConnection ?: return ""
        return TextJoining.leadingSeparator(connection.getTextBeforeCursor(1, 0))
            .also { pendingSeparator = it }
    }

    private fun onEngineError() {
        if (destroyed) return
        if (engineState != EngineState.LISTENING && engineState != EngineState.LOADING) return
        engineState = EngineState.FAILED
        setStatus(getString(R.string.ime_status_failed))
        refreshMicButton()
    }

    // -- Keyboard switch ----------------------------------------------------

    /**
     * Returns to the keyboard the user switched here from. The framework's
     * usual finish/hide callbacks then stop dictation, exactly as when the
     * system's own IME switcher is used.
     */
    private fun switchToPreviousKeyboard() {
        switchToPreviousInputMethod()
    }

    // -- Editing keys --------------------------------------------------------

    private fun deleteBackwards() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        if (ic.getSelectedText(0)?.isNotEmpty() == true) {
            // commitText("") is the documented way to delete selected text.
            ic.commitText("", 1)
        } else {
            // One code point per press, so surrogate pairs (emoji) go together.
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val decision = EditorActions.decide(info?.actionId ?: 0, info?.actionLabel, info?.imeOptions ?: 0)
        when (decision) {
            is EditorActions.Decision.Perform -> ic.performEditorAction(decision.actionId)
            EditorActions.Decision.SendEnterKey -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    // -- UI ------------------------------------------------------------------

    private fun setStatus(text: String) {
        uiState = uiState.copy(status = text)
    }

    private fun refreshMicButton() {
        uiState = uiState.copy(
            micState = when (engineState) {
                EngineState.LOADING -> MicVisualState.LOADING
                EngineState.LISTENING -> MicVisualState.LISTENING
                EngineState.FAILED -> MicVisualState.FAILED
                else -> MicVisualState.IDLE
            },
            micEnabled = micEnabled(),
        )
    }
}
