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
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.foundation.RepoHostedList
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * GitHub release 下载 (应用内更新的安装包) 的加速镜像.
 *
 * 大陆常见 api.github.com 与 jsDelivr 能通、github.com 的下载不通或极慢. 公共加速站存活期短, 各地、各运营商通不通也不一样
 * (2026-09 一位测试者那里 ghfast.top 与 github.com 都连不上, 清单里另外几个通), 所以清单放在仓库根目录的
 * `github-download-mirrors.json`, 死一个换一个不用发版; 下载时原地址与所有镜像一起试, 用最快的那个
 * (见 `DefaultFileDownloader`).
 *
 * 条目两种写法:
 * - 前缀 `https://域名[/前缀]`: 原地址整个接在后面, 如 `https://gh-proxy.com/https://github.com/…`;
 * - 含 [URL_PLACEHOLDER] 的模板: 原地址换进去, 给把地址放在参数里的站用.
 */
class GitHubDownloadMirrors(
    listCache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) {
    private val list = RepoHostedList(SPEC, listCache, client, scope)

    /** GitHub 上的 [url] 的全部来源: 原地址在前, 然后按清单顺序各个镜像. */
    suspend fun sourcesOf(url: String): List<String> =
        listOf(url) + list.entries.first().map { resolve(it, url) }

    companion object {
        const val URL_PLACEHOLDER = "{url}"

        /** 归一化清单条目, 规则同 [EndpointUrls.normalizeBaseUrl]; 认不出返回 `null`. */
        fun normalize(input: String): String? =
            if (URL_PLACEHOLDER in input) {
                EndpointUrls.normalizeBaseUrl(input.replace(URL_PLACEHOLDER, EndpointUrls.PATH_PLACEHOLDER))
                    ?.replace(EndpointUrls.PATH_PLACEHOLDER, URL_PLACEHOLDER)
            } else {
                EndpointUrls.normalizeBaseUrl(input)
            }

        /** 原地址 [url] 经镜像 [entry] (已归一化) 的下载地址. */
        fun resolve(entry: String, url: String): String =
            if (URL_PLACEHOLDER in entry) entry.replace(URL_PLACEHOLDER, url) else "$entry/$url"

        private val SPEC = RepoHostedList.Spec(
            fileName = "github-download-mirrors.json",
            field = "mirrors",
            // 2026-09-26 大陆测试者实测能用的三个 (从快到慢), 加上之前另一位测试者那里通的 ghfast
            bundled = listOf(
                "https://gh.xxooo.cf",
                "https://gh-proxy.com",
                "https://gh-proxy.org",
                "https://ghfast.top",
            ),
            normalize = ::normalize,
        )
    }
}
