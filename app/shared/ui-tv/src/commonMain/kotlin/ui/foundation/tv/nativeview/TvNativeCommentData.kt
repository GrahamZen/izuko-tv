/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Immutable

// 原生评论列表 (TvNativeCommentListView, 安卓) 吃的数据: 公共代码里把评论转成它, 原生侧只画.

/** 原生评论列表的一楼 (见 [TvNativeCommentListView]): 字都已经备好 (时间已格式化、正文已压成文字 + 行内表情). */
@Immutable
data class TvNativeComment(
    /** 稳定标识: 焦点按它认. */
    val key: String,
    val avatarUrl: String?,
    val name: String,
    /** 个人签名, 跟在昵称后面的灰字; null = 没有. */
    val sign: String?,
    /** 楼层与时间 (`#3  10-25 12:00`). */
    val meta: String,
    /** 评分 (1~10); null = 不画星. */
    val rating: Int?,
    val body: TvNativeInlineText,
    /** 已被删除: 正文处写一句「删除了回复」. */
    val deleted: Boolean,
    /** 别人贴的表情 (回应): 表情 + 人数排成一行; 空 = 没有. */
    val reactions: TvNativeInlineText,
    /** 楼下摆出来的前几条回复. */
    val replies: List<TvNativeCommentReply>,
    /** 这一楼一共多少条回复; 比 [replies] 多时末尾写「共 N 条回复」. */
    val replyCount: Int,
)

/** 楼下摆出来的一条回复. */
@Immutable
data class TvNativeCommentReply(
    val key: String,
    val name: String,
    val meta: String,
    /** 非空 = 回复的是同层某条回复, 写明回复谁. */
    val replyToName: String?,
    val body: TvNativeInlineText,
    val deleted: Boolean,
)

/** 原生文字: 表情处是一个占位符 ([TV_NATIVE_STICKER_PLACEHOLDER]), 由视图取图画上去. */
@Immutable
data class TvNativeInlineText(val text: String, val stickers: List<TvNativeSticker>) {
    companion object {
        val Empty = TvNativeInlineText("", emptyList())
    }
}

/** 一枚行内表情: [index] 是占位符在文字里的位置. */
@Immutable
data class TvNativeSticker(val index: Int, val url: String)

/** 列表到边时按的方向 (见 [TvNativeCommentListView.onEdge]). */
enum class TvNativeCommentEdge { UP, DOWN, LEFT, RIGHT }

/** 见 [TvNativeCommentListController.entryIndex]. */
const val TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM = -1

/** 行内表情的占位符 (Unicode 的「对象替换字符」). */
const val TV_NATIVE_STICKER_PLACEHOLDER = '￼'

/** 文案模板里填参数的地方 (Compose 那侧按它取模板, 原生侧换成实际的名字 / 数字). */
const val TV_NATIVE_TEXT_ARG = "\u0001"
