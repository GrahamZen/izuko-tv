/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

/**
 * 给用户看的这台设备的叫法: 型号 + 一小段设备号 (如 `SHIELD Android TV 3f2a`), 重装应用也不变.
 * 用在电视生成的 Bangumi 令牌名上 (见 `BangumiWebLogin`), 用户在令牌列表里分得清是哪台电视的.
 */
expect fun Context.deviceLabel(): String
