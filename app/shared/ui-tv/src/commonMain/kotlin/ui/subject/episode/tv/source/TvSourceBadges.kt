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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.him188.ani.app.domain.media.MediaAvFeatures
import me.him188.ani.app.domain.media.MediaDynamicRange
import me.him188.ani.app.domain.media.MediaSurroundAudio
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeTypeface
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_badge_dolby_atmos
import me.him188.ani.app.ui.lang.tv_source_badge_dolby_vision
import me.him188.ani.app.ui.lang.tv_source_badge_dolby_vision_unsupported
import me.him188.ani.datasources.api.Media
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

/**
 * 资源条目上的特性标签 (见 [tvSourceBadgesOf]): 画面的动态范围 ([picture], 画成金色实底, 一眼看得出是 HDR 片源) 与多声道 / 无损音轨 (描边).
 * 条目本身的底色不变 —— 底色只表示焦点与选中 (见 TvSourceRowView), 特性靠标签. [warning] 的标签用错误色描边, 详情里写一句原因.
 */
enum class TvSourceBadge(val picture: Boolean, val warning: Boolean = false) {
    DolbyVision(true),

    /** 杜比视界 profile 5, 而这台设备没有杜比视界解码器: 放出来颜色不对 (偏紫偏绿). */
    DolbyVisionUnsupported(false, warning = true),
    Hdr10Plus(true),
    Hdr10(true),
    Hlg(true),
    Hdr(true),
    DolbyAtmos(false),
    DtsX(false),
    TrueHd(false),
    DtsHd(false),
}

/** [media] 的特性标签: 画面、声音各至多一个 (取最好的那种), 来自标题与网盘读到的 (见 [MediaAvFeatures.of]). */
internal fun tvSourceBadgesOf(media: Media): List<TvSourceBadge> = tvSourceBadgesOf(MediaAvFeatures.of(media))

/**
 * @param decodesDolbyVision 这台设备有没有杜比视界解码器. 没有时 profile 5 (没有 HDR10 底层) 标成 [TvSourceBadge.DolbyVisionUnsupported];
 * profile 8 能当 HDR10 放, 不标. 标题里看不出 profile, 照常标.
 */
internal fun tvSourceBadgesOf(
    features: MediaAvFeatures,
    decodesDolbyVision: Boolean = deviceDecodesDolbyVision,
): List<TvSourceBadge> = listOfNotNull(
    when (features.dynamicRange) {
        MediaDynamicRange.DolbyVision ->
            if (features.dolbyVisionProfile == 5 && !decodesDolbyVision) TvSourceBadge.DolbyVisionUnsupported else TvSourceBadge.DolbyVision
        MediaDynamicRange.Hdr10Plus -> TvSourceBadge.Hdr10Plus
        MediaDynamicRange.Hdr10 -> TvSourceBadge.Hdr10
        MediaDynamicRange.Hlg -> TvSourceBadge.Hlg
        MediaDynamicRange.Hdr -> TvSourceBadge.Hdr
        null -> null
    },
    when (features.audio) {
        MediaSurroundAudio.DolbyAtmos -> TvSourceBadge.DolbyAtmos
        MediaSurroundAudio.DtsX -> TvSourceBadge.DtsX
        MediaSurroundAudio.TrueHd -> TvSourceBadge.TrueHd
        MediaSurroundAudio.DtsHd -> TvSourceBadge.DtsHd
        null -> null
    },
)

/**
 * 胶囊 / 源那一栏右端的标签: [media] 里最好的画面特性 (组里有一条是杜比视界就标杜比视界), 不进去也看得出有没有 HDR 片源.
 * 只标画面一个 (地方小), 也不标「不支持」(组里各条的 profile 不一样, 进去看条目).
 */
internal fun tvSourceBestPictureBadges(media: List<Media>): List<TvSourceBadge> {
    val best = media.mapNotNull { MediaAvFeatures.of(it).dynamicRange }.minOrNull() ?: return emptyList()
    return tvSourceBadgesOf(MediaAvFeatures(dynamicRange = best), decodesDolbyVision = true)
}

/** 这台设备有没有杜比视界的视频解码器 (查一次). */
private val deviceDecodesDolbyVision: Boolean by lazy {
    runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) }
        }
    }.getOrDefault(true)
}

