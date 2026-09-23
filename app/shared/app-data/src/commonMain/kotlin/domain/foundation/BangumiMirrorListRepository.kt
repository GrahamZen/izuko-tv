/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.preference.BangumiMirrorCache
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours

/**
 * 镜像清单: 内置一份兜底, 真正的清单在运行期拉.
 *
 * **为什么非得能远程更新**: 镜像域名的死亡率极高 —— 2026 年 5~8 月之间 `bgmmi.anibt.net` 与
 * `bangumi.rdd.moe` 停服、`bangumi.one` 被 TLS 阻断、`bangumi.lol` 被人拿下, `bangumi.pro` 又搬到了 `bangumi.vip`.
 * 发一版写死一个域名, 等它死了整个功能就跟着死, 而用户没法自己救.
 *
 * **清单本身放哪的循环问题**: 它跟 bangumi 一样要能在大陆下载到. 所以按顺序试几个入口
 * (见 [LIST_URLS]), 都不通就用内置那份 —— 而**"自建地址"那一档永远不依赖这里的任何东西**,
 * 那是真正的逃生口.
 */
class BangumiMirrorListRepository(
    private val cache: Settings<BangumiMirrorCache>,
    /**
     * **惰性取, 不能在构造期就要**: `HttpClientProvider` 要装 [BangumiMirrorFeatureHandler],
     * 那个 handler 的路由来自 [BangumiEndpointProvider], 而 [BangumiEndpointProvider] 的镜像清单
     * 又来自本仓库 —— 三者在 Koin 里绕成一个环, 构造期直接取会无限递归 (真机上是启动即
     * StackOverflowError). 清单只在 [refreshIfStale] 里拉一次, 那时依赖图早就建完了.
     */
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) {
    private val logger = logger<BangumiMirrorListRepository>()

    /** 给 [BangumiEndpointProvider] 用. 拉到过就用拉到的, 否则用内置那份. */
    val mirrors: Flow<List<String>> = cache.flow.map { cached ->
        cached.mirrors.takeIf { it.isNotEmpty() } ?: BangumiMirrorList.BUNDLED
    }

    init {
        scope.launch {
            refreshIfStale()
        }
    }

    private suspend fun refreshIfStale() {
        val cached = cache.flow.first()
        val age = currentTimeMillis() - cached.updatedAt
        if (cached.mirrors.isNotEmpty() && age < REFRESH_INTERVAL_MILLIS) {
            return
        }
        for (url in LIST_URLS) {
            val fetched = try {
                client().use { get(url).body<RemoteMirrorList>() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "bangumi mirror list: $url unreachable (${e::class.simpleName})" }
                continue
            }
            // **过一遍归一化**: 清单是远程内容, 里面写着什么不由我们决定; 认不出来的条目直接丢掉,
            // 免得把一个畸形域名塞进 host 改写里
            val mirrors = fetched.mirrors.mapNotNull { BangumiMirrorHosts.normalizeMirrorRoot(it) }.distinct()
            if (mirrors.isEmpty()) {
                logger.warn { "bangumi mirror list from $url has no usable entry, ignoring" }
                continue
            }
            cache.set(BangumiMirrorCache(mirrors = mirrors, updatedAt = currentTimeMillis()))
            logger.info { "bangumi mirror list updated from $url: $mirrors" }
            return
        }
        logger.info { "bangumi mirror list: all sources unreachable, keeping ${cached.mirrors.ifEmpty { BangumiMirrorList.BUNDLED }}" }
    }

    @Serializable
    private class RemoteMirrorList(
        val mirrors: List<String> = emptyList(),
    )

    private companion object {
        /**
         * 清单的下载入口, 按顺序试.
         *
         * jsDelivr 排前面: 它在大陆的可达性一般比 `raw.githubusercontent.com` 好.
         * 两个都不通也没关系 —— 内置清单兜着, 而且真正的逃生口是"自建地址"那一档.
         */
        val LIST_URLS
            get() = currentAniBuildConfig.projectRepository.let { repo ->
                listOf(
                    "https://cdn.jsdelivr.net/gh/$repo@main/bangumi-mirrors.json",
                    "https://raw.githubusercontent.com/$repo/main/bangumi-mirrors.json",
                )
            }

        /** 多久拉一次. 镜像死得快, 但也没必要每次启动都拉. */
        val REFRESH_INTERVAL_MILLIS = 24.hours.inWholeMilliseconds
    }
}
