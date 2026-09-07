package com.skyd.podaura.ui.screen.article

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.skyd.compone.component.BackIcon
import com.skyd.compone.component.ComponeFloatingActionButton
import com.skyd.compone.component.ComponeIconButton
import com.skyd.compone.component.ComponeScaffold
import com.skyd.compone.component.ComponeTopBar
import com.skyd.compone.component.DefaultBackClick
import com.skyd.compone.component.dialog.WaitingDialog
import com.skyd.compone.component.navigation.LocalNavBackStack
import com.skyd.compone.ext.onlyHorizontal
import com.skyd.compone.ext.plus
import com.skyd.compone.ext.setText
import com.skyd.compone.ext.withoutTop
import com.skyd.mvi.MviEventListener
import com.skyd.mvi.getDispatcher
import com.skyd.podaura.ext.getOrDefault
import com.skyd.podaura.ext.safeItemKey
import com.skyd.podaura.model.bean.article.ArticleWithFeed
import com.skyd.podaura.model.bean.feed.FeedBean
import com.skyd.podaura.model.preference.appearance.article.ArticleItemMinWidthPreference
import com.skyd.podaura.model.preference.appearance.article.ArticleListTonalElevationPreference
import com.skyd.podaura.model.preference.appearance.article.ArticleTopBarTonalElevationPreference
import com.skyd.podaura.model.preference.appearance.article.ShowArticlePullRefreshPreference
import com.skyd.podaura.model.preference.appearance.article.ShowArticleTopBarRefreshPreference
import com.skyd.podaura.model.preference.behavior.article.AlwaysShowArticleFilterPreference
import com.skyd.podaura.model.preference.dataStore
import com.skyd.podaura.model.repository.download.SelectedArticleDownloader
import com.skyd.podaura.model.repository.download.rememberDownloadStarter
import com.skyd.podaura.ui.component.CircularProgressPlaceholder
import com.skyd.podaura.ui.component.ErrorPlaceholder
import com.skyd.podaura.ui.component.PagingRefreshStateIndicator
import com.skyd.podaura.ui.component.UuidList
import com.skyd.podaura.ui.component.navigation.deeplink.DeepLinkPattern
import com.skyd.podaura.ui.component.uuidListType
import com.skyd.podaura.ui.screen.feed.sheet.EditFeedSheet
import com.skyd.podaura.ui.screen.search.SearchRoute
import io.ktor.http.URLBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import podaura.shared.generated.resources.Res
import podaura.shared.generated.resources.article_delete_protected_by_download
import podaura.shared.generated.resources.article_deselect_all
import podaura.shared.generated.resources.article_download_confirm
import podaura.shared.generated.resources.article_download_result
import podaura.shared.generated.resources.article_screen_name
import podaura.shared.generated.resources.article_screen_search_article
import podaura.shared.generated.resources.article_select_all
import podaura.shared.generated.resources.article_selected_count
import podaura.shared.generated.resources.cancel
import podaura.shared.generated.resources.copy
import podaura.shared.generated.resources.download
import podaura.shared.generated.resources.download_without_notifications_tip
import podaura.shared.generated.resources.refresh
import podaura.shared.generated.resources.to_top
import kotlin.uuid.Uuid


