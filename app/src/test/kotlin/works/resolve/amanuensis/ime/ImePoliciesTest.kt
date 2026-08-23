package works.resolve.amanuensis.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import works.resolve.amanuensis.ime.EditorActions.Decision
import works.resolve.amanuensis.ime.EditorPolicy.FieldKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorPolicyTest {

    @Test
    fun plainTextFieldIsDictatable() {
        assertEquals(FieldKind.DICTATABLE, EditorPolicy.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun multiLineAndEmailFieldsAreDictatable() {
        assertEquals(
            FieldKind.DICTATABLE,
            EditorPolicy.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        )
        assertEquals(
            FieldKind.DICTATABLE,
            EditorPolicy.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
    }

    @Test
    fun passwordVariationsAreSensitive() {
        assertEquals(
            FieldKind.SENSITIVE,
            EditorPolicy.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
        assertEquals(
            FieldKind.SENSITIVE,
            EditorPolicy.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        )
        assertEquals(
            FieldKind.SENSITIVE,
            EditorPolicy.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        )
    }

    @Test
    fun numericPasswordIsSensitive() {
        assertEquals(
            FieldKind.SENSITIVE,
            EditorPolicy.classify(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
    }

    @Test
    fun plainNumericFieldIsDictatable() {
        assertEquals(
            FieldKind.DICTATABLE,
            EditorPolicy.classify(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL),
        )
    }

    @Test
    fun webPasswordFieldWithFlagsStillDetected() {
        val inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertEquals(FieldKind.SENSITIVE, EditorPolicy.classify(inputType))
    }

    @Test
    fun typeNullIsUnsupported() {
        assertEquals(FieldKind.UNSUPPORTED, EditorPolicy.classify(InputType.TYPE_NULL))
        assertEquals(FieldKind.UNSUPPORTED, EditorPolicy.classify(0))
    }
}

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

class AutoStartPolicyTest {

    @Test
    fun startsOnDictatableFieldWithCachedModelAndMicPermission() {
        assertEquals(
            true,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.DICTATABLE,
                modelPresent = true,
                micPermissionGranted = true,
                dictationActive = false,
            ),
        )
    }

    @Test
    fun neverStartsInSensitiveOrUnsupportedFields() {
        assertEquals(
            false,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.SENSITIVE,
                modelPresent = true,
                micPermissionGranted = true,
                dictationActive = false,
            ),
        )
        assertEquals(
            false,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.UNSUPPORTED,
                modelPresent = true,
                micPermissionGranted = true,
                dictationActive = false,
            ),
        )
    }

    @Test
    fun defersWhileModelCheckIsInFlight() {
        assertEquals(
            false,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.DICTATABLE,
                modelPresent = null,
                micPermissionGranted = true,
                dictationActive = false,
            ),
        )
    }

    @Test
    fun missingModelNeverAutoStarts() {
        assertEquals(
            false,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.DICTATABLE,
                modelPresent = false,
                micPermissionGranted = true,
                dictationActive = false,
            ),
        )
    }

    @Test
    fun noMicPermissionNeverAutoStarts() {
        // Auto-starting without the permission would fire the runtime
        // permission dialog over the host app; repeated denials make Android
        // permanently deny the permission.
        assertEquals(
            false,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.DICTATABLE,
                modelPresent = true,
                micPermissionGranted = false,
                dictationActive = false,
            ),
        )
    }

    @Test
    fun doesNotRestartAnActiveDictation() {
        assertEquals(
            false,
            AutoStartPolicy.shouldStartOnOpen(
                FieldKind.DICTATABLE,
                modelPresent = true,
                micPermissionGranted = true,
                dictationActive = true,
            ),
        )
    }
}
