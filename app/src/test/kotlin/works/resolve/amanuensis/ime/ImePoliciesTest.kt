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
