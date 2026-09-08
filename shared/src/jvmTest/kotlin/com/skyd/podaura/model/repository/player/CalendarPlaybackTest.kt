package com.skyd.podaura.model.repository.player

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.skyd.fundation.ext.nextMidnight
import com.skyd.podaura.model.bean.article.ArticleBean
import com.skyd.podaura.model.bean.article.ArticleWithFeed
import com.skyd.podaura.model.bean.article.EnclosureBean
import com.skyd.podaura.model.bean.feed.FeedBean
import com.skyd.podaura.model.db.AppDatabase
import com.skyd.podaura.model.db.instance
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarPlaybackTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var repository: PlayerRepository
    private val day = LocalDate(2026, 9, 8)
        .atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("podaura-calendar-test").toFile()
        database = AppDatabase.instance(
            Room.databaseBuilder<AppDatabase>(name = File(directory, "calendar.db").absolutePath)
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

    private suspend fun article(
        id: String,
        date: Long?,
        feed: String = "feed-a",
        urls: List<String> = listOf("https://example.com/$id.mp3"),
        link: String? = null,
    ) {
        database.articleDao().innerUpsertArticle(
            ArticleBean(articleId = id, feedUrl = feed, date = date, link = link)
        )
        database.enclosureDao().upsert(urls.map { EnclosureBean(id, it, 0, null) })
    }

    @Test
    fun completeDayMatchesPagedColumnAcrossFeedsAndMutedFilter() = runTest {
        database.feedDao().setFeed(FeedBean("feed-a"))
        database.feedDao().setFeed(FeedBean("feed-b"))
        database.feedDao().setFeed(FeedBean("muted", mute = true))
        // More than a page and more than the subscription queue's 50 neighbors per side.
        for (index in 0..129) {
            article(
                "item-${index.toString().padStart(3, '0')}",
                day + index,
                if (index % 2 == 0) "feed-a" else "feed-b",
            )
        }
        article("before", day - 1)
        article("tomorrow", day.nextMidnight())
        article("undated", null)
        article("muted-item", day + 150, "muted")
        article("text-only", day + 151, urls = emptyList())
        article("pdf-only", day + 152, urls = listOf("https://example.com/document.pdf"))
        article("tie-b", day + 153)
        article("tie-a", day + 153)

        for (excludeMuted in listOf(true, false)) {
            val pagingSource = database.articleDao()
                .getArticlesIn(day, day.nextMidnight(), excludeMuted)
            val column = mutableListOf<ArticleWithFeed>()
            var page = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false)
            ) as PagingSource.LoadResult.Page<Int, ArticleWithFeed>
            assertEquals(20, page.data.size)
            column += page.data
            while (page.nextKey != null) {
                page = pagingSource.load(
                    PagingSource.LoadParams.Append(
                        key = page.nextKey!!, loadSize = 20, placeholdersEnabled = false,
                    )
                ) as PagingSource.LoadResult.Page<Int, ArticleWithFeed>
                column += page.data
            }
            val queue = repository.requestPlaylistByCalendarDay(
                day, excludeMuted, includeMediaLinks = false,
            )
            val ids = queue.map { it.playlistMediaBean.articleId }
            val playableColumnIds = column.filter {
                it.articleWithEnclosure.enclosures.any { media -> media.isMedia }
            }.map { it.articleWithEnclosure.article.articleId }
            assertEquals(playableColumnIds, ids)
            assertEquals(if (excludeMuted) 132 else 133, queue.size)
            assertEquals(listOf("tie-a", "tie-b"), ids.takeLast(2))
            assertEquals(
                queue.indices.map { it.toDouble() },
                queue.map { it.playlistMediaBean.orderPosition },
            )
            pagingSource.invalidate()
        }
    }

    @Test
    fun multipleMediaAndOptionalMediaLinksStayWithTheirArticle() = runTest {
        database.feedDao().setFeed(FeedBean("feed-a"))
        article(
            "first", day,
            urls = listOf(
                "https://example.com/a.mp3",
                "https://example.com/b.mp4",
                "https://example.com/c.pdf",
            ),
            link = "https://example.com/a.mp3",
        )
        article("link-only", day + 1, urls = emptyList(), link = "https://example.com/link.mp3")
        article("last", day + 2)
        val withoutLinks = repository.requestPlaylistByCalendarDay(day, false, includeMediaLinks = false)
        assertEquals(
            listOf("first", "first", "last"),
            withoutLinks.map { it.playlistMediaBean.articleId },
        )
        val withLinks = repository.requestPlaylistByCalendarDay(day, false, includeMediaLinks = true)
        assertEquals(
            listOf("first", "first", "link-only", "last"),
            withLinks.map { it.playlistMediaBean.articleId },
        )
        assertEquals(
            listOf(
                "https://example.com/a.mp3", "https://example.com/b.mp4",
                "https://example.com/link.mp3", "https://example.com/last.mp3",
            ),
            withLinks.map { it.playlistMediaBean.url },
        )
        assertTrue(repository.requestPlaylistByCalendarDay(day.nextMidnight(), false, false).isEmpty())
    }
}
