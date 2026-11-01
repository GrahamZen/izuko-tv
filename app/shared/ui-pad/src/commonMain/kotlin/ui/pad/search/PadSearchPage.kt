/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.ui.exploration.search.SearchPageIntent
import me.him188.ani.app.ui.exploration.search.SearchPageState
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_search
import me.him188.ani.app.ui.lang.search_tv_clear_history
import me.him188.ani.app.ui.lang.search_tv_empty
import me.him188.ani.app.ui.pad.foundation.PadEmptyHint
import me.him188.ani.app.ui.pad.foundation.PadPosterGrid
import me.him188.ani.app.ui.pad.foundation.PadPosterPlaceholder
import me.him188.ani.app.ui.pad.foundation.PadPosterWallTheme
import me.him188.ani.app.ui.pad.foundation.PadSubjectCard
import me.him188.ani.app.ui.pad.foundation.padGlassColors
import me.him188.ani.app.ui.pad.foundation.rememberPadCollectionMenu
import me.him188.ani.app.ui.pad.foundation.rememberPadPosterWallLayout
import me.him188.ani.app.ui.search.collectItemsWithLifecycle
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import org.jetbrains.compose.resources.stringResource

/**
 * 平板的搜索页, 照电视版 (ui-tv 的 TvSearchPage) 的两态: 输入态 = 顶上玻璃搜索框 + 下面的候选 (空着时是搜索历史, 打字后是补全建议),
 * 点候选或按键盘的搜索就提交; 结果态 = 海报墙网格, 卡片从搜索框底下滚过. 带着查询进页 (如详情页点标签) 直接落在结果态.
 *
 * 交互是触屏: 点搜索框回输入态改词, 点卡片进详情页, 长按弹收藏菜单; 结果态按返回回输入态.
 */
@Composable
fun PadSearchPage(
    state: SearchPageState,
    onIntent: (SearchPageIntent) -> Unit,
    suggestionsPager: (String) -> Flow<PagingData<String>>,
    modifier: Modifier = Modifier,
) {
    PadPosterWallTheme {
        PadSearchPageContent(state, onIntent, suggestionsPager, modifier)
    }
}

