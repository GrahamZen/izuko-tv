/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 一个网盘的接入方式. 代码里只有通用的网盘引擎 ([CloudDriveApi]), 具体某个网盘的地址、参数、字段、状态码都写在这份规格里,
 * 随「网盘」数据源的 JSON (订阅或导入) 下发.
 *
 * 引擎按固定的一组操作 ([DriveApiConfig]) 访问网盘, 每个操作是一个请求模板 ([DriveRequest]) 加上从响应里取值的路径.
 * 路径语法同直链 API 数据源 (`data.list`、`items[0].url`、`list[type=video].id`).
 *
 * 模板变量写成 `{name}`. 各操作可用的变量见 [DriveApiConfig]; 所有请求都能用 `{host}` (见 [DriveHttpConfig.hosts])
 * 与 `{requestId}` (每次一个随机 UUID).
 *
 * @property id 稳定标识, 只用小写字母、数字与 `-`. 账号、记下的文件夹、添加的分享、资源都按它归属, 改了就是另一个网盘.
 * 自己网盘那个数据源的 id 是 `<id>-drive`.
 * @property name 显示名, 如设置里的分组标题、数据源名
 * @property names 其它语言的显示名 (语言标记 → 名字, 如 `en`、`zh-TW`), 没有对应语言时用 [name]
 */
@Serializable
data class CloudDriveProtocol(
    val id: String,
    val name: String,
    val names: Map<String, String> = emptyMap(),
    val iconUrl: String = "",
    val websiteUrl: String = "",
    val http: DriveHttpConfig = DriveHttpConfig(),
    val envelope: DriveEnvelope = DriveEnvelope(),
    val file: DriveFileFields = DriveFileFields(),
    /** 网盘根目录的文件夹 id. */
    val rootFolderId: String = "0",
    val api: DriveApiConfig = DriveApiConfig(),
    val login: DriveLoginConfig = DriveLoginConfig(),
    val playback: DrivePlaybackConfig = DrivePlaybackConfig(),
    val share: DriveShareConfig = DriveShareConfig(),
    val links: DriveLinkTemplates = DriveLinkTemplates(),
    /**
     * 账号档位 (见 [DriveApiConfig.tier]) 的显示名与播放并发数, 按顺序取第一个 [DriveTier.match] 对上的.
     */
    val tiers: List<DriveTier> = emptyList(),
    val legacy: DriveLegacyData? = null,
) {
    /** 按界面语言 [languageTag] (如 `zh-CN`、`en`) 取显示名. */
    fun displayName(languageTag: String): String = localized(names, languageTag) ?: name

    /** 账号档位 [tier] 对上的那一档; 没有档位信息或都对不上时为 null. */
    fun tierOf(tier: String): DriveTier? {
        if (tier.isBlank()) return null
        return tiers.firstOrNull { runCatching { Regex(it.match).containsMatchIn(tier) }.getOrDefault(false) }
    }

    val supportsSearch: Boolean get() = api.search != null
    val supportsTranscoded: Boolean get() = api.transcoded != null
    val supportsShares: Boolean get() = api.shareToken != null && api.listShare != null && share.linkPattern.isNotBlank()
    val supportsSaving: Boolean get() = api.saveFromShare != null && api.task != null && api.createFolder != null

    /** 自己网盘那个数据源的 id. */
    val driveMediaSourceId: String get() = "$id-drive"

    companion object {
        /** 按语言标记挑: 完全相同 → 同一语言 (`zh-TW` 对 `zh-HK` 不算) → 只写语言 (`zh`). */
        internal fun localized(values: Map<String, String>, languageTag: String): String? {
            if (values.isEmpty()) return null
            val tag = languageTag.replace('_', '-')
            values.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.let { return it.value }
            val language = tag.substringBefore('-')
            return values.entries.firstOrNull { it.key.equals(language, ignoreCase = true) }?.value
        }
    }
}

/**
 * @property hosts `{host}` 的取值. 连接层失败 (连不上、超时) 时换下一个重试, 并记住最后能用的那个
 * @property userAgent 请求接口与播放时默认用的 UA, 空表示不带
 * @property headers 每个接口请求都带的请求头 (除非 [DriveRequest.common] 为 false)
 * @property query 每个接口请求都带的参数 (除非 [DriveRequest.common] 为 false)
 * @property rotatingCookies 服务端会在响应里改写的登录 Cookie; 带登录态的请求收到这些 `Set-Cookie` 时合并回账号的 Cookie
 */
