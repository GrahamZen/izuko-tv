/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * TV 播放器控制层里可自定义的两行, 见 [TvPlayerChromeItem].
 *
 * 两行的渲染方式完全不同 (胶囊带文字标签并绑着浮出面板, 圆钮只有图标), 条目不能跨行搬,
 * 所以顺序与显隐都按行各算各的.
 */
@Immutable
@Serializable
enum class TvPlayerChromeRow {
    /** 进度条**上方**那一排胶囊 (聚焦即浮出面板). */
    PILLS,

    /** 进度条**下方**那一排圆钮与文字选项. */
    BOTTOM,
}

/**
 * TV 播放器控制层里一个可排列 / 可隐藏的条目.
 *
 * **枚举的声明顺序就是默认版式** (见 [TvPlayerChromeLayout.DEFAULT_ORDER]), 改动它等于改默认布局;
 * 而**枚举名是持久化的键** (设置以 JSON 存), 改名会让用户已排好的版式读不回来 —— 只能新增, 不能改名.
 *
 * 条目分三类:
 * - 普通功能条目: 一颗按钮.
 * - [isSeparator] 为真的装饰条目 ([DIVIDER_1] 等分组竖线与那段把两半推开的 [SPACER]): 不可聚焦,
 *   参与排列与显隐, 但不能当作焦点落点 (进度条上/下键的落点解析要跳过它们).
 * - [isConditional] 为真的条目: 本来就不是每次都在场的 (没有下一集 / 片源没有字幕轨 / 功能被关掉),
 *   "用户没隐藏"只是**允许它出现**, 真正在不在还要看运行时. 编辑页里会标一句.
 *
 * @since 6.0.7
 */
@Immutable
@Serializable
enum class TvPlayerChromeItem(
    val row: TvPlayerChromeRow,
    /** 装饰条目 (竖线 / 弹性留白): 不可聚焦. */
    val isSeparator: Boolean = false,
    /** 是否只在运行时条件满足时才出现 (见类文档). */
    val isConditional: Boolean = false,
    /** 只在触屏设备 (平板装了 TV 包) 上存在, 电视上连编辑页都不列出. */
    val isTouchOnly: Boolean = false,
) {
    // ---- 胶囊行 (左 -> 右) ----
    PILL_RECOMMENDATIONS(TvPlayerChromeRow.PILLS),
    PILL_STAFF(TvPlayerChromeRow.PILLS),
    PILL_CHARACTERS(TvPlayerChromeRow.PILLS),
    PILL_COMMENTS(TvPlayerChromeRow.PILLS),
    PILL_DANMAKU(TvPlayerChromeRow.PILLS),

    // ---- 图标行 (左 -> 右) ----
    RESTART(TvPlayerChromeRow.BOTTOM),
    NEXT_EPISODE(TvPlayerChromeRow.BOTTOM, isConditional = true),
    SKIP_OP_ED(TvPlayerChromeRow.BOTTOM),
    TOUCH_EPISODE_STRIP(TvPlayerChromeRow.BOTTOM, isTouchOnly = true),
    TOUCH_DETAILS(TvPlayerChromeRow.BOTTOM, isTouchOnly = true),
    DIVIDER_1(TvPlayerChromeRow.BOTTOM, isSeparator = true),
    MEDIA_SOURCE(TvPlayerChromeRow.BOTTOM),
    DIVIDER_2(TvPlayerChromeRow.BOTTOM, isSeparator = true),
    DANMAKU_TOGGLE(TvPlayerChromeRow.BOTTOM),
    DANMAKU_SETTINGS(TvPlayerChromeRow.BOTTOM),
    WATCH_TOGETHER(TvPlayerChromeRow.BOTTOM, isConditional = true),
    SPACER(TvPlayerChromeRow.BOTTOM, isSeparator = true),
    SUBTITLE_TRACK(TvPlayerChromeRow.BOTTOM, isConditional = true),
    PLAYBACK_SPEED(TvPlayerChromeRow.BOTTOM, isConditional = true),
    ASPECT_RATIO(TvPlayerChromeRow.BOTTOM, isConditional = true),
    DIVIDER_3(TvPlayerChromeRow.BOTTOM, isSeparator = true),
    COLLECTION(TvPlayerChromeRow.BOTTOM),
    PLAYER_STATS(TvPlayerChromeRow.BOTTOM),
    SHARE(TvPlayerChromeRow.BOTTOM),
    CACHE(TvPlayerChromeRow.BOTTOM),
    ;
}

