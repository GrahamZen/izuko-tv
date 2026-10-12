/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/** GitHub 上的一个安装包: 原地址, 以及 GitHub 接口给的大小与 SHA-256 (镜像回落时没有). */
data class RemoteFile(val name: String, val url: String, val size: Long?, val sha256: String?)

/**
 * 要装的版本.
 * @property files 候选安装包, 按 [PackageChoice] 的优先顺序
 * @property source 版本号是从哪查到的, 显示在界面上
 */
data class Target(val version: String, val files: List<RemoteFile>, val source: String)

/** 装哪个版本: 正式版, 或测试版 (预发布与正式版里最新的那个). */
enum class ReleaseChannel { STABLE, TEST }

/**
 * 查要装的版本: 正式版频道只看正式版, 测试版频道看最新的那个 (预发布或正式版). 与应用内更新同一套回落 (见 UpdateChecker):
 * 先 GitHub 接口 (资源列表、大小、SHA-256 都有, 顺带记下两个频道各自最新的版本 [latest]); 它连不上 (国内常见,
 * 还有未登录每小时 60 次的限流) 时拿版本号按命名规则合成下载地址: 正式版从 jsDelivr 的版本范围解析或 ghfast 代理的
 * `releases/latest` 跳转, 测试版从 jsDelivr 的版本列表 (只认版本号, 不认测试包的 `test-…` 与 `installer` 这类 tag);
 * 测试版查不到时退回正式版的来源.
 */
