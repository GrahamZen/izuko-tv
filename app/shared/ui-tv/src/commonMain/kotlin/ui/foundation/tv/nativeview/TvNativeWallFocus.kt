/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.KeyEvent
import android.view.View

/**
 * 整屏背景 ([TvNativeWallBackdropView]) 的对焦: 新番时间表的卡片墙 ([TvNativeGridPageView]), 以及开了「hero 态用模糊背景」时探索页
 * ([TvNativeExploreView]) 与追番 / 搜索页 (网格页) 的 hero 态共用这一套.
 *
 * 长按卡片 ([peek]): 别的卡淡没、被长按那张留着, 背景变清晰 (收藏菜单弹在旁边), 菜单关了倒放 ([endPeek]).
 * 点开 ([open]): 卡片全部淡没 (连同 chrome: 顶栏、hero 图、hero 文字里标题以外的几行, 见 [Host.applyFade])、背景变清晰, 到位后才进详情页
 * ([Host.onOpened]) —— 放大转场从这张整屏清晰图起 (全屏对全屏, 图原地不动); 从详情页回来倒放 ([endOpen]). 卡片淡没
 * [TV_WALL_BACKDROP_CARDS_MILLIS], 背景变清晰更慢 ([TV_WALL_BACKDROP_SHARPEN_MILLIS], 两头慢的缓动): 卡片先让开, 图再慢慢对上焦;
 * 点开时背景走完才进.
 *
 * 点开时背景还不是这张卡的 (停下来之前就按了) / 没有清晰图 (竖版封面、打码) 就不对焦 ([open] 返回 false, 调用方当场进). 清晰图还没解好:
 * 卡片照样当场开始淡 (按下的反馈), 背景等它最多 [TV_WALL_BACKDROP_OPEN_WAIT_MILLIS], 等不到就不对焦直接进 (状态照旧停在点开, 回来倒放).
 * 点开 (卡片淡没、背景对焦) 三档都走过渡: 流畅档当场到位的话这些活挤进同一帧, 反而比摊开掉得多; 长按窥视与倒放流畅档 ([Host.transitions] = false)
 * 当场到位 (倒放整屏清晰图叠着模糊图淡出, 不便宜). 两段过渡都按帧推进 ([TvNativeFrameTween]): 长按时收藏菜单那个新窗口一出来主线程会卡
 * 一下, 按墙钟算进度的话背景直接跳到清晰.
 *
 * @param view 挂延时回调用的视图 (页面视图自己)
 */
internal class TvNativeWallFocus(private val view: View, private val host: Host) {
    /** 页面视图实现. */
    interface Host {
        /** 整屏背景; null = 此刻没有 (没开 / 还没建). */
        val wall: TvNativeWallBackdropView?

        /** 视觉效果的过渡: 长按窥视与倒放要不要走过渡 (流畅档为 false: 当场到位; 点开三档都走). */
        val transitions: Boolean

        /** 卡片淡没的程度 [fade] (0..1), 留着不淡的那张 [keep] (-1 = 全部淡); [chrome] = 这次是点开 (顶栏、hero 图等跟着淡, 倒放完才撤). */
        fun applyFade(fade: Float, keep: Int, chrome: Boolean)

        /** 点开途中 (对焦还没到位, 还没进详情页): 这期间返回键取消点开 ([cancelOpen]). */
        fun onOpeningChanged(opening: Boolean)

        /** 对焦到位 (或清晰图等不到), 这就进第 [index] 张的详情页. 状态停在点开 (卡片淡没、背景清晰), 回来时页面调 [endOpen] 倒放. */
        fun onOpened(index: Int)
    }

    private enum class State { None, Peek, Open }

    private var state = State.None

    /** 对焦时留着不淡的那张 (长按的那张); -1 = 全部淡没. */
    var keep = -1
        private set

    /** 点开途中 (对焦还没到位, 还没进详情页) 的那张; -1 = 没有. */
    private var openingIndex = -1
    private var openStarted = false
    private var openWait: Runnable? = null

    /** 这一次对焦是点开 (chrome 跟着卡片淡, 倒放完才撤). */
    private var chrome = false