@Serializable
data class ArticleRoute(
    @SerialName("feedUrls")
    val feedUrls: List<String>? = null,
    @SerialName("groupIds")
    val groupIds: List<String>? = null,
    @SerialName("articleIds")
    val articleIds: UuidList? = null,
) : NavKey {
    fun toDeeplink(): String {
        return URLBuilder(BASE_PATH).apply {
            feedUrls?.let { parameters.append("feedUrls", Json.encodeToString(feedUrls)) }
            groupIds?.let { parameters.append("groupIds", Json.encodeToString(groupIds)) }
            articleIds?.let {
                parameters.append(
                    "articleIds",
                    UuidList.encodeUuidList(articleIds.uuids.map { Uuid.parse(it) })
                )
            }
        }.toString()
    }

    companion object {
        private const val BASE_PATH = "podaura://article.screen"

        val deepLinkPattern = DeepLinkPattern(
            serializer(),
            urlPattern = URLBuilder(BASE_PATH).apply {
                parameters.append("feedUrls", "{feedUrls}")
                parameters.append("groupIds", "{groupIds}")
                parameters.append("articleIds", "{articleIds}")
            }.build(),
            typeParsers = mapOf(UuidList.serializer().descriptor.kind to uuidListType())
        )

        @Composable
        fun ArticleLauncher(
            route: ArticleRoute,
            onBack: (() -> Unit)? = DefaultBackClick,
            windowInsets: WindowInsets = WindowInsets.safeDrawing
        ) {
            ArticleScreen(
                feedUrls = route.feedUrls.orEmpty(),
                groupIds = route.groupIds.orEmpty(),
                articleIds = route.articleIds?.uuids.orEmpty(),
                onBackClick = onBack,
                windowInsets = windowInsets
            )
        }
    }
}

