/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.platform.getAniUserAgent
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform

/**
 * 检查更新、下载安装包的仓库 (`AniBuildConfig.updateRepository`).
 * 发版前在真机上走一遍更新时, 把它指到测试仓库 —— 草稿 release 对应用不可见.
 */
internal val FORK_OWNER: String get() = currentAniBuildConfig.updateRepository.substringBefore('/')
internal val FORK_REPO: String get() = currentAniBuildConfig.updateRepository.substringAfter('/')

/** 每个 release 的更新说明模板 (CI 在它上面做变量替换后当 release body). 镜像回落时直接读 tag 下的这份. */
internal const val RELEASE_TEMPLATE_PATH = "ci-helper/release-template.md"

/**
 * 本应用的版本线: 主版本号**小于**这个数. 6 及以上是改分发包名之前的旧版本 (安装包叫 `ani-…`).
 *
 * 两条线共用一个仓库, 而镜像只能回答"最新版是哪个"这一个版本号 —— 不限定版本线的话, jsDelivr 会把
 * 版本号更大的旧版本当成本应用的最新版, 于是提示一个在 release 里根本不存在的安装包.
 * 前提是仓库里没有别的 1.x ~ 5.x 的 tag (上游历史上那批要删掉).
 */
internal const val UPDATE_LINE_MAJOR_EXCLUSIVE = 6

/** [version] (不带 `v`) 是不是本应用这条版本线上的. 解析不出主版本号的一律不算. */
internal fun isInUpdateLine(version: String): Boolean =
    version.substringBefore('.').toIntOrNull()?.let { it < UPDATE_LINE_MAJOR_EXCLUSIVE } ?: false

/**
 * 在 jsDelivr 上找本版本线最新正式版用的版本范围 (`<6`, URL 编码后的样子).
 * jsDelivr 按语义版本范围解析, 与 `@latest` 一样只含正式版 (实测 `@<6.0.6` 解析到 6.0.5).
 */
private const val JSDELIVR_UPDATE_LINE_RANGE = "%3C$UPDATE_LINE_MAJOR_EXCLUSIVE"

/**
 * jsDelivr 的几个入口, 按顺序试. 版本范围的解析只含正式版 (实测 6.0.6-alpha01 存在时 `@latest` 仍解析到 6.0.5),
 * 版本号在响应头 `x-jsd-version` 里; 解析结果有最长 12 小时的缓存, 刚发版时可能还是上一版.
 *
 * 不含 fastly 入口: 它在国内会 301 到 raw.githubusercontent.com, 真机上要么超时要么 10 秒才回, 且没有版本头.
 * cdn 入口在国内时好时坏 (同一台电视两次测试, 一次通一次同样 301 白等 20 秒), 放最后.
 */
internal val JSDELIVR_HOSTS = listOf("gcore.jsdelivr.net", "testingcf.jsdelivr.net", "cdn.jsdelivr.net")

/**
 * ghfast.top: 公共的 GitHub 下载代理, raw / releases/latest 跳转都能代理, API 与 atom 不行 (403).
 * 只在检查更新的镜像回落里用; 下载安装包的镜像见 `GitHubDownloadMirrors`.
 */
internal fun ghfastUrl(gitHubUrl: String) = "https://ghfast.top/$gitHubUrl"

/** 镜像回落时拿不到资源列表, 按 fork-release.yml 的命名规则合成; 不存在的那个下载时 404, 下载器接着试下一个. */
internal val RELEASE_APK_SUFFIXES = listOf(
    "arm64-v8a", "armeabi-v7a", "x86_64", "universal",
    "legacy-arm64-v8a", "legacy-armeabi-v7a", "legacy-universal",
)

internal fun releaseApkAssets(version: String): List<GitHubAsset> = RELEASE_APK_SUFFIXES.map { suffix ->
    val name = "${currentAniBuildConfig.updateAssetPrefix}-$version-$suffix.apk"
    GitHubAsset(name, "https://github.com/$FORK_OWNER/$FORK_REPO/releases/download/v$version/$name")
}

/** 候选版本算不算"可以更新过去": 在本版本线上 ([isInUpdateLine]), 并且比当前版本新. */
internal fun isUpdateCandidate(
    candidate: String,
    currentVersion: String,
    isNewer: (candidate: String, current: String) -> Boolean,
): Boolean = isInUpdateLine(candidate) && isNewer(candidate, currentVersion)

