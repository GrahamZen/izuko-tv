/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

data class SubjectSearchFilters(
    val tags: List<String>? = null, // "童年", "原创"
    /**
     * 按 bangumi 的**官方** meta_tags 筛, 如 `"TV"` / `"WEB"` / `"剧场版"`.
     *
     * 与 [tags] (用户打的标签) 不是一回事: 那些是众包的, 同一个意思有好几种写法; 这些是站方
     * 归一化过的, 拿来判"是不是连载形态"才靠得住. 多个之间是**且**关系, 所以"TV 或 WEB"
     * 只能分两次问.
     */
    val metaTags: List<String>? = null,
    val airDates: List<String>? = null, // YYYY-MM-DD
    val ratings: List<String>? = null, // ">=6", "<8"
    val ranks: List<String>? = null,
    /**
     * 按**评分人数**筛, 如 `">=1000"`.
     *
     * 排行榜必须靠它兜住"分不低但没几个人看过"的条目: bangumi 的 rank 不管观众规模, 于是
     * 1981 年只有 292 人评过分的作品照样能排进某个标签的前 20 (2026-09-07 实测).
     */
    val ratingCounts: List<String>? = null,
    val nsfw: Boolean? = null,
)
