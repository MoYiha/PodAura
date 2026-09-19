package com.skyd.podaura.model.repository.download

import com.skyd.downloader.Status
import com.skyd.fundation.di.get
import com.skyd.podaura.model.bean.article.ArticleWithFeed
import com.skyd.podaura.model.download.ArticleDownloadSource
import com.skyd.podaura.model.download.DownloadInfoBean
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exists
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

data class SelectedDownload(
    val source: ArticleDownloadSource,
    val url: String,
    val type: String?,
    val path: String,
) {
    val target: Pair<String, String> get() = url to path
}

data class SelectedDownloadPlan(
    val downloads: List<SelectedDownload>,
    val existingDownloads: List<SelectedDownload>,
    val noEnclosureCount: Int,
    val failedIds: Set<String>,
) {
    val queueCount: Int get() = downloads.distinctBy { it.target }.size
}

data class SelectedDownloadResult(
    val queuedCount: Int,
    val existingCount: Int,
    val noEnclosureCount: Int,
    val failedIds: Set<String>,
)

class SelectedArticleDownloader internal constructor(
    private val getTasks: suspend () -> List<DownloadInfoBean>,
    private val fileExists: suspend (DownloadInfoBean) -> Boolean,
    private val directory: suspend (String, ArticleDownloadSource) -> String,
    private val enqueue: suspend (SelectedDownload) -> Unit,
) {
    suspend fun prepare(
        selectedIds: Set<String>,
        articles: List<ArticleWithFeed>,
    ): SelectedDownloadPlan {
        val tasks = getTasks().groupBy { it.url to it.path }
        val downloads = mutableListOf<SelectedDownload>()
        val failed =
            (selectedIds - articles.map { it.articleWithEnclosure.article.articleId }.toSet())
                .toMutableSet()
        val existing = mutableListOf<SelectedDownload>()
        var noEnclosure = 0
        for ((articleWithEnclosure) in articles) {
            val article = articleWithEnclosure.article
            val enclosure = articleWithEnclosure.enclosures.firstOrNull()
            if (enclosure == null) {
                noEnclosure++
                continue
            }
            try {
                val protocol = Url(enclosure.url).protocol
                require(protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS)
                val source = ArticleDownloadSource(article.articleId, article.feedUrl)
                val download = SelectedDownload(
                    source, enclosure.url, enclosure.type, directory(enclosure.url, source),
                )
                if (tasks[download.target].orEmpty().any { isExisting(it) }) existing += download
                else downloads += download
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed += article.articleId
            }
        }
        return SelectedDownloadPlan(downloads, existing, noEnclosure, failed)
    }

    suspend fun execute(plan: SelectedDownloadPlan): SelectedDownloadResult {
        // Recheck all candidates: tasks and files may have changed during confirmation.
        val tasks = getTasks().groupBy { it.url to it.path }
        val queued = mutableSetOf<Pair<String, String>>()
        val failed = plan.failedIds.toMutableSet()
        var existing = 0
        for (download in plan.downloads + plan.existingDownloads) {
            try {
                if (download.target in queued ||
                    tasks[download.target].orEmpty().any { isExisting(it) }
                ) {
                    existing++
                } else {
                    enqueue(download)
                    queued += download.target
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed += download.source.articleId
            }
        }
        return SelectedDownloadResult(queued.size, existing, plan.noEnclosureCount, failed)
    }

    private suspend fun isExisting(task: DownloadInfoBean): Boolean = when (task.status) {
        Status.Queued, Status.Started, Status.Downloading -> true
        Status.Success -> fileExists(task)
        else -> false
    }

    companion object {
        fun create(starter: DownloadStarter) = SelectedArticleDownloader(
            getTasks = { get<IDownloadManager>().getAllDownloadTasks() },
            fileExists = { PlatformFile(PlatformFile(it.path), it.fileName).exists() },
            directory = { url, source -> starter.downloadDirectory(url, source) },
            enqueue = {
                starter.download(url = it.url, type = it.type, articleDownloadSource = it.source)
            },
        )
    }
}