/**
 * 从 releases 里挑出第一个「可以更新过去 ([isUpdateCandidate])、而且本机与本分发版真装得上」的.
 *
 * **必须一路往下找, 不能只看最上面那个**: 最新的 release 里未必有能装的包 —— 老设备遇到不出兼容包的
 * 版本, 或者排在最上面的是别的版本线的 release. 只看第一个的话这些情况一律表现为"没有更新".
 *
 * [releases] 按 tag 所指提交的时间倒序 (GitHub API 的默认顺序), 所以第一个满足条件的就是最新的可用版本.
 *
 * @param abis 设备的完整 ABI 列表, 见 [pickInstallableApks]; `null` = 非 Android, 不按 ABI 挑
 * @param legacy 低于正式包 minSdk 的设备只能装兼容包, 见 [LEGACY_APK_MARKER]
 */
internal fun selectUsableRelease(
    releases: List<GitHubRelease>,
    currentVersion: String,
    releaseClass: ReleaseClass,
    assetPrefix: String,
    abis: List<String>?,
    legacy: Boolean = false,
    isNewer: (candidate: String, current: String) -> Boolean,
    onSkip: (GitHubRelease) -> Unit = {},
): Pair<GitHubRelease, List<GitHubAsset>>? = releases
    .asSequence()
    .filter { !it.draft }
    .filter { release ->
        when (releaseClass) {
            ReleaseClass.STABLE -> !release.prerelease
            else -> true // BETA / ALPHA / RC: include prerelease
        }
    }
    .mapNotNull { release ->
        val candidate = release.tagName.removePrefix("v")
        if (!isUpdateCandidate(candidate, currentVersion, isNewer)) return@mapNotNull null
        val apks = release.assets.filter { it.name.endsWith(".apk") && it.name.startsWith("$assetPrefix-") }
        if (abis == null) return@mapNotNull release to apks
        val installable = apks.pickInstallableApks(abis, legacy)
        if (installable.isEmpty()) {
            onSkip(release)
            return@mapNotNull null
        }
        release to installable
    }
    .firstOrNull()

private val TAG_IN_RELEASE_URL = Regex("""/releases/tag/v?([^/?#]+)""")

/** 全架构包的文件名标记 (见 ReleaseArtifactNames); 任何设备都装得上, 作为 ABI 匹配不到时的兜底. */
private const val UNIVERSAL_APK_MARKER = "universal"

/**
 * Android 7.1 兼容包的文件名标记: `ani-<版本>-legacy-<架构>.apk` (fork-release.yml 上传时命名).
 * 与正式包只差这一段, 架构段完全同名, 所以按 ABI 挑包之前必须先按这个标记把两族分开.
 */
private const val LEGACY_APK_MARKER = "-legacy-"

/**
 * 正式包的 minSdk (gradle.properties 的 `android.min.sdk`). 低于它的设备只能装兼容包
 * (`-Pani.android.legacy=true` 构建, minSdk 25), 装正式包会 `INSTALL_FAILED_OLDER_SDK`.
 */
private const val NORMAL_APK_MIN_SDK = 27

/**
 * 该资产是否是 [abi] 的包. release 资产名形如 `ani-<版本>-<架构>.apk`
 * (见 `ReleaseArtifactNames.androidApp`), 所以按 `-<架构>.` 这一整段匹配.
 *
 * 不能用 `abi in name` 纯子串: 32 位 x86 设备的 ABI 就叫 `x86`, 而它是 `x86_64` 的子串,
 * 会把 64 位包当成装得上的.
 */
private fun GitHubAsset.isForAbi(abi: String) = "-$abi." in name

/**
 * 见 [UpdateChecker.pickInstallableApks]; 抽成顶层纯函数以便测试 (设备 ABI 列表与是否老设备由调用方给出).
 *
 * [legacy] = 设备低于正式包 minSdk, 只能装兼容包. 两族包的架构段同名 (`-arm64-v8a.` / `-universal.`),
 * 不先切开的话: 老设备会拿到正式包 (装不上); 正常设备走 universal 兜底时也会拿到兼容包 ——
 * GitHub 按文件名返回, `-legacy-universal` 排在 `-universal` 前面.
 */
