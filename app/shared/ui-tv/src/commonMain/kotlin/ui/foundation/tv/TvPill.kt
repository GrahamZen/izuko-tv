/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * **播放器胶囊按钮的外壳** (Prime Video 样式): 圆头、未聚焦白 14%、聚焦反色成白底黑字.
 *
 * 播放器控制层那一行按钮 (选集/一起看/弹幕/评论/相关推荐)、OP/ED 跳过按钮、弹幕输入胶囊
 * 原本各自写了一遍同一个 `Surface` + `Row` —— 三份形状、两档底色、内边距、图文间距全都逐字重复.
 * 改一次配色要改三处, 而它们必须看着是同一行里的同类按钮.
 *
 * 内容 (图标 + 文字, 或输入框) 由调用方给, 外壳只管形状、两档配色与内边距.
 *
 * @param highlighted 是否反色. 一般就是"聚焦时" —— 但弹幕输入胶囊展开成输入框后即使聚焦
 *   也要保持深色 (白底上没法放输入光标), 所以这里收的是布尔而不是自己去读焦点.
 * @param interactionSource 必须与调用方读聚焦态用的是同一个, 否则 [highlighted] 与实际焦点对不上.
 */
@Composable
internal fun TvPillShell(
    highlighted: Boolean,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    /** 触屏: 未聚焦时第一下只聚焦 (聚焦本身就浮面板的胶囊用), 见 [tvTouchFocusOnTap]. */
    touchTwoStep: Boolean = false,
    /**
     * 外框描边. 要描边就走这里, 别在 [modifier] 上挂 `Modifier.border`, 也别交给 [Surface] 的同名参数:
     * 前者会被可点击 [Surface] 内部的最小触控区撑大 (描边比按钮大一圈), 后者画在背景**之下** ——
     * 聚焦时那层白色实心背景会把它盖掉, 只剩最外侧一点边缘, 看着像颜色被冲淡了.
     * 这里把它画成内容之上的一层, 边界与形状都取自 [Surface] 本身.
     */
    border: BorderStroke? = null,
    /**
     * 内容压暗: 取 `LocalContentColor` 的文字 (与图标) 降到 [TV_PILL_DIMMED_CONTENT_ALPHA]. 用来标出"这颗胶囊背后的
     * 内容没加载出来", 调用方同时把图标换成 [TvPillFailedIcon]. 底色与聚焦反色不变, 按钮照旧可聚焦、可点.
     */
    dimmed: Boolean = false,
    /**
     * 这颗胶囊背后的内容正在加载: 底色缓慢地明暗呼吸 (未聚焦时往白里亮, 聚焦时白底往灰里暗).
     * 加载超过 [TV_PILL_LOADING_PULSE_DELAY_MILLIS] 才开始动, 快的加载不闪一下; 停下时从当前颜色渐回原样.
     * 胶囊不在屏上时 (控制层收起 / 整层透明) 调用方传 false, 不白白出帧.
     */
    loading: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val contentColor = if (highlighted) Color.Black else Color.White
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (!loading) {
            if (pulse.value != 0f) pulse.animateTo(0f, tween(TV_PILL_LOADING_SETTLE_MILLIS))
            return@LaunchedEffect
        }
        delay(TV_PILL_LOADING_PULSE_DELAY_MILLIS)
        // 系统关掉了动画 (「移除动画」/ 动画时长缩放为 0) 时每段 animateTo 一帧就走完,
        // 下面的循环会变成逐帧亮暗交替的频闪 —— 那就不呼吸
        if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) return@LaunchedEffect
        while (true) {
            pulse.animateTo(1f, TV_PILL_LOADING_PULSE_SPEC)
            pulse.animateTo(0f, TV_PILL_LOADING_PULSE_SPEC)
        }
    }
    // 呼吸只用黑白灰 (胶囊行不带主题色): 未聚焦的深色玻璃底往白里亮, 聚焦的白底往灰里暗
    val pulseColor = if (highlighted) Color.Black else Color.White
    val pulsePeakAlpha = if (highlighted) TV_PILL_LOADING_PULSE_PEAK_ALPHA_ON_LIGHT else TV_PILL_LOADING_PULSE_PEAK_ALPHA
    Surface(
        onClick = onClick,
        modifier = modifier.tvTouchFocusOnTap(twoStep = touchTwoStep),
        shape = CircleShape,
        color = if (highlighted) Color.White else Color.White.copy(alpha = TV_PILL_IDLE_ALPHA),
        contentColor = if (dimmed) contentColor.copy(alpha = TV_PILL_DIMMED_CONTENT_ALPHA) else contentColor,
        interactionSource = interactionSource,
    ) {
        Box(
            // 呼吸的进度只在绘制阶段读: 每帧只重画这一颗胶囊, 不重组. 画在 Surface 里面, 被它按形状裁掉
            Modifier.drawBehind {
                val progress = pulse.value
                if (progress > 0f) drawRect(pulseColor, alpha = progress * pulsePeakAlpha)
            },
        ) {
            Row(
                Modifier.padding(horizontal = TV_PILL_PADDING_H, vertical = TV_PILL_PADDING_V),
                horizontalArrangement = Arrangement.spacedBy(TV_PILL_CONTENT_SPACING),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
            border?.let { Box(Modifier.matchParentSize().border(it, CircleShape)) }
        }
    }
}

