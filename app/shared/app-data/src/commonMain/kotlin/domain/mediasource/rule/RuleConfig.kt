/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalSerializationApi::class)

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import me.him188.ani.app.domain.mediasource.directapi.Transform
import me.him188.ani.app.domain.mediasource.web.SelectorAutoMatchConfig
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormat
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.SubtitleLanguage

/**
 * 规则源的规则: 站点的搜索、详情、播放三段, 每段是一串步骤 ([RuleStep]).
 *
 * 每段从一个输入开始: 搜索段是关键词 (`{keyword}`), 详情段是条目页地址 (`{subjectUrl}`), 播放段是剧集页地址 (`{episodeUrl}`).
 * 步骤依次改写「当前值」(`{value}`, 起初就是这段的输入) 或存取变量. 任何一步失败 (取不到东西), 这一段就失败;
 * [RuleStep.First] 依次尝试几个分支, 用第一个成功的.
 *
 * - 搜索段以条目列表步骤 ([RuleStep.Subjects] / [RuleStep.JsonSubjects]) 结束, 产出站点上的条目 (名字与地址).
 * - 详情段以剧集列表步骤 ([RuleStep.Episodes] / [RuleStep.JsonEpisodes] / [RuleStep.RegexEpisodes]) 结束,
 *   产出线路与各线路的剧集 (名字与地址).
 * - 播放段结束时的当前值就是视频地址; 以 [RuleStep.Sniff] (`goal = video`) 结束表示交给 WebView 打开剧集页嗅探.
 *   播放段为空时剧集地址本身就是视频地址.
 *
 * 模板 (地址、请求体、[RuleStep.Template] 等) 里用 `{名字}` 取变量: `keyword` 默认做 URL 编码, 其余默认原样;
 * 写成 `{名字:url}` / `{名字:raw}` 可以指定. 没有这个变量的 `{...}` 原样保留 (可能是 JSON 里本来的花括号).
 * 内置变量还有 `baseUrl`, 以及最近一次请求的最终地址 `pageUrl`.
 *
 * 搜到条目之后怎么挑出当前这一集, 与网页抓取 (Selector) 数据源相同: [autoMatch] 与 [matchEpisodeSortFromName] 的含义也相同.
 */
@Serializable
data class RuleConfig(
    /** 相对地址以它为基准 (已经请求过页面时以那个页面为基准). */
    val baseUrl: String = "",
    /**
     * 每个请求都带上的请求头, 例如 `Referer`. 步骤里的同名请求头优先. 播放视频时也带上 ([RuleStep.MediaHeaders] 优先).
     *
     * 一般不要写 `User-Agent`: 请求默认用与 WebView 一致的 UA, 过了站点验证得到的 Cookie 只认这个 UA.
     */
    val headers: Map<String, String> = emptyMap(),
    val search: List<RuleStep> = emptyList(),
    val detail: List<RuleStep> = emptyList(),
    val play: List<RuleStep> = emptyList(),
    val autoMatch: SelectorAutoMatchConfig = SelectorAutoMatchConfig.Default,
    /** 从剧集名里取集号的正则, 命名分组 `ep` 是集号. 为空表示整个剧集名就是集号文本. */
    val matchEpisodeSortFromName: String = SelectorChannelFormat.DEFAULT_MATCH_EPISODE_SORT_FROM_NAME,
    /** 自动匹配时每个关键词最多打开几个搜索结果的条目页. */
    val maxSubjects: Int = 8,
    /** 两次搜索请求之间至少间隔多久 (毫秒). */
    val requestIntervalMillis: Long = 1000,
    /** 搜到的条目页 (剧集列表) 缓存多久 (毫秒), 切集时不重新请求. */
    val searchCacheTtlMillis: Long = 2 * 60 * 60 * 1000L,
    val defaultResolution: Resolution = Resolution.R1080P,
    val defaultSubtitleLanguage: SubtitleLanguage = SubtitleLanguage.ChineseSimplified,
    /**
     * 播放段没能取到地址时, WebView 打开剧集页嗅探视频用的匹配规则, 与网页抓取数据源的同名配置相同.
     * 其中的 `addHeadersToVideo` 也用于播放段取到的地址 (步骤 [RuleStep.MediaHeaders] 优先).
     *
     * 默认不跳转「嵌套页」: WebView 本来就加载页面里的 iframe, 子框架里的视频请求同样拦得到; 而嵌套页的默认正则会把
     * `video_m3u8/…?` 这样的视频地址、`xvip.…/main.css?` 这样的静态资源当成播放器页面反复跳转, 直到超时.
     */
    val matchVideo: SelectorSearchConfig.MatchVideoConfig = SelectorSearchConfig.MatchVideoConfig(enableNestedUrl = false),
)

