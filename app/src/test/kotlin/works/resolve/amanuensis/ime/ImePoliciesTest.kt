package works.resolve.amanuensis.ime

import android.view.inputmethod.EditorInfo
import works.resolve.amanuensis.ime.EditorActions.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class TextJoiningTest {

    @Test
    fun noSeparatorForEmptyField() {
        assertEquals("", TextJoining.leadingSeparator(""))
    }

    @Test
    fun noSeparatorWhenNull() {
        assertEquals("", TextJoining.leadingSeparator(null))
    }

    @Test
    fun spaceAfterNonWhitespace() {
        assertEquals(" ", TextJoining.leadingSeparator("Hello"))
        assertEquals(" ", TextJoining.leadingSeparator("sentence."))
    }

    @Test
    fun noSeparatorAfterExistingWhitespace() {
        assertEquals("", TextJoining.leadingSeparator("Hello "))
        assertEquals("", TextJoining.leadingSeparator("one\t"))
        assertEquals("", TextJoining.leadingSeparator("\n"))
    }
}

class CommittedLineTest {

    @Test
    fun deleteLengthJoinsTextAndSeparator() {
        assertEquals(5, CommittedLine(textLength = 4, separatorLength = 1).deleteLength)
        assertEquals(4, CommittedLine(textLength = 4, separatorLength = 0).deleteLength)
    }
}

class WordDeletionTest {

    @Test
    fun nullOrEmptyDeletesNothing() {
        assertEquals(0, WordDeletion.length(null))
        assertEquals(0, WordDeletion.length(""))
    }

    @Test
    fun wordTakesItsLeadingWhitespace() {
        assertEquals(6, WordDeletion.length("Hello world")) // " world"
    }

    @Test
    fun wordAtStartOfFieldHasNoWhitespace() {
        assertEquals(5, WordDeletion.length("Hello"))
    }

    @Test
    fun whitespaceRunCollapsesWithItsWord() {
        // Trailing whitespace takes its word with it — one press, one word.
        assertEquals(6, WordDeletion.length("Hello ")) // "Hello "
        assertEquals(5, WordDeletion.length("one  two")) // "  two"
    }

    @Test
    fun punctuationTravelsWithItsWord() {
        assertEquals(6, WordDeletion.length("Hello, world")) // " world"
        assertEquals(6, WordDeletion.length("Hello,")) // "Hello," as one unit
    }

    @Test
    fun whitespaceOnlyFieldDeletesAllOfIt() {
        assertEquals(3, WordDeletion.length("   "))
    }

    @Test
    fun surrogatePairsAreNotSplit() {
        // The word plus its leading space, emoji intact: " \uD83D\uDE00"
        assertEquals(3, WordDeletion.length("hi \uD83D\uDE00"))
        // A window beginning on an orphan low surrogate must spare it.
        assertEquals(3, WordDeletion.length("\uDF06abc"))
    }
}

class BackspaceTest {

    private val line = CommittedLine(textLength = 10, separatorLength = 1)

    @Test
    fun livePartialIsDiscardedBeforeAnythingElse() {
        val decision = Backspace.decide(
            partialActive = true,
            hasSelection = true,
            lastCommitted = line,
            cursorVerified = true,
            textBeforeCursor = "anything",
        )
        assertEquals(Backspace.Decision.DiscardPartial, decision)
    }

    @Test
    fun selectionBeatsLineRevert() {
        val decision = Backspace.decide(
            partialActive = false,
            hasSelection = true,
            lastCommitted = line,
            cursorVerified = true,
            textBeforeCursor = "anything",
        )
        assertEquals(Backspace.Decision.DeleteSelection, decision)
    }

    @Test
    fun verifiedLineIsRevertedInOneEdit() {
        val decision = Backspace.decide(
            partialActive = false,
            hasSelection = false,
            lastCommitted = line,
            cursorVerified = true,
            textBeforeCursor = "anything",
        )
        assertEquals(Backspace.Decision.RevertLine(line.deleteLength), decision)
    }