@Composable
private fun PadSearchPageContent(
    state: SearchPageState,
    onIntent: (SearchPageIntent) -> Unit,
    suggestionsPager: (String) -> Flow<PagingData<String>>,
    modifier: Modifier,
) {
    val nsfw = rememberNsfwPolicy()
    val menu = rememberPadCollectionMenu()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query.keywords, TextRange(state.query.keywords.length)))
    }
    var showResults by rememberSaveable { mutableStateOf(state.query.hasSearchRequest()) }

    // 提交一次搜索并切到结果态; 关键词不要求非空 (带着标签等条件进来时同样是一次搜索)
    val applyQuery: (String) -> Unit = apply@{ keywords ->
        val query = state.query.copy(keywords = keywords).normalized()
        if (!query.hasSearchRequest()) return@apply
        text = TextFieldValue(query.keywords, TextRange(query.keywords.length))
        keyboard?.hide()
        focusManager.clearFocus()
        onIntent(SearchPageIntent.UpdateQuery(query, submit = true))
        showResults = true
    }
    BackHandler(enabled = showResults) {
        showResults = false
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wall = rememberPadPosterWallLayout(maxWidth)
        val fieldTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PAD_SEARCH_FIELD_TOP
        val contentTop = fieldTop + PAD_SEARCH_FIELD_HEIGHT + PAD_SEARCH_CONTENT_GAP
        if (showResults) {
            val items = state.searchState.collectItemsWithLifecycle()
            if (items.itemCount == 0 && !items.isLoadingFirstPageOrRefreshing) {
                PadEmptyHint(stringResource(Lang.search_tv_empty))
            } else {
                PadPosterGrid(count = items.itemCount, layout = wall, topPadding = contentTop) { index ->
                    val info = items[index]
                    when {
                        info == null -> PadPosterPlaceholder(Modifier.fillMaxWidth())
                        info.hide -> {}
                        else -> PadSubjectCard(
                            subjectId = info.subjectId,
                            title = info.title,
                            coverUrl = info.imageUrl,
                            nsfw = nsfw,
                            menu = menu,
                            onClick = { onIntent(SearchPageIntent.OpenSubjectDetails(index, info)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            PadSearchCandidates(
                text = text.text,
                state = state,
                suggestionsPager = suggestionsPager,
                onPick = applyQuery,
                onRemoveHistory = { onIntent(SearchPageIntent.RemoveHistory(it)) },
                onClearHistory = { onIntent(SearchPageIntent.ClearHistory) },
                modifier = Modifier.padding(start = wall.margin, end = wall.margin, top = contentTop)
                    .widthIn(max = PAD_SEARCH_FIELD_MAX_WIDTH),
            )
        }
        PadSearchField(
            value = text,
            onValueChange = { text = it },
            onSubmit = { applyQuery(text.text) },
            onFocused = { showResults = false },
            modifier = Modifier.padding(start = wall.margin, end = wall.margin, top = fieldTop)
                .widthIn(max = PAD_SEARCH_FIELD_MAX_WIDTH)
                .fillMaxWidth(),
        )
    }
}

/** 玻璃搜索框: 同玻璃标签栏的底色与细亮边; 键盘上的「搜索」提交. 拿到焦点 (点了它) 就回输入态. */
@Composable
private fun PadSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = padGlassColors()
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .height(PAD_SEARCH_FIELD_HEIGHT)
            .background(glass.fill, CircleShape)
            .border(1.dp, glass.edge, CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = contentColor.copy(alpha = 0.7f))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text(
                    stringResource(Lang.exploration_search),
                    color = contentColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = contentColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
        }
        if (value.text.isNotEmpty()) {
            IconButton(onClick = { onValueChange(TextFieldValue("")) }) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = contentColor.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * 输入态的候选: 搜索框空着时是搜索历史 (每条可删, 右上角清除全部), 打字后是防抖后的补全建议, 点一条就提交.
 */
@Composable
private fun PadSearchCandidates(
    text: String,
    state: SearchPageState,
    suggestionsPager: (String) -> Flow<PagingData<String>>,
    onPick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var debounced by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        delay(PAD_SEARCH_SUGGESTION_DEBOUNCE_MILLIS)
        debounced = text
    }
    val isHistory = debounced.isEmpty()
    val values = remember(debounced) {
        if (debounced.isEmpty()) state.searchHistoryPager else suggestionsPager(debounced)
    }.collectAsLazyPagingItemsWithLifecycle()
    val contentColor = MaterialTheme.colorScheme.onSurface
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (isHistory && values.itemCount > 0) {
            item(key = "clear-history") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onClearHistory) {
                        Text(stringResource(Lang.search_tv_clear_history))
                    }
                }
            }
        }
        items(values.itemCount) { index ->
            val value = values[index] ?: return@items
            Row(
                Modifier.fillMaxWidth()
                    .height(PAD_SEARCH_CANDIDATE_HEIGHT)
                    .clickable { onPick(value) }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    if (isHistory) Icons.Rounded.History else Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor.copy(alpha = 0.6f),
                )
                Text(
                    value,
                    Modifier.weight(1f),
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isHistory) {
                    IconButton(onClick = { onRemoveHistory(value) }) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = contentColor.copy(alpha = 0.6f))
                    }
                }
            }
            HorizontalDivider(color = contentColor.copy(alpha = 0.08f))
        }
    }
}

private val PAD_SEARCH_FIELD_TOP = 12.dp
private val PAD_SEARCH_FIELD_HEIGHT = 48.dp
private val PAD_SEARCH_FIELD_MAX_WIDTH = 640.dp
private val PAD_SEARCH_CONTENT_GAP = 20.dp
private val PAD_SEARCH_CANDIDATE_HEIGHT = 52.dp

/** 补全建议的防抖, 同电视版. */
private const val PAD_SEARCH_SUGGESTION_DEBOUNCE_MILLIS = 300L
