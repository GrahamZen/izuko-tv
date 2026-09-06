/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.platform.getAniUserAgent
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 各版本更新说明的本地副本, 给「本版新功能」页用: 应用内更新 (电视上的提示与 Web 控制台) 下载安装包时存下目标版本的说明,
 * 装好后第一次打开不用等网络. 一个版本一个文件, 放在更新目录 ([UpdateManager.rootDir]) 的 `notes/` 下.
 */
class ReleaseNotesStore(private val dir: SystemPath) {
    suspend fun put(version: String, body: String) {
        val file = fileOf(version) ?: return
        withContext(Dispatchers.IO) {
            dir.createDirectories()
            file.writeText(body)
        }
    }

    suspend fun get(version: String): String? {
        val file = fileOf(version) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { file.takeIf { it.exists() }?.readText() }
                .onFailure { logger.warn(it) { "Failed to read release notes of $version" } }
                .getOrNull()
        }
    }

    /** 存下 [version] 里各版的说明 (下载安装包时调). 写不进去只记日志: 装好后还能去网上取. */
    suspend fun save(version: NewVersion) {
        for (changelog in version.changelogs) {
            runCatching { put(changelog.version, changelog.detailedChanges) }
                .onFailure { logger.warn(it) { "Failed to save release notes of ${changelog.version}" } }
        }
    }

    /** 存着说明的版本. */
    suspend fun versions(): List<String> = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext emptyList()
        dir.list().map { it.inSystem.name }.filter { it.endsWith(NOTES_SUFFIX) }.map { it.removeSuffix(NOTES_SUFFIX) }
    }

    /** [current] 的页面要的几版 (见 [releasesToShow]) 存着的说明, 新的在前; 连 [current] 自己都没存时返回 `null`. */
    suspend fun forRelease(current: String): List<Pair<String, String>>? {
        val versions = releasesToShow(versions(), current)
        if (current !in versions) return null
        return versions.mapNotNull { version -> get(version)?.let { version to it } }
    }

    /** 只留 [current] 的页面要的几版 (见 [releasesToShow]) 与比它新的 (下载了还没装的), 其余删掉. */
    suspend fun retainFor(current: String) = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext
        for (entry in dir.list()) {
            val file = entry.inSystem
            val version = file.name.removeSuffix(NOTES_SUFFIX)
            if (isShownWith(version, current) || isNewerVersion(version, current)) continue
            runCatching { file.delete() }
        }
    }

    private fun fileOf(version: String): SystemPath? =
        if (VERSION_FILE_NAME.matches(version)) dir.resolve(version + NOTES_SUFFIX) else null

    private companion object {
        private val logger = logger<ReleaseNotesStore>()
        private const val NOTES_SUFFIX = ".md"
        private val VERSION_FILE_NAME = Regex("[0-9A-Za-z._-]+")
    }
}

fun UpdateManager.releaseNotesStore(): ReleaseNotesStore = ReleaseNotesStore(rootDir.resolve("notes"))

/** 去网上取说明的结果, 见 [ReleaseNotesFetcher]. */
sealed interface ReleaseNotesAnswer {
    /** 版本号 → release body, 新的在前. */
    class Found(val notes: List<Pair<String, String>>) : ReleaseNotesAnswer

    /** 问到了, 但没有这一版 (自己编的包、删掉的 release). */
    data object Missing : ReleaseNotesAnswer

    /** 都连不上. */
    data object Unreachable : ReleaseNotesAnswer
}

/**
 * 去网上取更新说明: 先 GitHub 接口 (一次拿到最近几十个 release, 测试版要的同一版本号的几版都在里面), 连不上再从 jsDelivr 取那个 tag 下的
 * 说明模板 (只有这一版; 与检查更新的镜像回落同一套入口, 见 [JSDELIVR_HOSTS]).
 */