@Serializable
data class DriveHttpConfig(
    val hosts: List<String> = emptyList(),
    val userAgent: String = "",
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val rotatingCookies: List<String> = emptyList(),
    val connectTimeoutMillis: Long = 5_000,
    val socketTimeoutMillis: Long = 8_000,
    val requestTimeoutMillis: Long = 20_000,
)

/**
 * 接口响应的公共外壳: 从哪里看成功与否.
 *
 * @property code 错误码的路径, 空表示不看; 取不到值时算成功
 * @property okCodes 算成功的错误码
 * @property status 状态的路径, 空表示不看
 * @property authStatuses 状态是这些值时算没登录或登录失效 (HTTP 401 / 403 总是算)
 * @property message 错误说明的路径
 * @property capacityLimitCodes 网盘空间不够时的错误码 (转存时据此清理转存文件夹)
 */
@Serializable
data class DriveEnvelope(
    val code: String = "",
    val okCodes: List<String> = listOf("0"),
    val status: String = "",
    val authStatuses: List<String> = emptyList(),
    val message: String = "",
    val capacityLimitCodes: List<String> = emptyList(),
)

/**
 * 文件条目 (搜索、列目录、列分享的结果里的每一项) 里各字段的路径. 空表示没有这个字段.
 *
 * @property isDir 是不是文件夹; 值在 [dirValues] 里算文件夹
 * @property updatedAt 修改时间 (毫秒时间戳; [updatedAtSeconds] 时是秒)
 * @property duration 视频时长 (秒); 与文件大小一起算出平均码率, 显示在选源列表里
 * @property category 文件类别; 在 [videoCategories] 里的算视频. 没有类别字段时按扩展名认视频
 * @property shareToken 分享里的文件转存时要带的凭证
 */
@Serializable
data class DriveFileFields(
    val id: String = "id",
    val name: String = "name",
    val parentId: String = "",
    val isDir: String = "",
    val dirValues: List<String> = listOf("true"),
    val size: String = "",
    val updatedAt: String = "",
    val updatedAtSeconds: Boolean = false,
    val videoHeight: String = "",
    val duration: String = "",
    val category: String = "",
    val videoCategories: List<String> = emptyList(),
    val shareToken: String = "",
)

/**
 * 一个请求的模板.
 *
 * @property url 地址, 可带变量 (如 `https://{host}/api/list`)
 * @property query 参数; 值是列表变量时用逗号连起来
 * @property body JSON 请求体 (有它就按 JSON 发). 字符串值整个是一个变量 (如 `"{fileIds}"`) 时换成变量的 JSON 值 (列表变量是数组),
 * 否则在字符串里替换
 * @property userAgent 这个请求用的 UA, 空表示用 [DriveHttpConfig.userAgent]
 * @property cookie 带账号的 Cookie
 * @property common 带 [DriveHttpConfig.headers] 与 [DriveHttpConfig.query]
 * @property envelope 按 [DriveEnvelope] 检查响应; false 时只看 HTTP 状态
 */
@Serializable
data class DriveRequest(
    val method: String = "GET",
    val url: String,
    val query: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String = "",
    val cookie: Boolean = true,
    val common: Boolean = true,
    val envelope: Boolean = true,
)

/**
 * 网盘的各项操作. 没有的操作对应的功能不可用 (例如没有 [search] 就不能按名字搜自己网盘).
 *
 * 各操作可用的变量:
 * - [tier] / [nickname]: 无
 * - [search]: `{keyword}` `{page}` `{pageSize}`
 * - [listFolder]: `{folderId}` `{page}` `{pageSize}`
 * - [download]: `{fileIds}` (列表) `{fileId}` (第一个)
 * - [transcoded]: `{fileId}`
 * - [createFolder]: `{name}` `{parentId}`
 * - [deleteFiles]: `{fileIds}`
 * - [task]: `{taskId}` `{retry}` (第几次查, 从 0 起)
 * - [shareToken]: `{shareId}` `{passcode}`
 * - [listShare]: `{shareId}` `{passcode}` `{shareToken}` `{folderId}` `{page}` `{pageSize}`
 * - [saveFromShare]: `{shareId}` `{passcode}` `{shareToken}` `{fileIds}` `{fileTokens}` (列表, 与 fileIds 一一对应) `{folderId}` (存到哪)
 *   `{shareRootId}` (分享的根, 见 [DriveShareConfig.rootFolderId])
 *
 * @property tier 账号档位 (如会员类型), 用来显示与决定播放并发数 (见 [CloudDriveProtocol.tiers])
 * @property nickname 账号昵称
 * @property createFolder 取值是新文件夹的 id
 * @property deleteFiles 取值是后台任务 id (空表示已经完成)
 * @property saveFromShare 取值是后台任务 id; 任务完成时 [DriveTaskOp.resultIds] 给出新文件的 id
 */
