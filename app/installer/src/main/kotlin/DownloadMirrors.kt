/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import org.json.JSONObject

/**
 * GitHub 下载的加速镜像, 与应用内更新共用仓库根目录的 `github-download-mirrors.json` (见 GitHubDownloadMirrors):
 * 镜像死了改那个文件, 助手不用重发. 条目两种写法: 前缀 (GitHub 原地址整个接在后面), 或含 [PLACEHOLDER] 的模板.
 */
object DownloadMirrors {
    const val PLACEHOLDER = "{url}"

    /** 清单取不到时用的, 与 GitHubDownloadMirrors 内置的那份相同. */
    val BUNDLED = listOf("https://gh.xxooo.cf", "https://gh-proxy.com", "https://gh-proxy.org", "https://ghfast.top")

    private const val FILE_NAME = "github-download-mirrors.json"

    /** 仓库里的镜像清单; 各个来源都取不到时用内置的. */
    fun load(repository: String, log: (String) -> Unit): List<String> {
        val urls = listOf("testingcf.jsdelivr.net", "gcore.jsdelivr.net", "cdn.jsdelivr.net")
            .map { "https://$it/gh/$repository@main/$FILE_NAME" } +
            "https://raw.githubusercontent.com/$repository/main/$FILE_NAME"
        for (url in urls) {
            try {
                val array = JSONObject(Http.getText(url, connectTimeoutMs = 5_000, readTimeoutMs = 8_000)).optJSONArray("mirrors")
                val entries = (0 until (array?.length() ?: 0)).mapNotNull { normalize(array!!.optString(it)) }
                if (entries.isNotEmpty()) {
                    log("Mirrors from $url: $entries")
                    return entries
                }
            } catch (e: Exception) {
                log("Mirrors: $url failed (${e.describe()})")
            }
        }
        return BUNDLED
    }

    /** 补上缺的 `https://`、去掉末尾的 `/`; 不像地址的返回 `null`. */
    fun normalize(entry: String): String? {
        val trimmed = entry.trim().trimEnd('/')
        if (trimmed.isEmpty() || ' ' in trimmed) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!withScheme.startsWith("https://") && !withScheme.startsWith("http://")) return null
        return withScheme.takeIf { hostOf(it).contains('.') }
    }

    /** GitHub 原地址 [url] 经镜像 [entry] 的下载地址. */
    fun resolve(entry: String, url: String): String =
        if (PLACEHOLDER in entry) entry.replace(PLACEHOLDER, url) else "$entry/$url"
}
