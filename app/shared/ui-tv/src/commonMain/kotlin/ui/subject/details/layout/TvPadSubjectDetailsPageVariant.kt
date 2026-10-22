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
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadAttempt
import me.him188.ani.app.ui.subject.details.SubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.app.ui.user.SelfInfoUiState

/**
 * 触屏 (平板装了电视包 / iPad) 上电视界面的条目详情页: [SubjectDetailsPadPage]. 没有首屏大图, 也就没有放大进来 / 缩回去那两层
 * (壳里关掉了, 见 TvHeroZoomHandoff.detailsHasHero). 播放器内嵌的介绍页 (videoBackground) 仍用遥控器版 [TvSubjectDetailsPageVariant].
 * 底色与遥控器版同一套 ([TvDetailsTheme]).
 */
object TvPadSubjectDetailsPageVariant : SubjectDetailsPageVariant {
    @Composable
    override fun Page(
        state: SubjectDetailsState,
        selfInfo: SelfInfoUiState,
        layoutParams: SubjectDetailsLayoutParams,
        onPlay: (episodeId: Int) -> Unit,
        onClickTag: (Tag) -> Unit,
        onClickLogin: () -> Unit,
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
        if (videoBackground) {
            TvSubjectDetailsPageVariant.Page(
                state, selfInfo, layoutParams, onPlay, onClickTag, onClickLogin, onShowComments, modifier,
                onEpisodeCollectionUpdate, showTopBar, windowInsets, backgroundPalette, onClickOpenExternal,
                onCoverImageSuccess, onClickCache, videoBackground, onVideoBackgroundExitUp,
            )
            return
        }
        SubjectDetailsPadPage(
            state = state,
            selfInfo = selfInfo,
            onPlay = onPlay,
            onClickTag = onClickTag,
            onClickLogin = onClickLogin,
            onShowComments = onShowComments,
            onEpisodeCollectionUpdate = onEpisodeCollectionUpdate,
            windowInsets = windowInsets,
            onClickOpenExternal = onClickOpenExternal,
            onCoverImageSuccess = onCoverImageSuccess,
            onClickCache = onClickCache,
            modifier = modifier,
        )
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
            SubjectDetailsPadLoadingPlaceholder(subjectInfo, windowInsets, loadAttempt, modifier)
        }
    }

    @Composable
    override fun Theme(content: @Composable () -> Unit) {
        TvDetailsTheme(content)
    }
}
