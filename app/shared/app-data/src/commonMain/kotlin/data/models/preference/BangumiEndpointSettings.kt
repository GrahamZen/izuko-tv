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
 * bangumi 经哪些线路连: 每条线路 ([BangumiRouteKind]) 各自开关, 请求按固定顺序试开着的线路, 前一条连不上用下一条.
 *
 * 2026-05 起 bangumi 在中国大陆被大规模阻断, **API 也一样不通** (`api.bgm.tv` / `next.bgm.tv` /
 * `bangumi.tv` / `chii.in` 全中), 所以只连官方在大陆等于不可用. 社区做了若干反代镜像
 * (`bangumi.pro` 等), 但**它们的死亡率极高** —— 三个月里 `bgmmi.anibt.net`、`bangumi.rdd.moe` 寄,
 * `bangumi.one` 被 TLS 阻断, `bangumi.lol` 被人拿下. 所以清单必须能在运行期更新, 写死一个必烂.
 */
@Serializable
data class BangumiEndpointSettings(
    /**
     * 已发布版本里的「连接方式」. 只在读到旧数据 (还没存过 [routes]) 时用来推出各线路的开关, 见 [isEnabled];
     * 改过任何一条线路之后不再起作用.
     */
    val mode: BangumiEndpointMode? = null,
    /**
     * 用户自建镜像的根域名或地址, 形如 `bangumi.example.com` / `https://bangumi.example.com`.
     *
     * [BangumiRouteKind.CUSTOM] 开着时用. 存原样输入, 归一化交给
     * [BangumiMirrorHosts.normalizeMirrorRoot] —— 存归一化后的值会让用户看不懂自己填的东西去哪了.
     */
    val customBaseUrl: String = "",
    /**
     * 登录与收藏同步 (带凭证的请求, 以及登录本身) 也允许经第三方镜像 ([BangumiRouteKind.MIRRORS]).
     *
     * 默认关: 镜像方能看到经过它的一切, 包括登录凭证. 由用户在弹窗里了解风险后自己决定打开;
     * 界面同时建议优先用代理 (直连官方, 不经过第三方).
     */
    val allowCredentialsViaMirror: Boolean = false,
    /** 各线路开没开, 键是 [BangumiRouteKind.id]. 没写的线路按 [mode] 推出, 再没有就用 [BangumiRouteKind.enabledByDefault]. */
    val routes: Map<String, Boolean> = emptyMap(),
    @Suppress("PropertyName") @Transient val _placeHolder: Int = 0,
) {
    fun isEnabled(kind: BangumiRouteKind): Boolean = routes[kind.id] ?: when (mode) {
        null, BangumiEndpointMode.AUTO -> kind.enabledByDefault
        BangumiEndpointMode.DIRECT -> kind == BangumiRouteKind.ORIGIN
        BangumiEndpointMode.MIRROR -> kind == BangumiRouteKind.MIRRORS
        BangumiEndpointMode.CUSTOM -> kind == BangumiRouteKind.CUSTOM
    }

    /** 改一条线路的开关. 顺带把所有线路的开关都写下来, 之后不再看 [mode]. */
    fun withRoute(kind: BangumiRouteKind, enabled: Boolean): BangumiEndpointSettings =
        copy(mode = null, routes = BangumiRouteKind.entries.associate { it.id to (if (it == kind) enabled else isEnabled(it)) })

    /** 自建地址归一化后的根域名; 没填或填得不成样子时为 `null`. */
    val customRoot: String? get() = BangumiMirrorHosts.normalizeMirrorRoot(customBaseUrl)

    /**
     * 确定官方连不上之后的设置: 关掉「官方直连」, 之后的请求不再先吃一次官方的连接超时, 直到用户手动打开.
     * 只开着官方 (用户选了只连官方) 时不动: 那是他明确要的.
     */
    fun afterOriginUnreachable(): BangumiEndpointSettings {
        val others = BangumiRouteKind.entries.any { it != BangumiRouteKind.ORIGIN && isEnabled(it) }
        return if (isEnabled(BangumiRouteKind.ORIGIN) && others) withRoute(BangumiRouteKind.ORIGIN, false) else this
    }

    companion object {
        val Default = BangumiEndpointSettings()
    }
}

/**
 * 连 bangumi 的一类线路. **声明顺序就是尝试顺序**: 前面的连不上才用后面的.
 *
 * 加一类线路: 在这里加一项 (定好位置与默认开关), 在 `BangumiEndpointProvider` 里把它变成具体的 `BangumiRoute`,
 * 再给设置页、网页控制台与引导检测页各加一行. 调度 (回落、记住上次通的、凭证规矩) 不用动.
 */
enum class BangumiRouteKind(
    /** 存进设置的键, 改了等于丢掉用户的开关. */
    val id: String,
    val enabledByDefault: Boolean,
) {
    /** bangumi 官方地址. 确定连不上时自动关掉 (见 [BangumiEndpointSettings.afterOriginUnreachable]). */
    ORIGIN("origin", true),

    /**
     * 用户自己搭的反代 ([BangumiEndpointSettings.customBaseUrl]). 视为可信, **允许带 token**, 也代理得了主站 (换 token 与续期).
     * 用户自己的优先: 排在社区镜像前面.
     */
    CUSTOM("custom", false),

    /**
     * 社区镜像清单 (见 `BangumiMirrorListRepository`). 第三方能看到经过它的一切, 带凭证的请求默认不发过去
     * (见 [BangumiEndpointSettings.allowCredentialsViaMirror]); 它也代理不了主站, 换 token 与续期不发给它.
     */
    MIRRORS("mirrors", true),
}

