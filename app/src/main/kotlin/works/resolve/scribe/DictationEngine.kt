package works.resolve.scribe

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import works.resolve.scribe.ime.AudioLevels

/**
 * Live microphone dictation on the base [Transcriber] — our own capture loop
 * replacing [ai.moonshine.voice.MicTranscriber], whose close() joins its
 * decode thread with a 1s timeout (upstream #223): a native decode that
 * outlasts the join frees the transcriber mid-decode and crashes.
 *
 * Thread model (the #223 fix — native calls are NEVER concurrent):
 *
 *  - `load()`, `stop()`, `close()` run on the caller's serialized worker;
 *    `load()` and `close()` (the model-level native calls) happen there.
 *  - Every stream call (`createStream`/`startStream`/`addAudioToStream`/
 *    `stopStream`/`freeStream`) happens ONLY on the single capture thread,
 *    which exists exactly while a session is running.
 *  - `stop()`/`close()` flip a volatile flag and `join()` the capture thread
 *    with NO timeout before any further native call, so the capture thread
 *    has finished its own `stopStream`/`freeStream` before `close()` can
 *    free the model. No bounded-join heuristic.
 *
 * Blocking: `load()` reads the cached model (setup owns downloads),
 * `stop()` waits for one final ~100ms read plus flush, `close()` does `stop()`
 * plus the native teardown. Keep all of them off the main thread.
 *
 * All callbacks ([onText], [onLine], [onError], [onLevel]) are delivered on
 * the main thread; the transcriber emits events inline from the capture
 * thread, so every dispatch is posted.
 */
internal class DictationEngine(
    private val context: Context,
    private val onText: (String) -> Unit,          // changing partial
    private val onLine: (TranscriptLine) -> Unit,  // finished line
    private val onError: () -> Unit,
    private val onLevel: (Float) -> Unit,          // 0..1 live loudness
) {
    private val main = Handler(Looper.getMainLooper())

    private val transcriber = Transcriber().apply {
        addListener { event ->
            when (event) {
                is TranscriptEvent.LineTextChanged ->
                    main.post { onText(event.line.text.orEmpty()) }
                is TranscriptEvent.LineCompleted ->
                    main.post { onLine(event.line) }
                is TranscriptEvent.Error ->
                    main.post(onError)
                else -> Unit
            }
        }
    }

    /** Cleared to end a session; read by the capture loop after every read. */
    @Volatile private var running = false

    /** Owned by the worker thread that runs start()/stop()/close(). */
    private var captureThread: Thread? = null

    /**
     * Loads the model from the cache directory (setup owns downloads; the
     * service only starts dictation after checking presence). Blocking
     * (file I/O, native); call on the worker. Throws on failure.
     */
    fun load() {
        try {
            val directory = MoonshineModel.directory(context)
            transcriber.loadFromFiles(directory.absolutePath, MoonshineModel.arch)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load the speech-to-text model", e)
        }
    }

    /**
     * Starts a capture session. Blocking only for thread creation; the
     * stream itself is created and started on the capture thread. Callable
     * again after [stop] (fresh thread, fresh stream). Call on the worker,
     * after [load]. The capture-thread check is the one guard that matters:
     * a double start would put two threads on native stream calls at once.
     */
    fun start() {
        check(captureThread == null) { "previous session not stopped" }
        running = true
        captureThread = Thread(this::captureLoop, "scribe-mic-capture").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Ends the session. Blocking: joins the capture thread with no timeout —
     * the loop self-terminates within one ~100ms read, and the capture
     * thread's trailing `stopStream` (which flushes the final line) and
     * `freeStream` complete before this returns. Safe no-op when idle.
     */
    fun stop() {
        running = false
        captureThread?.join()
        captureThread = null
    }

    /**
     * [stop] plus native teardown of the model. Terminal; callers drop
     * their reference afterwards. Idempotent by construction — stop() is a
     * no-op when idle and Transcriber.close() guards its own handle.
     */
    fun close() {
        stop()
        transcriber.close()
    }

    // -- Capture -------------------------------------------------------------

    // The service only starts dictation after RECORD_AUDIO is granted, and
    // any capture failure routes to onError instead of crashing.
    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferBytes = maxOf(minBytes, MIN_BUFFER_BYTES)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            ).also { check(it.state == AudioRecord.STATE_INITIALIZED) }
        } catch (_: Throwable) {
            main.post(onError)
            return
        }

        // Stream lifecycle lives entirely on this thread: created and
        // started here, stopped (flushing the trailing final line) and freed
        // in the finally below, before stop()'s join() can return.
        // -1 = no stream yet, mirroring Transcriber's own defaultStreamHandle sentinel.
        var streamHandle = -1
        var streamStarted = false
        try {
            recorder.startRecording()
            streamHandle = transcriber.createStream()
            transcriber.startStream(streamHandle)
            streamStarted = true

            val samples = ShortArray(READ_SAMPLES)
            while (running) {
                val read = recorder.read(samples, 0, READ_SAMPLES)
                if (read <= 0) {
                    // A dead read is a capture failure, not a quiet exit —
                    // otherwise the service stays LISTENING with no audio.
                    main.post(onError)
                    break
                }
                val chunk = if (read == samples.size) samples else samples.copyOf(read)
                val floats = FloatArray(read) { chunk[it] / 32768f }
                main.post { onLevel(AudioLevels.magnitudeOf(AudioLevels.rms(chunk))) }
                transcriber.addAudioToStream(streamHandle, floats, SAMPLE_RATE)
            }
        } catch (_: Throwable) {
            main.post(onError)
        } finally {
            try {
                if (streamStarted) transcriber.stopStream(streamHandle)
                if (streamHandle >= 0) transcriber.freeStream(streamHandle)
            } catch (_: Throwable) {
                // A failure above may have torn things down already.
            }
            recorder.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val READ_SAMPLES = 1600 // ~100ms at 16kHz
        const val MIN_BUFFER_BYTES = 3200
    }
}