/**
 * 规则里的一步. JSON 里以 `op` 区分种类.
 */
@Serializable
@JsonClassDiscriminator("op")
sealed interface RuleStep {
    /**
     * 发请求, 当前值变成响应文本, `pageUrl` 变成最终地址 (跟随重定向之后).
     *
     * @property url 地址模板, 可以是相对地址. 为空表示这一段的输入地址 (条目页 / 剧集页).
     * @property contentType 有请求体时的类型: `form` (`application/x-www-form-urlencoded`)、`json`, 或完整的 MIME.
     */
    @Serializable
    @SerialName("fetch")
    data class Fetch(
        val url: String = "",
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
        val contentType: String = "",
    ) : RuleStep

    /**
     * 用浏览器打开页面.
     *
     * - `goal = html`: 取页面内容, 与 GET 的 [Fetch] 相同 —— 直连被站点的验证 (如 Cloudflare) 挡住时都会改用浏览器加载.
     * - `goal = video`: 用于播放段, 表示交给 WebView 打开剧集页, 按 [RuleConfig.matchVideo] 嗅探视频地址;
     *   执行到它时播放段就此结束, 后面的步骤不再执行.
     */
    @Serializable
    @SerialName("sniff")
    data class Sniff(
        val goal: String = GOAL_VIDEO,
        val url: String = "",
    ) : RuleStep {
        companion object {
            const val GOAL_VIDEO = "video"
            const val GOAL_HTML = "html"
        }
    }

    /**
     * 把当前值当作 HTML, 取第 [index] 个匹配 [css] 的元素的内容.
     *
     * @property attr `text` (文本; `<script>` / `<style>` 取其内容)、`html` (内部 HTML)、`outerHtml`, 或属性名.
     * 属性名前加 `abs:` (如 `abs:href`) 按页面地址补全成绝对地址.
     */
    @Serializable
    @SerialName("select")
    data class Select(
        val css: String,
        val attr: String = "text",
        val index: Int = 0,
    ) : RuleStep

    /**
     * 在当前值里找 [pattern] 的第一个匹配, 当前值变成分组 [group] (序号或分组名, `0` 是整个匹配; 可以是空串).
     * 没有匹配, 或这个分组没参与匹配时失败.
     *
     * 规则里的正则 ([pattern]、[Replace.pattern]、[RegexEpisodes.pattern]) 先按模板展开变量再编译, 量词 `{2,3}` 不受影响;
     * 编译不过时按 JavaScript 的宽松写法重试 (不构成量词的花括号当字面量).
     */
    @Serializable
    @SerialName("regex")
    data class Regex(
        val pattern: String,
        val group: String = "1",
        val ignoreCase: Boolean = false,
    ) : RuleStep

    /**
     * 替换当前值里的内容. [regex] 为 `false` 时 [pattern] 按字面匹配; [first] 为 `true` 时只替换第一处.
     * [replacement] 是模板, 可以引用变量; 按正则替换时还可以用 `$1` 引用分组.
     */
    @Serializable
    @SerialName("replace")
    data class Replace(
        val pattern: String,
        val replacement: String = "",
        val regex: Boolean = true,
        val first: Boolean = false,
    ) : RuleStep

    /**
     * 把当前值当作 JSON, 按 [path] 取值 (路径语法与直链 API 数据源相同, 如 `data.list[0].url`、`sites[site=bangumi].id`).
     * 取到对象或数组时, 当前值是它的 JSON 文本.
     */
    @Serializable
    @SerialName("json")
    data class Json(
        val path: String,
    ) : RuleStep

    /** 当前值变成模板 [value] 展开的结果. */
    @Serializable
    @SerialName("template")
    data class Template(
        val value: String,
    ) : RuleStep

    /** 把模板 [value] 展开的结果存进变量 [name], 当前值不变. */
    @Serializable
    @SerialName("set")
    data class SetVar(
        val name: String,
        val value: String = "{value}",
    ) : RuleStep

    /**
     * 取地址 [input] 里查询参数 [name] 的值 (已解码). [variable] 非空时存进这个变量、当前值不变, 否则当前值变成它.
     */
    @Serializable
    @SerialName("query")
    data class Query(
        val name: String,
        val input: String = "{value}",
        @SerialName("var")
        val variable: String = "",
    ) : RuleStep

    /** 对当前值做字符串变换 (base64、URL 解码、大小写互换等), 与直链 API 数据源的变换相同. */
    @Serializable
    @SerialName("transform")
    data class Transforms(
        val transforms: List<Transform>,
    ) : RuleStep

