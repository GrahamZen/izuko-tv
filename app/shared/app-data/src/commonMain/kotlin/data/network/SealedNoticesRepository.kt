/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.domain.foundation.RemoteFetch
import me.him188.ani.app.domain.foundation.RemoteRefresh
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 加密的应用内通知: 本仓库 release `notices` 的附件 `notices.txt` (密文, 格式见 [SealedText], 用途 [SealedText.Purpose.NOTICES]),
 * 用编进包里的钥匙解开. 只给装了应用的人看, 改了附件不用发版 (`scripts/release-notice.py --sealed`).
 *
 * 明文一行一条 `给哪些版本=那句话` (见 [SealedNotices.select]); 应用只读这一种通知 (ui-tv 的 `TvWhatsNew`).
 * - 启动时先读存下的那份; 什么时候取见 [RemoteRefresh]: 离上次取到超过 [REFRESH_INTERVAL] 就取 (每 [CHECK_INTERVAL] 看一眼,
 *   回到前台、冷启动时也看) —— 发了通知, 开着的电视一小时左右就收到 (同检查更新的频率);
 * - 附件经 [GitHubDownloadMirrors] 取 (原地址在前, 大陆连不上时走镜像), 每个入口最多等 [SOURCE_TIMEOUT];
 *   取到能解开的就存下, 都取不到用存下的; release 还没建 (原地址 404) 当没有通知. 包里没有钥匙 (没配的构建) 时一直没有.
 */
class SealedNoticesRepository(
    /** 存最近一次取到的密文. */
    file: SystemPath,
    private val mirrors: GitHubDownloadMirrors,
    /** 惰性取, 构造期不碰 HttpClientProvider (同 [SeriesGraphTableRepository]). */
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
    private val key: String = currentAniBuildConfig.appConfigKey,
    private val repository: String = currentAniBuildConfig.projectRepository,
) {
    private val logger = logger<SealedNoticesRepository>()
    private val cache = JsonFileCache(file, SavedSealedNotices.serializer())

    /** 解开的明文; 还没读到存下的、也还没取到时为 null; 没有通知是空串. */
    private val plain = MutableStateFlow<String?>(null)
    private val refresh = CompletableDeferred<RemoteRefresh?>()

    init {
        if (key.isEmpty()) {
            plain.value = ""
            refresh.complete(null)
        } else {
            scope.launch {
                // 先读存下的, 再开始取: 反过来的话新取到的可能被后读的旧的盖掉
                cache.read()?.let { saved -> open(saved.token)?.let { plain.value = it } }
                refresh.complete(
                    RemoteRefresh(
                        name = ASSET,
                        scope = scope,
                        staleAfter = REFRESH_INTERVAL,
                        checkEvery = CHECK_INTERVAL,
                        foregroundAfter = REFRESH_INTERVAL,
                        lastFetchedAt = { cache.read()?.fetchedAt },
                        fetch = ::fetch,
                    ),
                )
            }
        }
    }

    /** 给版本 [version] 的通知, 每句一行 (「标题：说明」); 有了就给, 之后取到新的再给. */
    fun noticesFor(version: String): Flow<List<String>> =
        plain.filterNotNull().map { SealedNotices.select(it, version) }.distinctUntilChanged()

    /** 冷启动用: 等建出来后的第一次检查做完 (取了或不用取), 最多 [wait], 再给现在的那份. */
    suspend fun currentFor(version: String, wait: Duration): List<String> {
        withTimeoutOrNull(wait) { refresh.await()?.awaitFirstCheck() }
        return SealedNotices.select(plain.value.orEmpty(), version)
    }

    private fun open(token: String): String? = if (token.isEmpty()) "" else SealedText.open(token, key, SealedText.Purpose.NOTICES)

    /** 取一份 (能用包里的钥匙解开才算; release 还没建算「没有通知」), 存下并交出去. */
    private suspend fun fetch(): Boolean {
        val url = GitHubFileSources.releaseAsset(repository, TAG, ASSET)
        val fetched = RemoteFetch.first(ASSET, mirrors.sourcesOf(url), client, SOURCE_TIMEOUT) { source, status, body ->
            when {
                status.isSuccess() -> body.trim().takeIf { open(it) != null }
                // 只信原地址的 404: 镜像出错时也可能回 404
                status == HttpStatusCode.NotFound && source == url -> ""
                else -> null
            }
        } ?: return false
        cache.write(SavedSealedNotices(fetched.value, currentTimeMillis()))
        plain.value = open(fetched.value)
        logger.info { if (fetched.value.isEmpty()) "sealed notices: no release yet" else "sealed notices updated from ${fetched.source}" }
        return true
    }

    private companion object {
        const val TAG = "notices"
        const val ASSET = "notices.txt"
        val REFRESH_INTERVAL = 1.hours

        /** 多久看一眼过没过期: 只读本地存的那份的时间, 不发请求. */
        val CHECK_INTERVAL = 10.minutes
        val SOURCE_TIMEOUT = 8.seconds
    }
}

@Serializable
private class SavedSealedNotices(
    val token: String,
    val fetchedAt: Long,
)

internal object SealedNotices {
    /**
     * 明文里给 [version] 的几句, 照原文顺序. 每行 `给哪些版本=那句话`:
     * `*` 给所有版本; `1.0.9` 只给这一版; 以 `*` 结尾的是前缀, 如 `1.0.9*` 给 1.0.9 和它的各个测试版 (1.0.9-alpha01…).
     */
    fun select(plain: String, version: String): List<String> = SealedText.lines(plain)
        .filter { (target, text) -> text.isNotEmpty() && matches(target, version) }
        .map { it.second }
        .distinct()

    private fun matches(target: String, version: String): Boolean = when {
        target == "*" -> true
        target.endsWith("*") -> version.startsWith(target.dropLast(1))
        else -> target == version
    }
}
