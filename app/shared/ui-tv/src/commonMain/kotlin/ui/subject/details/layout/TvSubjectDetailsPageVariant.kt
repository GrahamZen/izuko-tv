/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kmpalette.palette.graphics.Palette
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.ui.foundation.AniImageLoadSuccess
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadAttempt
import me.him188.ani.app.ui.subject.details.SubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.app.ui.user.SelfInfoUiState

/**
 * 条目详情页的 TV 变体. 与其他插槽不同, 它有两个方法 (页面本体 + 首屏占位),
 * 不能用 SAM lambda 写法.
 *
 * 页面本体、加载占位、放大与缩回那两层都在 [TvDetailsTheme] 里 (本体经 [Theme] 由调用方包): 浅色主题下底色换成电视的浅灰阶,
 * 几处必须同一个底色.
 */
object TvSubjectDetailsPageVariant : SubjectDetailsPageVariant {
    @Composable
    override fun Page(
        state: SubjectDetailsState,
        selfInfo: SelfInfoUiState,
        layoutParams: SubjectDetailsLayoutParams,
        onPlay: (episodeId: Int) -> Unit,
        onClickTag: (Tag) -> Unit,
        onClickLogin: () -> Unit,
        /** 参数 = 打开后落在第几条评论 (详情页评价卡点进来时是那一条). */
        onShowComments: (initialFocusIndex: Int) -> Unit,
        modifier: Modifier,
        onEpisodeCollectionUpdate: (SetEpisodeCollectionTypeRequest) -> Unit,
        showTopBar: Boolean,
        windowInsets: WindowInsets,
        backgroundPalette: Palette?,
        onClickOpenExternal: () -> Unit,
        onCoverImageSuccess: (AniImageLoadSuccess) -> Unit,
        onClickCache: (() -> Unit)?,
        videoBackground: Boolean,
        onVideoBackgroundExitUp: (() -> Unit)?,
    ) {
        ProvideTvScrollActivity {
            SubjectDetailsTvPage(
                state = state,
                selfInfo = selfInfo,
                layoutParams = layoutParams,
                onPlay = onPlay,
                onClickTag = onClickTag,
                onClickLogin = onClickLogin,
                onShowComments = onShowComments,
                modifier = modifier,
                onEpisodeCollectionUpdate = onEpisodeCollectionUpdate,
                showTopBar = showTopBar,
                windowInsets = windowInsets,
                backgroundPalette = backgroundPalette,
                onClickOpenExternal = onClickOpenExternal,
                onCoverImageSuccess = onCoverImageSuccess,
                onClickCache = onClickCache,
                videoBackground = videoBackground,
                onVideoBackgroundExitUp = onVideoBackgroundExitUp,
            )
        }
    }

    @Composable
    override fun LoadingPlaceholder(
        subjectInfo: SubjectInfo?,
        layoutParams: SubjectDetailsLayoutParams,
        modifier: Modifier,
        windowInsets: WindowInsets,
        loadAttempt: SubjectDetailsLoadAttempt,
    ) {
        TvDetailsTheme {
            SubjectDetailsTvLoadingPlaceholder(subjectInfo, layoutParams, modifier, windowInsets, loadAttempt)
        }
    }

    @Composable
    override fun Underlay() {
        TvDetailsTheme { TvHeroZoomLayer() }
    }

    @Composable
    override fun holdPlaceholder(subjectId: Int): Boolean = tvHeroZoomHoldsPlaceholder(subjectId)

    @Composable
    override fun Overlay() {
        TvDetailsTheme { TvHeroShrinkLayer() }
    }

    @Composable
    override fun Theme(content: @Composable () -> Unit) {
        TvDetailsTheme(content)
    }
}