@Composable
fun ArticleScreen(
    feedUrls: List<String>,
    groupIds: List<String>,
    articleIds: List<String>,
    onBackClick: (() -> Unit)? = DefaultBackClick,
    viewModel: ArticleViewModel = koinViewModel(),
    windowInsets: WindowInsets = WindowInsets.safeDrawing
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStack = LocalNavBackStack.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val listState: LazyGridState = rememberLazyGridState()
    var fabHeight by remember { mutableStateOf(0.dp) }
    var showFilterBar by rememberSaveable {
        mutableStateOf(dataStore.getOrDefault(AlwaysShowArticleFilterPreference))
    }

    val dispatch = viewModel.getDispatcher(
        feedUrls, groupIds, articleIds,
        startWith = ArticleIntent.Init(
            feedUrls = feedUrls,
            groupIds = groupIds,
            articleIds = articleIds,
        )
    )
    val uiState by viewModel.viewState.collectAsStateWithLifecycle()
    val selection = uiState.selectionState
    val downloadStarter = rememberDownloadStarter {
        scope.launch {
            snackbarHostState.showSnackbar(getString(Res.string.download_without_notifications_tip))
        }
    }
    val selectedDownloader =
        remember(downloadStarter) { SelectedArticleDownloader.create(downloadStarter) }
    DisposableEffect(viewModel, feedUrls, groupIds, articleIds) {
        onDispose {
            // The composition-owned dispatcher may already be closed during disposal.
            viewModel.viewModelScope.launch(Dispatchers.Main.immediate) {
                viewModel.processIntent(ArticleIntent.Selection.Exit)
            }
        }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = selection.active,
        onBackCompleted = { dispatch(ArticleIntent.Selection.Exit) },
    )
    selection.confirmation?.let { plan ->
        AlertDialog(
            onDismissRequest = { dispatch(ArticleIntent.Selection.DismissConfirmation) },
            title = { Text(stringResource(Res.string.download)) },
            text = { Text(stringResource(Res.string.article_download_confirm, plan.queueCount)) },
            confirmButton = {
                TextButton(onClick = {
                    dispatch(ArticleIntent.Selection.ConfirmDownload(plan, selectedDownloader))
                }) {
                    Text(stringResource(Res.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { dispatch(ArticleIntent.Selection.DismissConfirmation) }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    ComponeScaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            ComponeTopBar(
                title = {
                    Text(
                        text = if (selection.active) {
                            stringResource(
                                Res.string.article_selected_count,
                                selection.selectedIds.size
                            )
                        } else stringResource(Res.string.article_screen_name),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selection.active) BackIcon(
                        onClick = { dispatch(ArticleIntent.Selection.Exit) }
                    )
                    else if (onBackClick == DefaultBackClick) BackIcon()
                    else if (onBackClick != null) BackIcon(onClick = onBackClick)
                },
                colors = TopAppBarDefaults.topAppBarColors().copy(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                        ArticleTopBarTonalElevationPreference.current.dp
                    ),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
                        ArticleTopBarTonalElevationPreference.current.dp + 4.dp
                    ),
                ),
                actions = {
                    if (selection.active) {
                        ArticleSelectionActions(
                            selection = selection,
                            onSelectAll = {
                                dispatch(
                                    ArticleIntent.Selection.SelectAll(
                                        feedUrls, groupIds, articleIds, uiState.articleFilterState,
                                    )
                                )
                            },
                            onClearSelection = { dispatch(ArticleIntent.Selection.Clear) },
                            onDownload = {
                                dispatch(
                                    ArticleIntent.Selection.Download(
                                        selection.selectedIds,
                                        selectedDownloader
                                    )
                                )
                            },
                        )
                    } else {
                        ArticleBrowseActions(
                            articleListState = uiState.articleListState,
                            articleFilterState = uiState.articleFilterState,
                            showFilterBar = showFilterBar,
                            onFilterBarVisibilityChanged = { showFilterBar = it },
                            onRefresh = {
                                dispatch(ArticleIntent.Refresh(feedUrls, groupIds, articleIds))
                            },
                            onFilterMaskChanged = {
                                dispatch(
                                    ArticleIntent.UpdateFilter(feedUrls, groupIds, articleIds, it)
                                )
                            },
                            onSearch = {
                                navBackStack.add(
                                    SearchRoute.Article(feedUrls, groupIds, articleIds)
                                )
                            },
                        )
                    }
                },
                windowInsets = windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }.value,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ComponeFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    onSizeWithSinglePaddingChanged = { _, height -> fabHeight = height },
                    contentDescription = stringResource(Res.string.to_top),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = stringResource(Res.string.to_top),
                    )
                }
            }
        },
        contentWindowInsets = windowInsets,
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(
            LocalAbsoluteTonalElevation.current +
                    ArticleListTonalElevationPreference.current.dp
        ),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { paddingValues ->
        Content(
            uiState = uiState,
            selection = selection,
            onEnterSelection = { dispatch(ArticleIntent.Selection.Enter(it)) },
            onToggleSelection = { dispatch(ArticleIntent.Selection.Toggle(it)) },
            listState = listState,
            snackbarHostState = snackbarHostState,
            nestedScrollConnection = scrollBehavior.nestedScrollConnection,
            showFilterBar = showFilterBar && !selection.active,
            onRefresh = { dispatch(ArticleIntent.Refresh(feedUrls, groupIds, articleIds)) },
            onFilterMaskChanged = {
                if (!selection.active) dispatch(
                    ArticleIntent.UpdateFilter(
                        feedUrls = feedUrls,
                        groupIds = groupIds,
                        articleIds = articleIds,
                        filterMask = it,
                    )
                )
            },
            onFavorite = { articleWithFeed, favorite ->
                dispatch(
                    ArticleIntent.Favorite(
                        articleId = articleWithFeed.articleWithEnclosure.article.articleId,
                        favorite = favorite,
                    )
                )
            },
            onRead = { articleWithFeed, read ->
                dispatch(
                    ArticleIntent.Read(
                        articleId = articleWithFeed.articleWithEnclosure.article.articleId,
                        read = read,
                    )
                )
            },
            onDelete = { articleWithFeed ->
                dispatch(
                    ArticleIntent.Delete(
                        articleId = articleWithFeed.articleWithEnclosure.article.articleId,
                    )
                )
            },
            onMessage = { scope.launch { snackbarHostState.showSnackbar(it) } },
            onEditFeedSheet = { dispatch(ArticleIntent.OnEditFeedDialog(it)) },
            contentPadding = paddingValues + PaddingValues(bottom = fabHeight),
        )

        WaitingDialog(visible = uiState.loadingDialog)

        MviEventListener(viewModel.singleEvent) { event ->
            when (event) {
                is ArticleEvent.SelectionResultEvent.Downloaded -> {
                    val result = event.result
                    snackbarHostState.showSnackbar(
                        getString(
                            Res.string.article_download_result,
                            result.queuedCount, result.existingCount,
                            result.noEnclosureCount, result.failedIds.size,
                        )
                    )
                }

                is ArticleEvent.SelectionResultEvent.Failed ->
                    snackbarHostState.showSnackbar(event.msg)

                is ArticleEvent.InitArticleListResultEvent.Failed ->
                    snackbarHostState.showSnackbar(event.msg)

                is ArticleEvent.RefreshArticleListResultEvent.Failed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.msg,
                        actionLabel = getString(Res.string.copy),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        clipboard.setText(event.msg)
                    }
                }

                is ArticleEvent.FavoriteArticleResultEvent.Failed ->
                    snackbarHostState.showSnackbar(event.msg)

                is ArticleEvent.ReadArticleResultEvent.Failed ->
                    snackbarHostState.showSnackbar(event.msg)

                is ArticleEvent.DeleteArticleResultEvent.Failed ->
                    snackbarHostState.showSnackbar(event.msg)

                is ArticleEvent.DeleteArticleResultEvent.ProtectedByDownload ->
                    snackbarHostState.showSnackbar(
                        getString(Res.string.article_delete_protected_by_download)
                    )
            }
        }
    }
}

