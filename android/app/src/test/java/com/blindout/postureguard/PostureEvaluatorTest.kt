package com.blindout.postureguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureEvaluatorTest {
    private val base = Sample(width = 0.3f, pitch = 0f, roll = 0f)
    private val t = Thresholds(baseDistCm = 40, minDistCm = 30, pitchTolDeg = 15, rollTolDeg = 12)

    private fun keys(s: Sample) = PostureEvaluator.evaluate(s, base, t).problems.map { it.key }

    @Test
    fun goodPostureHasNoProblems() {
        assertTrue(keys(base).isEmpty())
    }

    @Test
    fun biggerFaceMeansCloser() {
        val e = PostureEvaluator.evaluate(base.copy(width = 0.6f), base, t)
        assertEquals(20f, e.distanceCm, 0.01f)
        assertEquals(listOf("close"), e.problems.map { it.key })
    }

    @Test
    fun lookingDownIsDetected() {
        // ML Kit: 아래를 보면 headEulerAngleX가 작아진다.
        assertEquals(listOf("down"), keys(base.copy(pitch = -20f)))
    }

    @Test
    fun lookingUpIsDetectedWithWiderTolerance() {
        assertTrue(keys(base.copy(pitch = 20f)).isEmpty())
        assertEquals(listOf("up"), keys(base.copy(pitch = 25f)))
    }

    @Test
    fun tiltIsDetectedBothWays() {
        assertEquals(listOf("tilt"), keys(base.copy(roll = 20f)))
        assertEquals(listOf("tilt"), keys(base.copy(roll = -20f)))
    }

    @Test
    fun averageOfSamples() {
        val avg = PostureEvaluator.average(listOf(Sample(0.2f, 2f, -4f), Sample(0.4f, 4f, 4f)))
        assertEquals(0.3f, avg.width, 0.0001f)
        assertEquals(3f, avg.pitch, 0.0001f)
        assertEquals(0f, avg.roll, 0.0001f)
    }
}
