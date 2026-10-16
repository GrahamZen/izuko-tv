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

/**
 * 反馈用的设备号: 故障工单与修正请求带上它, 维护者据此认出是哪台电视、拉黑乱发的. 只由本应用的设备 ID 加盐哈希得来, 推不回设备 ID;
 * 重装应用不变, 恢复出厂才变. 16 位十六进制.
 */
expect fun Context.feedbackDeviceId(): String

/** 故障工单里的「环境」: 机型、系统、芯片、内存等 (键是给维护者看的中文名), 开单时写进 issue. */
expect fun Context.feedbackEnvironment(): Map<String, String>
