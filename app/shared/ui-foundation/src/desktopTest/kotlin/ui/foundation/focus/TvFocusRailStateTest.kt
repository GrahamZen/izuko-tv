/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TvFocusRailState] 的记账不变量 —— 纯逻辑, 不经过 Compose (同 [TvFocusScopeTest] 里那两条:
 * 用 Compose UI 测试守这种东西会在桌面上假绿).
 *
 * 守的是本状态存在的唯一理由: **"聚焦即选中"只能由本行左右键引发的移焦触发**. 漏了的真机症状 ——
 * 追番页从卡片进详情页再快速返回被拽到第一个 tab 的第一张卡, 时间表同样操作被拽到 15 天前那天
 * (返回瞬间焦点系统会把默认焦点塞给行内第一项, 那一下绝不能改选中项).
 */
class TvFocusRailStateTest {
    private val scope = TvFocusScope()

    private fun rail(onMove: (Int) -> Unit = {}) =
        TvFocusRailState(scope, { index -> TvFocusKey("item$index") }, onMove)

    @Test
    fun `system assigned focus does not count as a user selection`() {
        val state = rail()
        // 没有经过 moveTo: 这是焦点系统塞过来的 (页面切换 / 焦点悬空后的重分配)
        assertFalse(state.onItemFocused(0))
        assertEquals(0, state.focusedIndex)
    }

    @Test
    fun `focus arriving after moveTo counts as a user selection, exactly once`() {
        val moved = mutableListOf<Int>()
        val state = rail { moved += it }

        state.moveTo(2)
        assertEquals(listOf(2), moved, "moveTo 必须把送焦交给调用方给的方式")
        assertTrue(state.onItemFocused(2))
        assertEquals(2, state.focusedIndex)

        // 封印是一次性的: 同一次解封不能让后续任何一次获焦都算"用户选的" —— 否则送焦之后
        // 焦点系统再动一下 (如目标项随数据替换重建) 就会又改一次选中项
        assertFalse(state.onItemFocused(3))
        assertEquals(3, state.focusedIndex)
    }

    @Test
    fun `focus landing on an item other than the armed target is not a selection`() {
        val state = rail()
        state.moveTo(5)
        // 送焦悬在半路 (目标项还没组合出来) 时别的项短暂获焦: 不是用户选的
        assertFalse(state.onItemFocused(4))
        // 而且不解除武装 —— 真正的目标随后到达仍要算选中 (长按连续换天靠这条)
        assertTrue(state.onItemFocused(5))
    }

    /**
     * 本类最要紧的一条: **送焦悬在半路被解除武装之后, 连原目标获焦也不能算用户选择**.
     *
     * 时间表往一枚还没组合出来的日期胶囊移动 -> 请求悬挂在 [TvFocusScope] 上; 用户此刻按下键进
     * 网格 -> [tvFocusRailKeys] 调 [TvFocusRailState.cancelArmedMove] 解除武装 (scope 那边的请求
     * 由 notifyUserNavigation 取消, 但那管不到本状态).
     *
     * **"原目标"这一半是必测的**: `onMove` 里那句 scrollToItem 是另起协程跑的, 不受请求取消影响,
     * 照样把行滚过去了 —— 组合窗口移到了包含旧目标的位置, 之后按遍历顺序分配的兜底焦点完全可能
     * 正好落在它身上. 只测"别的项获焦"会漏掉这条 (2026-08-23 审计逮到的就是这个漏洞).
     */
    @Test
    fun `nothing counts as a selection after the armed move is cancelled`() {
        val state = rail()
        state.moveTo(3) // 目标胶囊还没组合出来, 请求悬挂

        scope.notifyUserNavigation() // scope 上的请求被取消
        state.cancelArmedMove() // 按键路由随之解除武装

        // 兜底焦点落在别的项上
        assertFalse(state.onItemFocused(0), "取消之后别的项获焦不该算用户选择")
        assertEquals(0, state.focusedIndex, "下标记账照旧要更新")
        // 兜底焦点正好落回旧目标 (行已被 scrollToItem 滚过去, 它现在就在组合窗口里)
        assertFalse(state.onItemFocused(3), "取消之后连原目标获焦也不该算用户选择")
    }

    @Test
    fun `long press moves in a row each count as a selection`() {
        val state = rail()
        // 长按方向键连续换天/换标签: 每一发都是 moveTo + 目标到位, 每一发都要选中
        state.moveTo(1)
        assertTrue(state.onItemFocused(1))
        state.moveTo(2)
        assertTrue(state.onItemFocused(2))
    }

    @Test
    fun `focusedIndex starts unknown`() {
        // -1 不表示"焦点不在本行" (按键路由只在焦点位于本行子树内才被调用), 而是"还不知道是哪一项":
        // requestFocus 已被接受、目标 onFocusChanged 还没上报的那段窗口. tvFocusRailKeys 对右键
        // 必须在这个窗口里消费按键, 否则落到默认方向搜索会绕回第一项.
        assertEquals(-1, rail().focusedIndex)
    }
}
