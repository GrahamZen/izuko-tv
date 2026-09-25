/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * 镜像清单: 仓库根目录的 `bangumi-mirrors.json`, 内置一份兜底 (见 [RepoHostedList]).
 *
 * **为什么非得能远程更新**: 镜像域名的死亡率极高 —— 2026 年 5~8 月之间 `bgmmi.anibt.net` 与
 * `bangumi.rdd.moe` 停服、`bangumi.one` 被 TLS 阻断、`bangumi.lol` 被人拿下, `bangumi.pro` 又搬到了 `bangumi.vip`.
 * 发一版写死一个域名, 等它死了整个功能就跟着死, 而用户没法自己救.
 *
 * 清单本身也要能在大陆下载到, 都下载不到就用内置那份 —— 而**「自建地址」那一档永远不依赖这里的任何东西**,
 * 那是真正的逃生口.
 *
 * client 惰性取的原因见 [RepoHostedList]: [BangumiMirrorFeatureHandler] 的路由来自 [BangumiEndpointProvider],
 * 而后者的镜像清单来自这里.
 */
class BangumiMirrorListRepository(
    cache: Settings<RepoHostedListCache>,
    client: () -> ScopedHttpClient,
    scope: CoroutineScope,
) : RepoHostedList(SPEC, cache, client, scope) {
    /** 镜像根域名 (已归一化), 按优先级排. 给 [BangumiEndpointProvider] 用. */
    val mirrors: Flow<List<String>> get() = entries

    private companion object {
        /** 远程来的每一条都归一成根域名: 别把畸形域名塞进 host 改写里. */
        val SPEC = Spec(
            fileName = "bangumi-mirrors.json",
            field = "mirrors",
            bundled = BangumiMirrorList.BUNDLED,
            normalize = BangumiMirrorHosts::normalizeMirrorRoot,
        )
    }
}
