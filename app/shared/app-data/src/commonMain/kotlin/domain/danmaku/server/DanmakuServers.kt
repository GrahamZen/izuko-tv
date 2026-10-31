/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku.server

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.danmaku.api.provider.BuiltInDanmakuServices

/**
 * 订阅文件 `danmakuServers` 字段里的一项, 也是用户手动添加时存的格式.
 *
 * 这个格式由第三方托管: 只做兼容的改动, 不认识的 [type] 整项跳过.
 *
 * @param name 播放器里显示的名字; 空着时用地址的域名
 * @param url 接口地址, 拼上 `/api/v2/...` 就是弹弹 play 兼容接口. 访问令牌通常就写在路径里
 * @param type 接口格式, 不写是弹弹 play 兼容 ([TYPE_DANDANPLAY]), 也是目前唯一支持的
 * @param features 能做什么: [FEATURE_MATCH] 按番自动匹配, [FEATURE_LINK] 按视频链接取弹幕. 不写就两种都试,
 *   没有的接口请求时会被认出来
 */
@Serializable
data class DanmakuServerEntry(
    val name: String = "",
    val url: String,
    val type: String? = null,
    val features: List<String>? = null,
) {
    companion object {
        const val TYPE_DANDANPLAY = "dandanplay"
        const val FEATURE_MATCH = "match"
        const val FEATURE_LINK = "link"
    }
}

/**
 * 生效的一台弹幕服务器.
 *
 * @param subscriptionId 来自哪个订阅; 手动添加的为 `null`
 */
data class DanmakuServerItem(
    val name: String,
    val baseUrl: String,
    val autoMatch: Boolean,
    val linkImport: Boolean,
    val enabled: Boolean,
    val subscriptionId: String?,
)

/** 弹弹 play 官方接口要签名, 不能当成服务器添加; 应用本身就带它. */
private const val OFFICIAL_DANDANPLAY_HOST = "api.dandanplay.net"

/** 播放器弹幕源已经占用的名字: 服务器也叫这个的话, 开关会和内置来源连在一起. */
private val RESERVED_NAMES = BuiltInDanmakuServices.mapTo(HashSet()) { it.value.lowercase() }

/** 规范化接口地址: 去空白、结尾的 `/` 与 `/api/v2`. 不是 http(s) 地址或指向弹弹 play 官方接口时为 `null`. */
fun normalizeDanmakuServerUrl(raw: String): String? {
    val url = raw.trim().trimEnd('/').removeSuffix("/api/v2").trimEnd('/')
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
    val host = runCatching { Url(url).host }.getOrNull()
    if (host.isNullOrBlank() || host.equals(OFFICIAL_DANDANPLAY_HOST, ignoreCase = true)) return null
    return url
}

/**
 * 合并手动添加的服务器与各订阅带来的服务器: 手动的在前; 同一地址只留第一个; 地址不合法或 [DanmakuServerEntry.type]
 * 不认识的跳过; 已停用的订阅不算; 名字撞了在前面加序号 (弹幕源开关按名字分; 放前面是因为胶囊上长名字的结尾会被截掉).
 */
fun mergeDanmakuServers(
    saved: SavedDanmakuServers,
    subscriptions: List<MediaSourceSubscription>,
): List<DanmakuServerItem> {
    val entries = saved.servers.map { it to null } +
            subscriptions.filter { it.enabled }.flatMap { sub -> sub.danmakuServers.map { it to sub.subscriptionId } }
    val seenUrls = HashSet<String>()
    val usedNames = HashSet(RESERVED_NAMES)
    return entries.mapNotNull { (entry, subscriptionId) ->
        val type = entry.type
        if (type != null && !type.equals(DanmakuServerEntry.TYPE_DANDANPLAY, ignoreCase = true)) return@mapNotNull null
        val baseUrl = normalizeDanmakuServerUrl(entry.url) ?: return@mapNotNull null
        if (!seenUrls.add(baseUrl)) return@mapNotNull null
        val name = uniqueName(entry.name.trim().ifEmpty { Url(baseUrl).host }, usedNames)
        DanmakuServerItem(
            name = name,
            baseUrl = baseUrl,
            autoMatch = entry.features?.contains(DanmakuServerEntry.FEATURE_MATCH) ?: true,
            linkImport = entry.features?.contains(DanmakuServerEntry.FEATURE_LINK) ?: true,
            enabled = baseUrl !in saved.disabledUrls,
            subscriptionId = subscriptionId,
        )
    }
}

private fun uniqueName(name: String, used: MutableSet<String>): String {
    var candidate = name
    var suffix = 2
    while (!used.add(candidate.lowercase())) candidate = "${suffix++} $name"
    return candidate
}

/**
 * 用户手动添加的服务器, 以及停用了哪些 (订阅带来的服务器也能停用, 按规范化后的地址记).
 */
@Serializable
data class SavedDanmakuServers(
    val servers: List<DanmakuServerEntry> = emptyList(),
    val disabledUrls: Set<String> = emptySet(),
)
