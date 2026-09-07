package com.skyd.podaura.model.repository.player

import kotlinx.coroutines.flow.Flow

interface IPlayerRepository {
    fun insertPlayHistory(path: String, duration: Long, articleId: String?): Flow<Unit>

    fun updateLastPlayPosition(
        path: String,
        lastPlayPosition: Long,
        duration: Long,
        articleId: String?,
    ): Flow<Unit>

    fun requestLastPlayPosition(path: String, fallbackPath: String? = null): Flow<Long>
}
