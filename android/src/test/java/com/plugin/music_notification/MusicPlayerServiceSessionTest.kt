package com.plugin.music_notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Session persistence tests for the identity queue documented in docs/android/playback-session.md. */
class MusicPlayerServiceSessionTest {
    private fun song(id: Long, kind: String): MusicPlayerService.QueueSongInfo {
        return MusicPlayerService.QueueSongInfo(
            id = id,
            name = "Song $id",
            deviceId = if (kind == "kaulan") "device-$id" else null,
            sourceKind = kind,
            localUri = if (kind == "local_raw") "content://media/$id" else null,
            tempSongUrl = if (kind == "temporary") "https://example.test/$id.mp3" else null,
            lufs = null,
            coverUrl = null
        )
    }

    @Test
    fun durableSessionSnapshot_filtersTemporaryTracksAndRemapsCurrentIndex() {
        val snapshot = MusicPlayerService.SessionSnapshot(
            queue = MusicPlayerService.PlayingQueueSnapshot(
                listOf(song(-1, "temporary"), song(10, "kaulan"), song(11, "local_raw")),
                2
            ),
            runtime = MusicPlayerService.PlaybackRuntimeSnapshot(false, 0L, 0L),
            playMode = "sequential",
            currentSongId = 11
        )

        val durable = MusicPlayerService.durableSessionSnapshot(snapshot)

        assertEquals(listOf(10L, 11L), durable.queue.songs.map { it.id })
        assertEquals(1, durable.queue.currentIndex)
        assertEquals(11L, durable.currentSongId)
    }

    @Test
    fun durableSessionSnapshot_clearsSelectionWhenTemporaryTrackIsCurrent() {
        val snapshot = MusicPlayerService.SessionSnapshot(
            queue = MusicPlayerService.PlayingQueueSnapshot(
                listOf(song(10, "kaulan"), song(-1, "temporary")),
                1
            ),
            runtime = MusicPlayerService.PlaybackRuntimeSnapshot(true, 5L, 10L),
            playMode = "loop",
            currentSongId = -1
        )

        val durable = MusicPlayerService.durableSessionSnapshot(snapshot)

        assertEquals(listOf(10L), durable.queue.songs.map { it.id })
        assertNull(durable.queue.currentIndex)
        assertNull(durable.currentSongId)
        assertEquals(false, durable.runtime.isPlaying)
        assertEquals(0L, durable.runtime.positionMs)
        assertEquals(0L, durable.runtime.durationMs)
    }
}
