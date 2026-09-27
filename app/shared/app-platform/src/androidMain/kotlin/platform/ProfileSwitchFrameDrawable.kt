/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.max

/**
 * 换人过场的那一帧加上进度条的填充 (截下来的画面里已经有进度条的空槽, 见 [ProfileSwitchFrame.Frame.track]).
 * 选人页截好之后、中转页、新进程的落地页、主界面的窗口底与界面上的过场画的都是它, 几个进程之间画面与进度条都接得上.
 *
 * 进度 = 从开始换人起按时间慢慢逼近 [TIME_CAP] (换进程那几秒什么消息都没有, 条也在走) 与本阶段下限 ([floor]) 取大, 只增不减;
 * [finish] 之后在 [FINISH_MILLIS] 内走满. 挂在 View 上时自己逐帧重画.
 */
class ProfileSwitchFrameDrawable(
    val frame: ProfileSwitchFrame.Frame,
    floor: Float,
) : Drawable() {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }

    /** 本阶段的下限, 只升不降. */
    var floor: Float = floor
        set(value) {
            if (value > field) {
                field = value
                invalidateSelf()
            }
        }

    private var finishFrom = -1f
    private var finishStartUptime = 0L

    /** 首页准备好了: 从现在的进度走满. */
    fun finish() {
        if (finishFrom >= 0f) return
        finishFrom = progress()
        finishStartUptime = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    private val finished: Boolean
        get() = finishFrom >= 0f && SystemClock.uptimeMillis() - finishStartUptime >= FINISH_MILLIS

    /** 现在该画到哪 (0..1). */
    fun progress(): Float {
        val elapsed = (SystemClock.elapsedRealtime() - frame.startElapsedRealtime).coerceAtLeast(0L)
        val running = max(floor, TIME_CAP * (1f - exp(-elapsed.toFloat() / TIME_CONSTANT_MILLIS))).coerceAtMost(1f)
        if (finishFrom < 0f) return running
        val t = ((SystemClock.uptimeMillis() - finishStartUptime).toFloat() / FINISH_MILLIS).coerceIn(0f, 1f)
        return finishFrom + (1f - finishFrom) * t
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val bitmap = frame.bitmap
        canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
        val track = frame.track ?: return
        val scaleX = bounds.width().toFloat() / bitmap.width
        val scaleY = bounds.height().toFloat() / bitmap.height
        val left = bounds.left + track.left * scaleX
        val top = bounds.top + track.top * scaleY
        val right = bounds.left + track.right * scaleX
        val bottom = bounds.top + track.bottom * scaleY
        val radius = (bottom - top) / 2
        val progress = progress()
        if (progress > 0f) canvas.drawRoundRect(left, top, left + (right - left) * progress, bottom, radius, radius, fillPaint)
        if (!finished) invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        /** 各阶段的下限: 选人页截好 / 中转页 / 新进程的落地页 / 主界面刚起来. 主界面里之后按首屏封面的加载比例往上走 */
        const val FLOOR_CAPTURED = 0.05f
        const val FLOOR_RELAY = 0.15f
        const val FLOOR_LANDING = 0.35f
        const val FLOOR_MAIN = 0.45f

        /** 按时间最多走到这里, 剩下的等首页真的好了 */
        const val TIME_CAP = 0.9f

        /** 按时间走的快慢: 过了这么久走到 [TIME_CAP] 的六成多 */
        const val TIME_CONSTANT_MILLIS = 2500f

        const val FINISH_MILLIS = 200L

        /** 填充色 (槽的颜色在截下来的画面里, 由选人页画, 白色 20%) */
        const val FILL_COLOR = 0xDDFFFFFF.toInt()
    }
}
