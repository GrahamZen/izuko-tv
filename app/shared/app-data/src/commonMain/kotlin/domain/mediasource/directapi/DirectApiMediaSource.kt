/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import kotlinx.serialization.Serializable
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
 * [DirectApiMediaSource] 的用户侧配置.
 */
@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class DirectApiMediaSourceArguments(
    override val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val config: DirectApiConfig = DirectApiConfig(),
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments {
    companion object {
        val Default = DirectApiMediaSourceArguments(name = "直链 API")

        /**
         * 配置示例, 也是添加数据源时给出的模板. 地址是 example.com, 连不上任何真实服务,
         * 照着把地址与取值路径改成目标站点的即可.
         *
         * 例子里凑齐了几种麻烦情况: protobuf 响应、包在 JSON 数字数组里的 protobuf、
         * 站内搜索不准需要二次请求校验、播放地址被混淆.
         */
        val Example = DirectApiMediaSourceArguments(
            name = "直链 API",
            description = "示例配置, 把地址与取值路径换成目标站点的",
            config = DirectApiConfig(
                baseUrl = "https://api.example.com",
                subject = DirectApiConfig.SubjectConfig(
                    request = DirectApiConfig.RequestConfig(
                        url = "{baseUrl}/search?keyword={subjectName}",
                        format = ResponseFormat.Protobuf,
                        itemsPath = "1",
                    ),
                    idPath = "1",
                    // 站内搜索通常不准, 用条目详情里的 bangumi id 校验, 不要信第一条
                    verify = DirectApiConfig.RequestConfig(
                        url = "{baseUrl}/detail/{candidateId}",
                        format = ResponseFormat.Json,
                    ),
                    verifyPath = "sites[site=bangumi].id",
                ),
                episode = DirectApiConfig.EpisodeConfig(
                    request = DirectApiConfig.RequestConfig(
                        url = "{baseUrl}/episodes/{subjectId}",
                        format = ResponseFormat.Protobuf,
                        itemsPath = "1",
                    ),
                    matchPath = "5[1=bangumi].2",
                    sortPath = "2",
                    valuePath = "2",
                ),
                lines = DirectApiConfig.LinesConfig(
                    request = DirectApiConfig.RequestConfig(
                        url = "{baseUrl}/vod/{subjectId}/{episodeId}",
                        format = ResponseFormat.ProtobufInJsonBytes,
                        itemsPath = "1",
                    ),
                    urlPath = "1",
                    titlePath = "4",
                    channelPath = "5",
                    subjectNamePath = "6",
                    // 例: 播放地址是插了一个垃圾字符的 base64
                    urlTransforms = listOf(
                        Transform(TransformOp.RemoveCharAt, index = 3),
                        Transform(TransformOp.Base64Decode),
                    ),
                    // 例: 线路名形如 "alpha-11", 后缀会变, 而线路名会作为偏好保存下来, 必须稳定
                    channelTransforms = listOf(
                        Transform(TransformOp.SubstringBeforeLast, value = "-"),
                    ),
                    // 例: 站点只给代号时, 在这里翻成看得懂的名字; 没列出的原样显示
                    channelNames = mapOf("alpha" to "线路甲"),
                    maxPerChannel = 2,
                ),
            ),
        )
    }
}

object DirectApiMediaSourceCodec : DefaultMediaSourceCodec<DirectApiMediaSourceArguments>(
    DirectApiMediaSource.FactoryId,
    DirectApiMediaSourceArguments::class,
    currentVersion = 1,
    DirectApiMediaSourceArguments.serializer(),
)

/**
 * 用 JSON 配置对接"调接口拿视频直链"的站点, 不需要为每个站点写代码.
 *
 * 配置格式见 [DirectApiConfig]; [DirectApiMediaSourceArguments.Example] 是一份完整的例子.
 */
class DirectApiMediaSource(
    override val mediaSourceId: String,
    config: MediaSourceConfig,
    client: ScopedHttpClient,
) : DirectLinkMediaSource() {
    companion object {
        val FactoryId = FactoryId("direct-api")

        val INFO = MediaSourceInfo(
            displayName = "直链 API",
            description = "用 JSON 配置对接返回视频直链的接口",
        )
    }

    private val arguments = config.deserializeArgumentsOrNull(DirectApiMediaSourceArguments.serializer())
        ?: DirectApiMediaSourceArguments.Default

    private val engine = DirectApiEngine(arguments.config, client)

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = arguments.name,
        description = arguments.description.takeIf { it.isNotBlank() },
        iconUrl = arguments.iconUrl.takeIf { it.isNotBlank() },
    )

    override suspend fun checkConnection(): ConnectionStatus =
        if (engine.checkConnection()) ConnectionStatus.SUCCESS else ConnectionStatus.FAILED

    override suspend fun queryLinks(request: MediaFetchRequest): List<DirectLink> = engine.queryLinks(request)

    class Factory : MediaSourceFactory {
        override val factoryId: FactoryId get() = FactoryId
        override val allowMultipleInstances: Boolean get() = true
        override val info: MediaSourceInfo get() = INFO

        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient,
        ): MediaSource = DirectApiMediaSource(mediaSourceId, config, client)
    }
}
