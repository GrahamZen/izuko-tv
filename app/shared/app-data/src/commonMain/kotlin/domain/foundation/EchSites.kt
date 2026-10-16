/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.domain.session.auth.BangumiOAuthRelay

/**
 * 走加密直连 (ECH) 的请求改写到哪个域名: 只在本机认得的域名 (`.ech.invalid` 之下), [BangumiEchTransport] 见到就换回真实域名, 连 Cloudflare 的地址、
 * 把真实域名藏进 ECH 握手. Cloudflare 上的站共用一组地址 ([BangumiEchAddressList]) 与一份 ECH 配置 ([BangumiEchConfig]), 所以不止 bangumi:
 * 反馈中转 (Cloudflare Worker, workers.dev 在大陆连不上) 也能这样连.
 *
 * - bangumi 用线路自己的域名根 ([BangumiRoute.Ech.HOST_ROOT], `api.bgm.tv` → `api.bgm-tv.ech.invalid`), 由调度改写 (见 [BangumiRoutingFeature]);
 * - 其余的站在真实域名后面接 [SUFFIX] (`x.workers.dev` → `x.workers.dev.ech.invalid`), 由用到的地方自己改写 (见 [echUrlOf]).
 *   不在 Cloudflare 上的站这样连只会握手失败, 不会以明文发到别处.
 */
object EchSites {
    const val SUFFIX = ".ech.invalid"

    /** [host] 在加密直连上的域名. */
    fun echHostOf(host: String): String = BangumiRoute.Ech.hostFor(host) ?: (host + SUFFIX)

    /** [echHostOf] 的反向; [host] 不是加密直连的域名时为 `null`. */
    fun originHostOf(host: String): String? =
        BangumiRoute.Ech.originHostOf(host)
            ?: host.removeSuffix(SUFFIX).takeIf { host.endsWith(SUFFIX) && '.' in it }

    /** [url] 改到加密直连上; 不是 https 地址时为 `null` (加密直连只有 TLS). */
    fun echUrlOf(url: String): String? {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return null
        if (parsed.protocol.name != "https") return null
        return URLBuilder(parsed).apply { host = echHostOf(parsed.host) }.buildString()
    }

    /** debug 包里加密直连第一次用到时自检的站 (见 ConscryptEchTransport): bangumi 与反馈中转. */
    val selfCheckHosts: List<String> = listOfNotNull(
        "api.${BangumiMirrorHosts.ORIGIN_ROOT}",
        runCatching { Url(BangumiOAuthRelay.CALLBACK_URL).host }.getOrNull(),
    )
}
