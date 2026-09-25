/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.app.data.models.preference.RepoHostedListCache
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
 * 在本项目仓库根目录维护的一份清单 (Bangumi 镜像、TMDB 图片入口…).
 *
 * 这类地址会死、会搬家, 写死在包里就只能跟着发版; 放在仓库里, 改了 json, 已经装上的包隔天就用上.
 *
 * - 每 [REFRESH_INTERVAL_MILLIS] 拉一次, 下载入口按 [GitHubFileSources] 的顺序试;
 * - 拉到的每一条都过 [Spec.normalize], 认不出的丢掉 —— 清单是远程内容, 里面写着什么不由我们决定;
 * - 都拉不到用上次存下的, 从没拉到过用包里内置的 [Spec.bundled].
 *
 * 文件内容是 `{"<Spec.field>": ["条目", ...]}`.
 */
open class RepoHostedList(
    private val spec: Spec,
    private val cache: Settings<RepoHostedListCache>,
    /**
     * **惰性取, 不能在构造期就要**: 清单会被装在 HttpClient 上的特性用到 (镜像改写、入口回落), 那些特性要在
     * `HttpClientProvider` 建好之前就位 —— 构造期取 client 会在 Koin 里绕成环, 真机上是启动即 StackOverflowError.
     * 清单只在 [refreshIfStale] 里拉, 那时依赖图早就建完了.
     */
    private val client: () -> ScopedHttpClient,
    scope: CoroutineScope,
    private val repository: () -> String = { currentAniBuildConfig.projectRepository },
) {
    class Spec(
        /** 仓库根目录下的文件名. */
        val fileName: String,
        /** json 里放清单的字段. */
        val field: String,
        /** 从没拉到过时用的, 按优先级排. */
        val bundled: List<String>,
        /** 远程来的每一条都过它; 返回 `null` = 认不出, 丢掉. */
        val normalize: (String) -> String?,
    )

    private val logger = logger<RepoHostedList>()

    /** 清单, 按优先级排. 拉到过就用拉到的, 否则用内置那份. */
    val entries: Flow<List<String>> = cache.flow
        .map { cached -> cached.entries.ifEmpty { spec.bundled } }
        .distinctUntilChanged()

    init {
        scope.launch {
            refreshIfStale()
        }
    }

    internal suspend fun refreshIfStale() {
        val cached = cache.flow.first()
        if (cached.entries.isNotEmpty() && currentTimeMillis() - cached.updatedAt < REFRESH_INTERVAL_MILLIS) {
            return
        }
        for (url in GitHubFileSources.urls(repository(), spec.fileName)) {
            val text = try {
                client().use { get(url).bodyAsText() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "${spec.fileName}: $url unreachable (${e::class.simpleName})" }
                continue
            }
            val entries = parse(text)?.mapNotNull(spec.normalize)?.distinct().orEmpty()
            if (entries.isEmpty()) {
                logger.warn { "${spec.fileName} from $url has no usable entry, ignoring" }
                continue
            }
            cache.set(RepoHostedListCache(entries = entries, updatedAt = currentTimeMillis()))
            logger.info { "${spec.fileName} updated from $url: $entries" }
            return
        }
        logger.info { "${spec.fileName}: all sources unreachable, keeping ${cached.entries.ifEmpty { spec.bundled }}" }
    }

    /** 取出 [Spec.field] 里的字符串条目; 格式不对返回 `null`. */
    private fun parse(text: String): List<String>? {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return null
        }
        val array = (root as? JsonObject)?.get(spec.field) as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
    }

    private companion object {
        /** 多久拉一次. 这些地址死得快, 但也没必要每次启动都拉. */
        val REFRESH_INTERVAL_MILLIS = 24.hours.inWholeMilliseconds
    }
}

/**
 * GitHub 仓库里一个文件的下载入口, 按顺序试.
 *
 * jsDelivr 在前: 在中国大陆连得上, `raw.githubusercontent.com` 常常连不上, 放最后兜底.
 * jsDelivr 的三个节点里 testingcf 在前: 推送后主动刷新缓存时, testingcf 与 cdn 刷新即生效, gcore 不认主动刷新,
 * 边缘缓存最长 12 小时才过期; 大陆真机上 testingcf 与 gcore 一样快, cdn 时好时坏 (见更新检查的 `JSDELIVR_HOSTS`).
 */
object GitHubFileSources {
    fun urls(repository: String, path: String, branch: String = "main"): List<String> =
        listOf("testingcf.jsdelivr.net", "gcore.jsdelivr.net", "cdn.jsdelivr.net")
            .map { "https://$it/gh/$repository@$branch/$path" } +
                "https://raw.githubusercontent.com/$repository/$branch/$path"
}
