/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.View
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import me.him188.ani.app.ui.foundation.lan.QrCodeMatrix
import me.him188.ani.app.ui.foundation.lan.encodeQrCode
import kotlin.math.floor
import kotlin.math.min

/** 二维码的底与模块 (ARGB), 见 [tvNativeQrColors]. */
@Immutable
internal data class TvNativeQrColors(val bg: Int, val fg: Int)

/**
 * 二维码的配色: 浅底深码 (同 Web 控制台的码, 老扫码器也认) —— 深色主题的 primary 本身是浅色, 当底、onPrimary 画码;
 * 浅色主题换 primaryContainer 与 onPrimaryContainer.
 */
internal fun tvNativeQrColors(colors: ColorScheme): TvNativeQrColors {
    val dark = colors.surface.luminance() < 0.5f
    return TvNativeQrColors(
        bg = (if (dark) colors.primary else colors.primaryContainer).toArgb(),
        fg = (if (dark) colors.onPrimary else colors.onPrimaryContainer).toArgb(),
    )
}

/** 二维码四周留白的模块数 (规范至少 4, 扫码器靠它定位), 按模块数留白时用 (见 [TvNativeQrView.setLook]). */
internal const val TV_NATIVE_QR_QUIET_MODULES = 4

/**
 * 二维码: 圆角底上画模块, 码在格子里居中; 模块边长取整像素 (相邻模块之间不出灰缝). 同 Compose 的 QrCodeImage. 画在视图里最大的正方形里.
 * 四周留白两种给法 (见 [setLook]): 定的像素 (码的边长由排法定死, 模块按留白之内的边长分), 或模块数 (模块按整个边长分).
 * 没有内容时给了空框的颜色就沿边画一圈细框 (框里写什么由外面摆), 没给什么都不画.
 */
internal class TvNativeQrView(context: Context) : View(context) {
    private var content: String? = null
    private var matrix: QrCodeMatrix? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0
    }
    private val modulePaint = Paint()
    private val rect = RectF()
    private var cornerPx = 0f
    private var quietPx = 0
    private var quietModules = 0

    /** 此刻画的内容 (测试用). */
    val shownContent: String? get() = content

    /** 码里的内容; null = 没有码. */
    fun setContent(content: String?) {
        if (content == this.content) return
        this.content = content
        matrix = content?.let { text -> matrices[text] ?: encodeQrCode(text)?.also { matrices.put(text, it) } }
        invalidate()
    }

    /**
     * 底色 [bg]、模块色 [fg] 与圆角. 四周留白: [quietModules] 大于 0 时留这么多个模块宽, 否则留 [quietPx] 像素.
     * [placeholderBorder] 是没有内容时那圈细框 (1dp) 的颜色, 0 = 不画.
     */
    fun setLook(bg: Int, fg: Int, cornerPx: Float, quietPx: Int = 0, quietModules: Int = 0, placeholderBorder: Int = 0) {
        bgPaint.color = bg
        modulePaint.color = fg
        borderPaint.color = placeholderBorder
        borderPaint.strokeWidth = resources.displayMetrics.density
        this.cornerPx = cornerPx
        this.quietPx = quietPx
        this.quietModules = quietModules
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height)
        if (content == null) {
            if (Color.alpha(borderPaint.color) == 0) return
            val half = borderPaint.strokeWidth / 2
            rect.set(half, half, side - half, side - half)
            canvas.drawRoundRect(rect, cornerPx, cornerPx, borderPaint)
            return
        }
        val m = matrix ?: return
        rect.set(0f, 0f, side.toFloat(), side.toFloat())
        canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
        val cell: Float
        val offset: Float
        if (quietModules > 0) {
            val modules = side / (m.size + quietModules * 2)
            cell = modules.toFloat()
            offset = (side - modules * m.size) / 2f
        } else {
            val inner = side.toFloat() - quietPx * 2
            cell = floor(inner / m.size)
            offset = quietPx + (inner - cell * m.size) / 2f
        }
        if (cell <= 0f) return
        for (y in 0 until m.size) {
            for (x in 0 until m.size) {
                if (!m[x, y]) continue
                val left = offset + x * cell
                val top = offset + y * cell
                canvas.drawRect(left, top, left + cell, top + cell, modulePaint)
            }
        }
    }

    private companion object {
        /** 编过的码 (内容 → 模块): 每次打开弹窗、换一行都要画的那几个码 (控制台的码、外部链接) 不重新编. 只在主线程上用. */
        val matrices = LruCache<String, QrCodeMatrix>(8)
    }
}
