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
import android.view.inputmethod.InputConnection
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
import works.resolve.amanuensis.ime.AutoStartPolicy
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

    private enum class EngineState { IDLE, LOADING, STOPPING, READY, LISTENING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService

    private var mic: MicTranscriber? = null

    /** Set in [onDestroy]; all Moonshine callbacks are gated on it. */
    @Volatile private var destroyed = false

    private var engineState = EngineState.IDLE

    /**
     * One dictation session: the editor connection captured at start, plus
     * per-line state. Moonshine flushes the trailing final line *after*
     * stop() returns, so a live session deliberately outlives LISTENING —
     * ending it is what drops a late final.
     */
    private class Session(val connection: InputConnection) {
        /** Separator for the line being dictated, until it is committed. */
        var pendingSeparator: String? = null
    }

    private var session: Session? = null

    /** Monotonic id of the latest load/start request; see [startDictation]. */
    @Volatile private var requestGeneration = 0

    /** Null until the first async cache check; false re-checks, so a model downloaded in setup mid-process is picked up. */
    @Volatile private var modelPresent: Boolean? = null

    /** Guards against repeatedly pushing the setup screen over the host app. */
    private var setupPromptShown = false

    /** Keyboard opened while the cache check is in flight; its callback then auto-starts. */
    private var autoStartPending = false

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

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (!restarting) {
            // Different editor: drop any trailing final still in flight for the old one.
            session = null
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        autoStartPending = false
        if (modelPresent != true) {
            // The model may have been downloaded in setup since the last look.
            checkModel(launchSetupIfMissing = !restarting)
        }
        stopDictation()
        refreshStatus()
        refreshMicButton()
        maybeAutoStartDictation()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        autoStartPending = false
        stopDictation()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onWindowHidden() {
        autoStartPending = false
        stopDictation()
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

    /** STOPPING is deliberately not active: the serialized worker drains the queued stop before any new load/start. */
    private fun dictationActive(): Boolean =
        engineState == EngineState.LOADING || engineState == EngineState.LISTENING

    /** Starts dictation without a mic press on keyboard open; deferred via [autoStartPending] while the cache check runs. */
    private fun maybeAutoStartDictation() {
        when (modelPresent) {
            null -> autoStartPending = true
            true -> if (
                AutoStartPolicy.shouldStartOnOpen(
                    modelPresent,
                    micPermissionGranted(),
                    dictationActive(),
                )
            ) {
                startDictation()
            }
            false -> Unit // Missing model: the setup flow owns this case.
        }
    }

    // -- Model cache --------------------------------------------------------

    private fun checkModel(launchSetupIfMissing: Boolean) {
        worker.execute {
            val present = MoonshineModel.isDownloaded(this)
            main.post {
                if (destroyed) return@post
                modelPresent = present
                if (!present && launchSetupIfMissing && !setupPromptShown) {
                    setupPromptShown = true
                    openSetupScreen()
                }
                refreshStatus()
                refreshMicButton()
                if (autoStartPending) {
                    autoStartPending = false
                    maybeAutoStartDictation()
                }
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
        session = Session(currentInputConnection ?: return)
        engineState = EngineState.LOADING
        val generation = ++requestGeneration
        setStatus("") // Any previous status line is cleared; the loader shows loading.
        refreshMicButton()
        // load()/start() block; keep them off the main thread, serialized on the worker.
        worker.execute {
            try {
                mic?.load()
            } catch (e: Exception) {
                main.post {
                    if (!destroyed && engineState == EngineState.LOADING &&
                        generation == requestGeneration
                    ) {
                        engineState = EngineState.FAILED
                        setStatus(getString(R.string.ime_status_failed))
                        refreshMicButton()
                    }
                }
                return@execute
            }
            // Cancelled mid-load: skip start() so the mic/permission dialog
            // can never appear after the keyboard hid.
            if (generation != requestGeneration) return@execute
            try {
                mic?.start()
            } catch (e: Exception) {
                main.post {
                    if (!destroyed && engineState == EngineState.LOADING &&
                        generation == requestGeneration
                    ) {
                        engineState = EngineState.FAILED
                        setStatus(getString(R.string.ime_status_failed))
                        refreshMicButton()
                    }
                }
                return@execute
            }
            main.post {
                if (!destroyed && engineState == EngineState.LOADING &&
                    generation == requestGeneration
                ) {
                    engineState = EngineState.LISTENING
                    setStatus("")
                    refreshMicButton()
                }
                // Else a queued mic.stop() already follows on the worker.
            }
        }
    }

    private fun stopDictation() {
        when (engineState) {
            EngineState.LISTENING -> {
                engineState = EngineState.READY
                setStatus("")
                refreshMicButton()
                // The trailing final arrives after stop() returns; the session stays live for it.
                mic?.stop()
            }
            EngineState.LOADING -> {
                // Worker is inside blocking load()/start(): enter the
                // non-startable STOPPING state, end the session, and queue
                // mic.stop() strictly after the in-flight job. Only the
                // ack below re-exposes READY.
                engineState = EngineState.STOPPING
                requestGeneration++ // Invalidate the in-flight request.
                session = null
                setStatus("")
                refreshMicButton()
                worker.execute {
                    mic?.stop()
                    main.post {
                        if (!destroyed && engineState == EngineState.STOPPING) {
                            engineState = EngineState.READY
                            refreshMicButton()
                            refreshStatus()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun onPartialText(text: String) {
        if (destroyed || engineState != EngineState.LISTENING) return
        val s = session ?: return
        s.connection.setComposingText(separatorFor(s) + text, 1)
    }

    private fun onFinalLine(line: TranscriptLine) {
        if (destroyed) return
        val s = session ?: return
        val text = line.text.orEmpty()
        if (text.isNotEmpty()) {
            // Replaces the composing (partial) region and commits the final line.
            s.connection.commitText(separatorFor(s) + text, 1)
        } else {
            s.connection.finishComposingText()
        }
        s.pendingSeparator = null
    }

    private fun separatorFor(s: Session): String {
        // Computed once per segment, from the text before the composing span.
        s.pendingSeparator?.let { return it }
        return TextJoining.leadingSeparator(s.connection.getTextBeforeCursor(1, 0))
            .also { s.pendingSeparator = it }
    }

    private fun onEngineError() {
        if (destroyed) return
        if (engineState != EngineState.LISTENING && engineState != EngineState.LOADING) return
        engineState = EngineState.FAILED
        setStatus(getString(R.string.ime_status_failed))
        refreshMicButton()
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
