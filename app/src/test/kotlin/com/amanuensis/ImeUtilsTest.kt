package com.amanuensis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeUtilsTest {
    private val packageName = "com.amanuensis"

    @Test
    fun enabledWhenFlatComponentWithAbbreviatedClassIsPresent() {
        val enabled = "some.other.ime/.ImeService:com.amanuensis/.AmanuensisInputMethodService"
        assertTrue(isPackageImeEnabled(enabled, packageName))
    }

    @Test
    fun enabledWhenFlatComponentWithFullyQualifiedClassIsPresent() {
        val enabled = "com.amanuensis/com.amanuensis.AmanuensisInputMethodService"
        assertTrue(isPackageImeEnabled(enabled, packageName))
    }

    @Test
    fun notEnabledWhenOtherImesOnly() {
        val enabled = "some.other.ime/.ImeService:com.google.android.inputmethod.latin/.LatinIME"
        assertFalse(isPackageImeEnabled(enabled, packageName))
    }

    @Test
    fun notEnabledWhenNull() {
        assertFalse(isPackageImeEnabled(null, packageName))
    }

    @Test
    fun notEnabledWhenEmpty() {
        assertFalse(isPackageImeEnabled("", packageName))
    }

    @Test
    fun siblingPackageDoesNotFalsePositive() {
        // Regression: startsWith(packageName) alone would match this.
        val enabled = "com.amanuensis.fake/.ImeService"
        assertFalse(isPackageImeEnabled(enabled, packageName))
    }

    @Test
    fun siblingPackageAmongOthersDoesNotFalsePositive() {
        val enabled = "com.amanuensis.fake/.ImeService:other.pkg/.Ime"
        assertFalse(isPackageImeEnabled(enabled, packageName))
    }
}
