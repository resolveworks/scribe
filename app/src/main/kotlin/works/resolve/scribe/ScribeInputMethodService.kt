package works.resolve.scribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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
import works.resolve.scribe.ime.Backspace
import works.resolve.scribe.ime.CommittedLine
import works.resolve.scribe.ime.EditorActions
import works.resolve.scribe.ime.TextJoining
import works.resolve.scribe.ui.ime.ImeKeyboard
import works.resolve.scribe.ui.ime.ImeUiState
import works.resolve.scribe.ui.ime.MicVisualState
import works.resolve.scribe.ui.theme.ScribeTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** How much text before the cursor to inspect for word deletion at once. */
private const val WORD_WINDOW_CHARS = 256

/** Upper bound for the growing window, to keep the fetch bounded. */
private const val WORD_WINDOW_MAX_CHARS = 4096

/**
 * Scribe voice IME.
 *
 * Moonshine usage follows the binding's contract: construction is cheap, the
 * blocking [MicTranscriber.load] / [MicTranscriber.start] run on one
 * serialized background executor, `onText` is treated as a changing partial
 * (composing text), `onLine` as a finished line (committed text), and the
 * model stays loaded and reusable while the service lives.
 */
class ScribeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private enum class EngineState { IDLE, LOADING, STOPPING, LISTENING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService

    private var mic: MicTranscriber? = null

    /** Set in [onDestroy]; all Moonshine callbacks are gated on it. */
    @Volatile private var destroyed = false

    @Volatile private var engineState = EngineState.IDLE

    /** Separator for the line being dictated, until it is committed. */
    private var pendingSeparator: String? = null

    /**
     * Backspace ledger, Gboard-style: the lengths of each committed line
     * (separator included), never the text itself. One press reverts the
     * most recent entry while [expectedCursor] still verifies.
     */
    private val committedLines = ArrayDeque<CommittedLine>()

    /** True while a dictated partial is set as composing text. */
    private var partialActive = false

    /** Cursor position after our last ledger edit; null when unknown. */
    private var expectedCursor: Int? = null

    /** Null until the first async cache check; false re-checks, so a model downloaded in setup mid-process is picked up. */
    private var modelPresent: Boolean? = null

