package works.resolve.scribe

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import android.content.Context
import java.io.File

/**
 * Shared view of the Moonshine speech-model cache — the single owner of
 * the model spec used by the app. Nothing else picks an arch or language:
 * setup downloads via [download], the IME checks [isDownloaded], and
 * [DictationEngine] loads from [directory].
 */
object MoonshineModel {

    /** The model architecture [DictationEngine] loads via loadFromFiles. */
    val arch: Int = JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING

    private fun spec(): ModelSpec =
        ModelSpec.stt("en", arch, false)

    /** The cache directory the spec resolves to; what [DictationEngine] loads from. */
    fun directory(context: Context): File =
        ModelCache.directoryFor(context, spec(), null)

    /**
     * True when every model file [DictationEngine] needs is already cached.
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
