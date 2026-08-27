package works.resolve.scribe

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import android.content.Context
import java.io.File

/**
 * Shared view of the Moonshine speech-model cache.
 *
 * Setup uses it to check and download the model ahead of time; the IME uses it
 * to decide whether dictation is possible without paying for a blocking
 * [ai.moonshine.voice.MicTranscriber.load] first.
 *
 * The spec mirrors MicTranscriber's defaults (English, medium streaming arch,
 * no spelling model), so this resolves the exact directory MicTranscriber
 * downloads into and reads from — a model downloaded here is found by load().
 */
object MoonshineModel {

    private fun spec(): ModelSpec =
        ModelSpec.stt("en", JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING, false)

    private fun directory(context: Context): File =
        ModelCache.directoryFor(context, spec(), null)

    /**
     * True when every model file MicTranscriber needs is already cached.
     * Blocking (JNI catalog + file I/O); keep off the main thread.
     */
    fun isDownloaded(context: Context): Boolean =
        AssetDownloader().isModelPresent(directory(context), spec())

    /**
     * Downloads any missing model files into the cache. Blocking network I/O;
     * [onProgress] reports an overall 0..1 fraction (null while the total size
     * is unknown) from the downloader thread. Returns false on failure; a
     * partial download stays on disk and resumes on the next call.
     */
    fun download(context: Context, onProgress: (Float?) -> Unit): Boolean =
        try {
            AssetDownloader().ensureModelPresent(directory(context), spec()) {
                    _, fileIndex, totalFiles, bytesDownloaded, bytesTotal ->
                val withinFile =
                    if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal else null
                onProgress(
                    if (withinFile != null && totalFiles > 0) {
                        (fileIndex - 1 + withinFile) / totalFiles
                    } else {
                        null
                    }
                )
            }
            true
        } catch (e: Exception) {
            false
        }
}
