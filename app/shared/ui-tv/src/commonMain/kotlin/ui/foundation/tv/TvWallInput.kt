/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

/*
 * 平板模式 (触屏) 下海报墙上手指点卡的规则, 只做判定, 不碰画面与滚动; 原生海报墙的几个视图都按这里判, 判完怎么挪焦点、怎么开由各自去做.
 */

/** 确认海报墙上的一张卡 (按确定, 或点已经聚焦的那张) 的结果, 见 [tvWallConfirm]. */
enum class TvWallConfirm {
    /** 占位 (分页还没到的、推荐行尾正在接的): 不做什么. */
    Ignore,

    /** 卡片墙上先进 hero 态 (海报墙确定键两步的第一步). */
    EnterHero,

    /** 打开这一部 (进详情页). */
    Open,
}

/**
 * 确认一张卡: 占位 ([isPlaceholder]) 上不做什么; 这一页有 hero 态 ([heroEnabled]) 而此刻在卡片墙上 (不在 hero 态) 时先进 hero 态,
 * hero 态里 (或这一页没有 hero 态) 才打开.
 */
fun tvWallConfirm(isPlaceholder: Boolean, heroEnabled: Boolean, heroActive: Boolean): TvWallConfirm = when {
    isPlaceholder -> TvWallConfirm.Ignore
    heroEnabled && !heroActive -> TvWallConfirm.EnterHero
    else -> TvWallConfirm.Open
}

/**
 * 触屏上点一张还没聚焦的卡时, 同一下是否也确认: 卡片墙上点一下 = 聚焦并进 hero 态;
 * hero 态里点另一张只换焦点 (再点才打开), 占位也只聚焦.
 */
fun tvWallTapConfirmsWhileFocusing(isPlaceholder: Boolean, heroEnabled: Boolean, heroActive: Boolean): Boolean =
    tvWallConfirm(isPlaceholder, heroEnabled, heroActive) == TvWallConfirm.EnterHero

/** 按触屏的规矩走时 (平板: 按下不动焦点, 轻点抬起才算) 手指点一张卡的结果, 见 [tvWallTouchTap]. */
enum class TvWallTouchTap {
    /** 当成点 hero 区: 打开聚焦的那张. */
    OpenFocused,

    /** 只把焦点换到这张 (hero 态里换 hero 内容). */
    FocusOnly,

    /** 聚焦这张并确认 (卡片墙上一下进 hero 态). */
    FocusAndConfirm,

    /** 确认 (同按确定, 见 [tvWallConfirm]). */
    Confirm,
}

/**
 * 按触屏的规矩走时手指点一张卡: hero 态里点到聚焦行上面那几行 ([aboveFocusedRow], 它们淡没在 hero 区里, 看不见) 算点 hero 区,
 * 点没聚焦的那张 ([isFocused] 为 false) 只换焦点、再点才打开; 卡片墙上点一下就聚焦并进 hero 态 (见 [tvWallTapConfirmsWhileFocusing]);
 * 占位与没有 hero 态的页面上点一下就确认 (占位上确认不做什么, 见 [tvWallConfirm]).
 */
fun tvWallTouchTap(
    isPlaceholder: Boolean,
    heroEnabled: Boolean,
    heroActive: Boolean,
    isFocused: Boolean,
    aboveFocusedRow: Boolean,
): TvWallTouchTap = when {
    heroActive && aboveFocusedRow -> TvWallTouchTap.OpenFocused
    heroActive && !isFocused -> TvWallTouchTap.FocusOnly
    tvWallTapConfirmsWhileFocusing(isPlaceholder, heroEnabled, heroActive) -> TvWallTouchTap.FocusAndConfirm
    else -> TvWallTouchTap.Confirm
}
