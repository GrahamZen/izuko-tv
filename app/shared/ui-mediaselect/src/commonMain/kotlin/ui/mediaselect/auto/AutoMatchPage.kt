/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.auto

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.LocalPlatform
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.rememberTestMediaSelectorState
import me.him188.ani.app.ui.mediaselect.selector.MediaSelectorWebSourcesColumn
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.platform.isMobile

/**
 * 自动匹配页 = 这一集的缓存 ([AutoMatchCachedRow]) + WEB 源列表 + 救援按钮 (在查询失败的源之前, 见 [MediaSelectorWebSourcesColumn]). 不画顶栏, 不画「正在观看」.
 * 验证码文案沿用 webCaptchaRequiredMessage(kind) 与 iOS 分支 media_selector_web_captcha_unsupported.
 *
 * @param onClickItem 用户点了线路 chip (channel.original); 宿主负责 select + 关闭容器.
 * @param onRequestManualSearch 点了「找不到想看的？手动查找」; null 时不显示按钮 (宿主没有手动查找).
 * @param scrollable 为 true 时整页 verticalScroll (列表本身是 Column, 不是 LazyColumn).
 *
 * 点源的名字进这个源的全部条目 ([SourceResourcesPage]), 返回 (左上角箭头或返回键) 回到源列表.
 */
@Composable
fun AutoMatchPage(
    state: MediaSelectorState,
    onClickItem: (Media) -> Unit,
    onRestartSource: (instanceId: String) -> Unit,
    onRequestManualSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bottomPadding = if (LocalPlatform.current.isMobile()) 0.dp else 8.dp

    var openedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    val openedSource = openedSourceId?.let { id -> presentation.webSources.find { it.instanceId == id } }
    BackHandler(enabled = openedSource != null) { openedSourceId = null }
    if (openedSource != null) {
        SourceResourcesPage(
            openedSource,
            presentation.filteredCandidates,
            presentation.selected,
            onBack = { openedSourceId = null },
            onClickItem = onClickItem,
            modifier = modifier.padding(bottom = bottomPadding),
        )
        return
    }

    MediaSelectorWebSourcesColumn(
        presentation.webSources,
        selectedSource = { presentation.selectedWebSource },
        selectedChannel = { presentation.selectedWebSourceChannel },
        onSelect = { _, channel ->
            channel.original?.let { onClickItem(it) }
        },
        onRefresh = { onRestartSource(it.instanceId) },
        onResolveCaptcha = { source ->
            scope.launch {
                if (state.resolveCaptcha(source)) {
                    onRestartSource(source.instanceId)
                }
            }
        },
        onRequestManualSearch = onRequestManualSearch,
        onOpenSource = { openedSourceId = it.instanceId },
        header = { AutoMatchCachedRow(autoMatchCachedItems(presentation.filteredCandidates), presentation.selected, onClickItem, Modifier.fillMaxWidth()) },
        modifier = modifier
            .testTag(AutoMatchPageTestTags.ROOT)
            .padding(bottom = bottomPadding)
            .fillMaxWidth()
            .ifThen(scrollable) { verticalScroll(rememberScrollState()) },
    )
}

object AutoMatchPageTestTags {
    const val ROOT = "auto_match_page"
    const val RESCUE_BUTTON = "auto_match_rescue_button"
    const val SOURCE_PAGE = "auto_match_source_page"
    const val SOURCE_BACK = "auto_match_source_back"

    /** 源的名字 (点了进 [SourceResourcesPage]). */
    fun sourceName(instanceId: String) = "auto_match_source_name_$instanceId"

    /** 「已缓存」一行 ([AutoMatchCachedRow]) 与其中的一条. */
    const val CACHED_ROW = "auto_match_cached_row"
    fun cachedItem(mediaId: String) = "auto_match_cached_item_$mediaId"
}

@OptIn(TestOnly::class)
@PreviewLightDark
@Composable
private fun PreviewAutoMatchPage() {
    ProvideCompositionLocalsForPreview {
        Surface {
            AutoMatchPage(
                state = rememberTestMediaSelectorState(),
                onClickItem = {},
                onRestartSource = {},
                onRequestManualSearch = {},
            )
        }
    }
}
