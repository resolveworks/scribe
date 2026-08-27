package works.resolve.scribe.ime

import android.view.inputmethod.EditorInfo

//
// Pure policy helpers for the IME. Kept free of Android object graphs so
// they are trivially unit-testable; the service feeds them plain values
// from android.view.inputmethod.EditorInfo and the input connection.
//

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

/**
 * One committed dictation line, recorded as lengths only — the text itself
 * is never kept, so the ledger cannot become a transcript. [deleteLength]
 * counts the separator that was prepended too, so one delete removes
 * exactly what one commit inserted.
 */
data class CommittedLine(
    val textLength: Int,
    val separatorLength: Int,
) {
    val deleteLength: Int get() = textLength + separatorLength
}

/**
 * How far back one backspace press deletes in text this keyboard did not
 * output: the whitespace run touching the cursor together with the word
 * before it, so each press removes one whole word (punctuation travels with
 * its word). Mirrors LatinIME's `deleteWord`, which walks the same two runs
 * and deletes the span in a single call.
 */
object WordDeletion {

    /** Char count before the cursor one press should remove; 0 for nothing. */
    fun length(textBeforeCursor: CharSequence?): Int {
        if (textBeforeCursor.isNullOrEmpty()) return 0
        var start = textBeforeCursor.length
        // The run touching the cursor and the run before it go together: a
        // word takes the whitespace before it, trailing whitespace takes
        // its word. One press, one word.
        val touchingWhitespace = textBeforeCursor[start - 1].isWhitespace()
        while (start > 0 && textBeforeCursor[start - 1].isWhitespace() == touchingWhitespace) start--
        while (start > 0 && textBeforeCursor[start - 1].isWhitespace() != touchingWhitespace) start--
        // A fetch window that begins mid-surrogate pair must not split it.
        if (start == 0 && textBeforeCursor[0].isLowSurrogate()) start = 1
        return textBeforeCursor.length - start
    }
}

/**
 * Decides what a backspace press should do, mirroring Gboard/LatinIME's
 * backspace decision tree: the keyboard's own unstable output is discarded
 * whole first, a selection beats reverting history, a committed line is
 * reverted in one edit only while the cursor still sits where our last edit
 * left it, and anything else — the text that was already in the field
 * before our lines — is deleted word by word.
 */
object Backspace {

    sealed interface Decision {
        /** A live partial is composing: discard the whole region in one shot. */
        data object DiscardPartial : Decision

        /** Text is selected: delete the selection. */
        data object DeleteSelection : Decision

        /** The last committed line is verifiably ours: revert it in one edit. */
        data class RevertLine(val length: Int) : Decision

        /** Nothing of ours applies: delete one preceding word in one edit. */
        data class DeleteWord(val length: Int) : Decision
    }

    fun decide(
        partialActive: Boolean,
        hasSelection: Boolean,
        lastCommitted: CommittedLine?,
        cursorVerified: Boolean,
        textBeforeCursor: CharSequence?,
    ): Decision = when {
        partialActive -> Decision.DiscardPartial
        hasSelection -> Decision.DeleteSelection
        lastCommitted != null && cursorVerified -> Decision.RevertLine(lastCommitted.deleteLength)
        else -> Decision.DeleteWord(WordDeletion.length(textBeforeCursor))
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
