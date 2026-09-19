package com.baining.str.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {
    @Test
    fun maximumDurationIsTenHours() {
        assertEquals(36_000, RecordingPolicy.MAX_SECONDS)
    }

    @Test
    fun longRecordingUsesSegmentsBelowLegacyThreeMinuteLimit() {
        assertTrue(RecordingPolicy.SEGMENT_SECONDS < 180)
        assertEquals(212, RecordingPolicy.requiredSegments(36_000))
    }
}