    @Test
    fun movedCursorFallsBackToWordDeletion() {
        val decision = Backspace.decide(
            partialActive = false,
            hasSelection = false,
            lastCommitted = line,
            cursorVerified = false,
            textBeforeCursor = "prior text ",
        )
        assertEquals(Backspace.Decision.DeleteWord(5), decision)
    }

    @Test
    fun emptyLedgerDeletesWords() {
        val decision = Backspace.decide(
            partialActive = false,
            hasSelection = false,
            lastCommitted = null,
            cursorVerified = true,
            textBeforeCursor = "word",
        )
        assertEquals(Backspace.Decision.DeleteWord(4), decision)
    }

    @Test
    fun nothingBeforeCursorDeletesNothing() {
        val decision = Backspace.decide(
            partialActive = false,
            hasSelection = false,
            lastCommitted = null,
            cursorVerified = false,
            textBeforeCursor = "",
        )
        assertEquals(Backspace.Decision.DeleteWord(0), decision)
    }
}

class EditorActionsTest {

    @Test
    fun explicitActionIsPerformed() {
        val decision = EditorActions.decide(EditorInfo.IME_ACTION_SEND)
        assertEquals(Decision.Perform(EditorInfo.IME_ACTION_SEND), decision)
    }

    @Test
    fun searchAndDoneArePerformed() {
        assertEquals(
            Decision.Perform(EditorInfo.IME_ACTION_SEARCH),
            EditorActions.decide(EditorInfo.IME_ACTION_SEARCH),
        )
        assertEquals(
            Decision.Perform(EditorInfo.IME_ACTION_DONE),
            EditorActions.decide(EditorInfo.IME_ACTION_DONE),
        )
    }

    @Test
    fun noActionSendsEnterKey() {
        assertEquals(Decision.SendEnterKey, EditorActions.decide(EditorInfo.IME_ACTION_NONE))
    }

    @Test
    fun unspecifiedActionSendsEnterKey() {
        assertEquals(Decision.SendEnterKey, EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED))
    }

    @Test
    fun noEnterActionFlagSendsEnterKeyEvenWithAction() {
        val imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(Decision.SendEnterKey, EditorActions.decide(imeOptions))
    }

    @Test
    fun actionMaskedOutOfOtherFlags() {
        val imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NAVIGATE_NEXT
        assertEquals(Decision.Perform(EditorInfo.IME_ACTION_NEXT), EditorActions.decide(imeOptions))
    }

    @Test
    fun customActionLabelAndIdWinOverImeOptions() {
        val decision = EditorActions.decide(actionId = 42, actionLabel = "Go!", imeOptions = EditorInfo.IME_ACTION_DONE)
        assertEquals(Decision.Perform(42), decision)
    }

    @Test
    fun customActionWithNoneIdFallsBackToImeOptions() {
        val decision = EditorActions.decide(
            actionId = EditorInfo.IME_ACTION_NONE,
            actionLabel = "Go!",
            imeOptions = EditorInfo.IME_ACTION_DONE,
        )
        assertEquals(Decision.Perform(EditorInfo.IME_ACTION_DONE), decision)
    }

    @Test
    fun customActionIdWithoutLabelFallsBackToImeOptions() {
        val decision = EditorActions.decide(actionId = 42, actionLabel = null, imeOptions = EditorInfo.IME_ACTION_DONE)
        assertEquals(Decision.Perform(EditorInfo.IME_ACTION_DONE), decision)
    }

    @Test
    fun customActionWithEmptyLabelFallsBackToImeOptions() {
        val decision = EditorActions.decide(actionId = 42, actionLabel = "", imeOptions = EditorInfo.IME_ACTION_SEND)
        assertEquals(Decision.Perform(EditorInfo.IME_ACTION_SEND), decision)
    }
}
