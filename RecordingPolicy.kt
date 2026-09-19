package com.baining.str.manager

object RecordingPolicy {
    const val SEGMENT_SECONDS = 170
    const val MAX_SECONDS = 10 * 60 * 60

    fun requiredSegments(durationSeconds: Int): Int {
        if (durationSeconds <= 0) return 0
        return (durationSeconds + SEGMENT_SECONDS - 1) / SEGMENT_SECONDS
    }
}