class ReleaseNotesFetcher(private val client: ScopedHttpClient) {
    /** [version] 的页面要的几版 (见 [releasesToShow]); GitHub 连不上时只有 [version] 自己. */
    suspend fun forRelease(version: String): ReleaseNotesAnswer {
        val releases = try {
            client.use {
                get("https://api.github.com/repos/$FORK_OWNER/$FORK_REPO/releases") {
                    parameter("per_page", 30)
                    header(HttpHeaders.UserAgent, getAniUserAgent())
                    notesTimeout()
                }.bodyAsText()
            }.let { json.decodeFromString<List<GitHubRelease>>(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "Release notes from GitHub failed: ${e::class.simpleName}: ${e.message}, trying jsDelivr" }
            return fromJsDelivr(version)
        }
        val bodies = releases.filter { !it.draft }.associate { it.tagName.removePrefix("v") to it.body }
        val versions = releasesToShow(bodies.keys, version)
        logger.info { "Release notes from GitHub for $version: $versions" }
        if (version !in versions) return ReleaseNotesAnswer.Missing
        return ReleaseNotesAnswer.Found(versions.map { it to bodies.getValue(it) })
    }

    /**
     * 只取 [version] 这一版.
     *
     * @param withMirrors GitHub 连不上时要不要再试 jsDelivr. 那边是 tag 下的模板, 发版后在 GitHub 上补的通知
     * ([ReleaseHighlights.notice]) 不在里面, 只为查通知时不必再试.
     */
    suspend fun of(version: String, withMirrors: Boolean = true): ReleaseNotesAnswer {
        val fromGitHub = try {
            client.use {
                val response = get("https://api.github.com/repos/$FORK_OWNER/$FORK_REPO/releases/tags/v$version") {
                    header(HttpHeaders.UserAgent, getAniUserAgent())
                    expectSuccess = false
                    notesTimeout()
                }
                when {
                    response.status == HttpStatusCode.NotFound -> ReleaseNotesAnswer.Missing
                    response.status.isSuccess() -> ReleaseNotesAnswer.Found(
                        listOf(version to json.decodeFromString<GitHubRelease>(response.bodyAsText()).body),
                    )

                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "Release notes of $version from GitHub failed: ${e::class.simpleName}: ${e.message}" }
            null
        }
        return fromGitHub ?: if (withMirrors) fromJsDelivr(version) else ReleaseNotesAnswer.Unreachable
    }

    private suspend fun fromJsDelivr(version: String): ReleaseNotesAnswer {
        var missing = false
        for (host in JSDELIVR_HOSTS) {
            try {
                val body = client.use {
                    val response = get("https://$host/gh/$FORK_OWNER/$FORK_REPO@v$version/$RELEASE_TEMPLATE_PATH") {
                        expectSuccess = false
                        notesTimeout()
                    }
                    when {
                        response.status.isSuccess() -> response.bodyAsText()
                        else -> {
                            if (response.status == HttpStatusCode.NotFound) missing = true
                            logger.info { "Release notes of $version from jsDelivr $host: ${response.status.value}" }
                            null
                        }
                    }
                }
                if (body != null) return ReleaseNotesAnswer.Found(listOf(version to body.withReleaseMacros(version)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "Release notes of $version from jsDelivr $host failed: ${e::class.simpleName}: ${e.message}" }
            }
        }
        return if (missing) ReleaseNotesAnswer.Missing else ReleaseNotesAnswer.Unreachable
    }

    /** 模板里的变量, 同 fork-release.yml 生成 release body 时的替换. */
    private fun String.withReleaseMacros(version: String) = this
        .replace("\$GIT_TAG", "v$version")
        .replace("\$TAG_VERSION", version)
        .replace("\$REPO_OWNER", FORK_OWNER)
        .replace("\$ASSET_PREFIX", currentAniBuildConfig.updateAssetPrefix)

    private fun HttpRequestBuilder.notesTimeout() {
        timeout {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }

    private companion object {
        private val logger = logger<ReleaseNotesFetcher>()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