@Serializable
data class DriveApiConfig(
    val tier: DriveValueOp? = null,
    val nickname: DriveValueOp? = null,
    val search: DriveListOp? = null,
    val listFolder: DriveListOp? = null,
    val download: DriveDownloadOp? = null,
    val transcoded: DriveTranscodedOp? = null,
    val createFolder: DriveValueOp? = null,
    val deleteFiles: DriveValueOp? = null,
    val task: DriveTaskOp? = null,
    val shareToken: DriveShareTokenOp? = null,
    val listShare: DriveListOp? = null,
    val saveFromShare: DriveValueOp? = null,
)

/** 取一个值的操作. */
@Serializable
data class DriveValueOp(val request: DriveRequest, val value: String = "")

/**
 * 列文件的操作.
 *
 * @property items 文件条目的路径 (各字段见 [CloudDriveProtocol.file])
 * @property total 总数的路径, 空表示不知道 (取到不满一页为止)
 * @property pageSize 每页几个, 即 `{pageSize}`
 */
@Serializable
data class DriveListOp(
    val request: DriveRequest,
    val items: String,
    val total: String = "",
    val pageSize: Int = 100,
)

/**
 * 取直链. 条目的 id、文件名、所在文件夹按 [CloudDriveProtocol.file] 取.
 *
 * @property url 条目里直链的路径
 */
@Serializable
data class DriveDownloadOp(
    val request: DriveRequest,
    val items: String,
    val url: String,
)

/**
 * 取转码后的播放地址, 播放时取能用的档位里画面最高的.
 *
 * @property accessible 这一档能不能用 (值在 [accessibleValues] 里算能用), 空表示都能用
 */
@Serializable
data class DriveTranscodedOp(
    val request: DriveRequest,
    val items: String,
    val url: String,
    val height: String = "",
    val accessible: String = "",
    val accessibleValues: List<String> = listOf("true"),
)

/**
 * 查后台任务 (删除、转存) 的进度.
 *
 * @property status 状态的路径; 在 [done] 里算完成, 在 [failed] 里算失败, 其余继续等
 * @property resultIds 任务产出的文件 id (转存后的新文件) 的路径; 有值时算完成
 */
@Serializable
data class DriveTaskOp(
    val request: DriveRequest,
    val status: String,
    val done: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val message: String = "",
    val resultIds: String = "",
    val maxPolls: Int = 10,
    val intervalMillis: Long = 1_000,
)

/**
 * 打开分享. 查看分享的请求都不带账号 Cookie, 出错一律算分享不可用 (与登录无关).
 *
 * @property token 之后列分享、转存要带的令牌 (`{shareToken}`) 的路径, 空表示不需要
 * @property title 分享标题的路径
 */
@Serializable
data class DriveShareTokenOp(
    val request: DriveRequest,
    val token: String = "",
    val title: String = "",
)

/**
 * @property requiredCookies 登录后的 Cookie 必须带的名字 (手填 Cookie 与扫码时据此判断是不是登录态)
 * @property cookieHint 手填 Cookie 时的说明 (去哪里复制)
 */
@Serializable
data class DriveLoginConfig(
    val requiredCookies: List<String> = emptyList(),
    val cookieHint: String = "",
    val qr: DriveQrLogin? = null,
)

/**
 * 扫码登录: 申请令牌 → 显示二维码 → 轮询 → (可选) 用票据换登录 Cookie. 各步响应里的 `Set-Cookie` 合起来就是登录 Cookie.
 *
 * @property token 令牌的路径; 可用变量 `{requestId}`
 * @property okStatuses 申请令牌时算成功的状态 ([status] 路径), 空表示不看
 * @property content 二维码内容, 可用 `{token}`
 * @property poll 轮询请求, 可用 `{token}` `{requestId}`
 * @property confirmedStatuses 轮询时算已确认的状态 (同时要取到 [ticket])
 * @property expiredStatuses 轮询时算二维码失效的状态
 * @property ticket 确认后票据的路径, 即 [exchange] 里的 `{ticket}`
 * @property exchange 用票据换 Cookie, 没有时轮询那一步的 Cookie 就是登录 Cookie
 * @property nickname [exchange] 响应里昵称的路径
 * @property appName 扫码用的 App 名, 界面上写「用 xxx 扫码」
 * @property mobileOpen 手机上直接唤起 App 确认的链接 (控制台用), 可用 `{url}` (二维码内容, 已做 URL 编码)
 */
