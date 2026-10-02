/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.matcher

/**
 * 可选能力: 这个源的站点要很久才发出视频请求, WebView 嗅探至少要等这么久.
 *
 * 与 [WebVideoDirectResolver] 一样由 [WebVideoMatcher] 的实现顺带实现, 不加进 [WebVideoMatcher] 本体.
 * 没实现或没规定的源照全局设置. 安卓的解析器认它 (见 [sniffTimeoutMillis]).
 */
interface WebVideoSniffTimeout {
    /** 嗅探至少等多久 (毫秒); `null` = 照全局设置. */
    val sniffTimeoutMillis: Long?
}

/**
 * 这次嗅探最多等多久: 全局设置 [globalMillis] 与匹配器的要求取较大者 —— 源的要求是站点至少要等这么久,
 * 全局设置是用户愿意等多久 (网络慢的人会调长). 匹配器的要求最多算 [MAX_SOURCE_SNIFF_TIMEOUT_MILLIS], 免得一个源卡很久才换源.
 */
fun List<WebVideoMatcher>.sniffTimeoutMillis(globalMillis: Long): Long {
    val requested = mapNotNull { (it as? WebVideoSniffTimeout)?.sniffTimeoutMillis }.maxOrNull() ?: return globalMillis
    return maxOf(globalMillis, requested.coerceAtMost(MAX_SOURCE_SNIFF_TIMEOUT_MILLIS))
}

/** 与设置里能选的最长嗅探时长相同. */
const val MAX_SOURCE_SNIFF_TIMEOUT_MILLIS: Long = 30_000
