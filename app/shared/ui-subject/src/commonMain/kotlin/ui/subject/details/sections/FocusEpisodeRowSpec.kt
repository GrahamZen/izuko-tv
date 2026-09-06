/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem

/**
 * [FocusEpisodeCarousel] 的卡片行换一种实现时 (电视端的原生行, 见 ui-tv 的 TvNativeEpisodeRow), 轮播交给它的全部东西.
 *
 * 轮播 (大脑) 管数据、落点、唯一的送焦通道、信息行与长按弹窗; 行只管画卡片与固定锚位聚焦框、卡片之间的左右移动与跟焦点滚动,
 * 焦点与点击经回调报回轮播. 焦点不在卡片上时的滚动与送焦由轮播经 [controller] 下指令.
 *
 * 轮播每次重组换一份. 随按键变化的量 (落点、压暗分界、倒计时) 一律是 provider, 由行在自己的效应里读, 轮播 body 不订阅.
 */
@Stable
class FocusEpisodeRowSpec(
    val episodes: List<EpisodeListItem>,
    /** 正在播放 (详情页: 下一集要看) 的那一集: 卡上画声浪图标、换主色底. */
    val currentEpisodeId: Int?,
    val episodeStills: Map<Int, String>,
    val playProgress: Map<Int, Float>,
    val cellWidth: Dp,
    val cellHeight: Dp,
    val cellSpacing: Dp,
    /** 滚动停靠位 = 固定聚焦框的左缘. 行本身全宽出血. */
    val horizontalPadding: Dp,
    val monochrome: Boolean,
    val glass: Boolean,
    /** 卡片支持长按确认键 ([onLongClick]); false = 按住也只算点击, 也没有按压反馈. */
    val longPressEnabled: Boolean,
    /** 焦点从行外进来时落到第几张 (展示中的那一集). */
    val entryIndex: () -> Int,
    /** 压暗分界: 下标小于它的卡压暗到 [EPISODE_PAST_CARD_DIM_ALPHA]. 焦点离开本行时不清 (见轮播里 dimPivotIndex 的说明). */
    val dimPivotIndex: () -> Int,
    /** 每张卡额外的不透明度 (与压暗相乘), 见 [FocusEpisodeCarousel] 的 cardAlpha. */
    val cardAlpha: ((index: Int) -> Float)?,
    /** 非 null 时固定聚焦框画成倒计时环, 见 [FocusEpisodeCarousel] 的 anchorCountdown. */
    val anchorCountdown: (() -> Float)?,
    /**
     * 挂在装行的那个节点上: 调用方给的 rowFocusRequester 与 rowFocusModifier, 以及上 / 下键的固定去向 (见 [FocusEpisodeCarousel] 的
     * upFocus / downFocus; 行里的卡不是 Compose 焦点节点, 挂不了 focusProperties, 在这个节点上按键预览接住).
     */
    val modifier: Modifier,
    val controller: FocusEpisodeRowController,
    val onCardFocused: (index: Int) -> Unit,
    /** 第 [index] 张失去焦点. 行内移动时与新卡的 [onCardFocused] 谁先谁后由实现定, 轮播两种顺序都接得住. */
    val onCardFocusLost: (index: Int) -> Unit,
    val onClick: (index: Int) -> Unit,
    val onLongClick: (index: Int) -> Unit,
)

/**
 * 轮播对自定义卡片行 ([FocusEpisodeRowSpec]) 下的指令: 焦点不在卡片上时的滚动 (数据到了对齐落点、弹窗里左右切集) 与送焦通道的送焦.
 * 行建出来时把自己挂到 [target] 上; 没挂上时指令什么都不做.
 */
@Stable
class FocusEpisodeRowController {
    interface Target {
        /** 滚到第 [index] 张停在停靠位; [animated] = false 时一步到位. */
        fun scrollTo(index: Int, animated: Boolean)

        /** 第 [index] 张已经排出来就送焦, 返回是否送上. */
        fun focusCard(index: Int): Boolean

        /** 第 [index] 张此刻在不在屏上 (露出一截也算). */
        fun isOnScreen(index: Int): Boolean
    }

    var target: Target? = null

    fun scrollTo(index: Int, animated: Boolean) {
        target?.scrollTo(index, animated)
    }

    fun focusCard(index: Int): Boolean = target?.focusCard(index) == true

    fun isOnScreen(index: Int): Boolean = target?.isOnScreen(index) == true
}