/**
 * 胶囊背后的内容加载失败时顶替原图标的警示图标 (文字照旧, 由 [TvPillShell] 的 `dimmed` 压暗).
 * 自带颜色, 不跟着压暗: 灰掉的文字配一个红色感叹号, 与"正常 / 加载中"一眼分得开.
 */
@Composable
internal fun TvPillFailedIcon(highlighted: Boolean) {
    Icon(
        Icons.Rounded.ErrorOutline,
        contentDescription = null,
        modifier = Modifier.size(TV_PILL_ICON_SIZE),
        tint = if (highlighted) TV_PILL_FAILED_ICON_ON_LIGHT else MaterialTheme.colorScheme.error,
    )
}

/** 未聚焦时的底色不透明度 (白): 压得住画面又不抢戏. */
private const val TV_PILL_IDLE_ALPHA = 0.14f

/** 压暗时内容 (取 `LocalContentColor` 的文字) 的不透明度, 见 [TvPillShell] 的 `dimmed`. */
private const val TV_PILL_DIMMED_CONTENT_ALPHA = 0.5f

/**
 * 加载中呼吸最亮时白色叠加层的不透明度, 见 [TvPillShell] 的 `loading`: 未聚焦 (深色玻璃底) 那一档,
 * 底色从白 14% 亮到约 40%, 与旁边的胶囊明显分得开又不刺眼.
 */
private const val TV_PILL_LOADING_PULSE_PEAK_ALPHA = 0.3f

/** 同上, 聚焦 (白底黑字) 那一档叠的是黑色: 白底暗到约 65% 灰, 黑字照样清楚. */
private const val TV_PILL_LOADING_PULSE_PEAK_ALPHA_ON_LIGHT = 0.35f

/** 聚焦 (白底) 时失败图标的颜色: 主题的错误色在深色主题里是浅红, 放白底上看不清. */
private val TV_PILL_FAILED_ICON_ON_LIGHT = Color(0xFFB3261E)

/** 加载持续多久之后才开始呼吸: 多数加载一两秒内就完, 短于这个的不闪. */
private const val TV_PILL_LOADING_PULSE_DELAY_MILLIS = 500L

/** 呼吸半程 (变亮或变暗一次) 的时长: 一个来回 1.2 秒, 慢到不像闪烁. */
private val TV_PILL_LOADING_PULSE_SPEC = tween<Float>(durationMillis = 600, easing = EaseInOut)

/** 加载结束后从当前亮度回到原样的时长. */
private const val TV_PILL_LOADING_SETTLE_MILLIS = 250

/** 胶囊里图标的尺寸. */
internal val TV_PILL_ICON_SIZE = 14.dp

/** 胶囊内边距 (横/纵). */
internal val TV_PILL_PADDING_H = 14.dp
internal val TV_PILL_PADDING_V = 8.dp

/** 图标与文字之间的间距. */
private val TV_PILL_CONTENT_SPACING = 6.dp
