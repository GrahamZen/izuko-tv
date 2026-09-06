/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Immutable

/**
 * 起播自动选源这一步的进展. 播放页的加载提示与后台会话的状态行据此写「正在搜索数据源 9/14 · 已找到 37 条」,
 * 查完却没选中时说清是没结果还是要手选.
 *
 * 只留显示要用的几个数, 方便去重: 整份 EpisodePageState 随弹幕统计等刷得很勤, 加载提示直接读它的话每刷一次都要重组.
 */
@Immutable
data class SourceSearchProgress(
    /** 查完了的源 (成功 / 失败 / 要验证 / 被限流都算). */
    val finished: Int,
    /** 参与搜索的源: 启用的, 不含本地缓存. */
    val total: Int,
    /** 通过筛选 (属于这一集等) 的资源条数. */
    val found: Int,
    /** 还在查的源名, 按列表顺序, 最多 3 个. */
    val pendingNames: List<String>,
    /** 还在查的源一共几个 (可能比 [pendingNames] 多). */
    val pendingCount: Int,
    /** 全部查完却没选中时的原因; 还在查或已经选中时为 `null`. */
    val stuck: SourceSearchStuck?,
)

/** 全部数据源查完却没选中时的两种情况, 与后台会话的 NeedsSelection / NoMedia 同一判据. */
enum class SourceSearchStuck {
    /** 有结果, 但不会自动选 (偏好不是 WEB), 要用户到「选择数据源」里挑. */
    NeedsManualSelection,

    /** 全部查完, 一个可播的结果都没有. */
    NoMedia,
}
