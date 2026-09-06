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
 * bangumi 走哪个地址.
 *
 * 2026-05 起 bangumi 在中国大陆被大规模阻断, **API 也一样不通** (`api.bgm.tv` / `next.bgm.tv` /
 * `bangumi.tv` / `chii.in` 全中), 所以直连分支在大陆等于不可用. 社区做了若干反代镜像
 * (`bangumi.pro` 等), 但**它们的死亡率极高** —— 三个月里 `bgmmi.anibt.net`、`bangumi.rdd.moe` 寄,
 * `bangumi.one` 被 TLS 阻断, `bangumi.lol` 被人拿下. 所以清单必须能在运行期更新, 写死一个必烂.
 */
@Serializable
data class BangumiEndpointSettings(
    val mode: BangumiEndpointMode = BangumiEndpointMode.AUTO,
    /**
     * 用户自建镜像的根域名或地址, 形如 `bangumi.example.com` / `https://bangumi.example.com`.
     *
     * 只在 [BangumiEndpointMode.CUSTOM] 下用. 存原样输入, 归一化交给
     * [BangumiMirrorHosts.normalizeMirrorRoot] —— 存归一化后的值会让用户看不懂自己填的东西去哪了.
     */
    val customBaseUrl: String = "",
    /**
     * [BangumiEndpointMode.AUTO] 与 [BangumiEndpointMode.MIRROR] 下, 登录与收藏同步 (带凭证的请求, 以及登录本身) 也允许经第三方镜像.
     *
     * 默认关: 镜像方能看到经过它的一切, 包括登录凭证. 由用户在弹窗里了解风险后自己决定打开;
     * 界面同时建议优先用代理 (直连官方, 不经过第三方).
     */
    val allowCredentialsViaMirror: Boolean = false,
    @Suppress("PropertyName") @Transient val _placeHolder: Int = 0,
) {
    /**
     * 确定官方连不上之后的设置: 「官方连不上时用镜像」改成「用镜像」(见 [BangumiEndpointMode.AUTO]);
     * 其他档是用户明确选的, 原样不动.
     */
    fun afterOriginUnreachable(): BangumiEndpointSettings =
        if (mode == BangumiEndpointMode.AUTO) copy(mode = BangumiEndpointMode.MIRROR) else this

    companion object {
        val Default = BangumiEndpointSettings()
    }
}

/**
 * 远程拉到的镜像清单的本地缓存.
 *
 * 与 [BangumiEndpointSettings] 分开存: 那是用户的选择, 这是缓存 —— 混在一起会让"备份/恢复设置"
 * 把一份过期的清单也搬过去.
 */
@Serializable
data class BangumiMirrorCache(
    /** 已归一化的镜像根域名, 按优先级排. 空 = 还没拉到过, 用内置那份. */
    val mirrors: List<String> = emptyList(),
    val updatedAt: Long = 0,
    @Suppress("PropertyName") @Transient val _placeHolder: Int = 0,
) {
    companion object {
        val Default = BangumiMirrorCache()
    }
}

enum class BangumiEndpointMode {
    /** 只连 bangumi 官方地址, 不通就是不通. */
    DIRECT,

    /**
     * 直连优先, **连不上才顺着镜像清单试**. 一旦确定官方连不上 (请求在官方那一跳连接失败、换到镜像成功),
     * 就自动改成 [MIRROR] 并保存, 之后不再先试官方, 直到用户手动改回.
     *
     * 刻意不做启动时的主动探测: 探测在启动风暴里出假红叉是有案底的 (见连通性探测那一档),
     * 而且非大陆用户不该为此付启动延迟. 用"请求真失败了再回落"这个信号最诚实.
     *
     * **镜像下带 token 的请求默认只走直连** —— 第三方镜像能看到经过它的一切, 包括登录凭证.
     * 于是镜像默认是"匿名浏览可用, 收藏同步不可用"; 用户了解风险后可以打开
     * [BangumiEndpointSettings.allowCredentialsViaMirror], 让登录与收藏同步也经过镜像. [MIRROR] 同样.
     */
    AUTO,

    /**
     * 只用镜像清单, 不再尝试官方. [AUTO] 确定官方连不上时自动改成这一档; 用户也可以直接选.
     * 带 token 的请求照 [AUTO] 的规矩 (见 [BangumiEndpointSettings.allowCredentialsViaMirror]).
     */
    MIRROR,

    /**
     * 只用 [BangumiEndpointSettings.customBaseUrl].
     *
     * 用户自己搭的 (Mirrox / CF Worker 之类) 视为可信, **允许带 token** —— 登录与收藏同步都能用.
     */
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
