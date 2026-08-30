package works.resolve.scribe

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import works.resolve.scribe.ime.AudioLevels
import java.util.concurrent.LinkedBlockingQueue

/**
 * Live microphone dictation on the base [Transcriber] — our own capture loop
 * replacing [ai.moonshine.voice.MicTranscriber], whose close() joins its
 * decode thread with a 1s timeout (upstream #223): a native decode that
 * outlasts the join frees the transcriber mid-decode and crashes.
 *
 * It is also a producer/consumer split: a capture thread that only reads PCM
 * and a processing thread that owns every Moonshine call. Reading the mic and
 * transcribing it are serialized only through a lossless unbounded queue, so
 * a slow inference pass can never stall the mic (and its levels) and never
 * drops audio either — the queue absorbs the burst and inference batches it.
 *
 * Thread model (the #223 fix — native calls are NEVER concurrent):
 *
 *  - `load()` and `close()` (the model-level native calls) run on the
 *    caller's serialized worker. `load()` is idempotent: repeated keyboard
 *    openings reuse the loaded model instead of leaking native copies.
 *  - Every stream call — `start()`, `addAudio()`, `stop()` on the
 *    Transcriber's default stream — happens ONLY on the single processing
 *    thread of the current session.
 *  - `stop()`/`close()` stop and join the capture thread first (so no more
 *    audio arrives), then join the processing thread with NO timeout, so its
 *    trailing `Transcriber.stop()` (which flushes the final line) completes
 *    before `close()` can free the model. No bounded-join heuristic.
 *
 * Blocking: `load()` reads the cached model (setup owns downloads), `stop()`
 * waits for one ~32ms read plus the drain/flush, `close()` does `stop()` plus
 * the native teardown. Keep all of them off the main thread.
 *
 * All callbacks ([onText], [onLine], [onError], [onLevel]) are delivered on
 * the main thread; the transcriber emits events inline from the processing
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
                else -> Unit
            }
        }
    }

    /** Cleared to end a session; read by both threads after each step. */
    @Volatile private var running = false

    /** Owned by the worker thread that runs start()/stop()/close(). */
    private var sessionThreads: Pair<Thread, Thread>? = null

    /**
     * Loads the model from the cache directory (setup owns downloads; the
     * service only starts dictation after checking presence). Idempotent: a
     * no-op once loaded, so repeated keyboard openings reuse the model.
     * Blocking (file I/O, native); call on the worker. Throws on failure.
     */
    fun load() {
        if (transcriber.isLoaded) return
        val directory = MoonshineModel.directory(context)
        transcriber.loadFromFiles(directory.absolutePath, MoonshineModel.arch)
    }

    /**
     * Starts a session: a capture thread that owns only the AudioRecord, and
     * a processing thread that owns every stream call on the default stream.
     * Blocking only for thread creation. Callable again after [stop] (fresh
     * threads, fresh stream). Call on the worker, after [load]. The session
     * check is the one guard that matters: a double start would put two
     * threads on native stream calls at once.
     */
    fun start() {
        check(sessionThreads == null) { "previous session not stopped" }
        running = true
        val queue = LinkedBlockingQueue<FloatArray>()
        val processing = Thread({ processingLoop(queue) }, "scribe-moonshine").apply {
            isDaemon = true
            start()
        }
        val capture = Thread({ captureLoop(queue) }, "scribe-mic-capture").apply {
            isDaemon = true
            start()
        }
        sessionThreads = capture to processing
    }

    /**
     * Ends the session. Blocking: joins the capture thread with no timeout
     * (the loop self-terminates within one ~32ms read and enqueues the
     * end-of-stream marker), then joins the processing thread with no
     * timeout — its trailing `Transcriber.stop()` flushes the final line
     * before this returns. Safe no-op when idle.
     */
    fun stop() {
        val threads = sessionThreads ?: return
        running = false
        val (capture, processing) = threads
        capture.join()
        processing.join()
        sessionThreads = null
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

    /**
     * The capture thread owns the AudioRecord and nothing else. It reads
     * small (~32ms) chunks so [onLevel] stays live even while inference
     * batches behind it, computes the level from the fresh PCM, and enqueues
     * the chunk for the processing thread. Whatever way it exits — normal
     * stop, init or read failure — it enqueues the end-of-stream marker
     * after its last audio, so the processing thread always terminates.
     */
    // The service only starts dictation after RECORD_AUDIO is granted, and
    // any capture failure routes to onError instead of crashing.
    @SuppressLint("MissingPermission")
    private fun captureLoop(queue: LinkedBlockingQueue<FloatArray>) {
        var recorder: AudioRecord? = null
        var recordingStarted = false
        try {
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val bufferBytes = maxOf(minBytes, MIN_BUFFER_BYTES)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            ).also { check(it.state == AudioRecord.STATE_INITIALIZED) }

            recorder.startRecording()
            recordingStarted = true
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
                val level = AudioLevels.magnitudeOf(AudioLevels.rms(chunk))
                val floats = FloatArray(read) { chunk[it] / 32768f }
                // Levels come from the fresh PCM, not the backlog, so the
                // meter tracks the voice even when inference is batching.
                main.post { onLevel(level) }
                // Lossless: the queue is unbounded, so inference lag slows
                // nothing on the mic side and drops no audio.
                queue.offer(floats)
            }
        } catch (_: Exception) {
            main.post(onError)
        } finally {
            if (recordingStarted) recorder?.stop()
            recorder?.release()
            // FIFO marker after the last captured audio; the unbounded queue
            // makes add() non-blocking.
            queue.add(END_OF_STREAM)
        }
    }

    // -- Processing ----------------------------------------------------------

    /**
     * The processing thread owns every Moonshine stream call for the
     * session. It drains queued chunks in batches (one `addAudio` per drain)
     * and, on the end-of-stream marker, flushes the final line via
     * `Transcriber.stop()` — all before stop()'s join() can return.
     */
    private fun processingLoop(queue: LinkedBlockingQueue<FloatArray>) {
        var streamStarted = false
        try {
            transcriber.start()
            streamStarted = true
            val batch = ArrayList<FloatArray>()
            while (true) {
                batch.add(queue.take())
                queue.drainTo(batch)
                // Capture is the only producer and enqueues EOS last, so if
                // this batch contains it, it can only be the final element.
                val endOfStream = batch.last() === END_OF_STREAM
                if (endOfStream) batch.removeAt(batch.lastIndex)
                val samples = batch.sumOf { it.size }
                if (samples > 0) {
                    val audio = FloatArray(samples)
                    var offset = 0
                    for (c in batch) {
                        System.arraycopy(c, 0, audio, offset, c.size)
                        offset += c.size
                    }
                    transcriber.addAudio(audio, SAMPLE_RATE)
                }
                batch.clear()
                if (endOfStream) break
            }
        } catch (_: Exception) {
            // End capture promptly: its next ~32ms read exits the loop (and
            // enqueues EOS), rather than queueing audio nobody will use.
            running = false
            main.post(onError)
        } finally {
            // Flush the trailing final line before stop()'s join returns.
            if (streamStarted) transcriber.stop()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val READ_SAMPLES = 512 // ~32ms at 16kHz
        const val MIN_BUFFER_BYTES = 2048

        /** Marks the end of the stream; its empty size means "no audio". */
        val END_OF_STREAM = FloatArray(0)
    }
}
