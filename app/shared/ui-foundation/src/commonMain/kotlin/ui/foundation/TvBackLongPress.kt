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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 遥控器全局长按手势的通用宿主: 一份跟踪器认领某个键集的「长按」, 短按语义原样放行.
 * 现有两份实例 —— 返回键 ([TvBackLongPressHost], 长按=一步回到当前上下文的"底"/弹快捷菜单)
 * 与播放键 (长按=回到正在播放), 都由应用根部创建并挂拦截器.
 *
 * ## 为什么是"根部一个跟踪器 + 各处注册语义", 而不是各页自挂 onPreviewKeyEvent
 *
 * 长按判定要吃掉"触发之后"的连发与 KeyUp (否则松手那记 KeyUp 又触发一次短按语义, 比如返回键
 * 会把刚收干净的界面再退一层), 而普通短按必须原样放行 —— 这套计数/认领/吞残余的状态机只能有
 * 一份, 分散到各页就会出现两处同时计数、一处认领另一处漏放 KeyUp 的缝. 于是每个键集一个跟踪器,
 * 挂在应用根部 ([tvKeyLongPressInterceptor], preview 阶段祖先先行, 主窗口内该键的所有事件都
 * 先经过它); "长按之后干什么"由当下在场的界面注册 ([TvKeyLongPressHandler]), 后注册的先问 ——
 * 组合树越深的注册越晚, 恰好就是"最内层的上下文先接手" (如返回键: 播放器 > 根部兜底).
 *
 * 处理器返回 false = "此处无事可做", 继续问下一个; 全都拒绝则本次手势退回普通短按语义.
 *
 * ## 独立窗口 (Dialog / Popup / DropdownMenu) 靠桥接接进来
 *
 * 它们的按键送往自己的窗口, 到不了主窗口的这个拦截器. 弹层内容根挂
 * [Modifier.tvOverlayWindowKeys][me.him188.ani.app.ui.foundation.tvOverlayWindowKeys] 把事件
 * 转发给本跟踪器 (CompositionLocal 能跨窗口边界, Dialog 内容是父组合的子组合), 并在认领的
 * 那一刻顺手关掉自己 —— 长按的语义是"一步到位", 留着这层弹窗会挡在刚收干净的画面前面.
 * 短按不受影响 (跟踪器不消费), 弹窗自己的返回照旧只关一层.
 *
 * ## 与节点级 [tvLongPressKey] 的并存规则
 *
 * 判定参数同源 ([LONG_PRESS_KEY_DOWN_COUNT] + [LONG_PRESS_MIN_HOLD]), 残余免疫同一条
 * (只认从本窗口起手的手势, 首发 `repeatCount == 0`). 同一个键既有根部长按又有节点级
 * [tvLongPressKey] 时 (播放键): 根部拦截器先收到事件, 认领那一刻起节点再也看不到后续连发与
 * KeyUp —— 节点自己的长按永远到不了阈值, 短按 (阈值前松手) 则不受影响. 所以节点级只负责短按,
 * 长按语义一律交给根部注册.
 */
@Stable
open class TvKeyLongPressHost(private val keys: Set<Key>) {
    private class Entry(val handler: () -> Boolean)

    // 注册栈与跟踪器状态都只在主线程的组合效应/按键回调里读写, 不需要同步, 也刻意不用
    // snapshot 状态 —— 按键回调里写 mutableStateOf 会让每一发连发都触发一轮失效
    private val entries = ArrayList<Entry>()
    private var live = false // 手势从本窗口起手 (残余免疫的判据)
    private var fired = false // 本次手势已问过处理器 (无论有没有人认领, 不再问第二次)
    private var claimed = false // 已认领: 余下连发与 KeyUp 全部消费
    private var downCount = 0
    private var downMark: TimeMark? = null

    /** 注册一个长按处理器 (后注册的先问). 返回注销函数. */
    fun register(handler: () -> Boolean): () -> Unit {
        val entry = Entry(handler)
        entries.add(entry)
        return { entries.remove(entry) }
    }

    private fun fireLongPress(): Boolean {
        for (i in entries.indices.reversed()) {
            if (entries[i].handler()) return true
        }
        return false
    }

