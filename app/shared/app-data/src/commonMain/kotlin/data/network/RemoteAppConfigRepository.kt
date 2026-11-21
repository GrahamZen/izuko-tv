/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.platform.RemoteAppConfig
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * 加密的应用配置的最新一份 (交流群、弹弹 play 凭据、TMDB token, 见 [RemoteAppConfig]): 本仓库 `lists` 分支上的 `app-config.txt`
 * (密文, 格式见 [SealedText]), 用编进包里的钥匙 ([appConfigKey][me.him188.ani.app.platform.AniBuildConfig.appConfigKey]) 解开后
 * 交给 [RemoteAppConfig.remote]. 要换哪一项只改那份密文, 不用发版.
 *
 * - 启动时先把存下的那份交出去; 之后每 [CHECK_INTERVAL] 看一眼, 离上次取到超过 [REFRESH_INTERVAL] 才去取;
 * - 入口按 [GitHubFileSources] 的顺序试 (jsDelivr 在前, 大陆连得上), 每个最多等 [SOURCE_TIMEOUT];
 *   都取不到、取到的解不开 (钥匙换过、文件坏了) 就接着用存下的或编包时内置的;
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
                var saved = cache.read()
                saved?.let { publish(it.token) }
                while (true) {
                    if (saved == null || currentTimeMillis() - saved.fetchedAt >= REFRESH_INTERVAL.inWholeMilliseconds) {
                        fetch()?.let { token ->
                            saved = SavedAppConfig(token, currentTimeMillis()).also { cache.write(it) }
                            publish(token)
                        }
                    }
                    delay(CHECK_INTERVAL)
                }
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

    /** 取到的密文 (能用包里的钥匙解开才算); 都取不到时为 `null`. */
    private suspend fun fetch(): String? {
        val failures = mutableListOf<String>()
        for (url in GitHubFileSources.urls(repository, PATH, branch = LISTS_BRANCH)) {
            val token = try {
                withTimeoutOrNull(SOURCE_TIMEOUT) {
                    client().use {
                        val response = get(url) { expectSuccess = false }
                        if (response.status.isSuccess()) response.bodyAsText().trim() else null.also { failures += "$url ${response.status}" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$url ${e::class.simpleName}"
                null
            }
            if (token != null && SealedAppConfig.open(token, key) != null) {
                logger.info { "app config updated from $url" }
                return token
            }
            if (token != null) failures += "$url unreadable"
        }
        logger.info { "app config: no usable source (${failures.joinToString()})" }
        return null
    }

    private companion object {
        /** 放密文的分支: 与代码的 main 分开, 改配置只推这个分支. */
        const val LISTS_BRANCH = "lists"
        const val PATH = "app-config.txt"
        val REFRESH_INTERVAL = 24.hours
        val CHECK_INTERVAL = 1.hours
        val SOURCE_TIMEOUT = 10.seconds
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