@Serializable
data class DriveQrLogin(
    val start: DriveRequest,
    val token: String,
    val status: String = "",
    val okStatuses: List<String> = emptyList(),
    val content: String,
    val poll: DriveRequest,
    val confirmedStatuses: List<String> = emptyList(),
    val expiredStatuses: List<String> = emptyList(),
    val ticket: String = "",
    val exchange: DriveRequest? = null,
    val nickname: String = "",
    val appName: String = "",
    val mobileOpen: DriveMobileOpen? = null,
    val timeoutSeconds: Int = 300,
    val pollIntervalSeconds: Int = 2,
)

/**
 * 手机网页上唤起 App 完成扫码确认.
 *
 * @property android 安卓上的唤起链接
 * @property ios iOS 上的唤起链接
 * @property inAppUserAgent 页面本身开在那个 App 里时 UA 带的标记: 这时直接打开二维码内容
 */
@Serializable
data class DriveMobileOpen(
    val android: String = "",
    val ios: String = "",
    val inAppUserAgent: String = "",
)

/**
 * @property headers 播放器请求直链、转码地址时带的请求头, 可用 `{cookie}` (账号 Cookie) 与 `{userAgent}` ([DriveHttpConfig.userAgent])
 * @property parallelConnections 原文件直链默认并发几个连接分块取 (每个连接限速的网盘用), 0 或 1 表示单连接; 档位可覆盖
 */
@Serializable
data class DrivePlaybackConfig(
    val headers: Map<String, String> = emptyMap(),
    val parallelConnections: Int = 0,
)

/**
 * @property match 正则, 对上账号档位就是这一档
 * @property label 显示名
 * @property labels 其它语言的显示名 (同 [CloudDriveProtocol.names])
 * @property parallelConnections 这一档播放原文件的并发连接数, null 表示用 [DrivePlaybackConfig.parallelConnections]
 */
@Serializable
data class DriveTier(
    val match: String,
    val label: String = "",
    val labels: Map<String, String> = emptyMap(),
    val parallelConnections: Int? = null,
) {
    fun displayLabel(languageTag: String): String = CloudDriveProtocol.localized(labels, languageTag) ?: label
}

/**
 * @property linkPattern 分享链接的正则 (在任意文字里找), 第 1 组是分享 id, 第 2 组 (可选) 是链接里带着的提取码.
 * 链接里没带提取码时, 在它后面到下一个链接之前的文字里找「提取码 / 密码 / 访问码」
 * @property url 分享页地址, 可用 `{shareId}`; 也是分享资源占位地址的前缀
 * @property rootFolderId 分享的根文件夹 id
 */
@Serializable
data class DriveShareConfig(
    val linkPattern: String = "",
    val url: String = "",
    val rootFolderId: String = "0",
)

/**
 * @property file 自己网盘文件的占位地址, 必须是 http(s), 可用 `{fileId}`; 播放时由解析器认出来再取直链. 误打开时最好落在网盘的网页上
 * @property folder 文件夹的网页地址 (资源的原始链接), 可用 `{folderId}`
 */
@Serializable
data class DriveLinkTemplates(
    val file: String = "",
    val folder: String = "",
)

/**
 * 这个网盘以前由专门的代码支持时留下的数据, 用协议接管时认领过来 (登录态、记下的文件夹、添加的分享、数据源).
 *
 * @property accountKey 旧账号设置的键 (字段同 [me.him188.ani.app.data.models.preference.CloudDriveAccount])
 * @property addedSharesKey 旧「添加的分享」设置的键
 * @property driveFactoryId 旧「自己网盘」数据源的类型
 * @property addedSharesFactoryId 旧「我添加的分享」数据源的类型
 * @property shareSearchFactoryId 旧「分享搜索」数据源的类型 (参数的 `config` 原样搬过来)
 */
@Serializable
data class DriveLegacyData(
    val accountKey: String = "",
    val addedSharesKey: String = "",
    val driveFactoryId: String = "",
    val addedSharesFactoryId: String = "",
    val shareSearchFactoryId: String = "",
)
