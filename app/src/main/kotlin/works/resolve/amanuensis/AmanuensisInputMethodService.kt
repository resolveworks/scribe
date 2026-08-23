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
import works.resolve.amanuensis.ime.EditorPolicy
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
     * The InputConnection captured when the current dictation session started.
     * A late final callback is dropped unless it still matches the editor the
     * user was dictating into.
     */
    private var sessionConnection: InputConnection? = null

    /** Separator computed for the line currently being dictated, until committed. */
    private var pendingSeparator: String? = null

    /** Committed text of the current session, shown in the preview. */
    private val sessionText = StringBuilder()

    /** Current partial (uncommitted) text of the session, shown in the preview. */
    private var partialText: String = ""

    /** Monotonic id of the latest load/start request; see [startDictation]. */
    @Volatile private var requestGeneration = 0

    /**
     * Whether the Moonshine model is cached. Null until the first (async)
     * cache check completes. True is sticky for the process lifetime; false
     * re-checks on every input start, so a model downloaded in setup later
     * in this same process is picked up.
     */
    @Volatile private var modelPresent: Boolean? = null

    /** Guards against repeatedly pushing the setup screen over the host app. */
    private var setupPromptShown = false

    /**
     * Set when the keyboard opens while the model-cache check is still in
     * flight; the check's callback then starts dictation instead of leaving
     * the keyboard sitting idle until a mic press.
     */
    private var autoStartPending = false

    private var fieldKind = EditorPolicy.FieldKind.DICTATABLE

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
        // close() interrupts threads and joins; never run it on the main
        // thread, and never concurrently with a blocking load()/start().
        worker.execute { runCatching { m?.close() } }
        worker.shutdown()
        inputComposeView?.disposeComposition()
        inputComposeView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        inputComposeView?.disposeComposition()
        applyFieldKind()
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        fieldKind = EditorPolicy.classify(info?.inputType ?: 0)
        autoStartPending = false
        if (modelPresent != true) {
            // The model may have been downloaded in setup since the last look;
            // re-check the cache off the main thread.
            checkModel(launchSetupIfMissing = !restarting)
        }
        // A trailing final from a normal stop may still be in flight for the
        // editor we were just dictating into; only if a *different* editor is
        // now focused do we wipe the session, so an old editor's transcript
        // is never displayed or committed into the new field.
        val sameEditor = sessionConnection !== null && sessionConnection === currentInputConnection
        stopDictation()
        if (!sameEditor) {
            sessionConnection = null
            pendingSeparator = null
            sessionText.setLength(0)
            partialText = ""
            setPreview("")
        }
        applyFieldKind()
        refreshMicButton()
        // The keyboard just opened: begin dictating right away instead of
        // waiting for a mic press.
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

    private fun applyFieldKind() {
        when (fieldKind) {
            EditorPolicy.FieldKind.SENSITIVE ->
                setStatus(getString(R.string.ime_status_sensitive))
            EditorPolicy.FieldKind.UNSUPPORTED ->
                setStatus(getString(R.string.ime_status_unsupported))
            EditorPolicy.FieldKind.DICTATABLE -> when {
                modelPresent == false ->
                    setStatus(getString(R.string.ime_status_model_missing))
                !micPermissionGranted() ->
                    setStatus(getString(R.string.ime_status_permission_missing))
                modelPresent == null ->
                    // First model-cache check still running.
                    setStatus(getString(R.string.ime_status_loading))
                else -> when (engineState) {
                    EngineState.LOADING, EngineState.STOPPING -> setStatus(getString(R.string.ime_status_loading))
                    EngineState.LISTENING -> setStatus(getString(R.string.ime_status_listening))
                    EngineState.FAILED -> setStatus(getString(R.string.ime_status_failed))
                    else -> setStatus(getString(R.string.ime_status_idle))
                }
            }
        }
        if (fieldKind != EditorPolicy.FieldKind.DICTATABLE) {
            setPreview("")
        } else {
            setPreview(sessionText.toString() + (pendingSeparator ?: "") + partialText)
        }
    }

    private fun micEnabled(): Boolean =
        fieldKind == EditorPolicy.FieldKind.DICTATABLE &&
            engineState != EngineState.LOADING &&
            engineState != EngineState.STOPPING

    /**
     * Cheap synchronous check; the IME never requests the permission itself.
     * Without it, start() would push the SDK's permission dialog over the
     * host app at every open until Android permanently denies the permission
     * — setup owns requests.
     */
    private fun micPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // -- Auto-start ----------------------------------------------------------

    /**
     * Whether a dictation is loading or already running; such a session is
     * never auto-restarted. A stop in flight (STOPPING) is deliberately not
     * "active": the serialized worker runs the queued mic.stop() before any
     * newly queued load/start, so auto-starting over it is safe.
     */
    private fun dictationActive(): Boolean =
        engineState == EngineState.LOADING || engineState == EngineState.LISTENING

    /**
     * Starts dictation without a mic press whenever the keyboard opens on a
     * field that accepts dictated text. While the model-cache check is still
     * in flight the decision is deferred to its callback via
     * [autoStartPending].
     */
    private fun maybeAutoStartDictation() {
        if (fieldKind != EditorPolicy.FieldKind.DICTATABLE) return
        when (modelPresent) {
            null -> autoStartPending = true
            true -> if (
                AutoStartPolicy.shouldStartOnOpen(
                    fieldKind,
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
                applyFieldKind()
                refreshMicButton()
                if (autoStartPending) {
                    autoStartPending = false
                    maybeAutoStartDictation()
                }
            }
        }
    }

    private fun openSetupScreen() {
        // The model is downloaded here during setup; bring it forward at the
        // point of use, the same way the system mic-permission prompt appears.
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
        val ic = currentInputConnection ?: return
        sessionConnection = ic
        pendingSeparator = null
        sessionText.setLength(0)
        partialText = ""
        setPreview("")
        engineState = EngineState.LOADING
        val generation = ++requestGeneration
        setStatus(getString(R.string.ime_status_loading))
        refreshMicButton()
        // load() downloads the model on first use and start() may block on a
        // permission prompt; both stay off the main thread on the single
        // serialized worker so lifecycle operations can never interleave.
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
            // Cancelled while load() was blocking: do not call start() at
            // all, so the permission dialog/microphone can never pop up
            // after the keyboard hid. The ordered stop queued by
            // stopDictation() follows on this same serialized worker and
            // acknowledges STOPPING -> READY.
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
                    setStatus(getString(R.string.ime_status_listening))
                    refreshMicButton()
                }
                // Otherwise the IME was hidden or the session stopped while
                // the blocking load()/start() was in flight: stopDictation()
                // already queued an ordered mic.stop() behind this job, so
                // the microphone cannot keep recording.
            }
        }
    }

    private fun stopDictation() {
        when (engineState) {
            EngineState.LISTENING -> {
                engineState = EngineState.READY
                setStatus(getString(R.string.ime_status_idle))
                refreshMicButton()
                // Non-blocking; Moonshine flushes the trailing line, which
                // arrives as a final onLine callback while the connection
                // guard is still valid.
                mic?.stop()
            }
            EngineState.LOADING -> {
                // The worker is still inside blocking load()/start(). Enter
                // the non-startable STOPPING state, invalidate the session
                // so a late final callback can never be committed, and queue
                // an ordered mic.stop() that runs strictly after the
                // in-flight load/start job — the serialized executor
                // guarantees the ordering. Calling stop() now instead would
                // race start() setting its running flag and be lost. Only
                // the acknowledgement below re-exposes READY, so no second
                // request can start against the dying one.
                engineState = EngineState.STOPPING
                requestGeneration++ // Invalidate the in-flight load/start request.
                sessionConnection = null
                pendingSeparator = null
                setStatus(getString(R.string.ime_status_loading))
                refreshMicButton()
                worker.execute {
                    mic?.stop()
                    main.post {
                        if (!destroyed && engineState == EngineState.STOPPING) {
                            engineState = EngineState.READY
                            setStatus(getString(R.string.ime_status_idle))
                            refreshMicButton()
                            applyFieldKind()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun onPartialText(text: String) {
        if (destroyed || engineState != EngineState.LISTENING) return
        val ic = sessionConnection ?: return
        if (currentInputConnection !== ic) return
        // Sensitive fields must never preview or receive dictated content.
        if (fieldKind != EditorPolicy.FieldKind.DICTATABLE) return
        partialText = text
        ic.setComposingText(separatorFor(ic) + text, 1)
        setPreview(sessionText.toString() + (pendingSeparator ?: "") + text)
    }

    private fun onFinalLine(line: TranscriptLine) {
        if (destroyed || fieldKind != EditorPolicy.FieldKind.DICTATABLE) return
        val ic = sessionConnection ?: return
        if (currentInputConnection !== ic) return
        val text = line.text.orEmpty()
        if (text.isNotEmpty()) {
            // Replaces the composing (partial) region and commits the final line.
            ic.commitText(separatorFor(ic) + text, 1)
            sessionText.append(pendingSeparator ?: "").append(text)
        } else {
            ic.finishComposingText()
        }
        partialText = ""
        pendingSeparator = null
        setPreview(sessionText.toString())
    }

    private fun separatorFor(ic: InputConnection): String {
        // Computed once per segment, from the text before the composing span.
        pendingSeparator?.let { return it }
        val separator = TextJoining.leadingSeparator(ic.getTextBeforeCursor(1, 0))
        pendingSeparator = separator
        return separator
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
            // Replace the active selection with nothing: commitText("") is
            // the documented way to delete selected text.
            ic.commitText("", 1)
        } else {
            // No selection: delete exactly one Unicode code point, so
            // surrogate pairs (emoji) and other non-BMP characters go in a
            // single press.
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

    private fun setPreview(text: String) {
        uiState = uiState.copy(preview = text)
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
