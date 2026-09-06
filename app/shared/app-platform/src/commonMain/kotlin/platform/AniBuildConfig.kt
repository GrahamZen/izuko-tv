/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import androidx.compose.runtime.Stable
import me.him188.ani.utils.platform.currentPlatform


@Stable
interface AniBuildConfig {
    /**
     * `3.0.0-rc04`
     */
    val versionName: String
    val isDebug: Boolean
    val dandanplayAppId: String
    val dandanplayAppSecret: String

    /** TMDB API Read Access Token (v4 Bearer), 用于获取横版背景图/剧集缩略图. 未配置时为空串. */
    val tmdbApiToken: String
        get() = ""

    /**
     * 直连 bangumi 的 OAuth 应用凭据 (`ani.bangumi.oauth.client.id` / `.secret`).
     *
     * bangumi 的授权码换 token 必须带 secret, 而它没有 PKCE 之类的免 secret 流程, 只能随包发。
     * 未配置时为空串, 登录入口据此提示"这个构建没带凭据"。
     */
    val bangumiOauthClientId: String
        get() = ""

    val bangumiOauthClientSecret: String
        get() = ""
    val sentryDsn: String
    val distroChannel: String

    /**
     * 构建时所在的 git 分支名, 如 `main`. CI 里 PR 构建为 PR 的源分支, tag 构建为 tag 名.
     * 无法获取 (源码包构建, 或没有 git) 时为空.
     */
    val gitBranch: String
        get() = ""

    /**
     * 构建时 HEAD 的完整 commit sha (40 位十六进制). 无法获取时为空.
     */
    val gitCommitSha: String
        get() = ""

    /**
     * HEAD commit 的提交时间, ISO-8601, 如 `2026-09-20T12:00:00+08:00`. 无法获取时为空.
     */
    val gitCommitTime: String
        get() = ""

    /** 本项目的 GitHub 仓库, `owner/repo` (见 gradle.properties 的 `ani.repository`): 项目链接、UA 与镜像清单都按它拼地址. */
    val projectRepository: String
        get() = "GrahamZen/izuko-tv"

    /**
     * 更新时要装的 APK 在 release 里的文件名前缀 (`<前缀>-<版本>-<架构>.apk`, 见 gradle.properties).
     * 仓库里改分发包名之前的 release 叫 `ani-…`, 按前缀只认自己的包.
     */
    val updateAssetPrefix: String
        get() = "ani"

    /** 检查更新、下载安装包的 GitHub 仓库, `owner/repo`; 默认是 [projectRepository], 发版前走真机更新时可指到测试仓库. */
    val updateRepository: String
        get() = projectRepository

    val sentryEnabled: Boolean
        get() = true
    val analyticsEnabled: Boolean
        get() = true

    val isDefaultDistro: Boolean
        get() = distroChannel == DISTRO_PLATFORM_DEFAULT

    companion object {
        @Stable
        fun current(): AniBuildConfig = currentAniBuildConfig

        private const val DISTRO_PLATFORM_DEFAULT = "default"
    }
}

/**
 * E.g. `3000` for `3.0.0`, `3012` for `3.1.2`
 */
val AniBuildConfig.fourDigitVersionCode: String
    get() = buildString {
        val split = versionName.substringBefore("-").split(".")
        if (split.size == 3) {
            split[0].toIntOrNull()?.let {
                append(it.toString())
            }
            split[1].toIntOrNull()?.let {
                append(it.toString().padStart(2, '0'))
            }
            split[2].toIntOrNull()?.let {
                append(it.toString())
            }
        } else {
            for (section in split) {
                section.toIntOrNull()?.let {
                    append(it.toString())
                }
            }
        }
    }

@Stable
@PublishedApi
internal expect val currentAniBuildConfigImpl: AniBuildConfig

@Stable
inline val currentAniBuildConfig: AniBuildConfig get() = currentAniBuildConfigImpl

/**
 * 满足各个数据源建议格式的 User-Agent (`<开发者>/<应用>/<版本> (<平台>) (<项目地址>)`), 所有 HTTP 请求都应该带此 UA.
 *
 * 用本 fork 自己的身份. Bangumi 按 UA 认客户端与版本, 会拦掉有 bug 的老版 Animeko (`open-ani/ani/` 且版本 ≤ 4.8.1);
 * fork 的版本号自成一条线 (新包从 1.x 起), 顶着上游的名字就会被当成那些老版本, 登录信息、收藏、剧集、角色全部 403
 * (2026-09-22 实测).
 */
fun getAniUserAgent(
    version: String = currentAniBuildConfig.versionName,
    platform: String = currentPlatform().nameAndArch,
    repository: String = currentAniBuildConfig.projectRepository,
): String = "GrahamZen/izuko-tv/$version ($platform) (https://github.com/$repository)"