class ReleaseLookup(
    private val repository: String,
    private val assetPrefix: String,
    private val log: (String) -> Unit,
) {
    /** GitHub 接口查到的两个频道各自最新的版本与安装包 (有这个架构的安装包的); 接口连不上时为空. */
    val latest = mutableMapOf<ReleaseChannel, Target>()

    /** 查不到时抛 [LookupException], 带上每个来源的原因. */
    fun find(suffixes: List<String>, channel: ReleaseChannel = ReleaseChannel.STABLE): Target {
        val failures = mutableListOf<String>()
        try {
            val target = fromGitHubApi(suffixes, channel)
            if (target != null) return target
            failures += "GitHub: no ${channel.name.lowercase()} release has ${suffixes.joinToString()}"
        } catch (e: Exception) {
            failures += "GitHub: ${e.describe()}"
        }
        if (channel == ReleaseChannel.TEST) {
            try {
                val version = latestOnJsDelivrList()
                if (version != null) return synthesized(version, suffixes, "jsDelivr")
                failures += "jsDelivr list: no version"
            } catch (e: Exception) {
                failures += "jsDelivr list: ${e.describe()}"
            }
        }
        for (host in JSDELIVR_HOSTS) {
            try {
                val version = latestOnJsDelivr(host)
                if (version != null) return synthesized(version, suffixes, "jsDelivr")
                failures += "$host: no version"
            } catch (e: Exception) {
                failures += "$host: ${e.describe()}"
            }
        }
        try {
            val version = latestOnGhfast()
            if (version != null) return synthesized(version, suffixes, "ghfast")
            failures += "ghfast: no version"
        } catch (e: Exception) {
            failures += "ghfast: ${e.describe()}"
        }
        throw LookupException(failures)
    }

    private fun fromGitHubApi(suffixes: List<String>, channel: ReleaseChannel): Target? {
        val body = Http.getText("https://api.github.com/repos/$repository/releases?per_page=30")
        val releases = JSONArray(body)
        var found: Target? = null
        // 按 tag 所指提交的时间倒序: 每个频道第一个满足条件的就是最新的
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val prerelease = release.optBoolean("prerelease")
            val version = release.optString("tag_name").removePrefix("v")
            // 只认发版用的版本号: 发给个别用户的测试包 (test-… / 1.0.6.1-…) 不算
            if (!VERSION.matches(version) || !PackageChoice.isInVersionLine(version)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
            val files = suffixes.mapNotNull { suffix ->
                val name = PackageChoice.assetName(assetPrefix, version, suffix)
                val asset = byName[name] ?: return@mapNotNull null
                RemoteFile(
                    name = name,
                    url = asset.optString("browser_download_url"),
                    size = asset.optLong("size", -1).takeIf { it > 0 },
                    sha256 = asset.optString("digest").takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase(),
                )
            }
            if (files.isEmpty()) {
                log("Release $version has none of $suffixes, skipping")
                continue
            }
            val target = Target(version, files, "GitHub")
            latest.getOrPut(ReleaseChannel.TEST) { target }
            if (!prerelease) latest.getOrPut(ReleaseChannel.STABLE) { target }
            if (found == null && (channel == ReleaseChannel.TEST || !prerelease)) {
                log("GitHub: latest ${channel.name.lowercase()} is $version, files ${files.map { it.name }}")
                found = target
            }
            if (latest.size == ReleaseChannel.entries.size) break
        }
        return found
    }

    /** jsDelivr 列出的 tag 按版本号从新到旧排, 第一个本应用版本线上的就是最新的 (含测试版). */
    private fun latestOnJsDelivrList(): String? {
        val body = Http.getText("https://data.jsdelivr.com/v1/packages/gh/$repository", connectTimeoutMs = 6_000, readTimeoutMs = 10_000)
        val versions = JSONObject(body).optJSONArray("versions") ?: return null
        val version = (0 until versions.length())
            .map { versions.getJSONObject(it).optString("version") }
            .firstOrNull { VERSION.matches(it) && PackageChoice.isInVersionLine(it) }
        log("jsDelivr list: latest is $version")
        return version
    }

    /** jsDelivr 按语义版本范围 (主版本 < 6) 解析出的最新正式版, 在响应头 `x-jsd-version` 里. */
    private fun latestOnJsDelivr(host: String): String? {
        val connection = Http.open(
            "https://$host/gh/$repository@%3C6/$RELEASE_TEMPLATE_PATH",
            connectTimeoutMs = 6_000,
            readTimeoutMs = 10_000,
        )
        try {
            val code = connection.responseCode
            val version = connection.getHeaderField("x-jsd-version")?.removePrefix("v")
            log("jsDelivr $host: status=$code, version=$version")
            return version?.takeIf { code in 200..299 && PackageChoice.isInVersionLine(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** ghfast 代理 `releases/latest` 的跳转: 一路跟到 `/releases/tag/v<版本>` 为止, 不取页面本身. */
    private fun latestOnGhfast(): String? {
        var url = "https://ghfast.top/https://github.com/$repository/releases/latest"
        repeat(MAX_REDIRECTS) {
            TAG_IN_URL.find(url)?.let { match ->
                return match.groupValues[1].takeIf { PackageChoice.isInVersionLine(it) }
            }
            val connection = Http.open(url, connectTimeoutMs = 6_000, readTimeoutMs = 10_000, followRedirects = false)
            try {
                val code = connection.responseCode
                if (code !in 300..399) {
                    log("ghfast: status=$code at $url")
                    return null
                }
                val location = connection.getHeaderField("Location") ?: return null
                url = URL(URL(url), location).toString()
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun synthesized(version: String, suffixes: List<String>, source: String): Target {
        log("$source: picked $version")
        val files = suffixes.map { suffix ->
            val name = PackageChoice.assetName(assetPrefix, version, suffix)
            RemoteFile(name, "https://github.com/$repository/releases/download/v$version/$name", size = null, sha256 = null)
        }
        return Target(version, files, source)
    }

    companion object {
        /** 每个 release 的更新说明模板, 每个 tag 都有, 拿它让 jsDelivr 解析版本. */
        private const val RELEASE_TEMPLATE_PATH = "ci-helper/release-template.md"
        private const val SHA256_PREFIX = "sha256:"
        private const val MAX_REDIRECTS = 5
        private val TAG_IN_URL = Regex("""/releases/tag/v?([^/?#]+)""")

        /** 发版用的 tag 去掉 v 之后的样子: 1.0.8 / 1.0.8-alpha05. */
        private val VERSION = Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""")

        /** 同 UpdateChecker.JSDELIVR_HOSTS: fastly 入口在国内会跳 raw.githubusercontent.com, 不用. */
        private val JSDELIVR_HOSTS = listOf("gcore.jsdelivr.net", "testingcf.jsdelivr.net", "cdn.jsdelivr.net")
    }
}

class LookupException(val failures: List<String>) : IOException(failures.joinToString("; "))

internal fun Throwable.describe(): String = when (this) {
    is HttpStatusException -> "HTTP $code"
    else -> "${this::class.java.simpleName}: ${message.orEmpty()}"
}
