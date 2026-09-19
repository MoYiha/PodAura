package com.skyd.podaura.ui.screen.article

import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingConfig
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.skyd.downloader.download.DownloadConstraints
import com.skyd.podaura.model.bean.article.ArticleBean
import com.skyd.podaura.model.bean.article.EnclosureBean
import com.skyd.podaura.model.bean.feed.FeedBean
import com.skyd.podaura.model.db.AppDatabase
import com.skyd.podaura.model.db.instance
import com.skyd.podaura.model.download.ArticleDownloadSource
import com.skyd.podaura.model.download.DownloadInfoBean
import com.skyd.podaura.model.repository.article.ArticleRepository
import com.skyd.podaura.model.repository.article.DownloadArticleProtectionResolver
import com.skyd.podaura.model.repository.download.IDownloadManager
import com.skyd.podaura.model.repository.download.SelectedArticleDownloader
import com.skyd.podaura.model.repository.feed.RssHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleSelectionViewModelTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var viewModel: ArticleViewModel
    private val store = ViewModelStore()
    private val feedUrl = "https://example.com/feed"

    @BeforeTest
    fun setUp() = runBlocking {
        directory = Files.createTempDirectory("article-selection-state-test").toFile()
        database = AppDatabase.instance(
            Room.databaseBuilder<AppDatabase>(File(directory, "test.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
        )
        val manager = object : IDownloadManager {
            override suspend fun getAllDownloadTasks(): List<DownloadInfoBean> = emptyList()
            override suspend fun download(
                url: String, path: String, fileName: String?,
                articleDownloadSource: ArticleDownloadSource?, constraints: DownloadConstraints,
            ): String = error("Unexpected real download")
        }
        withContext(Dispatchers.Main) {
            viewModel = ArticleViewModel(
                ArticleRepository(
                    database.feedDao(), database.articleDao(), RssHelper {}, PagingConfig(20),
                    DownloadArticleProtectionResolver(manager, database.enclosureDao()),
                )
            )
            store.put("articles", viewModel)
        }
        database.feedDao().setFeed(FeedBean(url = feedUrl))
    }

    @AfterTest
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        database.close()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun overOneHundredRequiresConfirmationAndBusyStateRejectsChanges() = runBlocking {
        insertEpisodes(101)
        val gate = CompletableDeferred<Unit>()
        var queued = 0
        var preparations = 0
        val downloader = SelectedArticleDownloader(
            getTasks = { preparations++; gate.await(); emptyList() },
            fileExists = { true }, directory = { _, _ -> "/downloads" }, enqueue = { queued++ },
        )
        send(ArticleIntent.Selection.Enter("episode-0"))
        assertEquals(setOf("episode-0"), awaitSelection { it.active }.selectedIds)
        awaitSelection { it.active }
        send(ArticleIntent.Selection.SelectAll(listOf(feedUrl), emptyList(), emptyList(), 0))
        val selected = awaitSelection { !it.busy && it.selectedIds.size == 101 }
        send(ArticleIntent.Selection.Download(selected.selectedIds, downloader))
        awaitSelection { it.busy }
        send(ArticleIntent.Selection.Download(selected.selectedIds, downloader))
        send(ArticleIntent.Selection.Toggle("episode-0"))
        send(ArticleIntent.Selection.Clear)
        gate.complete(Unit)
        val confirmation = awaitSelection { !it.busy && it.confirmation != null }
        assertEquals(101, confirmation.selectedIds.size)
        assertEquals(1, preparations)
        assertEquals(0, queued)
        val plan = assertNotNull(confirmation.confirmation)
        assertEquals(101, plan.queueCount)

        val event = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent() }
        send(ArticleIntent.Selection.ConfirmDownload(plan, downloader))
        val result = assertIs<ArticleEvent.SelectionResultEvent.Downloaded>(event.await()).result
        assertEquals(101, result.queuedCount)
        val completed = awaitSelection { !it.active && !it.busy }
        assertEquals(101, queued)
        assertTrue(completed.selectedIds.isEmpty())
    }

    @Test
    fun oneHundredQueuesDirectlyAndOnlyFailedEpisodesStaySelected() = runBlocking {
        insertEpisodes(100)
        val downloader = SelectedArticleDownloader(
            getTasks = { emptyList() }, fileExists = { true }, directory = { _, _ -> "/downloads" },
            enqueue = { check(it.source.articleId != "episode-0") },
        )
        send(ArticleIntent.Selection.Enter())
        awaitSelection { it.active }
        repeat(100) { send(ArticleIntent.Selection.Toggle("episode-$it")) }
        val selected = awaitSelection { it.selectedIds.size == 100 }
        val event = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent() }
        send(ArticleIntent.Selection.Download(selected.selectedIds, downloader))
        val result = assertIs<ArticleEvent.SelectionResultEvent.Downloaded>(event.await()).result
        val state = awaitSelection { !it.busy && it.selectedIds == setOf("episode-0") }
        assertNull(state.confirmation)
        assertTrue(state.active)
        assertEquals(99, result.queuedCount)
    }

    @Test
    fun leavingDuringPreparationCannotRestoreOldSelection() = runBlocking {
        insertEpisodes(2)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val downloader = SelectedArticleDownloader(
            getTasks = {
                started.complete(Unit)
                try { gate.await() } finally { cancelled.complete(Unit) }
                emptyList()
            },
            fileExists = { true }, directory = { _, _ -> "/downloads" },
            enqueue = { error("Cancelled batch must not enqueue") },
        )
        send(ArticleIntent.Selection.Enter())
        awaitSelection { it.active }
        send(ArticleIntent.Selection.Toggle("episode-0"))
        val selected = awaitSelection { it.selectedIds == setOf("episode-0") }
        send(ArticleIntent.Selection.Download(selected.selectedIds, downloader))
        withTimeout(10_000) { started.await() }

        send(ArticleIntent.Selection.Exit)
        awaitSelection { !it.active }
        withTimeout(10_000) { cancelled.await() }
        send(ArticleIntent.Selection.Enter())
        awaitSelection { it.active }
        send(ArticleIntent.Selection.Toggle("episode-1"))
        val state = awaitSelection { it.selectedIds == setOf("episode-1") }
        assertFalse(state.busy)
        assertNull(state.confirmation)
        var queued = 0
        val nextDownloader = SelectedArticleDownloader(
            getTasks = { emptyList() }, fileExists = { true },
            directory = { _, _ -> "/downloads" }, enqueue = { queued++ },
        )
        val event = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent() }
        send(ArticleIntent.Selection.Download(state.selectedIds, nextDownloader))
        assertIs<ArticleEvent.SelectionResultEvent.Downloaded>(event.await())
        awaitSelection { !it.active && !it.busy }
        assertEquals(1, queued)
        send(ArticleIntent.Selection.Exit)
        assertEquals(ArticleSelectionState(), awaitSelection { !it.active })
    }

    @Test
    fun duplicateDownloadsBeforeBusyStateIsRenderedAreIgnored() = runBlocking {
        insertEpisodes(1)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var taskReads = 0
        var queued = 0
        val downloader = SelectedArticleDownloader(
            getTasks = {
                taskReads++
                started.complete(Unit)
                gate.await()
                emptyList()
            },
            fileExists = { true }, directory = { _, _ -> "/downloads" }, enqueue = { queued++ },
        )
        send(ArticleIntent.Selection.Enter())
        awaitSelection { it.active }
        send(ArticleIntent.Selection.Toggle("episode-0"))
        val selected = awaitSelection { it.selectedIds.isNotEmpty() }
        val event = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent() }
        withContext(Dispatchers.Main.immediate) {
            repeat(2) {
                viewModel.processIntent(ArticleIntent.Selection.Download(selected.selectedIds, downloader))
            }
        }
        withTimeout(10_000) { started.await() }
        gate.complete(Unit)
        assertIs<ArticleEvent.SelectionResultEvent.Downloaded>(event.await())
        awaitSelection { !it.active && !it.busy }
        assertEquals(1, queued)
        assertEquals(2, taskReads)
    }

    @Test
    fun preparationFailureEmitsEventAndKeepsSelectionForRetry() = runBlocking {
        insertEpisodes(1)
        val downloader = SelectedArticleDownloader(
            getTasks = { error("Cannot read download tasks") },
            fileExists = { true }, directory = { _, _ -> "/downloads" },
            enqueue = { error("Must not enqueue") },
        )
        send(ArticleIntent.Selection.Enter())
        awaitSelection { it.active }
        send(ArticleIntent.Selection.Toggle("episode-0"))
        val selected = awaitSelection { it.selectedIds.isNotEmpty() }
        val event = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent() }
        send(ArticleIntent.Selection.Download(selected.selectedIds, downloader))
        assertEquals(
            ArticleEvent.SelectionResultEvent.Failed("Cannot read download tasks"),
            event.await(),
        )
        val state = awaitSelection { !it.busy }
        assertTrue(state.active)
        assertEquals(setOf("episode-0"), state.selectedIds)
    }

    private suspend fun awaitEvent(): ArticleEvent = withTimeout(10_000) {
        viewModel.singleEvent.first()
    }

    private suspend fun send(intent: ArticleIntent) = withContext(Dispatchers.Main.immediate) {
        viewModel.processIntent(intent)
    }

    private suspend fun awaitSelection(predicate: (ArticleSelectionState) -> Boolean): ArticleSelectionState =
        withTimeout(10_000) {
            viewModel.viewState.first { predicate(it.selectionState) }.selectionState
        }

    private suspend fun insertEpisodes(count: Int) {
        repeat(count) {
            val id = "episode-$it"
            database.articleDao().innerUpsertArticle(ArticleBean(articleId = id, feedUrl = feedUrl))
            database.enclosureDao().upsert(listOf(EnclosureBean(id, "https://example.com/$id.mp3", 0, "audio/mpeg")))
        }
    }
}
