package works.resolve.scribe.ime

import kotlin.math.pow
import kotlin.math.sqrt

//
// Pure math for the mic-volume visualization. Kept free of Android object
// graphs so it is trivially unit-testable; the capture side feeds it raw
// PCM 16-bit chunks and the UI gets a smooth 0..1 loudness value.
//

/**
 * Maps raw mic PCM to a 0..1 "how loud is it right now" value.
 *
 * Raw RMS spans roughly 0.001..0.3 over normal speech, but a circle that
 * only reacts to shouts is useless. The perceptual curve
 * `1 - 0.1^(24 * rms)` compresses that dynamic range — the exponent makes
 * the mapping grow steeply at first — so ordinary speech reads visibly on
 * the pulsing circle while clipping still saturates near 1. Same approach
 * as FUTO's voice input.
 */
internal object AudioLevels {

    /**
     * Root-mean-square of [samples] normalized to [-1, 1] (each sample is
     * divided by `Short.MAX_VALUE`). Empty input yields 0f.
     */
    internal fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val v = s / Short.MAX_VALUE.toDouble()
            sum += v * v
        }
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * Perceptual loudness mapping `1 - 0.1^(24 * rms)`, coerced into
     * 0f..1f so negative or NaN input cannot escape the range.
     */
    internal fun magnitudeOf(rms: Float): Float =
        (1f - 0.1f.pow(24f * rms)).coerceIn(0f, 1f)
}