    /** 卡片此刻的淡没程度 (0..1). */
    var fade = 0f
        private set
    private var fadeTween: TvNativeFrameTween? = null
    private var sharpGoal = 0f
    private var sharpEnd: (() -> Unit)? = null

    /** 这一段对焦 / 倒放要不要过渡 (见 [animateSharp]). */
    private var sharpAnimated = true
    private var sharpTween: TvNativeFrameTween? = null

    /** 点开途中. */
    val opening: Boolean get() = openingIndex >= 0

    /** 停在点开的样子 (点开途中或已进详情页、还没倒放). */
    val isOpen: Boolean get() = state == State.Open

    /** 没在对焦 (长按 / 点开都没有). */
    val isIdle: Boolean get() = state == State.None

    /**
     * 点开第 [index] 张: [matches] = 背景此刻是这张卡的、能变清晰. 能对焦就开始对焦, 到位后再进 (返回 true); 否则 false, 调用方当场进.
     */
    fun open(index: Int, matches: Boolean): Boolean {
        val wall = host.wall ?: return false
        if (state != State.None || !matches) return false
        state = State.Open
        keep = -1
        chrome = true
        openingIndex = index
        openStarted = false
        host.onOpeningChanged(true)
        // 卡片与 chrome 当场开始淡 (按下的反馈), 背景等清晰图就位再对焦
        animateFade(1f, animated = true)
        wall.prepareSharp()
        if (openStarted) return true
        if (wall.sharpReady) {
            startOpen()
        } else {
            val wait = Runnable { if (openingIndex >= 0 && !openStarted) finishOpen() }
            openWait = wait
            view.postDelayed(wait, TV_WALL_BACKDROP_OPEN_WAIT_MILLIS)
        }
        return true
    }

    private fun startOpen() {
        openStarted = true
        cancelWait()
        animateSharp(1f, animated = true) { finishOpen() }
    }

    /** 对焦到位 / 清晰图等不到 (卡片已经淡了, 背景留在模糊版): 进详情页. 状态照旧停在点开, 回来时页面调 [endOpen] 倒放. */
    private fun finishOpen() {
        val index = openingIndex
        if (index < 0) return
        openingIndex = -1
        cancelWait()
        host.onOpeningChanged(false)
        host.onOpened(index)
    }

    private fun cancelWait() {
        openWait?.let { view.removeCallbacks(it) }
        openWait = null
    }

    /** 点开途中按了返回: 不进了, 倒放. */
    fun cancelOpen() {
        if (openingIndex < 0) return
        openingIndex = -1
        cancelWait()
        host.onOpeningChanged(false)
        release()
    }

    /** 从点开进去的详情页回来了 (页面在本页回到前台、缩回层撤掉之后调): 倒放回模糊与卡片. */
    fun endOpen() {
        if (state == State.Open && openingIndex < 0) release()
    }

    /** 恢复点开的状态 (返回时页面重建): 卡片淡没、背景清晰 (清晰图解好就直接出现), 等页面调 [endOpen]. */
    fun restoreOpen() {
        val wall = host.wall ?: return
        if (state != State.None) return
        state = State.Open
        keep = -1
        chrome = true
        fadeTween?.cancel()
        setFade(1f)
        sharpTween?.cancel()
        sharpGoal = 1f
        sharpEnd = null
        wall.sharpness = 1f
        wall.prepareSharp()
    }

    /** 长按第 [index] 张: 别的卡淡没, [matches] (背景是这张卡的) 时背景变清晰. */
    fun peek(index: Int, matches: Boolean) {
        val wall = host.wall ?: return
        if (state != State.None) return
        state = State.Peek
        keep = index
        animateFade(1f)
        if (matches) {
            wall.prepareSharp()
            animateSharp(1f)
        }
    }

    /** 长按的菜单关了 (或没弹出来): 倒放. */
    fun endPeek() {
        if (state == State.Peek) release()
    }

