package com.example.swipe2pdf

import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    @Test fun pageCountHasOneLessSwipe() {
        assertEquals(0, CapturePlan.swipesFor(1))
        assertEquals(9, CapturePlan.swipesFor(10))
        assertEquals(998, CapturePlan.swipesFor(999))
    }

    @Test(expected = IllegalArgumentException::class) fun pageCountRejectsZero() { CapturePlan.swipesFor(0) }

    @Test fun stateTransitions() {
        val s = StateMachine()
        listOf(JobState.Preparing, JobState.Capturing, JobState.Paused, JobState.Capturing, JobState.Reviewing, JobState.Processing, JobState.Completed)
            .forEach(s::move)
        assertEquals(JobState.Completed, s.state)
    }

    @Test fun failedPdfReturnsToReview() {
        val s = StateMachine()
        listOf(JobState.Preparing, JobState.Capturing, JobState.Reviewing, JobState.Processing, JobState.Reviewing).forEach(s::move)
        assertEquals(JobState.Reviewing, s.state)
    }

    @Test fun captureErrorStillAllowsPdf() {
        val s = StateMachine()
        listOf(JobState.Preparing, JobState.Capturing, JobState.Error, JobState.Processing).forEach(s::move)
        assertTrue(s.canMove(JobState.Completed))
    }

    @Test fun restoredCapturesStartInReview() {
        val s = StateMachine()
        assertTrue(s.canMove(JobState.Reviewing))
        assertFalse(s.canMove(JobState.Processing))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidStateTransition() { StateMachine().move(JobState.Completed) }

    @Test(expected = IllegalArgumentException::class) fun cannotSkipReviewAfterCapture() {
        val s = StateMachine()
        s.move(JobState.Preparing); s.move(JobState.Capturing); s.move(JobState.Processing)
    }

    @Test fun normalizedCoordinateConversion() {
        assertEquals(PixelRect(100, 100, 900, 1900), NormalizedRect(.1f, .05f, .9f, .95f).pixelBounds(1000, 2000))
    }

    @Test fun cropValidation() {
        assertTrue(NormalizedRect(0f, 0f, 1f, 1f).valid())
        assertFalse(NormalizedRect(.8f, 0f, .2f, 1f).valid())
        assertEquals(50, NormalizedRect(0f, 0f, .5f, 1f).pixelBounds(100, 100).right)
    }

    @Test fun uniqueKoreanFilename() {
        assertEquals("강의.pdf", ImageAndPdf.uniqueName(emptySet(), "강의"))
        assertEquals("강의 (3).pdf", ImageAndPdf.uniqueName(setOf("강의.pdf", "강의 (2).pdf"), "강의.pdf"))
        assertEquals("스캔.pdf", ImageAndPdf.uniqueName(emptySet(), "  "))
    }

    @Test fun ordering() {
        assertEquals(listOf("b", "a", "c"), reorder(listOf("a", "b", "c"), 1, 0))
        assertEquals(listOf("a", "c", "b"), reorder(listOf("a", "b", "c"), 1, 2))
    }

    @Test fun configRoundTripKeepsCommaInFilename() {
        val c = JobConfig(pages = 42, startX = .9f, endX = .1f, y = .4f, gestureMs = 250, delayMs = 1200,
            crop = NormalizedRect(.1f, .2f, .8f, .9f), filename = "강의, 3주차")
        assertEquals(c, ConfigCodec.decode(ConfigCodec.encode(c)))
        assertEquals(JobConfig(), ConfigCodec.decode("broken"))
        assertEquals(JobConfig(), ConfigCodec.decode(null))
    }

    @Test fun captureNamesSortByJobThenPage() {
        val names = listOf("200_0.png", "100_10.png", "100_2.png", "100_0.png", "junk.png")
        assertEquals(listOf("100_0.png", "100_2.png", "100_10.png", "200_0.png", "junk.png"), CaptureNaming.sort(names))
        assertEquals("100_2.png", CaptureNaming.name(100, 2))
    }

    @Test fun sampleSizeKeepsAtLeastMaxSide() {
        assertEquals(1, sampleSizeFor(1080, 2400, 2000))
        assertEquals(2, sampleSizeFor(2000, 4000, 2000))
        assertEquals(4, sampleSizeFor(1440, 3200, 800))
        assertEquals(1, sampleSizeFor(100, 100, 2000))
    }

    @Test fun similarityDetectsNearDuplicates() {
        val a = IntArray(16) { 0x808080 }
        val b = IntArray(16) { 0x818181 }
        val c = IntArray(16) { 0x000000 }
        assertEquals(1.0, fingerprintSimilarity(a, a), 1e-9)
        assertTrue(fingerprintSimilarity(a, b) > .985)
        assertTrue(fingerprintSimilarity(a, c) < .6)
        assertEquals(setOf(1), similarIndices(listOf(a, b, c, null, c)))
    }
}
