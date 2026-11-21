/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.View
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeTypeface
import kotlin.math.ceil

/** 这台设备有没有杜比视界的视频解码器 (查一次). */
internal actual val deviceDecodesDolbyVision: Boolean by lazy {
    runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) }
        }
    }.getOrDefault(true)
}

/** 一排标签 (原生, 条目行里用, 见 TvSourceRowView): 宽度按字量, 高 [TvSourceBadgeStyle.heightPx]. */
internal class TvSourceBadgeView(context: Context) : View(context) {
    private var style: TvSourceBadgeStyle? = null
    private var badges: List<TvSourceBadge> = emptyList()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val rect = RectF()
    private var gradientFor: Pair<TvSourceBadgeStyle, Int>? = null

    /** 描边标签的颜色: 行的次要字色 (聚焦时跟着变深). */
    var outlineColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** [TvSourceBadge.warning] 的标签的颜色: 行的错误色. */
    var warningColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    val isEmpty: Boolean get() = badges.isEmpty()

    fun bind(badges: List<TvSourceBadge>, style: TvSourceBadgeStyle) {
        if (this.badges == badges && this.style == style) return
        this.badges = badges
        this.style = style
        tvSourceBadgePaint(style, textPaint)
        strokePaint.strokeWidth = style.strokePx
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val style = style
        if (style == null || badges.isEmpty()) {
            setMeasuredDimension(0, 0)
            return
        }
        setMeasuredDimension(tvSourceBadgeStripWidth(badges, style, textPaint), style.heightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val style = style ?: return
        val h = height.toFloat()
        if (gradientFor != (style to height)) {
            fillPaint.shader = LinearGradient(0f, 0f, 0f, h, style.pictureTop, style.pictureBottom, Shader.TileMode.CLAMP)
            gradientFor = style to height
        }
        val metrics = textPaint.fontMetrics
        val baseline = (h - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        var x = 0f
        for (badge in badges) {
            val label = style.labels[badge].orEmpty()
            val w = badgeWidth(label, style, textPaint).toFloat()
            rect.set(x, 0f, x + w, h)
            if (badge.picture) {
                canvas.drawRoundRect(rect, style.cornerPx, style.cornerPx, fillPaint)
                textPaint.color = style.pictureText
            } else {
                val color = if (badge.warning) warningColor else outlineColor
                val inset = style.strokePx / 2f
                rect.inset(inset, inset)
                strokePaint.color = color
                canvas.drawRoundRect(rect, style.cornerPx - inset, style.cornerPx - inset, strokePaint)
                textPaint.color = color
            }
            canvas.drawText(label, x + style.paddingHPx, baseline, textPaint)
            x += w + style.gapPx
        }
    }
}

/** 一排标签多宽 (行与「不占满宽度的那一屏」量宽度都用这个). */
internal fun tvSourceBadgeStripWidth(badges: List<TvSourceBadge>, style: TvSourceBadgeStyle, paint: Paint): Int {
    if (badges.isEmpty()) return 0
    return badges.sumOf { badgeWidth(style.labels[it].orEmpty(), style, paint) } + style.gapPx * (badges.size - 1)
}

/** 把 [paint] 设成标签的字. */
internal fun tvSourceBadgePaint(style: TvSourceBadgeStyle, paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)): Paint = paint.apply {
    textSize = style.textSizePx
    letterSpacing = style.letterSpacingEm
    typeface = tvNativeTypeface(700)
}

private fun badgeWidth(label: String, style: TvSourceBadgeStyle, paint: Paint): Int =
    ceil(paint.measureText(label)).toInt() + style.paddingHPx * 2