    /**
     * 背景刚换过目标 (页面调 [TvNativeWallBackdropView.show] 之后): 对焦期间要清晰图就当场解 (恢复点开的状态时, 目标在视图建好之后才到);
     * 长按时背景还没换到那张卡, 换到了 ([keepMatches]) 就接着变清晰.
     */
    fun onTargetChanged(keepMatches: Boolean) {
        val wall = host.wall ?: return
        if (state == State.None) return
        wall.prepareSharp()
        if (state == State.Peek && sharpGoal == 0f && keepMatches) animateSharp(1f)
    }

    /** 清晰图解好了 (接 [TvNativeWallBackdropView.onSharpReady]). */
    fun onSharpReady() {
        val wall = host.wall ?: return
        if (openingIndex >= 0 && !openStarted) {
            startOpen()
        } else if (wall.sharpness != sharpGoal && sharpTween?.running != true) {
            runSharpAnimation(wall)
        }
    }

    /**
     * 按键先过这里: 点开途中 (对焦还没到位) 方向键与确认键吞掉 (返回键交给页面, 见 [cancelOpen]), 返回 true. 按下确认键时当场解清晰图
     * (点开 / 长按都要它), 不等停留: 按住到抬起 / 长按阈值的这段正好用来解.
     */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (openingIndex >= 0 && (direction || tvNativeIsConfirmKey(code))) return true
        if (tvNativeIsConfirmKey(code) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) host.wall?.prepareSharp()
        return false
    }

    /** 页面视图离开窗口: 延时回调与两段过渡停掉 (状态不动). */
    fun detach() {
        cancelWait()
        fadeTween?.cancel()
        sharpTween?.cancel()
    }

    /** 整屏背景撤掉了 (关了开关): 状态清空, 卡片与 chrome 当场回来. */
    fun reset() {
        cancelWait()
        if (openingIndex >= 0) {
            openingIndex = -1
            host.onOpeningChanged(false)
        }
        fadeTween?.cancel()
        sharpTween?.cancel()
        sharpGoal = 0f
        sharpEnd = null
        state = State.None
        keep = -1
        setFade(0f)
        chrome = false
        host.applyFade(0f, -1, false)
    }

    private fun release() {
        state = State.None
        animateFade(0f) {
            keep = -1
            chrome = false
            host.applyFade(fade, keep, chrome)
        }
        animateSharp(0f)
    }

    private fun setFade(value: Float) {
        fade = value
        host.applyFade(value, keep, chrome)
    }

    private fun animateFade(to: Float, animated: Boolean = host.transitions, onEnd: (() -> Unit)? = null) {
        fadeTween?.cancel()
        if (!animated || fade == to) {
            setFade(to)
            onEnd?.invoke()
            return
        }
        val from = fade
        fadeTween = TvNativeFrameTween(
            TV_WALL_BACKDROP_CARDS_MILLIS, TV_NATIVE_FAST_OUT_SLOW_IN,
            onUpdate = { f -> setFade(from + (to - from) * f) },
            onEnd = onEnd,
        ).also { it.start() }
    }

    /** 背景清晰层的透明度走到 [to]. 清晰图还没解好就先记下, 解好时 ([onSharpReady]) 接着走. */
    private fun animateSharp(to: Float, animated: Boolean = host.transitions, onEnd: (() -> Unit)? = null) {
        val wall = host.wall ?: return
        sharpGoal = to
        sharpEnd = onEnd
        sharpAnimated = animated
        sharpTween?.cancel()
        if (!wall.sharpReady) {
            // 显示不出来: 从 0 起 (解好时从 0 走过去)
            wall.sharpness = 0f
            return
        }
        runSharpAnimation(wall)
    }

    private fun runSharpAnimation(wall: TvNativeWallBackdropView) {
        val to = sharpGoal
        val onEnd = sharpEnd
        sharpEnd = null
        if (!sharpAnimated || wall.sharpness == to) {
            wall.sharpness = to
            onEnd?.invoke()
            return
        }
        val from = wall.sharpness
        sharpTween = TvNativeFrameTween(
            TV_WALL_BACKDROP_SHARPEN_MILLIS, TV_WALL_BACKDROP_SHARPEN_EASING,
            onUpdate = { f -> wall.sharpness = from + (to - from) * f },
            onEnd = onEnd,
        ).also { it.start() }
    }
}
