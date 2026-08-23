package com.amanuensis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.amanuensis.ui.setup.SetupScreen
import com.amanuensis.ui.theme.AmanuensisTheme

class MainActivity : ComponentActivity() {

    private var imeEnabled by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()

        setContent {
            AmanuensisTheme {
                SetupScreen(
                    imeEnabled = imeEnabled,
                    micGranted = micGranted,
                    onOpenImeSettings = ::openImeSettings,
                    onShowPicker = ::showInputMethodPicker,
                    onRequestMicPermission = ::requestMicPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        imeEnabled = isImeEnabled(this)
        micGranted = isMicGranted(this)
    }

    private fun openImeSettings() {
        startActivity(
            android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
        )
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        // Conventional, safe way to show the system picker; a no-op on the
        // rare devices where it is not supported.
        runCatching { imm.showInputMethodPicker() }
    }

    private fun requestMicPermission() {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted || isMicGranted(this)
        }

    companion object {
        fun isImeEnabled(context: Context): Boolean = isPackageImeEnabled(
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
            ),
            context.packageName,
        )

        fun isMicGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