/**
 * TV 播放器控制层的自定义版式 (顺序 + 显隐), 见 [VideoScaffoldConfig.tvPlayerChrome].
 *
 * **[order] 为空 = 没排过**, 用默认顺序; 排过之后存的也只是一份"用户排出来的顺序", 与枚举的
 * 全集未必一致 —— 新版本加了按钮, 或者旧版本的按钮被删掉, 两边都会对不上. 所以读取一律走
 * [orderOf] / [visibleItemsOf], 它们会把缺的按默认位置补回去、把不认识的丢掉 (见 [resolveOrder]).
 *
 * @property order 用户排出来的顺序 (两行混在一个列表里, 按 [TvPlayerChromeItem.row] 分流).
 * @property hidden 被隐藏的条目.
 * @since 6.0.7
 */
@Immutable
@Serializable
data class TvPlayerChromeLayout(
    val order: List<TvPlayerChromeItem> = emptyList(),
    val hidden: Set<TvPlayerChromeItem> = emptySet(),
) {
    /** 本行的完整顺序 (含被隐藏的), 用户没排过的条目按默认位置补齐. */
    fun orderOf(row: TvPlayerChromeRow): List<TvPlayerChromeItem> =
        resolveOrder(order.filter { it.row == row }, DEFAULT_ORDER.filter { it.row == row })

    /** 本行**实际要渲染**的条目 (已去掉隐藏的). 运行时还有没有 (没有下一集之类) 由调用方再判. */
    fun visibleItemsOf(row: TvPlayerChromeRow): List<TvPlayerChromeItem> =
        orderOf(row).filterNot { it in hidden }

    fun isHidden(item: TvPlayerChromeItem): Boolean = item in hidden

    fun withHidden(item: TvPlayerChromeItem, hidden: Boolean): TvPlayerChromeLayout = copy(
        // order 一并落成显式顺序: 隐藏过的版式再遇上默认顺序变更时, 不该跟着一起变
        order = fullOrder(),
        hidden = if (hidden) this.hidden + item else this.hidden - item,
    )

    /**
     * 把 [item] 在它自己那一行里前移 / 后移一格 ([delta] = -1 / +1); 已在端点则原样返回.
     *
     * 只在行内动: 胶囊与圆钮的渲染方式完全不同, 跨行搬没有意义.
     *
     * @param among **眼前看得见的那些条目** (按顺序). 自定义页上会有条目不列出来 (触屏专有的两颗
     *   在电视上就不该出现), 而"按一次左键挪一格"说的是挪过眼前的一格 —— 拿整行全集算的话,
     *   撞上一个没列出来的条目时按键会像没反应一样. 传 `null` = 就用整行.
     */
    fun moved(
        item: TvPlayerChromeItem,
        delta: Int,
        among: List<TvPlayerChromeItem>? = null,
    ): TvPlayerChromeLayout {
        val rowOrder = orderOf(item.row)
        val visible = among ?: rowOrder
        val from = visible.indexOf(item)
        val toVisible = from + delta
        if (from < 0 || toVisible !in visible.indices) return this
        // 落到"眼前那一格"的另一侧: 中间那些没列出来的条目跟着一起被跨过去
        val target = visible[toVisible]
        val moved = rowOrder.toMutableList().apply {
            remove(item)
            val at = indexOf(target) + (if (delta > 0) 1 else 0)
            add(at, item)
        }
        // 另一行保持原样: 整份 order 按行重新拼起来
        val other = TvPlayerChromeRow.entries.filter { it != item.row }.flatMap { orderOf(it) }
        return copy(order = (moved + other).sortedBy { it.row.ordinal })
    }

    /** 两行都落成显式顺序 (存进 [order] 用). */
    private fun fullOrder(): List<TvPlayerChromeItem> =
        TvPlayerChromeRow.entries.flatMap { orderOf(it) }

    @Transient
    val isDefault: Boolean = hidden.isEmpty() && (order.isEmpty() || order == DEFAULT_ORDER)

    companion object {
        @Stable
        val Default = TvPlayerChromeLayout()

        /** 默认版式 = 枚举声明顺序, 见 [TvPlayerChromeItem]. */
        @Stable
        val DEFAULT_ORDER: List<TvPlayerChromeItem> = TvPlayerChromeItem.entries.toList()

        /**
         * 把存下来的顺序对齐到当前版本的全集 [defaults]:
         * 不认识的条目丢掉, 缺的按**它在默认版式里紧挨着的前一个条目**之后插回去.
         *
         * 后一条是关键: 新版本加的按钮必须落在它设计时该在的那一组里 (比如新的弹幕类按钮要挨着
         * 弹幕开关), 直接追到末尾会掉进右边的低频组, 看着像个 bug.
         */
        /**
         * 收拾装饰条目: 用户把一整组按钮藏掉之后, 常会剩下孤零零的竖线 (行首一根, 或者两根挨在一起).
         *
         * 规则是: 连在一起的装饰条目只留第一个; 行首的装饰条目丢掉; 行尾的竖线丢掉 ——
         * 行尾的 [TvPlayerChromeItem.SPACER] 留着, 它在那个位置等于"把按钮全部推到左边",
         * 是个有意义的排法.
         *
         * 渲染与编辑页的预览都要走这一步, 不然两边长得不一样.
         */
        fun tidySeparators(items: List<TvPlayerChromeItem>): List<TvPlayerChromeItem> {
            val result = mutableListOf<TvPlayerChromeItem>()
            for (item in items) {
                if (!item.isSeparator) {
                    result.add(item)
                    continue
                }
                if (result.isEmpty()) continue // 行首
                if (result.last().isSeparator) continue // 挨着上一根
                result.add(item)
            }
            val last = result.lastOrNull()
            if (last != null && last.isSeparator && last != TvPlayerChromeItem.SPACER) {
                result.removeAt(result.lastIndex)
            }
            return result
        }

        fun resolveOrder(
            saved: List<TvPlayerChromeItem>,
            defaults: List<TvPlayerChromeItem>,
        ): List<TvPlayerChromeItem> {
            if (saved.isEmpty()) return defaults
            val result = saved.distinct().filterTo(mutableListOf()) { it in defaults }
            if (result.size == defaults.size) return result
            for ((index, item) in defaults.withIndex()) {
                if (item in result) continue
                // 默认版式里它前面最近的、已经落位的那个条目 —— 插到它后面
                var anchor = -1
                for (i in index - 1 downTo 0) {
                    val at = result.indexOf(defaults[i])
                    if (at >= 0) {
                        anchor = at
                        break
                    }
                }
                result.add(anchor + 1, item)
            }
            return result
        }
    }
}

