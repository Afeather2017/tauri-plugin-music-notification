package com.plugin.music_notification

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicPlayerServiceTimerTest {
    @Test
    fun computePauseAfterDeadline_returnsZeroForNonPositiveDelay() {
        assertEquals(0L, MusicPlayerService.computePauseAfterDeadline(0L, 1_000L))
        assertEquals(0L, MusicPlayerService.computePauseAfterDeadline(-5L, 1_000L))
    }

    @Test
    fun computePauseAfterDeadline_addsDelayToCurrentTime() {
        assertEquals(61_000L, MusicPlayerService.computePauseAfterDeadline(60_000L, 1_000L))
    }

    @Test
    fun computeRemainingPauseAfterDelay_clampsExpiredDeadlinesToZero() {
        assertEquals(0L, MusicPlayerService.computeRemainingPauseAfterDelay(500L, 1_000L))
        assertEquals(500L, MusicPlayerService.computeRemainingPauseAfterDelay(1_500L, 1_000L))
    }
}
