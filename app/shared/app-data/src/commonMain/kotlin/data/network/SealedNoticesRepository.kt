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
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.JsonFileCache
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 加密的应用内通知: 本仓库 release `notices` 的附件 `notices.txt` (密文, 格式见 [SealedText], 用途 [SealedText.Purpose.NOTICES]),
 * 用编进包里的钥匙解开. 只给装了应用的人看 (换群这类不想公开写在 release 正文里的话), 改了附件当场生效 (`scripts/release-notice.py --sealed`).
 *
 * 明文一行一条 `给哪些版本=那句话` (见 [noticesFor]); 应用把给自己版本的几句与 release 正文「## 通知」里的合在一起显示 (ui-tv 的 `TvWhatsNew`).
 * 附件经 [GitHubDownloadMirrors] 取 (原地址在前, 大陆连不上时走镜像), 每个入口最多等 [SOURCE_TIMEOUT];
 * 取到能解开的就存下, 都取不到用存下的. release 还没建 (原地址 404) 当没有通知. 包里没有钥匙 (没配的构建) 时什么都不做.
 */
class SealedNoticesRepository(
    /** 存最近一次取到的密文. */
    file: SystemPath,
    private val mirrors: GitHubDownloadMirrors,
    /** 惰性取, 构造期不碰 HttpClientProvider (同 [SeriesGraphTableRepository]). */
    private val client: () -> ScopedHttpClient,
    private val key: String = currentAniBuildConfig.appConfigKey,
    private val repository: String = currentAniBuildConfig.projectRepository,
) {
    private val logger = logger<SealedNoticesRepository>()
    private val cache = JsonFileCache(file, SavedSealedNotices.serializer())

    /** 给版本 [version] 的通知, 每句一行 (「标题：说明」); 先去取最新的, 取不到用上次存下的. */
    suspend fun noticesFor(version: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val token = fetch() ?: cache.read()?.token ?: return emptyList()
        val plain = SealedText.open(token, key, SealedText.Purpose.NOTICES) ?: return emptyList()
        return SealedNotices.select(plain, version)
    }

    /** 取到的密文 (能用包里的钥匙解开才算), 并存下; release 还没建时为空串; 都取不到时为 `null`. */
    private suspend fun fetch(): String? {
        val url = "https://github.com/$repository/releases/download/$TAG/$ASSET"
        val failures = mutableListOf<String>()
        for (source in mirrors.sourcesOf(url)) {
            val result = try {
                withTimeoutOrNull(SOURCE_TIMEOUT) {
                    client().use {
                        val response = get(source) { expectSuccess = false }
                        when {
                            response.status.isSuccess() -> response.bodyAsText().trim()
                            // 只信原地址的 404: 镜像出错时也可能回 404
                            response.status == HttpStatusCode.NotFound && source == url -> ""
                            else -> null.also { failures += "$source ${response.status}" }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$source ${e::class.simpleName}"
                null
            }
            if (result == "") {
                logger.info { "sealed notices: no release yet" }
                return ""
            }
            if (result != null && SealedText.open(result, key, SealedText.Purpose.NOTICES) != null) {
                cache.write(SavedSealedNotices(result, currentTimeMillis()))
                logger.info { "sealed notices updated from $source" }
                return result
            }
            if (result != null) failures += "$source unreadable"
        }
        logger.info { "sealed notices: no usable source (${failures.joinToString()})" }
        return null
    }

    private companion object {
        const val TAG = "notices"
        const val ASSET = "notices.txt"
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