    /** 根部拦截器的事件入口. 返回 true = 消费本事件. */
    fun onRootKeyEvent(event: KeyEvent): Boolean {
        if (event.key !in keys) return false
        when (event.type) {
            KeyEventType.KeyDown -> {
                // 平台拿不到连发信息时退化为"第一发当新按下" (同 tvLongPressKey 的约定)
                val isRepeat = event.isAutoRepeat ?: live
                if (!isRepeat) {
                    live = true
                    fired = false
                    claimed = false
                    downCount = 1
                    downMark = TimeSource.Monotonic.markNow()
                    return false // 全新按下不消费: 短按语义 (各层的分层返回/直达播放) 不受影响
                }
                if (!live) return false // 残余连发: 手势不是从本窗口起手, 不数也不吞
                if (claimed) return true // 已认领: 吞掉余下连发
                if (fired) return false // 问过且没人认领: 整次手势保持普通短按语义
                downCount++
                val heldLongEnough = downMark?.let { it.elapsedNow() >= LONG_PRESS_MIN_HOLD } == true
                if (downCount >= LONG_PRESS_KEY_DOWN_COUNT && heldLongEnough) {
                    fired = true
                    claimed = fireLongPress()
                    return claimed
                }
                return false
            }

            KeyEventType.KeyUp -> {
                val consume = claimed
                live = false
                fired = false
                claimed = false
                downCount = 0
                downMark = null
                // 认领过的手势必须吞掉抬起: 放行的话这记 KeyUp 会再触发一次短按语义
                // (如 BackHandler 在抬起时派发), 把长按刚做完的事又搅一下
                return consume
            }

            else -> return false
        }
    }
}

/**
 * 返回键 (含 Escape) 的长按宿主. 在通用宿主之上多带一个跨页面的焦点交接标志:
 *
 * [pendingHomeFocus] —— 「回主界面」落地后的焦点交接: 置位者是发起回主页的那一方
 * (快捷菜单的「回到主界面」/主壳换 tab), 消费者是探索页 —— 它组合出来 (或本来就在) 时看到
 * 本标志, 就把焦点送上轮播主按钮并清零. 用标志而不是直接请求: 置位那一刻探索页可能还没组合.
 */
@Stable
class TvBackLongPressHost : TvKeyLongPressHost(TV_BACK_KEYS) {
    var pendingHomeFocus: Boolean by mutableStateOf(false)
}

/** 返回键. internal: 长按放大层 ([me.him188.ani.app.ui.foundation.tv.tvImageZoomKeys]) 也要认这两个键. */
internal val TV_BACK_KEYS = setOf(Key.Back, Key.Escape)

/**
 * 由应用根部 (TV 形态装配处) 提供; 其余形态为 null, [TvBackLongPressHandler] 与
 * [tvKeyLongPressInterceptor] 都退化为空操作.
 */
val LocalTvBackLongPressHost = staticCompositionLocalOf<TvBackLongPressHost?> { null }

/**
 * 播放键的长按宿主 (长按 = 回到正在播放). 处理器只有根部那一个, 下发它是为了让独立窗口的
 * 桥接 ([Modifier.tvOverlayWindowKeys][me.him188.ani.app.ui.foundation.tvOverlayWindowKeys])
 * 够得着.
 */
val LocalTvPlayLongPressHost = staticCompositionLocalOf<TvKeyLongPressHost?> { null }

/** 挂在应用根部的长按拦截器 (主窗口内 preview 阶段祖先先行, 该键集的所有按键先经过这里). */
fun Modifier.tvKeyLongPressInterceptor(host: TvKeyLongPressHost): Modifier =
    onPreviewKeyEvent { host.onRootKeyEvent(it) }

/**
 * 注册某个长按宿主的处理语义, 生命周期跟随组合 (离开组合自动注销). [host] 为 null 时空操作.
 *
 * [handler] 在长按阈值到达那一刻被问 (后注册的先问): 返回 true = 认领 (动作已做, 余下按键
 * 事件由跟踪器吞掉), false = 此处无事可做, 交给更外层的注册者.
 */
@Composable
fun TvKeyLongPressHandler(host: TvKeyLongPressHost?, handler: () -> Boolean) {
    if (host == null) return
    val currentHandler by rememberUpdatedState(handler)
    DisposableEffect(host) {
        val unregister = host.register { currentHandler() }
        onDispose { unregister() }
    }
}

/** [TvKeyLongPressHandler] 的返回键便捷版: 读 [LocalTvBackLongPressHost]. */
@Composable
fun TvBackLongPressHandler(handler: () -> Boolean) {
    TvKeyLongPressHandler(LocalTvBackLongPressHost.current, handler)
}
