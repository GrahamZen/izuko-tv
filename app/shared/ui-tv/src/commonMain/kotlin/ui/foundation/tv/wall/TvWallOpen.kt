/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.ui.foundation.tv.TvBackdropImage
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallOpenable

/**
 * 整屏模糊底的「点开」(同原生的 TvNativeWallFocus): 新番时间表的卡片墙、开着「hero 态用模糊背景」时探索 / 追番 / 搜索页的 hero 态,
 * 按确定不从背景图放大进详情页, 而是卡片与 hero 文字 [TV_WALL_OPEN_CARDS_MILLIS] 淡没 ([fade]), 模糊底上叠这一部的清晰原图
 * [TV_WALL_OPEN_SHARPEN_MILLIS] 变清晰 ([sharp], 两头慢中间快), 到位再进 —— 放大转场从这张整屏清晰图起 (全屏对全屏, 图原地不动);
 * 从详情页回来倒放. 清晰图 [TV_WALL_OPEN_WAIT_MILLIS] 内还没解好就不等, 背景留在模糊版直接进.
 *
 * 清晰层由 [TvWallSharpLayer] 画 (只在 [target] 不为 null 时组合). 回到本页时的倒放 / 点开途中按返回取消由页面接 TvNativeWallOpenEffects.
 */
@Stable
internal class TvWallOpen(private val scope: CoroutineScope) : TvNativeWallOpenable {
    /** 卡片与 hero 文字淡没的程度 (0..1), 页面在绘制里乘到它们的透明度上. */
    val fade = Animatable(0f)

    /** 清晰层的不透明度. */
    val sharp = Animatable(0f)

    /** 清晰层画哪张 (null = 不画). */
    var target: TvNativeWallBackdropTarget? by mutableStateOf(null)
        private set

    /** 点开途中 (还没进详情页). */
    var opening: Boolean by mutableStateOf(false)
        private set

    /** 倒放与恢复要不要过渡 (流畅档当场到位; 点开本身三档都走过渡, 同原生). */
    var transitions: Boolean = true

    /** 返回本页 (页面重建) 时恢复点开的样子要铺的那张. */
    var restoreTarget: () -> TvNativeWallBackdropTarget? = { null }

    /** 页面此刻被盖着 (不在前台), 页面给. */
    var pageHidden: () -> Boolean = { false }

    private var loaded = CompletableDeferred<Unit>()
    private var job: Job? = null

    /**
     * 点开: [target] 是此刻铺着的模糊底 (要是这张卡的、有清晰图). 能点开就开始, 到位后调 [onOpened] 进详情页并返回 true; 否则 false, 调用方当场进.
     */
    fun open(target: TvNativeWallBackdropTarget?, onOpened: () -> Unit): Boolean {
        if (opening || target == null || !target.sharp) return false
        this.target = target
        loaded = CompletableDeferred()
        opening = true
        job?.cancel()
        job = scope.launch {
            launch { fade.animateTo(1f, tween(TV_WALL_OPEN_CARDS_MILLIS, easing = FastOutSlowInEasing)) }
            val ready = withTimeoutOrNull(TV_WALL_OPEN_WAIT_MILLIS) { loaded.await() } != null
            if (ready) sharp.animateTo(1f, tween(TV_WALL_OPEN_SHARPEN_MILLIS, easing = TV_WALL_OPEN_SHARPEN_EASING))
            opening = false
            onOpened()
        }
        return true
    }

    /**
     * 当场进、不播点开 (触屏上点卡就进, 背景这时还是上一张的): 照点开过记账 —— 页面被盖住以后, 背景 ([restoreTarget]) 换成 [subjectId] 那张时
     * 把清晰层铺满、卡片藏起, 回到本页时照点开那样倒放 (清晰图淡回模糊底、卡片淡回来). 背景一直没换过来 (这部没有清晰图) 就照常回来.
     */
    fun openBehind(subjectId: Int) {
        job?.cancel()
        job = scope.launch {
            val t = snapshotFlow { restoreTarget()?.takeIf { pageHidden() && it.subjectId == subjectId && it.sharp } }.filterNotNull().first()
            target = t
            fade.snapTo(1f)
            sharp.snapTo(1f)
        }
    }

    /** 清晰层的图解好了. */
    fun onSharpLoaded() {
        loaded.complete(Unit)
    }

    override fun restoreWallOpen() {
        val t = restoreTarget() ?: return
        target = t
        scope.launch {
            fade.snapTo(1f)
            sharp.snapTo(1f)
        }
    }

    override fun endWallOpen() = release()

    override fun cancelWallOpen() {
        if (!opening) return
        job?.cancel()
        opening = false
        release()
    }

    /** 倒放回模糊底与卡片, 完了撤掉清晰层. */
    private fun release() {
        job?.cancel()
        job = scope.launch {
            if (transitions) {
                launch { sharp.animateTo(0f, tween(TV_WALL_OPEN_SHARPEN_MILLIS, easing = TV_WALL_OPEN_SHARPEN_EASING)) }
                fade.animateTo(0f, tween(TV_WALL_OPEN_CARDS_MILLIS, easing = FastOutSlowInEasing))
            } else {
                sharp.snapTo(0f)
                fade.snapTo(0f)
            }
            if (sharp.value <= 0f) target = null
        }
    }
}

/**
 * [TvWallOpen] 的清晰层: 整屏铺 [TvWallOpen.target] 的清晰原图 (与详情页同一个缓存键, 进详情页第一帧就有图), 透明度跟 [TvWallOpen.sharp] 走.
 * 登记成放大转场的来源 (全屏对全屏). 画在模糊底之上、卡片与 hero 之下.
 */
@Composable
internal fun TvWallSharpLayer(open: TvWallOpen, modifier: Modifier = Modifier) {
    val target = open.target ?: return
    Box(modifier.fillMaxSize().graphicsLayer { alpha = open.sharp.value }) {
        TvBackdropImage(target.url, target.subjectId, onLoaded = open::onSharpLoaded)
    }
}

/** 点开时卡片 (连同 hero 文字) 淡没、倒放时淡回的时长. */
private const val TV_WALL_OPEN_CARDS_MILLIS = 200

/** 背景变清晰 / 变回模糊的时长: 比卡片淡没长, 卡片先让开, 图再慢慢对上焦; 点开时它走完才进详情页. */
private const val TV_WALL_OPEN_SHARPEN_MILLIS = 400

/** 背景变清晰的缓动: 两头慢中间快 (标准缓动前段最快, 头几十毫秒就冒出大半张图, 看着像瞬间换了图). */
private val TV_WALL_OPEN_SHARPEN_EASING = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** 点开时清晰图还没解好, 最多等多久 (卡片照样先淡; 等不到就不对焦, 直接进详情页). */
private const val TV_WALL_OPEN_WAIT_MILLIS = 300L