@Composable
private fun ArticleSelectionActions(
    selection: ArticleSelectionState,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDownload: () -> Unit,
) {
    ComponeIconButton(
        onClick = onSelectAll,
        imageVector = Icons.Outlined.SelectAll,
        contentDescription = stringResource(Res.string.article_select_all),
        enabled = !selection.busy,
    )
    ComponeIconButton(
        onClick = onClearSelection,
        imageVector = Icons.Outlined.Deselect,
        contentDescription = stringResource(Res.string.article_deselect_all),
        enabled = !selection.busy && selection.selectedIds.isNotEmpty(),
    )
    if (selection.busy) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    } else {
        ComponeIconButton(
            onClick = onDownload,
            imageVector = Icons.Outlined.Download,
            contentDescription = stringResource(Res.string.download),
            enabled = selection.selectedIds.isNotEmpty(),
        )
    }
}

@Composable
private fun ArticleBrowseActions(
    articleListState: ArticleListState,
    articleFilterState: Int,
    showFilterBar: Boolean,
    onFilterBarVisibilityChanged: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onFilterMaskChanged: (Int) -> Unit,
    onSearch: () -> Unit,
) {
    if (ShowArticleTopBarRefreshPreference.current) {
        val angle = if (articleListState.loading) {
            val infiniteTransition = rememberInfiniteTransition(label = "topBarRefreshTransition")
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing)
                ),
                label = "topBarRefreshAnimate",
            ).value
        } else 0f
        ComponeIconButton(
            onClick = onRefresh,
            imageVector = Icons.Outlined.Refresh,
            contentDescription = stringResource(Res.string.refresh),
            rotate = angle,
            enabled = !articleListState.loading,
        )
    }
    FilterIcon(
        hasFilter = articleFilterState != FeedBean.DEFAULT_FILTER_MASK,
        showFilterBar = showFilterBar,
        onFilterBarVisibilityChanged = onFilterBarVisibilityChanged,
        onFilterMaskChanged = onFilterMaskChanged,
    )
    ComponeIconButton(
        onClick = onSearch,
        imageVector = Icons.Outlined.Search,
        contentDescription = stringResource(Res.string.article_screen_search_article),
    )
}

