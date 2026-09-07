package com.skyd.podaura.ui.player.coordinator

import com.skyd.podaura.model.repository.player.IPlayerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaybackProgressTest {
    @Test
    fun nonSeekableMediaStartsAtZeroAndKeepsRecording() = runTest {
        val repo = HistoryRepository()
        repo.positions["stream"] = 100_000L
        val progress = recorder(repo)
        assertEquals(0L, progress.start("stream", 0, null, null, seekable = false))
        progress.update(5)
        assertEquals(5_000L, repo.positions["stream"])
    }

    @Test
    fun checkpointsSurviveWithoutAnyShutdownCallback() = runTest {
        val repo = HistoryRepository()
        val progress = recorder(repo)
        progress.start("episode", 300, null, null)
        for (second in 1L..19L) progress.update(second)
        assertEquals(listOf(5_000L, 10_000L, 15_000L), repo.writes.map { it.second })
        assertEquals(15L, recorder(repo).start("episode", 300, null, null))
    }

    @Test
    fun pauseSavesEvenLessThanFiveSecondsAndDeletionIsRecreated() = runTest {
        val repo = HistoryRepository()
        val progress = recorder(repo)
        progress.start("episode", 300, null, null)
        progress.update(3)
        progress.save()
        assertEquals(3_000L, repo.positions["episode"])
        repo.positions.clear()
        progress.update(8)
        assertEquals(8_000L, repo.positions["episode"])
    }

    @Test
    fun rewindToZeroWinsOverQueuedOldTelemetryAndInitialRestart() = runTest {
        val repo = HistoryRepository()
        repo.positions["episode"] = 100_000L
        val progress = recorder(repo)
        progress.start("episode", 300, null, null)
        progress.seek(0)
        progress.update(101)
        progress.restarted(100)
        assertEquals(0L, repo.positions["episode"])
        progress.beginSeek()
        progress.restarted(0)
        progress.update(2)
        progress.save()
        assertEquals(2_000L, repo.positions["episode"])
    }

    @Test
    fun finishingMediaDoesNotWriteLaterTelemetryToIt() = runTest {
        val repo = HistoryRepository()
        val progress = recorder(repo)
        progress.start("first", 300, null, null)
        progress.update(13)
        progress.finish()
        progress.update(0)
        progress.start("second", 300, null, null)
        progress.update(6)
        assertEquals(13_000L, repo.positions["first"])
        assertEquals(6_000L, repo.positions["second"])
    }

    @Test
    fun nearEndRestartsButExplicitTimestampTakesPrecedence() = runTest {
        val repo = HistoryRepository()
        repo.positions["episode"] = 281_000L
        assertEquals(0L, recorder(repo).start("episode", 300, null, null))
        assertEquals(290L, recorder(repo).start("episode", 300, null, 290))
        repo.positions["episode"] = 279_000L
        assertEquals(279L, recorder(repo).start("episode", 300, null, null))
    }

    @Test
    fun sameMediaReloadUsesLastPositionAndExplicitZeroOverridesIt() = runTest {
        val repo = HistoryRepository()
        val progress = recorder(repo)
        progress.start("episode", 300, null, null)
        progress.update(12)
        assertEquals(12L, progress.start("episode", 300, null, null))
        assertEquals(0L, progress.start("episode", 300, null, 0))
    }

    @Test
    fun writeFailureIsRetriedOnNextPositionWithoutStoppingPlayback() = runTest {
        val repo = HistoryRepository()
        val errors = mutableListOf<Throwable>()
        val progress = PlaybackProgress(repo, errors::add)
        progress.start("episode", 300, null, null)
        repo.failure = IllegalStateException("disk unavailable")
        progress.update(5)
        assertEquals(1, errors.size)
        repo.failure = null
        progress.update(6)
        assertEquals(6_000L, repo.positions["episode"])
    }

    @Test
    fun cancellationIsNotSwallowedAsADatabaseFailure() = runTest {
        val repo = HistoryRepository()
        val progress = recorder(repo)
        progress.start("episode", 300, null, null)
        repo.failure = CancellationException()
        assertFailsWith<CancellationException> { progress.update(5) }
        assertTrue(repo.writes.isEmpty())
    }

    private fun recorder(repo: HistoryRepository) = PlaybackProgress(repo) { throw it }

    private class HistoryRepository : IPlayerRepository {
        val positions = mutableMapOf<String, Long>()
        val writes = mutableListOf<Pair<String, Long>>()
        var failure: Throwable? = null

        override fun requestLastPlayPosition(path: String, fallbackPath: String?): Flow<Long> =
            flowOf(positions[path] ?: positions[fallbackPath] ?: 0L)

        override fun insertPlayHistory(path: String, duration: Long, articleId: String?) = flow {
            positions.getOrPut(path) { 0L }
            emit(Unit)
        }

        override fun updateLastPlayPosition(
            path: String, lastPlayPosition: Long, duration: Long, articleId: String?,
        ) = flow {
            failure?.let { throw it }
            positions[path] = lastPlayPosition
            writes += path to lastPlayPosition
            emit(Unit)
        }
    }
}