    /** True once setup has been pushed over the host app; a prerequisite still missing after that returns the user to their previous keyboard. */
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
        refreshMicButton()
        // A window Recomposer looks up its owners from the IME window's root,
        // not only from the returned input view, so install them on both.
        window?.window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@ScribeInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@ScribeInputMethodService)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ScribeInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@ScribeInputMethodService)
            setContent {
                ScribeTheme {
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

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // A new (or restarted) field no longer matches anything we recorded.
        forgetLedger()
        partialActive = false
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        when {
            // The model may have been downloaded in setup since the last look.
            modelPresent != true -> checkModel()
            !micPermissionGranted() -> handlePrerequisiteMissing()
        }
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
                if (!present || !micPermissionGranted()) {
                    handlePrerequisiteMissing()
                } else {
                    maybeAutoStartDictation()
                }
                refreshMicButton()
            }
        }
    }

    /**
     * A setup-fixable prerequisite (model or microphone permission) is
     * missing. The first time, setup is pushed at the point of use; if the
     * user already saw setup and came back without fixing things, silently
     * return to their previous keyboard.
     */
    private fun handlePrerequisiteMissing() {
        if (!setupPromptShown) {
            setupPromptShown = true
            openSetupScreen()
        } else {
            switchToPreviousKeyboard()
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
            refreshMicButton()
        }
    }

    private fun stopDictation() {
        when (engineState) {
            EngineState.LISTENING -> {
                engineState = EngineState.IDLE
                refreshMicButton()
                // onFinalLine still accepts the trailing final posted after stop().
                mic?.stop()
            }
            EngineState.LOADING -> {
                // start() sets its running flag last, so stop must run after
                // the in-flight worker job or start would undo it.
                engineState = EngineState.STOPPING
                pendingSeparator = null
                refreshMicButton()
                worker.execute {
                    mic?.stop()
                    main.post {
                        if (!destroyed && engineState == EngineState.STOPPING) {
                            engineState = EngineState.IDLE
                            refreshMicButton()
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
        partialActive = true
    }

    private fun onFinalLine(line: TranscriptLine) {
        if (destroyed) return
        val connection = currentInputConnection ?: return
        val text = line.text.orEmpty()
        if (text.isNotEmpty()) {
            // Replaces the composing (partial) region and commits the final line.
            val separator = separatorForCurrentField()
            connection.commitText(separator + text, 1)
            // Ledger entry for backspace: lengths only, never the text.
            committedLines.addLast(
                CommittedLine(textLength = text.length, separatorLength = separator.length)
            )
            expectedCursor = cursorPosition(connection)
        } else {
            connection.finishComposingText()
        }
        partialActive = false
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

    /**
     * Gboard's backspace design: what this keyboard itself inserted is
     * reverted whole — live partial first, then selection, then the last
     * committed line — and once nothing of ours applies, deletion continues
     * word by word through the text that was already in the field. The pure
     * decision tree lives in [Backspace.decide].
     */
    private fun deleteBackwards() {
        val ic = currentInputConnection ?: return
        val decision = Backspace.decide(
            partialActive = partialActive,
            hasSelection = !ic.getSelectedText(0).isNullOrEmpty(),
            lastCommitted = committedLines.lastOrNull(),
            cursorVerified = expectedCursor != null && cursorPosition(ic) == expectedCursor,
            textBeforeCursor = textBeforeCursor(ic),
        )
        when (decision) {
            Backspace.Decision.DiscardPartial -> {
                // An empty commit replaces (removes) the composing region in
                // one shot, discarding the whole unstable partial.
                ic.commitText("", 1)
                partialActive = false
            }
            Backspace.Decision.DeleteSelection -> {
                // commitText("") is the documented way to delete selected text.
                ic.commitText("", 1)
                // The selection removed text our ledger still claims.
                forgetLedger()
            }
            is Backspace.Decision.RevertLine -> {
                ic.beginBatchEdit()
                // Armor only: a live partial took the branch above, so there
                // is no composing region left to finalize here.
                ic.finishComposingText()
                // One edit removes the line and the separator we prepended,
                // like Gboard reverting a committed word with its separator.
                ic.deleteSurroundingText(decision.length, 0)
                ic.endBatchEdit()
                committedLines.removeLast()
                expectedCursor = expectedCursor?.minus(decision.length)
            }
            is Backspace.Decision.DeleteWord -> {
                // Nothing of ours is left — or the ledger no longer matches
                // the field — so backspace keeps going through the text that
                // was there before our lines, one word per press.
                forgetLedger()
                if (decision.length > 0) {
                    // Char lengths from the window scan, which never splits a
                    // surrogate pair; a single edit removes the whole word.
                    ic.deleteSurroundingText(decision.length, 0)
                }
            }
        }
    }

    /**
     * Text before the cursor, fetched in growing windows until a fetch comes
     * back shorter than requested (the start of the field was reached), so a
     * word longer than one window is still measured whole — the same walk
     * LatinIME's `deleteWord` does.
     */
    private fun textBeforeCursor(ic: InputConnection): CharSequence? {
        var window = WORD_WINDOW_CHARS
        while (true) {
            val text = ic.getTextBeforeCursor(window, 0) ?: return null
            if (text.length < window || window >= WORD_WINDOW_MAX_CHARS) return text
            window *= 2
        }
    }

    /** Drops all backspace history: it no longer matches the field. */
    private fun forgetLedger() {
        committedLines.clear()
        expectedCursor = null
    }

    /**
     * The absolute cursor position, or null when the editor will not tell us
     * (no extracted text, or a selection). This is the ledger's Google-style
     * verification: after each committed line we record where our edit left
     * the cursor, and backspace may only revert history while it is still
     * there. Anything else means the user or the app changed the field.
     */
    private fun cursorPosition(ic: InputConnection): Int? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionStart != extracted.selectionEnd) {
            return null
        }
        return extracted.startOffset + extracted.selectionEnd
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        // The app inserts (or acts on) the newline itself, so our recorded
        // lengths and cursor position no longer describe the field.
        forgetLedger()
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
