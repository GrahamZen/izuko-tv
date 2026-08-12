/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.video.sidesheet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_close_selector
import me.him188.ani.app.ui.lang.subject_episode_select_media_source
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSelectorView
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.TestMediaSourceResultListPresentation
import me.him188.ani.app.ui.mediafetch.ViewKind
import me.him188.ani.app.ui.mediafetch.rememberTestMediaSelectorState
import me.him188.ani.app.ui.mediafetch.request.TestMediaFetchRequest
import me.him188.ani.app.ui.subject.episode.TAG_MEDIA_SELECTOR_SHEET
import me.him188.ani.app.ui.subject.episode.video.components.EpisodeVideoSideSheets
import me.him188.ani.app.ui.subject.episode.video.settings.SideSheetLayout
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.stringResource

@Suppress("UnusedReceiverParameter")
@Composable
fun EpisodeVideoSideSheets.MediaSelectorSheet(
    mediaSelectorState: MediaSelectorState,
    mediaSourceResultListPresentation: MediaSourceResultListPresentation,
    viewKind: ViewKind,
    onViewKindChange: (ViewKind) -> Unit,
    fetchRequest: MediaFetchRequest?,
    onFetchRequestChange: (MediaFetchRequest) -> Unit,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
    onRestartSource: (instanceId: String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 选中一个数据源后是否顺手关掉本面板, 见 `VideoScaffoldConfig.hideSelectorOnSelect`.
     *
     * **只有遥控器形态读它**, 见下面的 `hideOnSelectEffective`.
     */
    hideOnSelect: Boolean = false,
) {
    val selectMediaSourceText = stringResource(Lang.subject_episode_select_media_source)
    val closeSelectorText = stringResource(Lang.subject_episode_close_selector)
    // 指针形态一律选完即关: 那个开关默认是关的, 直接读它等于把"点完源面板还盖着播放器"变成默认行为,
    // 而这里从来只有一个出口 (右上角关闭按钮), 与手机上一贯的观感不符.
    // 遥控器形态才照配置走: 那边返回键本来就是关面板的固定出口, 留在面板上是刻意的取舍 ——
    // 换的源不行可以当场接着换下一个.
    val hideOnSelectEffective = hideOnSelect || !LocalAniUiBehavior.current.focusDrivenNavigation

    SideSheetLayout(
        title = { Text(text = selectMediaSourceText) },
        onDismissRequest = onDismissRequest,
        Modifier.testTag(TAG_MEDIA_SELECTOR_SHEET),
        closeButton = {
            IconButton(onClick = onDismissRequest) {
                Icon(Icons.Rounded.Close, contentDescription = closeSelectorText)
            }
        },
    ) {
        MediaSelectorView(
            mediaSelectorState,
            viewKind,
            onViewKindChange,
            fetchRequest,
            onFetchRequestChange,
            mediaSourceResultListPresentation,
            onRestartSource = onRestartSource,
            onRefresh,
            modifier.padding(horizontal = 16.dp)
                .fillMaxWidth()
                .navigationBarsPadding(),
            stickyHeaderBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClickItem = {
                mediaSelectorState.select(it)
                if (hideOnSelectEffective) {
                    onDismissRequest()
                }
            },
            singleLineFilter = true,
        )
    }
}


@OptIn(TestOnly::class)
@Composable
@Preview
@PreviewLightDark
private fun PreviewEpisodeVideoMediaSelectorSideSheet() {
    ProvideCompositionLocalsForPreview {
        val (viewKind, onViewKindChange) = rememberSaveable { mutableStateOf(ViewKind.WEB) }
        EpisodeVideoSideSheets.MediaSelectorSheet(
            mediaSelectorState = rememberTestMediaSelectorState(),
            mediaSourceResultListPresentation = TestMediaSourceResultListPresentation,
            viewKind = viewKind,
            onViewKindChange = onViewKindChange,
            fetchRequest = TestMediaFetchRequest,
            onFetchRequestChange = {},
            onDismissRequest = {},
            onRefresh = {},
            onRestartSource = {},
        )
    }
}