/**
 * 标签的样子 (见 [TvSourceBadgeView]). 画面类金色实底深色字 (聚焦的白底上也看得清); 其余描边, 颜色跟着行的次要字色.
 *
 * @property metaGapPx 标签与右边的信息 (分辨率、大小…) 之间
 * @property labels 各标签的字 (杜比的两个随界面语言, 其余是通用写法)
 */
@Immutable
data class TvSourceBadgeStyle(
    val heightPx: Int,
    val paddingHPx: Int,
    val gapPx: Int,
    val metaGapPx: Int,
    val cornerPx: Float,
    val strokePx: Float,
    val textSizePx: Float,
    val letterSpacingEm: Float,
    val pictureTop: Int,
    val pictureBottom: Int,
    val pictureText: Int,
    val labels: Map<TvSourceBadge, String>,
)

@Composable
internal fun rememberTvSourceBadgeStyle(): TvSourceBadgeStyle {
    val density = LocalDensity.current
    val labels = tvSourceBadgeLabels()
    return remember(density, labels) {
        with(density) {
            TvSourceBadgeStyle(
                heightPx = 16.dp.roundToPx(),
                paddingHPx = 5.dp.roundToPx(),
                gapPx = 4.dp.roundToPx(),
                metaGapPx = 8.dp.roundToPx(),
                cornerPx = 3.dp.toPx(),
                strokePx = 1.dp.toPx(),
                textSizePx = 10.sp.toPx(),
                letterSpacingEm = 0.02f,
                pictureTop = TV_SOURCE_BADGE_PICTURE_TOP.toArgb(),
                pictureBottom = TV_SOURCE_BADGE_PICTURE_BOTTOM.toArgb(),
                pictureText = TV_SOURCE_BADGE_PICTURE_TEXT.toArgb(),
                labels = labels,
            )
        }
    }
}

@Composable
private fun tvSourceBadgeLabels(): Map<TvSourceBadge, String> {
    val dolbyVision = stringResource(Lang.tv_source_badge_dolby_vision)
    val dolbyAtmos = stringResource(Lang.tv_source_badge_dolby_atmos)
    return remember(dolbyVision, dolbyAtmos) {
        TvSourceBadge.entries.associateWith { badge ->
            when (badge) {
                TvSourceBadge.DolbyVision, TvSourceBadge.DolbyVisionUnsupported -> dolbyVision
                TvSourceBadge.Hdr10Plus -> "HDR10+"
                TvSourceBadge.Hdr10 -> "HDR10"
                TvSourceBadge.Hlg -> "HLG"
                TvSourceBadge.Hdr -> "HDR"
                TvSourceBadge.DolbyAtmos -> dolbyAtmos
                TvSourceBadge.DtsX -> "DTS:X"
                TvSourceBadge.TrueHd -> "TrueHD"
                TvSourceBadge.DtsHd -> "DTS-HD"
            }
        }
    }
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

/** 详情弹窗里的同一排标签 (Compose); 有 [TvSourceBadge.warning] 的标签时下面写一句原因. */
@Composable
internal fun TvSourceBadgeChips(badges: List<TvSourceBadge>, modifier: Modifier = Modifier) {
    val labels = tvSourceBadgeLabels()
    val outline = LocalContentColor.current.copy(alpha = 0.7f)
    val error = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(4.dp)
    val textStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (badge in badges) {
                val color = if (badge.warning) error else outline
                val chip = if (badge.picture) {
                    Modifier.background(Brush.verticalGradient(listOf(TV_SOURCE_BADGE_PICTURE_TOP, TV_SOURCE_BADGE_PICTURE_BOTTOM)), shape)
                } else {
                    Modifier.border(1.dp, color, shape)
                }
                Text(
                    labels[badge].orEmpty(),
                    chip.padding(horizontal = 7.dp, vertical = 2.dp),
                    color = if (badge.picture) TV_SOURCE_BADGE_PICTURE_TEXT else color,
                    style = textStyle,
                    maxLines = 1,
                )
            }
        }
        if (TvSourceBadge.DolbyVisionUnsupported in badges) {
            Text(stringResource(Lang.tv_source_badge_dolby_vision_unsupported), style = MaterialTheme.typography.bodyMedium, color = error)
        }
    }
}

/** 画面类标签的金色 (上浅下深) 与上面的字. */
private val TV_SOURCE_BADGE_PICTURE_TOP = Color(0xFFF4DFA8)
private val TV_SOURCE_BADGE_PICTURE_BOTTOM = Color(0xFFD3A95A)
private val TV_SOURCE_BADGE_PICTURE_TEXT = Color(0xFF2B1D05)