internal fun List<GitHubAsset>.pickInstallableApks(abis: List<String>, legacy: Boolean = false): List<GitHubAsset> {
    if (abis.isEmpty()) return this
    val (legacyApks, normalApks) = partition { LEGACY_APK_MARKER in it.name }
    // 老设备: 只在兼容包里挑; 一个都没有 (旧 release 不出兼容包) 就是没有可装的, 交空列表让调用方不提示,
    // 绝不能退回正式包 —— 那只会换来 INSTALL_FAILED_OLDER_SDK.
    if (legacy) return legacyApks.pickByAbi(abis) ?: emptyList()
    // 正常设备: 兼容包一律不看. 命名规则变了 (匹配不到任何东西) 时宁可退回旧行为也不要空列表.
    return normalApks.pickByAbi(abis) ?: normalApks.ifEmpty { this }
}

/** 本机架构的专包在前, universal 兜底在后; 两个都没有返回 `null`. */
private fun List<GitHubAsset>.pickByAbi(abis: List<String>): List<GitHubAsset>? {
    // 按设备自己的偏好顺序取第一个"有对应包"的 ABI, 而不是只看首选 ABI —— 首选的那个不一定出包:
    // x86 电视模拟器首选 x86 (本项目不出), 但它支持 armeabi-v7a, 该装 v7 包
    val exact = abis.firstNotNullOfOrNull { abi -> firstOrNull { it.isForAbi(abi) } }
    val universal = firstOrNull { it.isForAbi(UNIVERSAL_APK_MARKER) }
    return listOfNotNull(exact, universal).ifEmpty { null }
}

/**
 * 检查更新. 首选 GitHub API; 它连不上 (国内常见, 另有未认证 60 次/小时/IP 的限流, 移动网络共用出口 IP 会撞上)
 * 时回落到国内大多可达的镜像, 见 [findLatestStableOnMirrors]. 镜像回落**只查正式版**.
 *
 * 走应用的统一客户端: 应用内设置的代理对更新检查同样生效, 每个请求也都进日志 (以前自建客户端, 两样都没有).
 *
 * 结果里的下载地址只有 GitHub 原地址 ([NewVersion.downloadUrlAlternatives], 每个可装的包一个); 加速镜像在下载时
 * 按仓库维护的清单展开 (`GitHubDownloadMirrors`), 下载器在原地址与镜像里挑最快的. 经第三方镜像下载的安全性靠两道:
 * Android 拒绝签名不同的覆盖安装; 走 GitHub 接口时按接口给的 SHA-256 校验 (镜像回落时退回来源旁边的 .sha1).
 */
class UpdateChecker(private val client: ScopedHttpClient) {
    /**
     * 检查是否有更新的版本. 返回最新版本的信息, 或者 `null` 表示没有新版本.
     * GitHub 与所有镜像都连不上时抛出 GitHub 那次的异常.
     */
    suspend fun checkLatestVersion(
        releaseClass: ReleaseClass,
        currentVersion: String = currentAniBuildConfig.versionName,
    ): NewVersion? {
        val gitHubError = try {
            val version = getVersionFromGitHub(currentVersion, releaseClass)
            // 连选中的安装包一起打出来: 装不上的报障 (架构不符) 只凭版本号看不出问题在哪,
            // 而选包发生在这一步, 到下载时才有日志就晚了 (用户不点下载就没有任何线索)
            logger.info { "Got latest version from GitHub: ${version?.name}, packages=${version?.packageNames()}" }
            return version
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error(e) { "Failed to get latest version from GitHub, trying mirrors" }
            e
        }
        val release = findLatestStableOnMirrors() ?: run {
            logger.warn { "Mirror update check failed too" }
            throw gitHubError
        }
        return newVersionFromMirror(release, currentVersion).also { version ->
            logger.info {
                "Got latest version from mirror (${release.source}): latest=${release.version}, " +
                    "new=${version?.name}, packages=${version?.packageNames()}"
            }
        }
    }

    private suspend fun getVersionFromGitHub(
        currentVersion: String,
        releaseClass: ReleaseClass,
    ): NewVersion? {
        val releases = client.use {
            get("https://api.github.com/repos/$FORK_OWNER/$FORK_REPO/releases") {
                parameter("per_page", 20)
                header(HttpHeaders.UserAgent, getAniUserAgent())
                mirrorTimeout()
            }.bodyAsText()
        }.let {
            json.decodeFromString<List<GitHubRelease>>(it)
        }

        val android = currentPlatform() as? Platform.Android
        val (latest, packages) = selectUsableRelease(
            releases = releases,
            currentVersion = currentVersion,
            releaseClass = releaseClass,
            assetPrefix = currentAniBuildConfig.updateAssetPrefix,
            abis = android?.supportedAbis,
            legacy = android?.sdkInt?.let { it < NORMAL_APK_MIN_SDK } ?: false,
            isNewer = ::isNewerThan,
            onSkip = { logger.info { "Release ${it.tagName} has no package installable on this device, skipping" } },
        ) ?: return null

        val versionName = latest.tagName.removePrefix("v")

        return NewVersion(
            name = versionName,
            changelogs = listOf(
                Changelog(
                    version = versionName,
                    publishedAt = latest.publishedAt,
                    changes = latest.body,
                ),
            ),
            downloadUrlAlternatives = packages.map { it.browserDownloadUrl },
            publishedAt = latest.publishedAt,
            sha256ByFileName = packages.mapNotNull { asset -> asset.sha256?.let { asset.name to it } }.toMap(),
        )
    }

