package com.skyd.podaura.ui.player.coordinator

import com.skyd.podaura.model.repository.player.IPlayerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.abs

// Accessed only by the engine actor: reads, checkpoints and media transitions stay ordered.
internal class PlaybackProgress(
    private val repository: IPlayerRepository,
    private val onError: (Throwable) -> Unit,
) {
    private data class Media(val path: String, val duration: Long, val articleId: String?)

    private var media: Media? = null
    private var position = 0L
    private var savedPosition = 0L
    private var seeking = false
    private var awaitingSeekEvent = false

    suspend fun start(
        path: String,
        duration: Long,
        articleId: String?,
        startPosition: Long?,
        fallbackPath: String? = null,
        seekable: Boolean = true,
    ): Long {
        finish()
        val restored = attempt {
            repository.requestLastPlayPosition(path, fallbackPath).first()
        } ?: 0L
        val target = startPosition?.coerceAtLeast(0L) ?: (restored / 1000L).takeIf {
            it > 0 && (duration <= 0 || duration - it > 20)
        } ?: 0L
        media = Media(path, duration, articleId)
        position = when {
            !seekable -> 0L
            duration > 0 -> target.coerceAtMost(duration)
            else -> target
        }
        savedPosition = position
        seeking = false
        awaitingSeekEvent = false
        attempt { repository.insertPlayHistory(path, duration, articleId).collect() }
        return position
    }

    suspend fun update(seconds: Long) {
        if (media == null || seeking || seconds < 0) return
        position = seconds
        if (abs(position - savedPosition) >= 5) save()
    }

    fun updateDuration(seconds: Long) {
        if (seconds > 0) media = media?.copy(duration = seconds)
    }

    suspend fun seek(seconds: Long) {
        if (media == null) return
        seeking = true
        awaitingSeekEvent = true
        position = seconds.coerceAtLeast(0)
        save()
    }

    fun beginSeek() {
        seeking = true
        awaitingSeekEvent = false
    }

    suspend fun restarted(seconds: Long) {
        if (awaitingSeekEvent) return
        val wasSeeking = seeking
        seeking = false
        awaitingSeekEvent = false
        update(seconds)
        if (wasSeeking) save()
    }

    suspend fun save() {
        val current = media ?: return
        attempt {
            repository.updateLastPlayPosition(
                current.path, position * 1000L, current.duration, current.articleId,
            ).collect()
            savedPosition = position
        }
    }

    suspend fun finish() {
        save()
        media = null
        seeking = false
        awaitingSeekEvent = false
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
        null
    }
}
