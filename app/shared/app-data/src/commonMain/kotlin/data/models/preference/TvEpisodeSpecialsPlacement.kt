/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable

/**
 * TV: 选集 (详情页选集轮播、播放器选集条与选集网格) 里特别篇怎么放. 特别篇 = SP / OP / ED 等非正片. 见 [ThemeSettings.tvEpisodeSpecials].
 *
 * 只管列表怎么摆; 上一集 / 下一集与自动连播始终只在同类型的剧集之间切换 (见 `EpisodeCollections.findNeighborEpisode`).
 * 存的是枚举名, 别删值也别改名 (设置解码不容忍认不得的枚举名).
 */
@Serializable
enum class TvEpisodeSpecialsPlacement {
    /** 不列特别篇. 条目只有特别篇、或正在播的就是特别篇时照常列出. */
    Hidden,

    /** 全部正片之后, 按类型分组 (SP、OP、ED…), 组内按序号. 与数据库里剧集的顺序一致. 默认. */
    AfterMain,

    /** 按序号插在正片之间, 如序号 20.5 的特别篇排在第 20 集后面; 序号相同时正片在前. */
    ByNumber,
}
