package com.skyd.podaura.ui.screen.article

import com.skyd.mvi.MviSingleEvent
import com.skyd.podaura.model.repository.download.SelectedDownloadResult

sealed interface ArticleEvent : MviSingleEvent {
    sealed interface SelectionResultEvent : ArticleEvent {
        data class Downloaded(val result: SelectedDownloadResult) : SelectionResultEvent
        data class Failed(val msg: String) : SelectionResultEvent
    }
    sealed interface InitArticleListResultEvent : ArticleEvent {
        data class Failed(val msg: String) : InitArticleListResultEvent
    }

    sealed interface RefreshArticleListResultEvent : ArticleEvent {
        data class Failed(val msg: String) : RefreshArticleListResultEvent
    }

    sealed interface FavoriteArticleResultEvent : ArticleEvent {
        data class Failed(val msg: String) : FavoriteArticleResultEvent
    }

    sealed interface ReadArticleResultEvent : ArticleEvent {
        data class Failed(val msg: String) : ReadArticleResultEvent
    }

    sealed interface DeleteArticleResultEvent : ArticleEvent {
        data object ProtectedByDownload : DeleteArticleResultEvent
        data class Failed(val msg: String) : DeleteArticleResultEvent
    }
}
