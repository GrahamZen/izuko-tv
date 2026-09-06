/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.bangumi

import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.next.apis.CharacterBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.CollectionBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.EpisodeBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.MiscBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.PersonBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.SearchBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.TrendingBangumiNextApi
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 直连 Bangumi 的 API 入口, 取代经由 Ani 服务器中转的 `AniApiProvider`.
 *
 * 两个 host 的分工:
 * - [NEXT_BASE_URL] (`next.bgm.tv/p1`) 是主力. 它与 Ani 服务端几乎 1:1, 因为 Ani 服务端本就是照它做的聚合.
 * - [V0_BASE_URL] (`api.bgm.tv/v0`) 只用于 p1 给不了的东西: 搜索 (p1 的搜索只返回 `SlimSubject`, 没有
 *   date/tags/summary) 与自己的用户资料里 p1 不提供的字段.
 *
 * token 不走生成代码的 `ApiClient.setBearerToken`: [ApiInvoker] 每次调用都用借来的 [ScopedHttpClient]
 * 新建一个 Api 实例, 拿不到 token flow. 鉴权统一由 HttpClient 上的 bangumi token feature 注入,
 * 那里同时负责把 token 限制在 `*.bgm.tv` 上.
 */
class BangumiApiProvider(
    @PublishedApi
    internal val client: ScopedHttpClient,
) {
    // next.bgm.tv/p1
    val subjectApi = ApiInvoker(client) { SubjectBangumiNextApi(NEXT_BASE_URL, it) }
    val episodeApi = ApiInvoker(client) { EpisodeBangumiNextApi(NEXT_BASE_URL, it) }
    val collectionApi = ApiInvoker(client) { CollectionBangumiNextApi(NEXT_BASE_URL, it) }
    val characterApi = ApiInvoker(client) { CharacterBangumiNextApi(NEXT_BASE_URL, it) }
    val personApi = ApiInvoker(client) { PersonBangumiNextApi(NEXT_BASE_URL, it) }
    val trendingApi = ApiInvoker(client) { TrendingBangumiNextApi(NEXT_BASE_URL, it) }
    val searchApi = ApiInvoker(client) { SearchBangumiNextApi(NEXT_BASE_URL, it) }
    val miscApi = ApiInvoker(client) { MiscBangumiNextApi(NEXT_BASE_URL, it) }

    // api.bgm.tv/v0
    val v0Api = ApiInvoker(client) { DefaultApi(V0_BASE_URL, it) }

    companion object {
        const val NEXT_BASE_URL = "https://next.bgm.tv"
        const val V0_BASE_URL = "https://api.bgm.tv"
    }
}
