/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.repository.subject.SubjectNsfw

/**
 * 界面里按设置处理 NSFW 作品 (所有页面同一套, 见 [SubjectNsfw]): 按条目 id 问. 读的是快照状态, 设置或登记表变了照常重组.
 *  - 隐藏: 列表交给界面之前过一遍 [visible];
 *  - 模糊: 封面 / 背景图按 [blurs] 打码 —— 原生卡片与背景层的 obscure、AsyncImage 的 [coverDownsample] / [backdropDownsample].
 *  - 自己的记录 (缓存管理、观看历史、正在播放) 不删条目, 两档都打码 ([ownedCoverDownsample]).
 * 页面不必自己读设置, 也不必数据里带标记, 有条目 id 就行.
 */
@Stable
class NsfwPolicy internal constructor(
    private val ids: State<Set<Int>>,
    private val mode: State<NsfwMode>,
) {
    /** 设置与登记表此刻的样子 (快照读): 在 remember 里用 [blurs] 等算东西时当 key, 变了重算. */
    val snapshot: Any get() = mode.value to ids.value

    /** [subjectId] 怎么显示: 登记过是 NSFW 的按设置, 否则照常. */
    fun modeOf(subjectId: Int): NsfwMode = if (subjectId in ids.value) mode.value else NsfwMode.DISPLAY

    /** 要从列表里去掉. */
    fun hides(subjectId: Int): Boolean = modeOf(subjectId) == NsfwMode.HIDE

    /** 封面 / 背景图要打码. */
    fun blurs(subjectId: Int): Boolean = modeOf(subjectId) == NsfwMode.BLUR

    /** [items] 去掉要隐藏的 (设置不是隐藏时原样返回). */
    fun <T> visible(items: List<T>, subjectIdOf: (T) -> Int): List<T> {
        if (mode.value != NsfwMode.HIDE) return items
        val hidden = ids.value
        return items.filterNot { subjectIdOf(it) in hidden }
    }

    /** 封面打码时 AsyncImage 的 downsampleLongEdgePx (不打码为 null). */
    fun coverDownsample(subjectId: Int): Int? = if (blurs(subjectId)) NSFW_OBSCURED_COVER_LONG_EDGE_PX else null

    /** 整屏背景图打码时 AsyncImage 的 downsampleLongEdgePx (不打码为 null). */
    fun backdropDownsample(subjectId: Int): Int? = if (blurs(subjectId)) NSFW_OBSCURED_BACKDROP_LONG_EDGE_PX else null

    /**
     * 自己的记录 (缓存管理、观看历史、正在播放) 里的封面: 不从列表里去掉 (删了就管不了自己下过、看过的东西), 隐藏与模糊两档都打码.
     * 返回 AsyncImage 的 downsampleLongEdgePx (不打码为 null).
     */
    fun ownedCoverDownsample(subjectId: Int): Int? =
        if (modeOf(subjectId) != NsfwMode.DISPLAY) NSFW_OBSCURED_COVER_LONG_EDGE_PX else null

    /** 同 [ownedCoverDownsample], 是不是要打码. */
    fun obscuresOwned(subjectId: Int): Boolean = modeOf(subjectId) != NsfwMode.DISPLAY
}

/** 当前设置下的 [NsfwPolicy]. */
@Composable
fun rememberNsfwPolicy(): NsfwPolicy {
    val ids = SubjectNsfw.ids.collectAsState()
    val mode = SubjectNsfw.mode.collectAsState()
    return remember(ids, mode) { NsfwPolicy(ids, mode) }
}

/**
 * NSFW 打码封面的解码长边 (px). 卡片 1080p 下长边约 320px, 缩到 24 ≈ 13 倍放大, 糊到认不出内容但
 * 还留得住主色调. sketch 的幂次采样只会落在 ≤ 请求的一档, 实际常是 12~24px.
 */
const val NSFW_OBSCURED_COVER_LONG_EDGE_PX = 24

/**
 * NSFW 打码背景图的解码长边 (px). 1080p 下背景框长边约 1267px, 取 48 ≈ 26 倍放大: 比封面糊得狠
 * (整屏大图, 细节更容易认出来), 但不至于像 24 那样放大 50 倍成一块块色斑.
 */
const val NSFW_OBSCURED_BACKDROP_LONG_EDGE_PX = 48
