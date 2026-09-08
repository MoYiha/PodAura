package com.skyd.podaura.model.repository.player

import com.skyd.fundation.ext.nextMidnight
import com.skyd.podaura.ext.asPlatformFile
import com.skyd.podaura.model.bean.LinkEnclosureBean
import com.skyd.podaura.model.bean.history.MediaPlayHistoryBean
import com.skyd.podaura.model.bean.playlist.PlaylistMediaBean
import com.skyd.podaura.model.bean.playlist.PlaylistMediaWithArticleBean
import com.skyd.podaura.model.bean.playlist.updateLocalMediaMetadata
import com.skyd.podaura.model.db.dao.ArticleDao
import com.skyd.podaura.model.db.dao.EnclosureDao
import com.skyd.podaura.model.db.dao.MediaPlayHistoryDao
import com.skyd.podaura.model.repository.BaseRepository
import com.skyd.podaura.ui.player.jumper.PlayDataMode
import com.skyd.podaura.ui.player.resolveToPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Clock

class PlayerRepository(
    private val mediaPlayHistoryDao: MediaPlayHistoryDao,
    private val articleDao: ArticleDao,
    private val enclosureDao: EnclosureDao,
) : BaseRepository(), IPlayerRepository {
    override fun insertPlayHistory(path: String, duration: Long, articleId: String?): Flow<Unit> =
        flow {
            mediaPlayHistoryDao.recordPlaybackStarted(
                createPlayHistory(path, duration, lastPlayPosition = 0L, articleId = articleId)
            )
            emit(Unit)
        }.flowOn(Dispatchers.IO)

    override fun updateLastPlayPosition(
        path: String,
        lastPlayPosition: Long,
        duration: Long,
        articleId: String?,
    ): Flow<Unit> = flow {
        mediaPlayHistoryDao.updateMediaPlayHistory(
            createPlayHistory(path, duration, lastPlayPosition, articleId)
        )
        emit(Unit)
    }.flowOn(Dispatchers.IO)

    private suspend fun createPlayHistory(
        path: String,
        duration: Long,
        lastPlayPosition: Long,
        articleId: String?,
    ): MediaPlayHistoryBean {
        val realArticleId = articleId?.takeIf { articleDao.exists(it) > 0 }
            ?: enclosureDao.getMediaArticleId(path)
        return MediaPlayHistoryBean(
            path = path,
            duration = duration,
            lastPlayPosition = lastPlayPosition,
            lastTime = Clock.System.now().toEpochMilliseconds(),
            articleId = realArticleId,
        )
    }

    override fun requestLastPlayPosition(path: String, fallbackPath: String?): Flow<Long> = flow {
        val history = mediaPlayHistoryDao.getMediaPlayHistory(path)
            ?: fallbackPath?.let { mediaPlayHistoryDao.getMediaPlayHistory(it) }
        emit(history?.lastPlayPosition ?: 0L)
    }.flowOn(Dispatchers.IO)

    suspend fun requestPlaylistByArticleId(
        articleId: String,
        reverse: Boolean = true,
    ): List<PlaylistMediaWithArticleBean> {
        return articleDao.getArticlesForPlaylist(articleId).flatMap { articleWithFeed ->
            val enclosures = articleWithFeed.articleWithEnclosure.enclosures
            enclosures.mapIndexed { index, enclosure ->
                PlaylistMediaWithArticleBean(
                    playlistMediaBean = PlaylistMediaBean(
                        playlistId = "",
                        url = enclosure.url,
                        articleId = articleWithFeed.articleWithEnclosure.article.articleId,
                        orderPosition = index.toDouble(),
                        createTime = Clock.System.now().toEpochMilliseconds(),
                    ),
                    article = articleWithFeed,
                )
            }
        }.run { if (reverse) reversed() else this }
    }

    suspend fun requestPlaylistByCalendarDay(
        day: Long,
        excludeMuted: Boolean,
        includeMediaLinks: Boolean,
    ): List<PlaylistMediaWithArticleBean> {
        val articles = articleDao.getArticleListIn(day, day.nextMidnight(), excludeMuted)
        return articles.flatMap { articleWithFeed ->
            val article = articleWithFeed.articleWithEnclosure
            val urls = article.enclosures.filter { it.isMedia }.map { it.url }.toMutableList()
            if (includeMediaLinks) {
                article.article.link?.takeIf { LinkEnclosureBean(it).isMedia }?.let { urls += it }
            }
            urls.distinct().map { url -> articleWithFeed to url }
        }.mapIndexed { index, (article, url) ->
            PlaylistMediaWithArticleBean(
                playlistMediaBean = PlaylistMediaBean(
                    playlistId = "",
                    url = url,
                    articleId = article.articleWithEnclosure.article.articleId,
                    orderPosition = index.toDouble(),
                    createTime = Clock.System.now().toEpochMilliseconds(),
                ),
                article = article,
            )
        }
    }

    suspend fun requestPlaylistByMediaLibraryList(
        files: List<PlayDataMode.MediaLibraryList.PlayMediaListItem>,
    ): List<PlaylistMediaWithArticleBean> {
        val articleMap =
            articleDao.getArticleWithFeedListByIds(files.mapNotNull { it.articleId })
                .associateBy { it.articleWithEnclosure.article.articleId }
        return files.mapIndexedNotNull { index, playMediaListItem ->
            val playbackUrl = playMediaListItem.path.asPlatformFile().resolveToPlayer()
                ?: return@mapIndexedNotNull null
            PlaylistMediaWithArticleBean(
                playlistMediaBean = PlaylistMediaBean(
                    playlistId = "",
                    url = playbackUrl,
                    articleId = articleMap[playMediaListItem.articleId]?.articleWithEnclosure?.article?.articleId,
                    orderPosition = index.toDouble(),
                    createTime = Clock.System.now().toEpochMilliseconds(),
                ).apply {
                    sourceUrl = playMediaListItem.path
                    historyUrl = playMediaListItem.historyUrl
                    updateLocalMediaMetadata()
                },
                article = articleMap[playMediaListItem.articleId],
            )
        }
    }

}
