/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.player

import kotlinx.coroutines.flow.StateFlow

/**
 * "当前这一代视频输出面上已经出过画面了没有" —— 播放器可选实现的信号.
 *
 * ## 为什么需要它
 *
 * 视频输出面 (Android 上是播放页自己那块 SurfaceView) 跟着页面生死: 退出播放页它就销毁, 回来
 * 是**新的一代**. 而保留播放会话让播放器本体活了下来, 于是"回到正在播放"时播放器立刻能出声,
 * 视频那一路却要先把解码器的输出重定向到新 Surface —— 部分芯片 (实测索尼 BRAVIA BF1 上的
 * 联发科解码器) 就地改不了, media3 只能释放并重建解码器, 再从关键帧重解到当前位置才有第一帧.
 * 那几百毫秒里画面是黑的而声音已经在走: 用户听得到却看不到, **那一两秒的画面内容是真的丢了**.
 *
 * 有了这个信号, 恢复播放就能等到画面就位再放声音 (见 RetainedPlaybackSessionHolder), 代价是
 * 多等一小会儿, 换来声画同时开始、一帧不漏.
 *
 * ## 语义
 *
 * - 输出面换代 (新 SurfaceView 接上 / 当前 Surface 销毁) 后为 `false`;
 * - 该代输出面上真的渲染过一帧之后为 `true`;
 * - 播放中途没换过输出面就一直是 `true`, 所以"手动暂停后恢复"不会白等.
 *
 * 拿不到这个信号的播放器 (桌面 mpv / iOS, 或没有注册过 Surface 的实现) 不实现本接口, 调用方
 * `as?` 拿不到就不等 —— 语义退化成老行为, 不是错误.
 */
interface VideoSurfaceFrameSignal {
    /** 当前这一代视频输出面上是否已经渲染过一帧. */
    val hasFrameOnCurrentSurface: StateFlow<Boolean>
}
