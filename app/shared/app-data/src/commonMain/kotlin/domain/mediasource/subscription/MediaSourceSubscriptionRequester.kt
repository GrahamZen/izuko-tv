/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.subscription

import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.rule.AniBakaSubscription
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException

fun interface MediaSourceSubscriptionRequester {
    @Throws(RepositoryException::class, CancellationException::class)
    suspend fun request(
        subscription: MediaSourceSubscription,
    ): SubscriptionUpdateData
}

/**
 * 订阅更新只走**直连**: 订阅源本来就是用户自己填的地址, 打不开该让他看见.
 *
 * [sourcesOf] 给出一个订阅地址的全部来源 (原地址在前): GitHub 上的地址带上加速镜像, 原地址不通时依次试镜像, 每个限时 [MIRROR_TIMEOUT_MILLIS].
 */
class MediaSourceSubscriptionRequesterImpl(
    private val client: ScopedHttpClient,
    private val sourcesOf: suspend (url: String) -> List<String> = { listOf(it) },
) : MediaSourceSubscriptionRequester {
    /**
     * 执行网络请求, 下载新订阅数据.
     */
    @Throws(RepositoryException::class, CancellationException::class)
    override suspend fun request(
        subscription: MediaSourceSubscription,
    ): SubscriptionUpdateData {
        val urls = sourcesOf(subscription.url).ifEmpty { listOf(subscription.url) }
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val text = client.use {
                    get(url) {
                        if (urls.size > 1) timeout { requestTimeoutMillis = MIRROR_TIMEOUT_MILLIS }
                    }.bodyAsText()
                }
                // 订阅地址也可以是 AniBaka 的规则库: 下载到的是它就转换成规则源
                return AniBakaSubscription.decodeOrNull(text, url) { ruleUrl -> client.use { get(ruleUrl).bodyAsText() } }
                    ?: MediaSourceCodecManager.json.decodeFromString(SubscriptionUpdateData.serializer(), text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (urls.size > 1) logger.warn { "Subscription request failed via $url: $e" }
                lastError = e
            }
        }
        throw lastError!!
    }

    private companion object {
        private val logger = logger<MediaSourceSubscriptionRequesterImpl>()
        private const val MIRROR_TIMEOUT_MILLIS = 15_000L
    }
}