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
 * 本机浏览器的真实 User-Agent, 取不到时返回 `null`.
 *
 * 用途是避免所有安装发出同一个 UA: HTTP client 自带的那个是写死的常量, 每台设备一模一样,
 * 站点按它就能精确认出这个应用的全部用户.
 *
 * 注意本机 UA 通常是移动版, 站点会因此返回移动版页面. 按页面结构解析的数据源不要用它.
 */
expect fun Context.deviceBrowserUserAgent(): String?
