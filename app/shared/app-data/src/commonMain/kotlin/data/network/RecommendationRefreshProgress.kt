/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

/**
 * 一次推荐重算进行到哪了 (见 [RecommendationRepository.refreshProgress]).
 *
 * 装完 / 登录后第一次进页时推荐区是空的, 一次重算要十几秒: 只写"需要十几秒"的话用户看不出是在动还是卡住了.
 * 读收藏的页数事先知道 (第一页就带总数); 找候选要发多少个请求事先不知道 (看种子、标签够不够用),
 * 所以那一段只报已经完成了几个.
 */
data class RecommendationRefreshProgress(
    val stage: Stage = Stage.Collections,
    /** 收藏已读几页; 第一页回来之前与 [collectionPagesTotal] 都是 0. */
    val collectionPagesDone: Int = 0,
    val collectionPagesTotal: Int = 0,
    /** 本次重算已完成的网络请求 (含读收藏的那几页). */
    val requestsDone: Int = 0,
) {
    enum class Stage {
        /** 读服务端的全部收藏 (没登录时跳过). */
        Collections,

        /** 按画像召回候选、排序. */
        Candidates,
    }
}