/**
 * 几套 TV 播放器版式 + 当前在用哪套, 见 [VideoScaffoldConfig.tvPlayerChrome].
 *
 * 为什么要"几套"而不是一套: 同一台电视上的用法本来就不止一种 —— 一个人看新番时想要弹幕那几颗
 * 排在最前, 陪人看老片时只想留下换源与进度, 而这两种排法互相覆盖. 存成几套之后, 换用法 = 换一套,
 * 不用每次重排.
 *
 * **名字是按位置算出来的**(「布局 1」「布局 2」…), 不让用户起名: 遥控器上打字是件苦差事,
 * 而这几套的区别本来就靠"排出来的样子"一眼认得出 —— 自定义页上摆的就是它本人.
 *
 * @property layouts 全部版式; **空 = 只有默认那一套** (没动过的人不该在设置里存一份冗余数据).
 * @property activeIndex 当前在用第几套; 越界时回落到第一套 (见 [active]).
 * @since 6.0.7
 */
@Immutable
@Serializable
data class TvPlayerChromePresets(
    val layouts: List<TvPlayerChromeLayout> = emptyList(),
    val activeIndex: Int = 0,
) {
    /** 实体化之后的列表: 至少一套. */
    @Transient
    val resolved: List<TvPlayerChromeLayout> = layouts.ifEmpty { listOf(TvPlayerChromeLayout.Default) }

    /** 当前在用的那套 —— 播放器只认这个. */
    @Transient
    val active: TvPlayerChromeLayout = resolved.getOrNull(activeIndex) ?: resolved.first()

    @Transient
    val activeIndexResolved: Int = activeIndex.coerceIn(0, resolved.lastIndex)

    /** 改写当前这套. */
    fun withActive(layout: TvPlayerChromeLayout): TvPlayerChromePresets = copy(
        layouts = resolved.toMutableList().apply { this[activeIndexResolved] = layout },
        activeIndex = activeIndexResolved,
    )

    fun switchedTo(index: Int): TvPlayerChromePresets =
        if (index in resolved.indices) copy(layouts = resolved, activeIndex = index) else this

    /**
     * 复制当前这套, 并切到新的那套上.
     *
     * 复制而不是新建一套默认的: 会想要第二套的人, 多半是"跟现在这套差不多, 但有几颗不一样",
     * 从空白重排一遍太亏. 想要干净的默认版式, 新建之后按一下「恢复默认」即可.
     */
    fun duplicatedActive(): TvPlayerChromePresets {
        if (resolved.size >= MAX_PRESETS) return this
        val index = activeIndexResolved + 1
        return copy(
            layouts = resolved.toMutableList().apply { add(index, active) },
            activeIndex = index,
        )
    }

    /** 删掉当前这套 (至少留一套); 焦点落到前一套上. */
    fun removedActive(): TvPlayerChromePresets {
        if (resolved.size <= 1) return this
        val index = activeIndexResolved
        return copy(
            layouts = resolved.toMutableList().apply { removeAt(index) },
            activeIndex = (index - 1).coerceAtLeast(0),
        )
    }

    companion object {
        @Stable
        val Default = TvPlayerChromePresets()

        /** 上限: 再多就该给它们起名字了, 而那需要在遥控器上打字. */
        const val MAX_PRESETS = 5
    }
}
