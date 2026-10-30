/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 画在播放器上单独一层里的弹幕. Android 上是一块 OpenGL 表面, 由自己的线程按 vsync 画: 所有在屏弹幕的图放在一张图集纹理里,
 * 每帧一次画完. 其他平台退回 [DanmakuHost].
 *
 * 位置、轨道、过期仍由 [DanmakuHostState] 算, 只是「画」不经过界面: 界面不随弹幕逐帧重画, 界面线程忙的时候弹幕也照常滚动.
 *
 * 这一层在系统合成里叠在视频之上、界面窗口之下, 并且会在窗口上挖洞: 要在视频之前组合, 否则会把画在它之前的窗口内容
 * (视频控件里的字幕) 清成透明. 因此字幕在弹幕之上.
 * 退回 [DanmakuHost] 时反过来, 要在视频之后组合: 视频的表面同样在窗口上挖洞, 会把画在它之前的弹幕清掉.
 * 此刻是哪一种看 [danmakuLayerHasOwnSurface].
 *
 * @param alpha 整层的不透明度, 在弹幕的不透明度设置之上再乘, 供显隐动画用; 为 0 时不画. 在画的时候读, 变化不引起重组.
 * @param snapshots 截图用, 见 [DanmakuLayerSnapshots]. 不支持的平台不登记.
 * @param playing 视频此刻在不在播. 给了它, 画弹幕的线程每帧自己读, 视频一停弹幕当帧停住, 不用等界面线程转告
 * (按暂停时界面线程常要忙一阵, 等它转告时弹幕已经多走了一段, 再退回去); 会在别的线程上读, 要线程安全 (如播放器状态 StateFlow 的值).
 * 只在单独画的那一层上起作用.
 */
@Composable
expect fun DanmakuLayer(
    state: DanmakuHostState,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    snapshots: DanmakuLayerSnapshots? = null,
    playing: (() -> Boolean)? = null,
)

/**
 * [DanmakuLayer] 此刻是不是画在单独的表面上: true 要在视频之前组合, false (退回 [DanmakuHost], 画在界面里) 要在视频之后组合.
 * 会变: Android 上发现这台设备画不了 GL 层时变成 false 并一直保持, 在组合里读的跟着重组.
 */
expect val danmakuLayerHasOwnSurface: Boolean

/** 截此刻弹幕层上画着的内容: 透明底, 与层同尺寸. */
class DanmakuLayerSnapshots {
    internal var source: (() -> ImageBitmap?)? = null

    /** 只在界面线程上调用. 层不在组合里时为 null. */
    fun capture(): ImageBitmap? = source?.invoke()
}

/**
 * 滚动弹幕在 [elapsedNanos] (弹幕的帧时间) 时左边缘的横坐标. 与 [FloatingDanmaku.distanceX] 同一个式子:
 * 弹幕在 [placeNanos] 时位于右边缘, 之后以 [speedPxPerSecond] 向左走.
 */
internal fun floatingDanmakuX(hostWidth: Int, elapsedNanos: Long, placeNanos: Long, speedPxPerSecond: Float): Float =
    hostWidth - ((elapsedNanos - placeNanos) / 1_000L) / 1_000_000f * speedPxPerSecond
