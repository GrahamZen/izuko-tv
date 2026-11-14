/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import org.openani.mediamp.source.UriMediaData
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 重新解析 [media] 拿一个新地址: 原来解析出来的那个被服务器拒绝了 (带签名的地址会过期, 网盘直链约 6 小时, 有的站点几十分钟).
 * 缓存下载与播放共用; 什么时候来换见 [me.him188.ani.utils.coroutines.RejectionRefresh].
 *
 * 解析得出新地址的只有两类: 网盘 (占位地址, 每次现取直链) 与接口源 ([ResourceLocation.WebVideo.isResolvedByApi]).
 * 搜索结果里直接带直链的资源解析出来还是原来那个, 调用方当成换不了. 要开网页现抓的 ([ResourceLocation.WebVideo]
 * 而不是接口源) 只在 [allowWebPage] 时解析.
 *
 * @param timeout 最多等多久. 等的时候被拒的那个请求停着: 下载可以多等, 播放不行 (原地址多半再试一次就好)
 * @return 解析不了、超时或解析出来的不是地址时 `null`
 */
suspend fun MediaResolver.resolveFreshLink(
    media: Media,
    episode: EpisodeMetadata,
    allowWebPage: Boolean = false,
    timeout: Duration = FRESH_LINK_TIMEOUT,
): UriMediaData? {
    when (val download = media.download) {
        is ResourceLocation.HttpStreamingFile -> {}
        is ResourceLocation.WebVideo -> if (!download.isResolvedByApi && !allowWebPage) return null
        else -> return null
    }
    if (!supports(media)) return null
    return withTimeoutOrNull(timeout) {
        coroutineScope { resolve(media, episode).open(this) }
    } as? UriMediaData
}

/** 网盘要转存时也就几秒; 卡住时别一直占着等它的下载 */
private val FRESH_LINK_TIMEOUT = 1.minutes
