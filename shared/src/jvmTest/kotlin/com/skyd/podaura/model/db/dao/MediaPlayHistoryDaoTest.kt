package com.skyd.podaura.model.db.dao

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.skyd.podaura.model.bean.history.MediaPlayHistoryBean
import com.skyd.podaura.model.db.AppDatabase
import com.skyd.podaura.model.db.instance
import com.skyd.podaura.model.repository.player.PlayerRepository
import com.skyd.podaura.ui.player.coordinator.PlaybackProgress
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaPlayHistoryDaoTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var repository: PlayerRepository

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("podaura-progress-test").toFile()
        openDatabase()
    }

    private fun openDatabase() {
        database = AppDatabase.instance(
            Room.databaseBuilder<AppDatabase>(name = File(directory, "history.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
        )
        repository = PlayerRepository(
            database.mediaPlayHistoryDao(), database.articleDao(), database.enclosureDao(),
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun metadataRefreshCannotReplaceNewerProgressWithZero() = runTest {
        val dao = database.mediaPlayHistoryDao()
        dao.updateMediaPlayHistory(MediaPlayHistoryBean("episode", 300, 42_000, 1, null))
        dao.recordPlaybackStarted(MediaPlayHistoryBean("episode", 350, 0, 2, null))
        val actual = dao.getMediaPlayHistory("episode")!!
        assertEquals(42_000L, actual.lastPlayPosition)
        assertEquals(350L, actual.duration)
        assertEquals(2L, actual.lastTime)
    }

    @Test
    fun checkpointCreatesMissingHistoryAndRecreatesDeletedHistoryIncludingZero() = runTest {
        val dao = database.mediaPlayHistoryDao()
        repository.updateLastPlayPosition("episode", 15_000, 300, null).collect()
        assertEquals(15_000L, dao.getMediaPlayHistory("episode")?.lastPlayPosition)
        repository.insertPlayHistory("episode", 300, null).collect()
        assertEquals(15_000L, dao.getMediaPlayHistory("episode")?.lastPlayPosition)
        dao.deleteMediaPlayHistory("episode")
        repository.updateLastPlayPosition("episode", 0, 300, null).collect()
        assertEquals(0L, dao.getMediaPlayHistory("episode")?.lastPlayPosition)
        assertEquals(300L, dao.getMediaPlayHistory("episode")?.duration)
    }

    @Test
    fun checkpointIsOnDiskWithoutFinishingThePlaybackSession() = runTest {
        val progress = PlaybackProgress(repository) { throw it }
        progress.start("episode", 300, null, null)
        for (second in 1L..19L) progress.update(second)
        database.close()
        openDatabase()
        val restored = PlaybackProgress(repository) { throw it }
        assertEquals(15L, restored.start("episode", 300, null, null))
    }

    @Test
    fun downloadUsesLegacyLocalProgressOnlyWhenSharedHistoryDoesNotExist() = runTest {
        val remote = "https://example.com/episode.mp3"
        val local = "/downloads/episode.mp3"
        repository.updateLastPlayPosition(local, 42_000, 300, null).collect()
        assertEquals(42_000L, repository.requestLastPlayPosition(remote, local).first())
        repository.updateLastPlayPosition(remote, 0, 300, null).collect()
        assertEquals(0L, repository.requestLastPlayPosition(remote, local).first())
    }
}
