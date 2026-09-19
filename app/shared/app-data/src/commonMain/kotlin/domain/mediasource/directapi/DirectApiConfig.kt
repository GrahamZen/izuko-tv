/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「调接口拿直链」类数据源的配置.
 *
 * 这类站点的形态是固定的三步: 用条目名搜到站内条目 -> 找到对应的剧集 -> 取该集的播放地址.
 * 把这三步写成配置, 就不必为每个站点写一个数据源.
 *
 * 响应可以是 JSON 或 protobuf ([ResponseFormat]), 取值用同一套路径语法 (见 [selectByPath]),
 * 地址上的混淆用 [Transform] 流水线还原.
 */
@Serializable
data class DirectApiConfig(
    /** 接口根地址, 在 URL 模板里用 `{baseUrl}` 引用. */
    val baseUrl: String = "",
    /** 第一步: 用条目名搜索, 拿到站内条目 id. */
    val subject: SubjectConfig = SubjectConfig(),
    /** 第二步: 在剧集列表里找到要播的那一集. */
    val episode: EpisodeConfig = EpisodeConfig(),
    /** 第三步: 取该集的播放地址. */
    val lines: LinesConfig = LinesConfig(),
    /**
     * 请求时使用的 User-Agent. 留空则用本机浏览器的真实 UA, 取不到时才退回 HTTP client 自带的那个
     * (那个是写死的常量, 每台设备一模一样, 站点按它就能认出这个应用的全部用户).
     */
    val userAgent: String = "",
) {
    @Serializable
    data class SubjectConfig(
        val request: RequestConfig = RequestConfig(),
        /** 搜索结果条目里, 站内条目 id 的路径. 它会成为后续 URL 模板里的 `{subjectId}`. */
        val idPath: String = "",
        /**
         * 校验请求. 站内搜索通常不准, 需要再请求一次条目详情, 确认它对应的正是我们要的 bangumi 条目.
         * 为 `null` 表示不校验, 直接用第一个搜索结果.
         */
        val verify: RequestConfig? = null,
        /** 校验请求响应里, bangumi 条目 id 的路径. 取到的值需与 `{bangumiSubjectId}` 相等才算匹配. */
        val verifyPath: String = "",
        /** 最多用前几个条目名去搜 (条目名按 中文名 -> 原名 -> 别名 排列). */
        val maxNames: Int = 3,
        /** 每个条目名最多校验前几个搜索结果. */
        val maxCandidates: Int = 5,
    )

    @Serializable
    data class EpisodeConfig(
        val request: RequestConfig = RequestConfig(),
        /**
         * 剧集条目里 bangumi 分集 id 的路径. 与 `{bangumiEpisodeId}` 相等即精确命中.
         * 为空则只能按集号匹配.
         */
        val matchPath: String = "",
        /** 剧集条目里集号的路径, 用于 [matchPath] 匹配不上时按集号回退匹配. */
        val sortPath: String = "",
        /** 命中的剧集条目里, 要传给第三步的值的路径 (通常就是集号). 它会成为 `{episodeId}`. */
        val valuePath: String = "",
    )

    @Serializable
    data class LinesConfig(
        val request: RequestConfig = RequestConfig(),
        /** 播放地址的路径. */
        val urlPath: String = "",
        /** 线路标题的路径 (通常是"第 X 集"或带清晰度的说明), 用于解析清晰度与字幕语言. */
        val titlePath: String = "",
        /** 线路名的路径. 会显示在数据源选择器里, 也会作为偏好保存, 所以要稳定. */
        val channelPath: String = "",
        /** 条目名的路径, 可为空. */
        val subjectNamePath: String = "",
        /** 还原播放地址的变换流水线. */
        val urlTransforms: List<Transform> = emptyList(),
        /** 处理线路名的变换流水线, 例如去掉会变的优先级后缀. */
        val channelTransforms: List<Transform> = emptyList(),
        /**
         * 线路名的显示名映射. 有的站点只给代号 (例如 "age"), 在这里翻成看得懂的站名.
         * 没列出的代号原样显示.
         */
        val channelNames: Map<String, String> = emptyMap(),
        /** 每条线路最多保留几个地址, 0 表示全部保留. 有的站一集有几十条. */
        val maxPerChannel: Int = 0,
    )

    /**
     * 一个 HTTP GET 请求.
     *
     * [url] 里可以用这些变量: `{baseUrl}` `{subjectName}` `{bangumiSubjectId}` `{bangumiEpisodeId}`
     * `{episodeSort}` `{episodeEp}`, 以及前面几步解析出的 `{candidateId}` `{subjectId}` `{episodeId}`.
     * 除 `{baseUrl}` 外的值都会做 URL 编码.
     */
    @Serializable
    data class RequestConfig(
        val url: String = "",
        val format: ResponseFormat = ResponseFormat.Json,
        /** 列表所在的路径. 为空表示响应本身就是列表. */
        val itemsPath: String = "",
    )
}

@Serializable
enum class ResponseFormat {
    @SerialName("json")
    Json,

    @SerialName("protobuf")
    Protobuf,

    /** protobuf 字节被包成了 JSON 数字数组, 例如 `[10,140,2,...]`. */
    @SerialName("protobufInJsonBytes")
    ProtobufInJsonBytes,
}

/**
 * 字符串变换. 站点常把播放地址做一层混淆, 用这些原语还原.
 */
@Serializable
data class Transform(
    val op: TransformOp,
    /** [TransformOp.RemoveCharAt] 用: 要删掉的字符下标. */
    val index: Int = 0,
    /** 分隔符 / 正则 / 要拼接的内容, 按 [op] 而定. */
    val value: String = "",
    /** [TransformOp.RegexReplace] 用: 替换成什么. */
    val replacement: String = "",
)

@Serializable
enum class TransformOp {
    /** 删掉 [Transform.index] 处的字符. 有的站会往 base64 里插一个垃圾字符. */
    @SerialName("removeCharAt")
    RemoveCharAt,

    /** base64 解码. 会自动补 `=`, 标准字母表解不出时再试 URL-safe 字母表. */
    @SerialName("base64Decode")
    Base64Decode,

    /** 大小写互换. */
    @SerialName("swapCase")
    SwapCase,

    @SerialName("urlDecode")
    UrlDecode,

    @SerialName("substringBefore")
    SubstringBefore,

    @SerialName("substringAfter")
    SubstringAfter,

    @SerialName("substringBeforeLast")
    SubstringBeforeLast,

    @SerialName("substringAfterLast")
    SubstringAfterLast,

    @SerialName("regexReplace")
    RegexReplace,

    @SerialName("prepend")
    Prepend,

    @SerialName("append")
    Append,

    @SerialName("trim")
    Trim,
}
