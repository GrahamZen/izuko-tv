/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.maccms

import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.RequestUserAgentAttribute
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.MediaSourceTier
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.source.direct.DirectLink
import me.him188.ani.datasources.api.source.direct.DirectLinkMediaSource
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 苹果 CMS 采集接口 (资源站常开的 `api.php/provide/vod`, TVBox 配置里 `type: 1` 的站点).
 */
@Serializable
data class MacCmsConfig(
    /**
     * 接口地址, 如 `https://example.com/api.php/provide/vod/`. 可以带参数 (`…/from/xxm3u8/`、`?ac=list`),
     * 搜索时 `ac` 换成 `detail` 并加上 `wd`.
     */
    val apiUrl: String = "",
    /** 最多用几个条目名去搜; 前一个名字搜到了就不再换. */
    val maxKeywords: Int = 2,
    /** 请求接口时用的 User-Agent, 留空用本机浏览器的. */
    val userAgent: String = "",
)

@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class MacCmsMediaSourceArguments(
    override val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val config: MacCmsConfig = MacCmsConfig(),
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments {
    companion object {
        val Default = MacCmsMediaSourceArguments(name = "苹果 CMS")

        /** 添加数据源时给出的模板. 地址是 example.com, 换成资源站的接口地址即可. */
        val Example = MacCmsMediaSourceArguments(
            name = "苹果 CMS",
            description = "示例配置, 把接口地址换成资源站的",
            config = MacCmsConfig(apiUrl = "https://api.example.com/api.php/provide/vod/"),
        )
    }
}

object MacCmsMediaSourceCodec : DefaultMediaSourceCodec<MacCmsMediaSourceArguments>(
    MacCmsMediaSource.FactoryId,
    MacCmsMediaSourceArguments::class,
    currentVersion = 1,
    MacCmsMediaSourceArguments.serializer(),
)

/**
 * 苹果 CMS 资源站: 按条目名调采集接口, 只取能直接播放的线路 (m3u8 / mp4), 逐集对到条目的剧集. 规则见 [MacCmsEngine].
 */
class MacCmsMediaSource(
    override val mediaSourceId: String,
    config: MediaSourceConfig,
    private val client: ScopedHttpClient,
) : DirectLinkMediaSource() {
    companion object {
        val FactoryId = FactoryId("maccms")

        val INFO = MediaSourceInfo(
            displayName = "苹果 CMS",
            description = "资源站的苹果 CMS 采集接口, 直接播放 m3u8",
        )
    }

    private val arguments = config.deserializeArgumentsOrNull(MacCmsMediaSourceArguments.serializer())
        ?: MacCmsMediaSourceArguments.Default

    private val engine = MacCmsEngine(arguments.config, arguments.name, ::fetchBytes)

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = arguments.name,
        description = arguments.description.takeIf { it.isNotBlank() },
        iconUrl = arguments.iconUrl.takeIf { it.isNotBlank() },
    )

    override suspend fun checkConnection(): ConnectionStatus {
        if (arguments.config.apiUrl.isBlank()) return ConnectionStatus.FAILED
        return runCatching { fetchBytes(MacCmsEngine.searchUrlOf(arguments.config.apiUrl, "test")) }
            .fold({ ConnectionStatus.SUCCESS }, { ConnectionStatus.FAILED })
    }

    override suspend fun queryLinks(request: MediaFetchRequest): List<DirectLink> = engine.queryLinks(request)

    private suspend fun fetchBytes(url: String): ByteArray {
        val userAgent = arguments.config.userAgent.takeIf { it.isNotBlank() } ?: DeviceBrowserUserAgentHolder.current()
        return try {
            client.use {
                get(url) {
                    userAgent?.let { ua -> attributes.put(RequestUserAgentAttribute, ua) }
                }.readRawBytes()
            }
        } catch (e: ResponseException) {
            // 异常消息里带着整个出错页面, 只留状态码
            throw IllegalStateException("HTTP ${e.response.status.value}: $url")
        }
    }

    class Factory : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val allowMultipleInstances: Boolean get() = true
        override val info: MediaSourceInfo get() = INFO

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource = MacCmsMediaSource(mediaSourceId, config, client)
    }
}