@Composable
private fun Content(
    uiState: ArticleState,
    selection: ArticleSelectionState,
    onEnterSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    listState: LazyGridState,
    snackbarHostState: SnackbarHostState,
    nestedScrollConnection: NestedScrollConnection,
    showFilterBar: Boolean,
    onRefresh: () -> Unit,
    onFilterMaskChanged: (Int) -> Unit,
    onFavorite: (ArticleWithFeed, Boolean) -> Unit,
    onRead: (ArticleWithFeed, Boolean) -> Unit,
    onDelete: (ArticleWithFeed) -> Unit,
    onMessage: (String) -> Unit,
    onEditFeedSheet: (String?) -> Unit,
    contentPadding: PaddingValues,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier = Modifier
            .pullToRefresh(
                state = state,
                enabled = ShowArticlePullRefreshPreference.current && !selection.active,
                onRefresh = onRefresh,
                isRefreshing = uiState.articleListState.loading
            )
            .padding(top = contentPadding.calculateTopPadding())
    ) {
        Column {
            AnimatedVisibility(visible = showFilterBar) {
                Column(modifier = Modifier.padding(contentPadding.onlyHorizontal())) {
                    FilterRow(
                        articleFilterMask = uiState.articleFilterState,
                        onFilterMaskChanged = onFilterMaskChanged,
                    )
                    HorizontalDivider()
                }
            }

            val currentContentPadding = contentPadding.withoutTop() + PaddingValues(vertical = 4.dp)
            when (val articleListState = uiState.articleListState) {
                is ArticleListState.Init -> CircularProgressPlaceholder(
                    contentPadding = currentContentPadding,
                )

                is ArticleListState.Failed -> ErrorPlaceholder(
                    modifier = Modifier.sizeIn(maxHeight = 200.dp),
                    text = articleListState.msg,
                    contentPadding = currentContentPadding,
                )

                is ArticleListState.Success -> {
                    ArticleList(
                        modifier = Modifier.nestedScroll(nestedScrollConnection),
                        articles = articleListState.articlePagingDataFlow.collectAsLazyPagingItems(),
                        selection = selection,
                        onEnterSelection = onEnterSelection,
                        onToggleSelection = onToggleSelection,
                        listState = listState,
                        onFavorite = onFavorite,
                        onRead = onRead,
                        onDelete = onDelete,
                        contentPadding = currentContentPadding,
                        onMessage = onMessage,
                        onEditFeedSheet = onEditFeedSheet,
                    )
                    uiState.editFeedUrl?.let { editFeedUrl ->
                        EditFeedSheet(
                            feedUrl = editFeedUrl,
                            onDismissRequest = { onEditFeedSheet(null) },
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }

        if (ShowArticlePullRefreshPreference.current) {
            LoadingIndicator(
                isRefreshing = uiState.articleListState.loading,
                state = state,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun ArticleList(
    modifier: Modifier = Modifier,
    articles: LazyPagingItems<ArticleWithFeed>,
    selection: ArticleSelectionState,
    onEnterSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    listState: LazyGridState,
    onFavorite: (ArticleWithFeed, Boolean) -> Unit,
    onRead: (ArticleWithFeed, Boolean) -> Unit,
    onDelete: (ArticleWithFeed) -> Unit,
    onMessage: (String) -> Unit,
    onEditFeedSheet: (String?) -> Unit,
    contentPadding: PaddingValues,
) {
    PagingRefreshStateIndicator(
        lazyPagingItems = articles,
        placeholderPadding = contentPadding,
    ) {
        LazyVerticalGrid(
            modifier = modifier
                .fillMaxSize()
                .testTag("ArticleLazyVerticalGrid"),
            columns = GridCells.Adaptive(ArticleItemMinWidthPreference.current.dp),
            state = listState,
            contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                count = articles.itemCount,
                key = articles.safeItemKey { it.articleWithEnclosure.article.articleId },
            ) { index ->
                when (val item = articles[index]) {
                    is ArticleWithFeed -> Article1Item(
                        data = item,
                        onEnterSelection = {
                            onEnterSelection(item.articleWithEnclosure.article.articleId)
                        },
                        selected = if (selection.active) {
                            item.articleWithEnclosure.article.articleId in selection.selectedIds
                        } else null,
                        selectionEnabled = !selection.busy && selection.confirmation == null,
                        onToggleSelection = {
                            onToggleSelection(item.articleWithEnclosure.article.articleId)
                        },
                        onFavorite = onFavorite,
                        onRead = onRead,
                        onDelete = onDelete,
                        onMessage = onMessage,
                        onEditFeedSheet = onEditFeedSheet,
                    )

                    null -> Article1ItemPlaceholder()
                }
            }
        }
    }
}
