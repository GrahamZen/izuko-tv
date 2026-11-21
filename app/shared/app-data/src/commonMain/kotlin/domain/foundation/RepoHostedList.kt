/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.hours

/**
 * 在仓库里维护的一份清单 (Bangumi 镜像、TMDB 图片入口…): 本项目仓库的放在清单分支 [GitHubFileSources.LISTS_BRANCH] 的根目录
 * (改清单只推那个分支, 不碰代码的 main).
 *
 * 这类地址会死、会搬家, 写死在包里就只能跟着发版; 放在仓库里, 改了 json, 已经装上的包隔天就用上.
 *
 * - 什么时候拉见 [RemoteRefresh] (每 [REFRESH_INTERVAL] 拉一次, 每 [CHECK_INTERVAL] 看一眼过期没有, 网络体检时不管过没过期都拉 [refreshNow]);
 *   入口按 [GitHubFileSources] 的顺序试 ([RemoteFetch]);
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
    /** 清单在哪个分支; 别的仓库 (如对应表仓库的反馈入口) 放在 main. */
    private val branch: String = GitHubFileSources.LISTS_BRANCH,
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

    /** 仓库根目录下的文件名. */
    val fileName: String get() = spec.fileName

    private val refresh = RemoteRefresh(
        name = spec.fileName,
        scope = scope,
        staleAfter = REFRESH_INTERVAL,
        checkEvery = CHECK_INTERVAL,
        lastFetchedAt = { cache.flow.first().takeIf { it.entries.isNotEmpty() }?.updatedAt },
        fetch = ::fetch,
    )

    init {
        registry.update { it + this }
    }

    /** 过期了 (或从没拉到过) 才拉; 等着清单的地方 (清单还空着时) 用它: 先等建出来后的第一次检查, 还是空的再拉一次. */
    internal suspend fun refreshIfStale() {
        refresh.awaitFirstCheck()
        refresh.refreshIfStale()
    }

    /** 不管多久前拉过, 现在就拉一次 (网络体检用: 出了问题的人马上用上仓库里最新的清单). 拉到了返回 `true`. */
    suspend fun refreshNow(): Boolean = refresh.refreshNow()

    /** 现在用的是哪份: 条目数、是不是包里内置的、多久前拉到的 (网络体检报告用). */
    suspend fun state(): State {
        val cached = cache.flow.first()
        return State(
            fileName = spec.fileName,
            entries = cached.entries.ifEmpty { spec.bundled }.size,
            bundled = cached.entries.isEmpty(),
            updatedAgoMillis = cached.updatedAt.takeIf { cached.entries.isNotEmpty() }?.let { currentTimeMillis() - it },
        )
    }

    class State(val fileName: String, val entries: Int, val bundled: Boolean, val updatedAgoMillis: Long?)

    private suspend fun fetch(): Boolean {
        // 某个入口拿到的不是这份清单 (错误页、格式不对、一条都认不出) 就试下一个
        val fetched = RemoteFetch.firstSuccessful(spec.fileName, GitHubFileSources.urls(repository(), spec.fileName, branch), client) { text ->
            parse(text)?.mapNotNull(spec.normalize)?.distinct()?.takeIf { it.isNotEmpty() }
        }
        if (fetched == null) {
            logger.info { "${spec.fileName}: keeping ${cache.flow.first().entries.ifEmpty { spec.bundled }}" }
            return false
        }
        cache.set(RepoHostedListCache(entries = fetched.value, updatedAt = currentTimeMillis()))
        logger.info { "${spec.fileName} updated from ${fetched.source}: ${fetched.value}" }
        return true
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

    companion object {
        private val registry = MutableStateFlow<List<RepoHostedList>>(emptyList())

        /** 这个进程里建过的清单 (网络体检先把它们都拉一遍). */
        val created: List<RepoHostedList> get() = registry.value

        /** 多久拉一次. 这些地址死得快, 但也没必要每次启动都拉. */
        private val REFRESH_INTERVAL = 24.hours

        /** 隔多久看一眼过期没有 (只读本地缓存的时间, 过期了才去拉). */
        private val CHECK_INTERVAL = 1.hours
    }
}

/**
 * GitHub 上的文件从哪些入口取, 应用里只有这一份 (检查更新、本版说明、清单、数据表都按它).
 *
 * 仓库文件 ([urls]): jsDelivr 在前 —— 在中国大陆连得上, `raw.githubusercontent.com` 常常连不上, 放最后兜底.
 * jsDelivr 的三个节点 ([JSDELIVR_HOSTS]): 大陆真机上 testingcf 与 gcore 一样快, cdn 时好时坏 (国内有时 301 到 raw, 白等 20 秒), 放最后;
 * 推送后主动刷新缓存 (purge) 只有 cdn 认, testingcf 与 gcore 的边缘缓存最长 12 小时才过期 —— 要马上生效的不放仓库文件, 放 release 附件.
 * release 附件 ([releaseAsset]) 没有 jsDelivr 入口, 走原地址与下载镜像 (`GitHubDownloadMirrors.sourcesOf`).
 */
object GitHubFileSources {
    val JSDELIVR_HOSTS = listOf("testingcf.jsdelivr.net", "gcore.jsdelivr.net", "cdn.jsdelivr.net")

    /** 本项目仓库里放清单类文件的分支 (只有清单、加密配置这些, 与代码的 main 分开: 改清单只推它). */
    const val LISTS_BRANCH = "lists"

    fun urls(repository: String, path: String, branch: String = "main"): List<String> =
        JSDELIVR_HOSTS.map { "https://$it/gh/$repository@$branch/$path" } +
            "https://raw.githubusercontent.com/$repository/$branch/$path"

    /** release [tag] 的附件 [name] 的原地址 (镜像地址由 `GitHubDownloadMirrors.sourcesOf` 接在后面). */
    fun releaseAsset(repository: String, tag: String, name: String): String =
        "https://github.com/$repository/releases/download/$tag/$name"
}
