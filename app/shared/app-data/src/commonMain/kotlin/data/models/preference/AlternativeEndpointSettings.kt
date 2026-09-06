/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 一项有好几个可互换入口的服务 (如 TMDB 图片) 用哪个入口.
 *
 * 候选入口是仓库里维护的清单 (见 `RepoHostedList`), 请求时怎么换、怎么回落见 `AlternativeEndpointsFeatureHandler`.
 */
@Serializable
data class EndpointSelection(
    val mode: EndpointSelectionMode = EndpointSelectionMode.AUTO,
    /**
     * [EndpointSelectionMode.FIXED] 下用的入口: 清单里的某一条 (已归一化).
     * 清单后来去掉了它也照用 —— 那是用户明确选的.
     */
    val fixedBaseUrl: String = "",
    /**
     * [EndpointSelectionMode.CUSTOM] 下用的入口. 存原样输入, 归一化交给 [EndpointUrls.normalizeBaseUrl]:
     * 存归一化后的值会让用户看不懂自己填的东西去哪了.
     */
    val customBaseUrl: String = "",
    @Suppress("PropertyName") @Transient val _placeHolder: Int = 0,
) {
    /**
     * 按这个选择依次试的入口.
     *
     * 选定的入口认不出 (比如选了「自定义」还没填) 时按 [EndpointSelectionMode.AUTO] 处理: 一个没填好的输入框
     * 不该让整项服务停摆.
     */
    fun baseUrls(candidates: List<String>): List<String> {
        val chosen = when (mode) {
            EndpointSelectionMode.AUTO -> null
            EndpointSelectionMode.FIXED -> EndpointUrls.normalizeBaseUrl(fixedBaseUrl)
            EndpointSelectionMode.CUSTOM -> EndpointUrls.normalizeBaseUrl(customBaseUrl)
        }
        return chosen?.let(::listOf) ?: candidates
    }

    companion object {
        val Default = EndpointSelection()
    }
}

enum class EndpointSelectionMode {
    /** 按清单顺序用第一个连得上的; 连不上换下一个, 记住通的那个. */
    AUTO,

    /** 只用清单里选定的那一个 ([EndpointSelection.fixedBaseUrl]). */
    FIXED,

    /** 只用用户自己填的地址 ([EndpointSelection.customBaseUrl]). */
    CUSTOM,
}

/**
 * 仓库维护的清单 (见 `RepoHostedList`) 拉到的本地缓存.
 *
 * 与用户的选择分开存: 那是用户的设置, 这是缓存 —— 混在一起会让「备份/恢复设置」把一份过期的清单也搬过去.
 */
@Serializable
data class RepoHostedListCache(
    /** 已归一化的条目, 按优先级排. 空 = 还没拉到过, 用内置那份. */
    val entries: List<String> = emptyList(),
    val updatedAt: Long = 0,
    @Suppress("PropertyName") @Transient val _placeHolder: Int = 0,
) {
    companion object {
        val Default = RepoHostedListCache()
    }
}

/**
 * 可互换入口的地址 (见 [EndpointSelection]): 两种写法.
 *
 * - **入口地址** `https://域名[:端口][/前缀]`: 原站的路径直接接在后面, 如 `https://images.tmdb.org` +
 *   `/t/p/w1280/a.jpg`.
 * - **模板**: 含一个 [PATH_PLACEHOLDER], 原站的路径换进去, 给把路径放在参数里的代理用, 如
 *   `https://wsrv.nl/?url=image.tmdb.org{path}`.
 */
object EndpointUrls {
    const val PATH_PLACEHOLDER = "{path}"

    /**
     * 归一化; 认不出返回 `null`. 入口地址归一成没有结尾斜杠的形状, 模板只把协议与域名转成小写.
     *
     * 接受粘进来的各种形状: 不写协议 (按 https)、结尾斜杠、前后空白. 不接受账号密码与片段; 入口地址也不能带查询串
     * (原站路径要直接接在后面, 要放进参数就写成模板).
     */
    fun normalizeBaseUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in s) s else "https://$s"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = withScheme.substringAfter("://")
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
        if (!isValidAuthority(authority) || '#' in rest) return null
        val tail = rest.substring(authority.length)
        val placeholders = tail.windowed(PATH_PLACEHOLDER.length).count { it == PATH_PLACEHOLDER }
        return when {
            placeholders == 1 -> "$scheme://$authority$tail"
            placeholders > 1 || '?' in tail || '{' in tail || '}' in tail -> null
            else -> "$scheme://$authority${tail.trimEnd('/')}"
        }
    }

    private fun isValidAuthority(authority: String): Boolean {
        if ('@' in authority) return false
        val host = authority.substringBefore(':')
        if (host.isEmpty() || '.' !in host || host.startsWith('.') || host.endsWith('.')) return false
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return false
        if (':' in authority) {
            val port = authority.substringAfter(':').toIntOrNull() ?: return false
            if (port !in 1..65535) return false
        }
        return true
    }

    /**
     * 原站的 [path] (以 `/` 开头, 可以带查询串) 在这个入口上的完整地址.
     *
     * @param entry 已归一化的入口地址或模板
     */
    fun resolve(entry: String, path: String): String =
        if (PATH_PLACEHOLDER in entry) entry.replace(PATH_PLACEHOLDER, path) else entry + path

    /** 给人看的简写: 入口地址去掉 `https://`; 模板只显示域名. */
    fun displayName(entry: String): String {
        val withoutScheme = entry.removePrefix("https://")
        return if (PATH_PLACEHOLDER in entry) {
            withoutScheme.takeWhile { it != '/' && it != '?' }
        } else {
            withoutScheme
        }
    }
}
