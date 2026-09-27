/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.os.SystemClock
import android.view.animation.Interpolator
import androidx.leanback.widget.BaseGridView
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 原生页面的平滑滚动曲线: 与 Compose 版页面同一组 spring ([TvScrollSpring], 质量 1), 连按时接上一段的速度 (同 TvScrollAnimator: 同方向
 * 才接, 反方向从静止起).
 *
 * 所有焦点滚动最后都落到 `RecyclerView.smoothScrollBy(dx, dy, interpolator, duration)`: 位置 = 插值器(已过时间 / 时长) × 距离. 这里把
 * spring 的位移曲线 (初位移 = 这一段的距离, 初速度 = 上一段此刻的速度) 归一化成插值器, 时长取到剩余位移不足半像素、速度不足每帧半像素
 * 为止 —— 再往后每帧都不到一个像素, Compose 版在那之后也不再出新帧. 带着初速起步时可能越过目标一点再回来, 同 Compose 版.
 *
 * leanback 的 VerticalGridView / HorizontalGridView 经 [BaseGridView.SmoothScrollByBehavior] 取插值器与时长; 自己算距离的列表
 * (探索页的纵向列表) 用 [plan]. [animated] = false (视觉效果流畅档) 时时长为 0, RecyclerView 当场一步滚到位.
 *
 * [pace] 可以在发起一次滚动前换掉 (远跳用 [TvScrollSpring.Far]), 换回来由调用方负责.
 */
class TvNativeSpringScroll(var pace: TvScrollSpring = TvScrollSpring.Step) : BaseGridView.SmoothScrollByBehavior {
    var animated: Boolean = true

    private var segmentStart = 0L
    private var segmentMillis = 0
    private var segmentDistance = 0f
    private var segmentSpring = TvScrollSpring.Step
    private var segmentVelocity = 0f
    private var lastPlan: TvNativeScrollPlan? = null

    /** 一段滚动的插值器与时长 (ms). */
    class TvNativeScrollPlan(val interpolator: Interpolator, val durationMillis: Int)

    /** 从此刻起滚 [distancePx] (带符号) 的计划, 并记下这一段 (下一段起步时按它此刻的速度接). */
    fun plan(distancePx: Float): TvNativeScrollPlan {
        val now = SystemClock.uptimeMillis()
        if (!animated || abs(distancePx) < 0.5f) {
            segmentMillis = 0
            return TvNativeScrollPlan(TV_NATIVE_LINEAR, 0).also { lastPlan = it }
        }
        // 上一段还在跑: 取它此刻的速度 (px/s), 同方向才带进这一段
        var v0 = 0f
        val elapsed = now - segmentStart
        if (segmentMillis > 0 && elapsed in 0 until segmentMillis) {
            val v = segmentDistance * tvSpringVelocity(segmentSpring, elapsed / 1000f, segmentVelocity / segmentDistance)
            if (v.sign == distancePx.sign) v0 = v
        }
        val spring = pace
        val v0n = v0 / distancePx
        val millis = tvSpringDurationMillis(spring, abs(distancePx), v0n)
        segmentStart = now
        segmentMillis = millis
        segmentDistance = distancePx
        segmentSpring = spring
        segmentVelocity = v0
        return TvNativeScrollPlan(tvSpringInterpolator(spring, millis, v0n), millis).also { lastPlan = it }
    }

    /** 滚动被别处打断 (瞬时定位、手动停下): 下一段从静止起. */
    fun reset() {
        segmentMillis = 0
    }

    override fun configSmoothScrollByInterpolator(dx: Int, dy: Int): Interpolator =
        plan(if (abs(dx) >= abs(dy)) dx.toFloat() else dy.toFloat()).interpolator

    override fun configSmoothScrollByDuration(dx: Int, dy: Int): Int {
        // leanback 先取插值器再取时长, 两次是同一段
        return lastPlan?.durationMillis ?: plan(if (abs(dx) >= abs(dy)) dx.toFloat() else dy.toFloat()).durationMillis
    }
}

/**
 * [spring] 的归一化位移: 初位移 −1 (离目标一整段)、初速度 [v0n] (单位: 段长 / 秒) 时 [seconds] 秒后离目标还差多少 (0 = 到了).
 * 只实现临界阻尼与过阻尼 —— 两条 [TvScrollSpring] 都不欠阻尼.
 */
