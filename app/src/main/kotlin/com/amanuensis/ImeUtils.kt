package com.amanuensis

/**
 * Checks whether any flattened input-method component ID in the
 * colon-separated [android.provider.Settings.Secure.ENABLED_INPUT_METHODS]
 * value belongs to [packageName].
 *
 * Flat component IDs look like `com.example/.ImeService` or
 * `com.example/com.example.ImeService`. Matching on the exact
 * `"$packageName/"` prefix avoids false positives for sibling packages
 * such as `com.example.fake`.
 */
fun isPackageImeEnabled(enabledInputMethods: String?, packageName: String): Boolean {
    if (enabledInputMethods.isNullOrEmpty()) return false
    val prefix = "$packageName/"
    return enabledInputMethods.split(':').any { id ->
        // A flat component name always separates package from class by '/'.
        id == packageName || id.startsWith(prefix)
    }
}
