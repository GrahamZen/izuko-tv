/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Stable

/** 原生评论列表在 [TvNativeCommentListController] 眼里的样子 (安卓的 TvNativeCommentListView 实现). */
interface TvNativeCommentListHandle {
    var entryIndex: Int
    var focusBlocked: Boolean

    /** 滚到第 [index] 条 (越界就不动); 焦点不动. */
    fun scrollToItem(index: Int)

    /** 像焦点走到了第 [index] 条那样把它滚进来, 焦点不动. */
    fun revealPosition(index: Int)
}

/**
 * 原生列表的控制柄: 让外面指定焦点进来时落在哪一条、滚到哪一条 (播放器评论面板: 从胶囊进来落在最下面那条, 从整楼退回来落在点开的那条).
 * 不给时 (「查看全部」) 第一次进来落在 initialFocusIndex, 之后按系统的规则找.
 */
@Stable
class TvNativeCommentListController {
    internal var view: TvNativeCommentListHandle? = null

    /** 焦点从外面进来时落在第几条; [TV_NATIVE_COMMENT_ENTRY_NEAREST_BOTTOM] = 看得见的里面最靠下的那条 (离下面的胶囊最近). */
    var entryIndex: Int = 0
        set(value) {
            field = value
            view?.entryIndex = value
        }

    /**
     * 不让焦点进来 (安卓视图那层): 列表藏在别的东西底下时 (「查看全部」里点开的整楼), 系统拿没人要的方向键在窗口里找下一个焦点,
     * 会绕过 Compose 的规则找到这些看不见的条目. 只在焦点已经不在列表里时设.
     */
    var focusBlocked: Boolean = false
        set(value) {
            field = value
            view?.focusBlocked = value
        }

    /** 滚到第 [index] 条 (排好之前也行, 排完生效); 焦点不动. */
    fun scrollTo(index: Int) {
        view?.scrollToItem(index)
    }

    /**
     * 像焦点走到了第 [index] 条那样把它滚进来 (同按方向键时的按需挪、同样的滚动曲线), 焦点不动: 焦点在别处翻着这些条目时 (整楼里左右翻楼)
     * 背后的列表跟着走, 焦点回来时那条已经在该在的地方, 不用再挪.
     */
    fun reveal(index: Int) {
        view?.revealPosition(index)
    }
}