/**
 * 一键设好几条线路的组合, 给首次启动引导这种「选一个就走」的地方用 (设置页里各条线路单独开关).
 * 自建地址不动: 那是用户自己填的.
 */
enum class BangumiConnectionPreset {
    /** 官方优先, 连不上自动换镜像 (确定连不上后关掉官方). */
    AUTO,

    /** 不连官方 (测出来连不上): 只用镜像. */
    SKIP_ORIGIN,

    /** 只连官方. */
    DIRECT,
    ;

    fun applyTo(settings: BangumiEndpointSettings): BangumiEndpointSettings = when (this) {
        AUTO -> settings.withRoute(BangumiRouteKind.ORIGIN, true)
            .withRoute(BangumiRouteKind.MIRRORS, true)

        SKIP_ORIGIN -> settings.withRoute(BangumiRouteKind.ORIGIN, false)
            .withRoute(BangumiRouteKind.MIRRORS, true)

        DIRECT -> settings.withRoute(BangumiRouteKind.ORIGIN, true)
            .withRoute(BangumiRouteKind.CUSTOM, false)
            .withRoute(BangumiRouteKind.MIRRORS, false)
    }
}

/**
 * 已发布版本里的「连接方式」, 现在只用来读旧设置 (见 [BangumiEndpointSettings.mode]). 新代码用 [BangumiRouteKind].
 */
enum class BangumiEndpointMode {
    /** 只连官方. */
    DIRECT,

    /** 官方优先, 连不上用镜像. */
    AUTO,

    /** 只用镜像 (多半是「官方连不上」自动切过来的). */
    MIRROR,

    /** 只用自建地址. */
    CUSTOM,
}

/**
 * bangumi 原站域名与镜像域名之间的换算.
 *
 * 镜像的形状是**整个域名族的通配反代** (`*.镜像根 → *.bgm.tv`), 社区那几个镜像与 Mirrox 的默认配置
 * 都是这样, 所以一个镜像只需要用根域名描述:
 *
 * | 原站 | 镜像 (根域名 `bangumi.pro`) |
 * |---|---|
 * | `bgm.tv` | `bangumi.pro` |
 * | `api.bgm.tv` | `api.bangumi.pro` |
 * | `next.bgm.tv` | `next.bangumi.pro` |
 * | `lain.bgm.tv` (图床) | `lain.bangumi.pro` |
 *
 * **不支持"单域名 + 路径前缀"那种反代** (CF Worker 常见写法): 那要求逐个接口改路径, 而通配子域
 * 的写法一条规则覆盖全部、图床也一起解决. 自建的人按通配子域配就行.
 */
object BangumiMirrorHosts {
    const val ORIGIN_ROOT = "bgm.tv"

    fun isBangumiHost(host: String): Boolean =
        host == ORIGIN_ROOT || host.endsWith(".$ORIGIN_ROOT")

    /**
     * 把 [host] 换算成镜像上的对应域名; [host] 不是 bangumi 域名时返回 `null`.
     *
     * @param mirrorRoot 已归一化的镜像根域名 (见 [normalizeMirrorRoot])
     */
    fun mirrorHostOf(host: String, mirrorRoot: String): String? = when {
        !isBangumiHost(host) -> null
        host == ORIGIN_ROOT -> mirrorRoot
        else -> host.removeSuffix(".$ORIGIN_ROOT") + "." + mirrorRoot
    }

    /**
     * [mirrorHostOf] 的反向: 把镜像上的域名换回原站的; [host] 不在 [mirrorRoot] 之下时返回 `null`.
     */
    fun originHostOf(host: String, mirrorRoot: String): String? = when {
        host == mirrorRoot -> ORIGIN_ROOT
        host.endsWith(".$mirrorRoot") -> host.removeSuffix(".$mirrorRoot") + "." + ORIGIN_ROOT
        else -> null
    }

    /**
     * 把用户输入归一成裸根域名; 认不出来就返回 `null`.
     *
     * 允许他们粘进来的各种形状: `https://x.com/`、`x.com`、`api.x.com` (**取后两段**, 因为填 api
     * 子域是很自然的误解)、末尾斜杠、空白.
     */
    fun normalizeMirrorRoot(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?')
        // 端口不支持: 镜像是整族通配子域, 带端口的写法没法换算子域
        if (':' in s) return null
        if (s.isEmpty() || '.' !in s) return null
        if (s.any { it.isWhitespace() }) return null
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        // 填了 bangumi 子域时取根: "api.bangumi.pro" -> "bangumi.pro"
        val parts = s.split('.')
        if (parts.size > 2 && parts.first() in KNOWN_SUBDOMAINS) {
            return parts.drop(1).joinToString(".")
        }
        return s
    }

    /**
     * bangumi 用到的子域. 只用来在归一化时识别"用户多填了一层子域", 不参与换算 ——
     * 换算是通配的, 新子域零配置。
     */
    private val KNOWN_SUBDOMAINS = setOf("api", "next", "lain", "fast", "doujin", "www")
}
