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
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.foundation.AlternativeEndpoints
import me.him188.ani.app.domain.foundation.RepoHostedList
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * TMDB 图片 (背景图、剧照) 的入口.
 *
 * 原站 `image.tmdb.org` 在中国移动的网络上 TLS 握手即被重置 (按 SNI 域名拦; 2026-09 全国拨测, 移动 31 个点只通 3 个),
 * 电信、联通正常. `images.tmdb.org` 是同一个 CDN 拉取区 (BunnyCDN) 挂的另一个官方域名, 有自己的证书, 同一轮拨测移动
 * 通 29 个, 图片与原站逐字节一致. 所以默认先用它, 连不上再用原站.
 *
 * 清单在仓库根目录的 `tmdb-image-hosts.json`: 哪天这两个都不行了, 往里加能用的入口 (把路径放在参数里的代理写成模板,
 * 见 [EndpointUrls]), 不用发版.
 */
class TmdbImageEndpoints(
    selection: Settings<EndpointSelection>,
    listCache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) : AlternativeEndpoints(
    name = "TMDB image",
    canonicalBaseUrl = CANONICAL_BASE_URL,
    probePath = PROBE_PATH,
    selection = selection,
    list = RepoHostedList(LIST, listCache, client, scope),
    scope = scope,
) {
    companion object {
        /** 数据里存的图片地址都以它开头. */
        const val CANONICAL_BASE_URL = "https://image.tmdb.org"

        /**
         * 检测用的图片 (w92 档, 几 KB). 不探裸目录 `t/p/w1280`: 那个路径边缘不缓存, 每次都回源, 实测要 2.5 秒才吐一个 404.
         * 真实图片命中边缘缓存, 快且稳, 顺带验证了图片确实下得下来.
         *
         * 这张图哪天被换掉也不影响判定: 只看能否拿到回应, 404 同样说明入口是通的.
         */
        const val PROBE_PATH = "/t/p/w92/rBOnrVlck7BIlGeWVlzYiZeg4l2.jpg"

        private val LIST = RepoHostedList.Spec(
            fileName = "tmdb-image-hosts.json",
            field = "hosts",
            bundled = listOf("https://images.tmdb.org", "https://image.tmdb.org"),
            normalize = EndpointUrls::normalizeBaseUrl,
        )
    }
}
