/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.danmaku.imported.DanmakuImporter
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuProvider
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuStore
import me.him188.ani.app.domain.danmaku.server.DanmakuServerRegistry
import me.him188.ani.app.domain.danmaku.server.DanmakuServerStore
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.platform.Context
import me.him188.ani.app.platform.files
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuProvider
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.Imported
import me.him188.ani.danmaku.api.provider.MatchingDanmakuProvider
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.module.Module
import kotlin.coroutines.cancellation.CancellationException

/** 弹幕服务器与导入弹幕的依赖注入定义. */
internal fun Module.danmakuExtraDefinitions(getContext: () -> Context) {
    single<DanmakuServerStore> { DanmakuServerStore(getContext().files.dataDir.resolve("danmaku-servers.json")) }
    single<DanmakuServerRegistry> {
        DanmakuServerRegistry(
            store = get(),
            subscriptions = get<MediaSourceSubscriptionRepository>().flow,
            // 惰性: 网络客户端的依赖里绕得回这里
            client = { get<HttpClientProvider>().get() },
        )
    }
    single<ImportedDanmakuStore> { ImportedDanmakuStore(getContext().files.dataDir.resolve("imported-danmaku")) }
    single<ImportedDanmakuProvider> { ImportedDanmakuProvider(get()) }
    single<DanmakuImporter> { DanmakuImporter(get(), get()) }
    single<DanmakuExtraProviders> { DanmakuExtraProviders(get(), get()) }
}

/**
 * 内置弹幕源之外的来源: 用户导入的弹幕, 以及用户添加或订阅的弹幕服务器.
 */
class DanmakuExtraProviders(
    private val imported: ImportedDanmakuProvider,
    private val servers: DanmakuServerRegistry,
) {
    /** 读不出服务器列表时当作没有: 内置弹幕源不能因为这些额外来源出错就取不到弹幕. */
    suspend fun all(): List<DanmakuProvider> {
        val serverProviders = try {
            servers.providers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read danmaku servers" }
            emptyList()
        }
        return listOf(imported) + serverProviders
    }

    fun matchingOrNull(id: DanmakuProviderId): MatchingDanmakuProvider? = servers.providerOrNull(id)

    private companion object {
        private val logger = logger<DanmakuExtraProviders>()
    }
}

/**
 * 同时问所有弹幕源, 每个源回来就发一次累积的结果 (按 [fetchers] 的顺序): 慢的源不拖住快的.
 * 最后一次发射是全部的结果.
 *
 * 还有来源没回来时不发空结果: 下游收到结果就认为加载完了, 会说「这一集没有弹幕」. 没导入过弹幕的集, 导入这个来源最先回来而且是空的.
 */
internal fun fetchAllConcurrently(
    fetchers: List<DanmakuFetcher>,
    request: DanmakuFetchRequest,
): Flow<List<DanmakuFetchResult>> = channelFlow {
    val results = arrayOfNulls<List<DanmakuFetchResult>>(fetchers.size)
    val lock = Mutex()
    var finished = 0
    fetchers.forEachIndexed { index, fetcher ->
        launch {
            val result = fetcher.fetch(request)
            lock.withLock {
                results[index] = result
                finished++
                val accumulated = results.filterNotNull().flatten()
                if (accumulated.isNotEmpty() || finished == fetchers.size) send(accumulated)
            }
        }
    }
}

/** 导入的弹幕不算远程结果: 它们不能顶替本地缓存, 也不进缓存. */
internal fun List<DanmakuFetchResult>.importedOnly(): List<DanmakuFetchResult> =
    filter { it.providerId == DanmakuProviderId.Imported }

internal fun List<DanmakuFetchResult>.withoutImported(): List<DanmakuFetchResult> =
    filterNot { it.providerId == DanmakuProviderId.Imported }
