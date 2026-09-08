package com.skyd.podaura.ui.screen.read

import com.skyd.podaura.ui.player.jumper.ArticlePlaylistSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadRouteTest {
    @Test
    fun calendarContextSurvivesNavigationStateSerialization() {
        val route = ReadRoute("article-id", playlistSource = ArticlePlaylistSource.CalendarDay(1_788_796_800_000))

        assertEquals(route, Json.decodeFromString<ReadRoute>(Json.encodeToString(route)))
    }

    @Test
    fun oldNavigationStateStillUsesSubscriptionQueue() {
        val route = Json.decodeFromString<ReadRoute>("""{"articleId":"article-id"}""")

        assertEquals(ArticlePlaylistSource.Subscription, route.playlistSource)
        assertEquals("podaura://read.screen/article-id", route.toDeeplink())
    }
}