internal fun tvSpringDisplacement(spring: TvScrollSpring, seconds: Float, v0n: Float = 0f): Float {
    val omega = sqrt(spring.stiffness)
    val zeta = spring.dampingRatio.coerceAtLeast(1f)
    val t = seconds.coerceAtLeast(0f)
    return if (zeta - 1f < 1e-4f) {
        // x(t) = (c1 + c2·t)·e^(−ωt), x(0) = c1 = −1, x'(0) = c2 − ω·c1 = v0n
        val c2 = v0n - omega
        (-1f + c2 * t) * exp(-omega * t)
    } else {
        // x(t) = A·e^(r1·t) + B·e^(r2·t), r1 / r2 为两个负实根
        val s = sqrt(zeta * zeta - 1f)
        val r1 = -omega * (zeta - s)
        val r2 = -omega * (zeta + s)
        val a = (v0n + r2) / (r1 - r2)
        val b = -1f - a
        a * exp(r1 * t) + b * exp(r2 * t)
    }
}

/** [tvSpringDisplacement] 的速度 (段长 / 秒). */
internal fun tvSpringVelocity(spring: TvScrollSpring, seconds: Float, v0n: Float = 0f): Float {
    val omega = sqrt(spring.stiffness)
    val zeta = spring.dampingRatio.coerceAtLeast(1f)
    val t = seconds.coerceAtLeast(0f)
    return if (zeta - 1f < 1e-4f) {
        val c2 = v0n - omega
        (c2 - omega * (-1f + c2 * t)) * exp(-omega * t)
    } else {
        val s = sqrt(zeta * zeta - 1f)
        val r1 = -omega * (zeta - s)
        val r2 = -omega * (zeta + s)
        val a = (v0n + r2) / (r1 - r2)
        val b = -1f - a
        a * r1 * exp(r1 * t) + b * r2 * exp(r2 * t)
    }
}

/** 从静止出发走完单位位移时 [seconds] 秒后的进度 (0..1). */
fun tvSpringProgress(spring: TvScrollSpring, seconds: Float): Float = 1f + tvSpringDisplacement(spring, seconds)

/**
 * 走 [distancePx] 像素 (初速度 [v0n], 段长 / 秒) 时, 剩余位移不足半像素、速度不足每帧半像素所需的时长 (ms), 至少一帧, 最多 2 秒.
 */
fun tvSpringDurationMillis(spring: TvScrollSpring, distancePx: Float, v0n: Float = 0f): Int {
    if (distancePx <= 1f) return TV_SPRING_MIN_MILLIS
    var ms = TV_SPRING_MIN_MILLIS
    while (ms < TV_SPRING_MAX_MILLIS) {
        val t = ms / 1000f
        val remaining = abs(tvSpringDisplacement(spring, t, v0n)) * distancePx
        val perFrame = abs(tvSpringVelocity(spring, t, v0n)) * distancePx * TV_SPRING_FRAME_SECONDS
        if (remaining < 0.5f && perFrame < 0.5f) break
        ms += 4
    }
    return ms
}

/** 走 [distancePx] 像素的时长 (整数像素版, 从静止起). */
fun tvSpringDurationMillis(spring: TvScrollSpring, distancePx: Int): Int = tvSpringDurationMillis(spring, max(distancePx, 0).toFloat())

/** [spring] 在 [durationMillis] 内走完的插值器 (初速度 [v0n]; 末端归一到 1, 途中可以略越过 1). */
fun tvSpringInterpolator(spring: TvScrollSpring, durationMillis: Int, v0n: Float = 0f): Interpolator {
    val seconds = durationMillis / 1000f
    val end = 1f + tvSpringDisplacement(spring, seconds, v0n)
    return Interpolator { u ->
        if (u >= 1f) 1f else (1f + tvSpringDisplacement(spring, u * seconds, v0n)) / end
    }
}

private val TV_NATIVE_LINEAR = Interpolator { it }

private const val TV_SPRING_MIN_MILLIS = 16
private const val TV_SPRING_MAX_MILLIS = 2000
private const val TV_SPRING_FRAME_SECONDS = 1f / 60f
