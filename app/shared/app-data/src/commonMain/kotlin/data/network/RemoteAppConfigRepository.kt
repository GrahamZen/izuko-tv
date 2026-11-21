/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.domain.foundation.RemoteFetch
import me.him188.ani.app.domain.foundation.RemoteRefresh
import me.him188.ani.app.platform.RemoteAppConfig
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.hours

/**
 * 加密的应用配置的最新一份 (交流群、弹弹 play 凭据、TMDB token, 见 [RemoteAppConfig]): 本仓库清单分支上的 `app-config.txt`
 * (密文, 格式见 [SealedText]), 用编进包里的钥匙 ([appConfigKey][me.him188.ani.app.platform.AniBuildConfig.appConfigKey]) 解开后
 * 交给 [RemoteAppConfig.remote]. 要换哪一项只改那份密文, 不用发版.
 *
 * - 启动时先把存下的那份交出去; 什么时候取见 [RemoteRefresh] (过期 [REFRESH_INTERVAL]、每 [CHECK_INTERVAL] 看一眼,
 *   回到前台离上次超过 [FOREGROUND_INTERVAL] 也取 —— 换掉的凭据尽快送到正在用的电视);
 * - 入口按 [GitHubFileSources] 的顺序试 ([RemoteFetch]); 都取不到、取到的解不开 (钥匙换过、文件坏了) 就接着用存下的或编包时内置的;
 * - 包里没有钥匙 (没配的构建) 时什么都不做.
 */
class RemoteAppConfigRepository(
    /** 存最近一次取到的密文 (丢了只是再取一次). */
    file: SystemPath,
    /** 惰性取, 构造期不碰 HttpClientProvider (同 [SeriesGraphTableRepository]). */
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
    private val key: String = currentAniBuildConfig.appConfigKey,
    private val repository: String = currentAniBuildConfig.projectRepository,
) {
    private val logger = logger<RemoteAppConfigRepository>()
    private val cache = JsonFileCache(file, SavedAppConfig.serializer())

    init {
        if (key.isNotEmpty()) {
            scope.launch {
                // 先交出存下的那份, 再开始取: 反过来的话新取到的可能被后交的旧的盖掉
                cache.read()?.let { publish(it.token) }
                RemoteRefresh(
                    name = PATH,
                    scope = scope,
                    staleAfter = REFRESH_INTERVAL,
                    checkEvery = CHECK_INTERVAL,
                    foregroundAfter = FOREGROUND_INTERVAL,
                    lastFetchedAt = { cache.read()?.fetchedAt },
                    fetch = ::fetch,
                )
            }
        }
    }

    private fun publish(token: String) {
        val config = SealedAppConfig.open(token, key)
        if (config == null) {
            logger.warn { "app config: saved copy can't be opened with the bundled key, keeping the bundled values" }
            return
        }
        RemoteAppConfig.remote.value = config
    }

    /** 取一份 (能用包里的钥匙解开才算), 存下并交出去. */
    private suspend fun fetch(): Boolean {
        val fetched = RemoteFetch.firstSuccessful(PATH, GitHubFileSources.urls(repository, PATH, GitHubFileSources.LISTS_BRANCH), client) { body ->
            body.trim().takeIf { SealedAppConfig.open(it, key) != null }
        } ?: return false
        cache.write(SavedAppConfig(fetched.value, currentTimeMillis()))
        publish(fetched.value)
        logger.info { "app config updated from ${fetched.source}" }
        return true
    }

    private companion object {
        const val PATH = "app-config.txt"
        val REFRESH_INTERVAL = 24.hours
        val CHECK_INTERVAL = 1.hours
        val FOREGROUND_INTERVAL = 1.hours
    }
}

@Serializable
private class SavedAppConfig(
    val token: String,
    val fetchedAt: Long,
)

/** 应用配置的明文是逐行的 `名字=值`; 密文格式见 [SealedText]. */
internal object SealedAppConfig {
    /** 解开 [token] 得到各项; 格式不对、钥匙不对、被改过时为 `null`. */
    fun open(token: String, key: String): Map<String, String>? =
        SealedText.open(token, key, SealedText.Purpose.APP_CONFIG)?.let { SealedText.lines(it).toMap() }
}
