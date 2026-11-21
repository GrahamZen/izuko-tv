/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 不发版就能换的几样配置: 交流群、弹弹 play 的应用凭据、TMDB 的 token.
 *
 * 编包时内置一份 ([AniBuildConfig] 里同名的字段); 应用运行时从仓库 `lists` 分支取加密的应用配置, 用包里的钥匙
 * ([AniBuildConfig.appConfigKey]) 解开后填进 [remote] (见 app-data 的 `RemoteAppConfigRepository`).
 * 读的时候某一项 [remote] 里有就用它, 没有就用内置的 —— 要换哪一项只改那份密文 (`scripts/app-config.py`).
 *
 * 名字与密文里的键一致, 也是 local.properties 属性去掉 `ani.` 前缀 (`dandanplay.app.id` ↔ `ani.dandanplay.app.id`).
 * 读的人每次用到时现读, 别缓存: 运行中途取到新的一份时, 下一次请求就换过去 (弹弹 play 客户端只在第一次用到时建一次, 换了要下次启动).
 */
object RemoteAppConfig {
    /** 运行时取到、解开的那份; 没取到过时为空. */
    val remote = MutableStateFlow<Map<String, String>>(emptyMap())

    /** [name] 这一项: [remote] 里有 (非空) 就用它, 否则用内置的 [bundled]. */
    fun value(name: String, bundled: String): String = remote.value[name]?.takeIf { it.isNotBlank() } ?: bundled

    val dandanplayAppId: String get() = value("dandanplay.app.id", currentAniBuildConfig.dandanplayAppId)
    val dandanplayAppSecret: String get() = value("dandanplay.app.secret", currentAniBuildConfig.dandanplayAppSecret)
    val tmdbApiToken: String get() = value("tmdb.api.token", currentAniBuildConfig.tmdbApiToken)
    val chatQqGroupId: String get() = value("chat.qq.id", currentAniBuildConfig.chatQqGroupId)
    val chatQqGroupLink: String get() = value("chat.qq.link", currentAniBuildConfig.chatQqGroupLink)
    val chatTelegramLink: String get() = value("chat.telegram.link", currentAniBuildConfig.chatTelegramLink)
}
