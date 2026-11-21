/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule.grid

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.exploration.schedule.ScheduleDay
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.tv.TvRailWallSelection
import me.him188.ani.app.ui.foundation.tv.nativeview.TvGridPageExtension

/**
 * 新番时间表顶上的日期行: 15 天各一枚玻璃胶囊, 写「今天 / 周三 / 上周三」加「月/日」. **聚焦即选中**, 海报墙等方向键松开才换
 * (见 [TvRailWallSelection]); 确定当场换, 下键进海报墙; 本行是最上面一层, 左边没有侧边栏, 两端与上键不动. 焦点或选中的那天滚到居中
 * (海报墙跨天时跟着滚); 焦点在海报墙里时随内容滚出屏, 点开进详情页时跟着卡片淡没.
 *
 * 页面只经这个接口用日期行. 按平台选实现只在 [rememberTvScheduleDateRow] 一处: 安卓上是挂在原生网格页上的玻璃签栏
 * ([rememberTvScheduleNativeDateRow], 日期与海报墙之间的焦点都在原生里走); 没有原生视图的端用 Compose 那份 ([rememberTvScheduleComposeDateRow]).
 */
internal interface TvScheduleDateRow {
    /** 挂到网格页海报墙上的部分 (给 TvNativeGridPageHost 的 extensions); Compose 那份没有. */
    val wallExtensions: List<TvGridPageExtension>

    /** 焦点给选中的那天 (还没排出来时记下, 排好再给), 同时滚到居中. */
    fun focusSelected()

    /**
     * 日期行在页面上占的那一块: [modifier] 给位置与高 (页面顶上、不随下面的内容一起滚走的那一层), 每次组合把 [days] 推进去.
     * [scrollAway] = 此刻往上滚走了多少 (px), [fade] = 点开时淡没了多少 (0..1), 都在绘制或动画里读; [onDown] = 下键进海报墙,
     * [onFocused] = 日期行拿到了焦点 (本页的兜底落点).
     */
    @Composable
    fun Content(
        days: List<ScheduleDay>,
        scrollAway: () -> Float,
        fade: () -> Float,
        onDown: () -> Boolean,
        onFocused: () -> Unit,
        modifier: Modifier,
    )
}

/** 日期行 (见 [TvScheduleDateRow]). [selection] = 选中的那天, [focus] = 页面的焦点域 (Compose 那份按它送焦). */
@Composable
internal expect fun rememberTvScheduleDateRow(selection: TvRailWallSelection<Int>, focus: TvFocusScope): TvScheduleDateRow