    private class MirrorRelease(val version: String, val templateBody: String, val source: String)

    /**
     * 在镜像上找本版本线 ([UPDATE_LINE_MAJOR_EXCLUSIVE]) 的最新正式版. 先 jsDelivr (一个请求同时拿到版本号与
     * 那一版的更新说明模板), 都不通再用 ghfast 代理 `releases/latest` 的跳转取 tag, 更新说明再经 ghfast 代理
     * raw 取. `null` = 全部不通.
     *
     * `releases/latest` 不能指定版本线: 它指向的是仓库里被标成 Latest 的那个 release, 可能是别的版本线的.
     * 那时交给调用方按版本线丢掉, 等同于这条镜像没找到.
     */
    private suspend fun findLatestStableOnMirrors(): MirrorRelease? {
        for (host in JSDELIVR_HOSTS) {
            val release = tryMirror("jsDelivr $host") {
                client.use {
                    val response = get(
                        "https://$host/gh/$FORK_OWNER/$FORK_REPO@$JSDELIVR_UPDATE_LINE_RANGE/$RELEASE_TEMPLATE_PATH",
                    ) {
                        expectSuccess = false
                        mirrorTimeout()
                    }
                    val version = response.headers["x-jsd-version"]?.removePrefix("v")
                    if (response.status.isSuccess() && !version.isNullOrBlank()) {
                        MirrorRelease(version, response.bodyAsText(), "jsDelivr $host")
                    } else {
                        logger.info { "Mirror jsDelivr $host: status=${response.status.value}, x-jsd-version=$version" }
                        null
                    }
                }
            }
            if (release != null) return release
        }
        val tag = tryMirror("ghfast latest") {
            val finalUrl = client.use {
                get(ghfastUrl("https://github.com/$FORK_OWNER/$FORK_REPO/releases/latest")) {
                    expectSuccess = false
                    mirrorTimeout()
                }.call.request.url.toString()
            }
            TAG_IN_RELEASE_URL.find(finalUrl)?.groupValues?.get(1).also {
                if (it == null) logger.info { "Mirror ghfast latest: no tag in final url $finalUrl" }
            }
        } ?: return null
        val body = tryMirror("ghfast raw template") {
            client.use {
                get(ghfastUrl("https://raw.githubusercontent.com/$FORK_OWNER/$FORK_REPO/v$tag/$RELEASE_TEMPLATE_PATH")) {
                    mirrorTimeout()
                }.bodyAsText()
            }
        }.orEmpty()
        return MirrorRelease(tag, body, "ghfast")
    }

    private fun newVersionFromMirror(release: MirrorRelease, currentVersion: String): NewVersion? {
        val versionName = release.version
        if (!isUpdateCandidate(versionName, currentVersion, ::isNewerThan)) {
            return null
        }
        val packages = releaseApkAssets(versionName).pickInstallableApks()
        if (packages.isEmpty() && currentPlatform() is Platform.Android) return null
        // 与 fork-release.yml 的 release-notes 步骤同一套替换
        val body = release.templateBody
            .replace("\$GIT_TAG", "v$versionName")
            .replace("\$TAG_VERSION", versionName)
            .replace("\$REPO_OWNER", FORK_OWNER)
            .replace("\$ASSET_PREFIX", currentAniBuildConfig.updateAssetPrefix)
        return NewVersion(
            name = versionName,
            changelogs = listOf(Changelog(version = versionName, publishedAt = "", changes = body)),
            downloadUrlAlternatives = packages.map { it.browserDownloadUrl },
            publishedAt = "",
        )
    }

