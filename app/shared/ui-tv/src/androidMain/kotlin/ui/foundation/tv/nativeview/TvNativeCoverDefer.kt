/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.View
import kotlin.math.abs

/**
 * 按住方向键连走时卡片先不解码, 停下来那一屏尽量对 (用户 10-12 定: 连走途中错着没关系, 停下来的要对; 「一片纯色」不行):
 * - 方向键自动连发、列表还在滚的时候, 新绑上的普通海报只查图片内存缓存 —— 命中 (或封面上本来就是这一张) 就当场整张换上 (封面、番名一起);
 *   没命中不读盘、不解码, 番名也不排字, 整张先顶着上一个条目 (连走时看不清; 新建的卡露出封面的纯色底), 拿到焦点的那张只当场换番名.
 * - 停下 (松手, 或按着但列表已经走到头停住) 后把这些卡整张换上, 离焦点近的先换; 上一个条目的封面先清掉 (露出底色), 解好淡入 ——
 *   停下来的画面里不留错图, 行数不多的页面走到头不用等松手.
 *
 * 连走时一闪而过的行停下时早已出屏, 给它们解封面纯属白费: 索尼上按住上下 3 秒要解 130~196 张封面, 解码线程吃掉全进程三分之一的 CPU,
 * 跟主线程 / 渲染线程抢核. (连走途中一次只解一张的「涓流」也试过: 一张接一张照样解七八十张, 迟到帧近乎回到不延后, 已去掉.)
 *
 * 「连发」沿用全局的按键追踪 (`TvNavKeyTracker.repeating`: 按住约 400ms 后系统开始自动连发, 到抬起为止; 单击不算), 「在滚」沿用全局的
 * `TvScrollActivity.isScrolling` (连续几帧不动才算停), 由海报墙样式 (rememberTvNativeWallStyle) 同步进来; 不报滚动的页面 (时间表 / 追番)
 * 不延后, 同不开. 卡片绑定时读 [active], 先没换上的卡报 [onDeferred], 封面已上屏、整张已换上的报 [onApplied], 离开窗口报 [onCardDetached].
 */
internal object TvNativeCoverDefer {
    /** 连走中: 新绑上的普通海报只查内存缓存, 没命中的先不换. */
    var active: Boolean = false
        private set

    /** 连走期间先没换上的卡, 停下 (松手或列表停住) 后整张换上. */
    private val pending = LinkedHashSet<TvNativeCardView>()

    /**
     * 方向键自动连发且列表还在滚 (同一个值可能被几面海报墙重复同步, 只在翻转时动作). 连发没松手但列表已经停了 (走到头) 也算停下, 当场补,
     * 行数不多的页面不用等松手.
     */
    fun setDeferring(deferring: Boolean) {
        if (deferring == active) return
        active = deferring
        if (!deferring) flush()
    }

    fun onDeferred(card: TvNativeCardView) {
        pending += card
    }

    /** 不用等停下了 (封面已上屏, 整张已换上). */
    fun onApplied(card: TvNativeCardView) {
        pending -= card
    }

    fun onCardDetached(card: TvNativeCardView) {
        pending -= card
    }

    /** 停下: 补齐留下的卡, 离焦点近的先补 —— 焦点所在那一行最先, 再按上下距离由近及远 (眼睛看着的地方先换成对的封面). */
    private fun flush() {
        if (pending.isEmpty()) return
        val cards = pending.filter { it.isAttachedToWindow }
        pending.clear()
        if (cards.isEmpty()) return
        val focused = cards.first().rootView.findFocus()
        val focusY = focused?.let(::centerY) ?: 0
        val focusRow = focused?.parent
        val ordered = cards
            .map { card -> card to ((if (focusRow != null && card.parent === focusRow) 0 else 1) to abs(centerY(card) - focusY)) }
            .sortedWith(compareBy({ it.second.first }, { it.second.second }))
        for ((card, _) in ordered) card.applyDeferredCard()
    }

    private val location = IntArray(2)

    private fun centerY(view: View): Int {
        view.getLocationOnScreen(location)
        return location[1] + view.height / 2
    }
}
