package com.skyd.podaura.ui.player.jumper

import kotlinx.serialization.Serializable

/** Determines which articles form the queue when playback starts. */
@Serializable
sealed interface ArticlePlaylistSource {
    @Serializable
    data object Subscription : ArticlePlaylistSource

    @Serializable
    data class CalendarDay(
        /** Start of the selected day in the device's local time zone, in epoch milliseconds. */
        val day: Long,
    ) : ArticlePlaylistSource
}
