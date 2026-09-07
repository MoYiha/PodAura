package com.skyd.podaura.model.repository.download

import com.skyd.downloader.Status
import com.skyd.podaura.model.bean.article.ArticleBean
import com.skyd.podaura.model.bean.article.ArticleWithEnclosureBean
import com.skyd.podaura.model.bean.article.ArticleWithFeed
import com.skyd.podaura.model.bean.article.EnclosureBean
import com.skyd.podaura.model.bean.feed.FeedBean
import com.skyd.podaura.model.download.DownloadInfoBean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SelectedArticleDownloaderTest {
    private val enqueued = mutableListOf<SelectedDownload>()
    private var tasks = emptyList<DownloadInfoBean>()
    private val missingFiles = mutableSetOf<String>()
    private val failures = mutableSetOf<String>()

    private fun downloader() = SelectedArticleDownloader(
        getTasks = { tasks },
        fileExists = { it.id !in missingFiles },
        directory = { _, _ -> "/downloads" },
        enqueue = {
            check(it.source.articleId !in failures)
            enqueued += it
        },
    )

    @Test
    fun usesOnlyFirstEnclosureAndReportsMissingArticlesAndEnclosures() = runTest {
        val downloader = downloader()
        val plan = downloader.prepare(
            setOf("audio", "empty", "deleted"),
            listOf(article("audio", "first", "second"), article("empty")),
        )
        val result = downloader.execute(plan)
        assertEquals(listOf(url("first")), enqueued.map { it.url })
        assertEquals(1, result.queuedCount)
        assertEquals(1, result.noEnclosureCount)
        assertEquals(setOf("deleted"), result.failedIds)
    }

    @Test
    fun skipsActiveAndCompletedFilesButRequeuesPausedFailedAndMissingFiles() = runTest {
        tasks = Status.entries.map { task(it.name, it) } + task("missing", Status.Success)
        missingFiles += "missing"
        val articles = tasks.map { article(it.id, it.id) }
        val downloader = downloader()
        val result = downloader.execute(downloader.prepare(tasks.map { it.id }.toSet(), articles))
        assertEquals(4, result.existingCount)
        assertEquals(
            setOf("Init", "Failed", "Paused", "Cancelled", "missing"),
            enqueued.map { it.source.articleId }.toSet(),
        )
    }

    @Test
    fun failedEnqueueDoesNotStopOtherEpisodesAndCanBeRetried() = runTest {
        failures += "bad"
        val articles = listOf(article("bad", "bad"), article("good", "good"))
        val downloader = downloader()
        val result = downloader.execute(downloader.prepare(setOf("bad", "good"), articles))
        assertEquals(1, result.queuedCount)
        assertEquals(setOf("bad"), result.failedIds)
        failures.clear()
        val retry = downloader.execute(downloader.prepare(result.failedIds, listOf(articles.first())))
        assertEquals(1, retry.queuedCount)
        assertEquals(emptySet(), retry.failedIds)
    }

    @Test
    fun confirmationCountDeduplicatesTargetsAndRechecksNewTasks() = runTest {
        val downloader = downloader()
        val plan = downloader.prepare(
            setOf("one", "two", "three"),
            listOf(article("one", "shared"), article("two", "shared"), article("three", "new")),
        )
        assertEquals(2, plan.queueCount)
        tasks = listOf(task("new", Status.Queued))
        val result = downloader.execute(plan)
        assertEquals(1, result.queuedCount)
        assertEquals(2, result.existingCount)
    }

    @Test
    fun taskAtAnotherDestinationDoesNotSuppressDownload() = runTest {
        tasks = listOf(task("episode", Status.Success).copy(path = "/elsewhere"))
        val downloader = downloader()
        val plan = downloader.prepare(setOf("episode"), listOf(article("episode", "episode")))
        assertEquals(1, downloader.execute(plan).queuedCount)
    }

    @Test
    fun unsupportedFirstEnclosureFailsWithoutFallingBackToSecond() = runTest {
        val article = article("unsupported", "first", "second")
        article.articleWithEnclosure.enclosures = article.articleWithEnclosure.enclosures.mapIndexed { i, e ->
            if (i == 0) e.copy(url = "magnet:?xt=test") else e
        }
        val plan = downloader().prepare(setOf("unsupported"), listOf(article))
        assertEquals(0, plan.queueCount)
        assertEquals(setOf("unsupported"), plan.failedIds)
    }

    @Test
    fun cancellationStopsTheBatch() = runTest {
        var calls = 0
        val downloader = SelectedArticleDownloader(
            getTasks = { emptyList() },
            fileExists = { true },
            directory = { _, _ -> "/downloads" },
            enqueue = { calls++; throw CancellationException() },
        )
        val plan = downloader.prepare(setOf("a", "b"), listOf(article("a", "a"), article("b", "b")))
        assertFailsWith<CancellationException> { downloader.execute(plan) }
        assertEquals(1, calls)
    }

    private fun article(id: String, vararg enclosures: String) = ArticleWithFeed(
        articleWithEnclosure = ArticleWithEnclosureBean(
            article = ArticleBean(articleId = id, feedUrl = "https://example.com/feed"),
            enclosures = enclosures.map { EnclosureBean(id, url(it), 0, "audio/mpeg") },
            categories = emptyList(),
            media = null,
        ),
        feed = FeedBean(url = "https://example.com/feed"),
    )

    private fun url(id: String) = "https://example.com/$id.mp3"

    private fun task(id: String, status: Status) = DownloadInfoBean(
        id = id, url = url(id), path = "/downloads", fileName = "$id.mp3", status = status,
        totalBytes = 0, downloadedBytes = 0, speedInBytePerMs = 0f, createTime = 0,
        failureReason = "",
    )
}
