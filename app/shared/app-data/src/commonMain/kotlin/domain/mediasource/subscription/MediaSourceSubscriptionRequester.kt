/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.subscription

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import kotlinx.serialization.json.io.decodeFromSource
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.toSource
import kotlin.coroutines.cancellation.CancellationException

fun interface MediaSourceSubscriptionRequester {
    @Throws(RepositoryException::class, CancellationException::class)
    suspend fun request(
        subscription: MediaSourceSubscription,
    ): SubscriptionUpdateData
}

/**
 * 订阅更新只走**直连**.
 *
 * 直连之前还有一层兜底: 直连失败时改让 Ani 服务器代取 (`/v1/subs/proxy`)。那个代理没了,
 * 于是直连失败就是失败 —— 订阅源本来就是用户自己填的地址, 打不开该让他看见.
 */
class MediaSourceSubscriptionRequesterImpl(
    private val client: ScopedHttpClient,
) : MediaSourceSubscriptionRequester {
    /**
     * 执行网络请求, 下载新订阅数据.
     */
    @Throws(RepositoryException::class, CancellationException::class)
    override suspend fun request(
        subscription: MediaSourceSubscription,
    ): SubscriptionUpdateData {
        suspend fun HttpResponse.decode() = bodyAsChannel().toSource().use {
            MediaSourceCodecManager.Companion.json.decodeFromSource(
                SubscriptionUpdateData.serializer(),
                it,
            )
        }

        return client.use {
            get(subscription.url).decode()
        }
    }
}