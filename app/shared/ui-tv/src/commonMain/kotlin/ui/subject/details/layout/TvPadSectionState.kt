/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.paging.LoadState
import me.him188.ani.app.data.repository.subject.EpisodesFetchState

/**
 * 触屏详情页头部以下一个区块的数据状态. 各区块的位置固定 (选集、角色、制作人员、评价、关联作品), 在路上时摆同样几何的占位,
 * 到了原地换成真内容 —— 几路数据先后不定, 不然先到的先冒出来, 后到的插进中间把下面的挤走. 确认没有的才撤掉.
 */
internal enum class TvPadSectionState { Loading, Content, Empty }

/**
 * 选集区: 界面上有分集 = 有内容; 条目还是占位、或分集还在取 (库里没有, 本进程还没取完, 或取到了界面还没收到) = 在路上;
 * 取完了确实没有、或没取到 = 没有.
 */
internal fun tvPadEpisodesState(hasEpisodes: Boolean, subjectPlaceholder: Boolean, fetch: EpisodesFetchState): TvPadSectionState =
    when {
        hasEpisodes -> TvPadSectionState.Content
        subjectPlaceholder -> TvPadSectionState.Loading
        fetch is EpisodesFetchState.Fetched && fetch.visibleCount == 0 -> TvPadSectionState.Empty
        fetch == EpisodesFetchState.Failed -> TvPadSectionState.Empty
        else -> TvPadSectionState.Loading
    }

/**
 * 角色 / 制作人员: 露出来的那几个到了 = 有内容; 总数为 0 = 没有; 其余在路上 —— 总数到了、列表还没刷出来的那一拍也算在路上,
 * 占位撑到真内容出现的那一帧 (同遥控器版的人物行, 见 tvPeopleRowState).
 */
internal fun tvPadPeopleState(itemCount: Int, totalCount: Int?): TvPadSectionState =
    when (tvPeopleRowState(itemCount, totalCount)) {
        TvPeopleRowState.CONTENT -> TvPadSectionState.Content
        TvPeopleRowState.EMPTY -> TvPadSectionState.Empty
        TvPeopleRowState.LOADING -> TvPadSectionState.Loading
    }

/**
 * 分页列表 (评价 / 关联作品): 有条目 = 有内容; 总数为 0、首屏加载完了还是空、或出错 = 没有 (出错时不留一块一直在的占位); 其余在路上.
 * 分页列表先换条目再报加载状态, 「加载完了」不会抢在条目之前.
 *
 * @param totalCount 服务端给的总数 (评价有, 关联作品没有; null = 不知道)
 */
internal fun tvPadPagedState(itemCount: Int, refresh: LoadState, totalCount: Int? = null): TvPadSectionState =
    when {
        itemCount > 0 -> TvPadSectionState.Content
        totalCount == 0 -> TvPadSectionState.Empty
        refresh is LoadState.Error || refresh is LoadState.NotLoading -> TvPadSectionState.Empty
        else -> TvPadSectionState.Loading
    }
