/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.api.provider

import me.him188.ani.danmaku.api.DanmakuServiceId

/** 用户自己导入 (文件或视频链接) 的弹幕. */
val DanmakuProviderId.Companion.Imported: DanmakuProviderId get() = DanmakuProviderId("imported")

/** 见 [DanmakuProviderId.Companion.Imported]. */
val DanmakuServiceId.Companion.Imported: DanmakuServiceId get() = DanmakuServiceId("Imported")

/** 应用自带的弹幕来源. 其余的都是用户添加或订阅的弹幕服务器, 来源 id 就是服务器名. */
val BuiltInDanmakuServices: Set<DanmakuServiceId> = setOf(
    DanmakuServiceId.Animeko,
    DanmakuServiceId.AcFun,
    DanmakuServiceId.Baha,
    DanmakuServiceId.Bilibili,
    DanmakuServiceId.Dandanplay,
    DanmakuServiceId.Tucao,
    DanmakuServiceId.Imported,
)

/** 见 [BuiltInDanmakuServices]. */
val DanmakuServiceId.isBuiltIn: Boolean get() = this in BuiltInDanmakuServices
