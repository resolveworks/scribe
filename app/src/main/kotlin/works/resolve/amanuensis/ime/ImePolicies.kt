package works.resolve.amanuensis.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Pure policy helpers for the IME. Kept free of Android object graphs so they
 * are trivially unit-testable; the service feeds them values from
 * [android.view.inputmethod.EditorInfo].
 */
object EditorPolicy {

    /** What kind of editor the IME is attached to. */
    enum class FieldKind { DICTATABLE, SENSITIVE, UNSUPPORTED }

    /**
     * Classifies an `EditorInfo.inputType`. Password-style text fields (plain,
     * visible, and web variations) and numeric password fields are sensitive:
     * dictation is disabled for them. `TYPE_NULL` means
     * the editor did not declare an input type, so we cannot trust it.
     */
    fun classify(inputType: Int): FieldKind {
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (clazz) {
            InputType.TYPE_NULL -> FieldKind.UNSUPPORTED
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> FieldKind.SENSITIVE
                else -> FieldKind.DICTATABLE
            }
            InputType.TYPE_CLASS_NUMBER ->
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    FieldKind.SENSITIVE
                } else {
                    FieldKind.DICTATABLE
                }
            else -> FieldKind.DICTATABLE
        }
    }
}

/**
 * Decides what separator to place before a dictated segment, given the text
 * that already sits before the cursor. An empty string means
 * "no separator needed". When the editor will not tell us the preceding text
 * we insert nothing, which is the least surprising fallback.
 */
object TextJoining {
    fun leadingSeparator(textBeforeCursor: CharSequence?): String {
        if (textBeforeCursor.isNullOrEmpty()) return ""
        return if (textBeforeCursor.last().isWhitespace()) "" else " "
    }
}

/** Decides how the enter key should behave for a given `EditorInfo`. */
object EditorActions {

    sealed interface Decision {
        data class Perform(val actionId: Int) : Decision
        data object SendEnterKey : Decision
    }

    /**
     * A custom action declared via `EditorInfo.actionLabel` + `actionId`
     * (set by editors like `setImeActionLabel`) wins over `imeOptions`.
     */
    fun decide(actionId: Int, actionLabel: CharSequence?, imeOptions: Int): Decision {
        if (actionId != EditorInfo.IME_ACTION_NONE &&
            actionId != EditorInfo.IME_ACTION_UNSPECIFIED &&
            !actionLabel.isNullOrEmpty()
        ) {
            return Decision.Perform(actionId)
        }
        return decide(imeOptions)
    }

    fun decide(imeOptions: Int): Decision {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Decision.SendEnterKey
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_UNSPECIFIED,
            -> Decision.SendEnterKey
            else -> Decision.Perform(action)
        }
    }
}

/**
 * Decides whether dictation should begin by itself when the keyboard opens,
 * without waiting for a mic press.
 */
object AutoStartPolicy {

    /**
     * Auto-start only on a dictatable field with a confirmed-cached model and
     * the microphone permission already granted.
     *
     * - A [modelPresent] of null means the cache check is still running; the
     *   caller must defer (or do nothing), not start.
     * - A missing model is the setup flow's case, never a dictation request.
     * - Without the mic permission, starting would push the SDK's runtime
     *   permission dialog over the host app at every keyboard open until the
     *   user denies it twice — after which Android fixes (permanently denies)
     *   the permission and no dialog can ever be shown again. The setup flow
     *   owns permission requests; the IME never asks.
     * - [dictationActive] (a load in flight or an ongoing session) must not
     *   be restarted. A stop in flight is deliberately *not* active: the new
     *   request queues behind the ordered stop on the service's single
     *   serialized worker, so the two can never interleave.
     */
    fun shouldStartOnOpen(
        fieldKind: EditorPolicy.FieldKind,
        modelPresent: Boolean?,
        micPermissionGranted: Boolean,
        dictationActive: Boolean,
    ): Boolean =
        fieldKind == EditorPolicy.FieldKind.DICTATABLE &&
            modelPresent == true &&
            micPermissionGranted &&
            !dictationActive
}
