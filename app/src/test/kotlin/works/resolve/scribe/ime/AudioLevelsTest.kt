package works.resolve.scribe.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelsTest {

    @Test
    fun silenceIsZeroAllTheWayThrough() {
        val rms = AudioLevels.rms(ShortArray(1024))
        assertEquals(0f, rms, 0f)
        assertEquals(0f, AudioLevels.magnitudeOf(rms), 1e-6f)
    }

    @Test
    fun fullScaleSquareWaveSaturates() {
        val samples = ShortArray(1024) { if (it % 2 == 0) 32767 else -32767 }
        val rms = AudioLevels.rms(samples)
        assertEquals(1f, rms, 1e-4f)
        assertEquals(1f, AudioLevels.magnitudeOf(rms), 1e-4f)
    }

    @Test
    fun tenPercentAmplitudeLandsMidScale() {
        val samples = ShortArray(1024) { 3276 }
        val rms = AudioLevels.rms(samples)
        assertEquals(3276f / 32767f, rms, 1e-4f) // ≈ 0.0999
        // 1 - 0.1^(24 * 0.0999) = 1 - 0.1^2.4 ≈ 0.996: the curve is already
        // near saturation at a tenth of full scale — that is the point.
        assertEquals(0.996f, AudioLevels.magnitudeOf(rms), 0.01f)
        // The ≈0.42 mark sits at one percent amplitude instead.
        assertEquals(0.425f, AudioLevels.magnitudeOf(0.01f), 0.01f)
    }

    @Test
    fun magnitudeIncreasesWithRms() {
        var previous = -1f
        var rms = 0f
        while (rms <= 0.2f) {
            val current = AudioLevels.magnitudeOf(rms)
            assertTrue("magnitude must increase at rms=$rms", current > previous)
            previous = current
            rms += 0.01f
        }
    }

    @Test
    fun emptyWindowHasZeroRms() {
        assertEquals(0f, AudioLevels.rms(ShortArray(0)), 0f)
    }

    @Test
    fun magnitudeClampsOutOfRangeInput() {
        assertEquals(0f, AudioLevels.magnitudeOf(-1f), 1e-6f)
        assertEquals(1f, AudioLevels.magnitudeOf(100f), 0f)
    }
}
