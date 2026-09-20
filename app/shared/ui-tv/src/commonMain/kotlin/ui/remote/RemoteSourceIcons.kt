/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * 手机网页上数据源名字前的图标: `api/source-icon?id=<实例 id 或数据源 id>` (设置里的数据源列表按实例 id, 播放器候选分组按数据源 id).
 * 取法同 App 的 `MediaSourceIcon`: 内置源 (蜜柑 / 动漫花园 / Bangumi) 的图标打包在应用里 (`iconResourceId`), 手机拿不到, 电视读出字节
 * 直接回; 其余用源自己配置的 `iconUrl`, 经 [RemoteImageProxy] 转发 —— 地址来自电视上的数据源配置而不是手机传来的, 所以不受它的图床
 * 白名单限制. 两样都没有回 404, 网页换成首字母圆标 (App 这时用 ui-avatars 生成首字母图, 这里不让手机再去连第三方).
 */
internal object RemoteSourceIcons {
    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()

    /** 打包在 ui-foundation 资源里的那几张, 读一次留着. */
    private val bundled = ConcurrentHashMap<String, ByteArray>()

    @OptIn(ExperimentalResourceApi::class)
    fun handle(request: LanHttpRequest): LanHttpResponse {
        val id = request.query.split('&').firstOrNull { it.startsWith("id=") }
            ?.let { runCatching { URLDecoder.decode(it.substring(3), Charsets.UTF_8.name()) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return LanHttpResponse.status(400, "Bad Request")
        val info = runBlocking { withTimeoutOrNull(2.seconds) { manager.allInstances.first() } }
            ?.firstOrNull { it.instanceId == id || it.mediaSourceId == id }?.source?.info
            ?: return LanHttpResponse.status(404, "Not Found")
        info.iconResourceId?.takeIf { it in BUNDLED }?.let { name ->
            val bytes = bundled.getOrPut(name) { runBlocking { Res.readBytes("drawable/$name") } }
            return LanHttpResponse.bytes(bytes, "image/png", cacheControl = "private, max-age=86400")
        }
        val url = info.iconUrl?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return LanHttpResponse.status(404, "Not Found")
        return RemoteImageProxy.serveTrusted(url)
    }

    /** 同 ui-settings 的 `getIconResourceOrNull` 认的那几张 (都在 ui-foundation 的 composeResources/drawable 下). */
    private val BUNDLED = setOf("mikan.png", "dmhy.png", "bangumi.png")
}