    /** 依次尝试几个分支, 用第一个成功的分支的结果. 都失败则这一步失败. */
    @Serializable
    @SerialName("first")
    data class First(
        val branches: List<List<RuleStep>>,
    ) : RuleStep

    /**
     * 苹果 CMS (MacCMS) 播放页: 从脚本里的 `var player_aaaa = {...}` 取 [key] (通常是 `url`),
     * 按其中的 `encrypt` 还原 (1: `unescape`, 2: base64 再 `unescape`). 同时把 `from` 存进同名变量.
     */
    @Serializable
    @SerialName("maccmsPlayer")
    data class MacCmsPlayer(
        @SerialName("var")
        val variable: String = "player_aaaa",
        val key: String = "url",
    ) : RuleStep

    /**
     * 当前值本身是地址时不变; 否则在当前值 (页面、脚本、JSON) 里找第一个视频地址 (`.m3u8` / `.mp4` / `.flv` / `.mkv`),
     * 当前值变成它. JSON 里转义的 `\/` 会还原.
     */
    @Serializable
    @SerialName("videoUrl")
    data object VideoUrl : RuleStep

    /** 播放视频时带上的请求头. [remove] 里的请求头不带 (包括默认会带的). */
    @Serializable
    @SerialName("mediaHeaders")
    data class MediaHeaders(
        val headers: Map<String, String> = emptyMap(),
        val remove: List<String> = emptyList(),
    ) : RuleStep

    /**
     * 条目列表 (搜索段的最后一步). 把当前值当作 HTML, 用 [list] 里第一个能选出元素的选择器选出每个条目 (卡片).
     *
     * 每个条目的地址: [link] 选出的元素的 [linkAttr]; 不填 [link] 时取卡片本身 (若是链接) 或卡片里第一个链接.
     * 名字: [name] 选出的元素的 [nameAttr] (为空取文本); 不填 [name] 时依次试链接的 `title`、图片的 `alt`、标题元素的文本、链接的文本.
     * [linkPattern] 非空时只保留地址里含有它的条目. 地址相同的条目只留第一个.
     */
    @Serializable
    @SerialName("subjects")
    data class Subjects(
        val list: List<String>,
        val name: String = "",
        val nameAttr: String = "",
        val link: String = "",
        val linkAttr: String = "href",
        val linkPattern: String = "",
    ) : RuleStep

    /**
     * 条目列表, 来自 JSON. [listPath] 是列表的路径, 每一项里按 [namePath] 取名字;
     * 地址按 [urlPath] 取, 取不到时用 [urlTemplate] 拼 (模板里可以用 `{id}` 即 [idPath] 取到的值, 以及该项的任意字段).
     */
    @Serializable
    @SerialName("jsonSubjects")
    data class JsonSubjects(
        val listPath: String = "",
        val idPath: String = "id",
        val namePath: String = "name",
        val urlPath: String = "",
        val urlTemplate: String = "",
    ) : RuleStep

    /**
     * 线路与剧集列表 (详情段的最后一步). 把当前值当作 HTML, [lists] 里第一个能选出元素的选择器选出各线路的剧集容器,
     * 每个容器里用 [items] 选出剧集 (取文本当名字、`href` 当地址).
     * [tabs] 里第一个能选出元素的选择器选出线路名, 与容器按顺序对应; 不填或对不上时线路没有名字.
     * [reverse] 为 `true` 时把每条线路的剧集倒过来 (站点按新到旧排列时).
     */
    @Serializable
    @SerialName("episodes")
    data class Episodes(
        val lists: List<String>,
        val tabs: List<String> = emptyList(),
        val items: String = "a",
        val reverse: Boolean = false,
    ) : RuleStep

    /**
     * 剧集列表, 来自 JSON. [listPath] 是剧集列表的路径; 每一项按 [namePath] 取名字,
     * 地址按 [urlPath] 取或用 [urlTemplate] 拼 (同 [JsonSubjects]). 只有一条线路, 名字是 [channelName] (可为空).
     */
    @Serializable
    @SerialName("jsonEpisodes")
    data class JsonEpisodes(
        val listPath: String = "",
        val idPath: String = "id",
        val namePath: String = "name",
        val urlPath: String = "",
        val urlTemplate: String = "",
        val channelName: String = "",
    ) : RuleStep

    /**
     * 剧集列表, 用正则从当前值 (任意文本) 里找出全部匹配: 命名分组 `url` 是地址 (必需), `name` 是名字,
     * `channel` 是线路名 (同名的归为一条线路). 名字为空时记作「第 N 集」(N 是它在线路里的位置).
     */
    @Serializable
    @SerialName("regexEpisodes")
    data class RegexEpisodes(
        val pattern: String,
        val ignoreCase: Boolean = false,
    ) : RuleStep
}
