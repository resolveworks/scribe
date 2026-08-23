package com.amanuensis

import android.Manifest
import android.content.Context
import android.content.Intent
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
import com.amanuensis.ui.setup.ModelState
import com.amanuensis.ui.setup.SetupScreen
import com.amanuensis.ui.theme.AmanuensisTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var imeEnabled by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var modelState by mutableStateOf(ModelState.NOT_DOWNLOADED)

    /** Overall download fraction; null while the size is unknown. */
    private var downloadProgress by mutableStateOf<Float?>(null)

    /** The Moonshine cache check and download block; keep them serialized off the main thread. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()

        setContent {
            AmanuensisTheme {
                SetupScreen(
                    imeEnabled = imeEnabled,
                    micGranted = micGranted,
                    modelState = modelState,
                    downloadProgress = downloadProgress,
                    onOpenImeSettings = ::openImeSettings,
                    onRequestMicPermission = ::requestMicPermission,
                    onDownloadModel = ::downloadModel,
                )
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        imeEnabled = isImeEnabled(this)
        micGranted = isMicGranted(this)
        if (modelState == ModelState.DOWNLOADING) return
        worker.execute {
            val downloaded = MoonshineModel.isDownloaded(this)
            runOnUiThread {
                if (modelState != ModelState.DOWNLOADING) {
                    modelState =
                        if (downloaded) ModelState.DOWNLOADED else ModelState.NOT_DOWNLOADED
                }
            }
        }
    }

    private fun openImeSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun requestMicPermission() {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun downloadModel() {
        if (modelState == ModelState.DOWNLOADING) return
        modelState = ModelState.DOWNLOADING
        downloadProgress = null
        worker.execute {
            val downloaded = MoonshineModel.download(this) { fraction ->
                runOnUiThread {
                    if (modelState == ModelState.DOWNLOADING) downloadProgress = fraction
                }
            }
            runOnUiThread {
                downloadProgress = null
                modelState = if (downloaded) ModelState.DOWNLOADED else ModelState.FAILED
            }
        }
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted || isMicGranted(this)
        }

    companion object {
        fun isImeEnabled(context: Context): Boolean =
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.any { it.packageName == context.packageName } == true

        fun isMicGranted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}