    /** 单个镜像请求: 失败只记一行 (不打栈, 回落链本来就预期会有失败) 并返回 null, 取消照常抛出. */
    private suspend fun <T> tryMirror(name: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.info { "Mirror $name failed: ${e::class.simpleName}: ${e.message}" }
        null
    }

    /**
     * 更新检查的请求一律短超时: 统一客户端默认连接 30 秒 / 请求 5 分钟, 不通的源会让回落迟迟轮不到.
     * 连接 5 秒是按单个地址算的, IPv6 不通的网络上多地址串行会更久, 所以请求上限另给 20 秒.
     */
    private fun HttpRequestBuilder.mirrorTimeout() {
        timeout {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 20_000
        }
    }

    private fun NewVersion.packageNames() = downloadUrlAlternatives.map { it.substringAfterLast('/') }.distinct()

    /**
     * 从 release 的全部 APK 里挑出本机装得上的: 本机架构的专包在前, universal 兜底在后, 其余一律不留.
     *
     * 不筛的后果是"自动更新后安装提示不兼容" (`INSTALL_FAILED_NO_MATCHING_ABIS`):
     * [downloadUrlAlternatives] 里的包会被 [me.him188.ani.app.tools.update.FileDownloader] 按顺序逐个尝试,
     * 第一个下成即停, 于是永远下载 release 里的第一个 APK —— 按文件名排序就是 `arm64-v8a`.
     * 32 位设备与 x86 设备装上去必然失败.
     *
     * 混入其它架构还有个更隐蔽的后果: 首选包下载失败时, 下载器会接着下另一个架构的包并
     * "成功" —— 下完照样装不上. 所以这里是 filter 而非单纯排序.
     *
     * 用设备的**完整** ABI 列表而不是只用首选 ABI ([me.him188.ani.utils.platform.Arch]):
     * 见 [Platform.Android.supportedAbis] —— 只看首选 ABI 时 x86 的电视模拟器会被当成 arm64 设备.
     * 非 Android 平台拿不到列表 (空), 此时不筛, 保持原有行为.
     *
     * 低于正式包 minSdk 的设备 (Android 7.1) 只能装 `-legacy-` 兼容包, 见 [LEGACY_APK_MARKER].
     */
    private fun List<GitHubAsset>.pickInstallableApks(): List<GitHubAsset> {
        val android = currentPlatform() as? Platform.Android
        return pickInstallableApks(
            abis = android?.supportedAbis ?: emptyList(),
            legacy = android?.sdkInt?.let { it < NORMAL_APK_MIN_SDK } ?: false,
        )
    }

    /**
     * Returns true if [candidate] is a newer version than [current].
     *
     * Handles semver with optional pre-release suffix, e.g. "4.0.0-beta04".
     * Stable (no suffix) is considered newer than any pre-release with the same numbers.
     */
    private fun isNewerThan(candidate: String, current: String): Boolean {
        if (candidate == current) return false

        fun parse(v: String): Pair<List<Int>, String> {
            val dashIdx = v.indexOf('-')
            return if (dashIdx >= 0) {
                v.substring(0, dashIdx).split('.').map { it.toIntOrNull() ?: 0 } to
                        v.substring(dashIdx + 1)
            } else {
                v.split('.').map { it.toIntOrNull() ?: 0 } to ""
            }
        }

        val (cNums, cPre) = parse(candidate)
        val (vNums, vPre) = parse(current)

        for (i in 0 until maxOf(cNums.size, vNums.size)) {
            val c = cNums.getOrElse(i) { 0 }
            val v = vNums.getOrElse(i) { 0 }
            if (c != v) return c > v
        }

        // Same numeric version — stable beats pre-release
        if (cPre.isEmpty() && vPre.isNotEmpty()) return true  // stable > beta
        if (cPre.isNotEmpty() && vPre.isEmpty()) return false  // beta < stable
        return cPre > vPre // both pre-release: compare lexicographically
    }

    private companion object {
        private val logger = logger<UpdateChecker>()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("body") val body: String = "",
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    /** GitHub 算的摘要, 形如 `sha256:<十六进制>`. 镜像回落时合成的资源没有. */
    @SerialName("digest") val digest: String? = null,
) {
    /** [digest] 里的 SHA-256 (十六进制小写); 不是 SHA-256 或没有时为 `null`. */
    val sha256: String?
        get() = digest?.takeIf { it.startsWith(SHA256_DIGEST_PREFIX) }?.removePrefix(SHA256_DIGEST_PREFIX)?.lowercase()
}

private const val SHA256_DIGEST_PREFIX = "sha256:"
