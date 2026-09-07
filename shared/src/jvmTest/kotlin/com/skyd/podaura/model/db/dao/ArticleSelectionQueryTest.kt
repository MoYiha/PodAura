package com.skyd.podaura.model.db.dao

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.skyd.podaura.model.bean.article.ArticleBean
import com.skyd.podaura.model.bean.feed.FeedBean
import com.skyd.podaura.model.db.AppDatabase
import com.skyd.podaura.model.db.instance
import com.skyd.podaura.model.repository.article.ArticleRepository
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArticleSelectionQueryTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private val feedUrl = "https://example.com/podcast's-feed"

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("article-selection-test").toFile()
        database = AppDatabase.instance(
            Room.databaseBuilder<AppDatabase>(File(directory, "test.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun selectsMatchingEpisodesBeyondTheLoadedPage() = runTest {
        database.feedDao().setFeed(FeedBean(url = feedUrl))
        repeat(160) { index ->
            database.articleDao().innerUpsertArticle(
                ArticleBean(
                    articleId = "episode-$index", feedUrl = feedUrl,
                    isRead = index >= 150, isFavorite = true,
                )
            )
        }
        fun query(idsOnly: Boolean) = ArticleRepository.genSql(
            feedUrls = listOf(feedUrl), articleIds = emptyList(),
            isFavorite = true, isRead = false, isMute = false,
            orderBy = FeedBean.SortBy.Date(false), idsOnly = idsOnly,
        )
        val page = database.articleDao().getArticlePagingSource(query(false)).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false)
        )
        assertEquals(20, assertIs<PagingSource.LoadResult.Page<*, *>>(page).data.size)
        assertEquals(
            (0 until 150).map { "episode-$it" }.toSet(),
            database.articleDao().getArticleIds(query(true)).toSet(),
        )
    }

    @Test
    fun respectsMuteFavoriteAndExplicitArticleScopeTogether() = runTest {
        val mutedFeed = "https://example.com/muted"
        database.feedDao().setFeed(FeedBean(url = feedUrl))
        database.feedDao().setFeed(FeedBean(url = mutedFeed, mute = true))
        listOf(
            ArticleBean(articleId = "include", feedUrl = feedUrl, isFavorite = true),
            ArticleBean(articleId = "not-favorite", feedUrl = feedUrl),
            ArticleBean(articleId = "muted", feedUrl = mutedFeed, isFavorite = true),
        ).forEach { database.articleDao().innerUpsertArticle(it) }
        val ids = database.articleDao().getArticleIds(
            ArticleRepository.genSql(
                feedUrls = emptyList(), articleIds = listOf("include", "not-favorite", "muted"),
                isFavorite = true, isRead = null, isMute = false,
                orderBy = FeedBean.SortBy.Title(true), idsOnly = true,
            )
        )
        assertEquals(listOf("include"), ids)
    }
}
