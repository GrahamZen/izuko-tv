/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.ComponentRegistry

/**
 * 注册动图 (GIF) 解码器.
 *
 * 不注册的话 GIF 只出第一帧 —— Bangumi 的表情包有不少是动图 (见
 * [me.him188.ani.app.ui.comment.BangumiStickers]). sketch 的 `sketch-animated-gif` 本来支持
 * 自动注册, 但 `createDefaultSketch` 关掉了 `componentLoaderEnabled`, 所以要手动来.
 *
 * **Android 上刻意用 Movie 版而不是 API 28+ 的 ImageDecoder 版**: 系统的
 * `android.graphics.ImageDecoder` 在部分设备上会对完全正常的图报
 * `Failed to create image decoder with message 'unimplemented'` —— 实测 Shield 上番剧封面与
 * 评论里贴的图都中招 (表现是占位撑开后又塌掉、图始终不出来). 解码器一旦被选中就不会回退,
 * 所以宁可一律走久经考验的 `Movie`.
 */
internal expect fun ComponentRegistry.Builder.addAniAnimatedDecoders()
