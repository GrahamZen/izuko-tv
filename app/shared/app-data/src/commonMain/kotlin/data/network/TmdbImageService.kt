/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.compose.runtime.mutableStateMapOf
import androidx.datastore.core.DataStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.settings.NetworkTroubleBeacon
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.datasources.bangumi.BangumiApiProvider
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 从 TMDB 获取条目的横版背景图 (backdrop), 用于 TV 详情页 Hero 背景等.
 *
 * Bangumi 只有竖版封面; TMDB 的 backdrop 是"剧"级别的, 用日文原名搜索命中即可,
 * 搜不到时沿 Bangumi 关联条目回溯到根条目再搜 (见 [searchLayered]).
 * 不涉及季/集映射 (TMDB 与 Bangumi 的季划分对不齐的问题只影响以后的分集缩略图,
 * 届时匹配键须用分集播出日期而非集号, 见 fork 内验证: 無職転生 两 cour 合并为 TMDB S1,
 * 進撃の巨人 Final Season 的 Bangumi 60 话对应 TMDB S4E1).
 *
 * 结果按 subjectId 持久缓存 (含"确认无图"的负缓存, 存空串); 网络错误不缓存.
 * 未配置 `ani.tmdb.api.token` 时直接返回 null, 功能自动关闭.
 */
class TmdbImageService(
    httpClientProvider: HttpClientProvider,
    private val dataStore: DataStore<TmdbImageCache>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
    /** 见 [seriesIndexService]; 生产由 Koin 注入单例, 测试留 null 自建. */
    private val injectedSeriesIndexService: SubjectSeriesIndexService? = null,
) {
    /**
     * TMDB 与 bangumi 两边的请求共用. **带 bangumi token**: 这个 client 也会去打
     * `api.bgm.tv/v0/subjects/{id}/subjects` (系列关系兜底), 而 R18 条目匿名访问是 404 ——
     * 见 seriesIndexService 那里的说明。token 只会加到 `*.bgm.tv` 上, TMDB 请求不受影响
     * (见 UseBangumiTokenFeature).
     */
    private val client = httpClientProvider.get(useBangumiToken = true)

    /**
     * 条目的系列索引, 用于解析系列主条目名 (见 [resolveLineageViaSeriesIndex]).
     */
    /**
     * 系列索引. **优先用注入进来的那一个** (Koin 单例, 同时给 `SubjectRelationsRepository` 用):
     * 它按 subjectId 缓存 BFS 结果, 而这条 BFS 最多 20 跳 —— 各建一个实例就是各存一份缓存,
     * 同一个条目的 BFS 会算两遍 (2026-09-06 真机日志: subject 638494 / 310194 各两次).
     *
     * 没注入时 (测试) 自建一个: **必须带 bangumi token**, R18 条目的
     * `/p1/subjects/{id}/relations` 匿名访问一律 404 (条目本身也 404), 于是系列索引整条失败
     * —— 表现是这类条目的 hero 背景/剧照要多等两个失败请求 (p1 404 → v0 兜底) 才开始匹配,
     * 甚至彻底没图 (2026-09-06 从真机日志抓到: subject 79201/377273 都是这样).
     * Koin 那个单例用的 `BangumiApiProvider` 本来就是 `useBangumiToken = true`, 语义一致.
     *
     * by lazy: 自建那条要用下面才声明的 resolveScope, 而属性按声明顺序初始化.
     */
    private val seriesIndexService: SubjectSeriesIndexService by lazy {
        injectedSeriesIndexService ?: SubjectSeriesIndexService(
            BangumiApiProvider(httpClientProvider.get(useBangumiToken = true)).subjectApi,
            // BFS 归 resolveScope: 调用方 (collectLatest 底下的 hero / 详情页) 走开不该把
            // 十几个请求的活儿作废, 见 SubjectSeriesIndexService 的 scope 参数
            scope = resolveScope,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 短连接超时: api.themoviedb.org 解析出的部分 IP 直连不通, 全局默认 30s 连接超时
     * 会让单个请求挂满半分钟才轮到重试 (实测 30183ms, 表现为 backdrop 半分钟才出来).
     * 5s 连不上就报错, 交给全局 HttpRequestRetry 换连接重试.
     */
    private fun HttpRequestBuilder.shortConnectTimeout() {
        timeout {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 20_000
        }
    }

    /**
     * 关联回溯兜底专用的超时, 比 [shortConnectTimeout] 更短.
     *
     * 那只是"直搜没命中时去 Bangumi 要个根条目名"的兜底, 却卡在 hero 出图的关键路径上;
     * 而 `api.bgm.tv` 在部分网络下和 TMDB 一样被 DNS 投毒, 5 秒连接超时意味着 IPv4 + IPv6
     * 各等满 = 10 秒白等 (issue #7 报告者日志实测单次 9.2 秒). 2 秒够通的网络跑完一次
     * 关联查询, 不通的也早点让路去走削字兜底.
     */
    private fun HttpRequestBuilder.lineageTimeout() {
        timeout {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 6_000
        }
    }

    /**
     * 当前生效的 API 域名下标 ([API_BASE_URLS]). 回退成功后记住, 免得之后每个请求都先在
     * 不通的那个上白等一次超时. 只在进程内有效, 冷启动重新从主域名开始试.
     */
    private var activeApiBaseIndex = 0

    /**
     * 用 Ani 的关系索引解析系列主条目名 —— 墙内可直连的那条路.
     *
     * 接口一次就返回 `seriesMainSubjectNames`, 既不必像 Bangumi 那条路逐跳回溯, 也不用再
     * 查一次条目详情拿名字. 上游已把绝大多数请求从 bgm 迁到自家服务器, 这里跟上.
     *
     * 代价是拿不到「主线故事」出边, 判不了衍生作, 所以 [BgmLineage.isDerivative] 给 **null
     * (未知)** 而不是 false —— false 的语义是"确认正传", 会让分集索引把 S0 殿后, 衍生条目
     * 就此错拿正片数据. 未知则维持原顺序, 与拿不到关系数据时一致.
     * 拿不到系列主条目 (或主条目就是自己) 时返回 null, 由调用方回落到 Bangumi.
     *
     * **排除条目自己的每一个名字, 不只是 [originalName]**: 这个接口返回的是系列内**全部**
     * 条目名 (原名与中文名各算一个), 只比原名的话, 「うらおん!」会把紧跟其后的自身中文名
     * 「K-On!:Ura-On!」当成系列主条目名 —— 它在 TMDB 上什么都搜不到, 而真正的母条目
     * 「けいおん！」只在 Bangumi 的「主线故事」出边上, 于是回落被这个假结果彻底挡死.
     * 归一化后比, 因为同系列条目名常只差标点 (`うらおん!` / `うらおん!!`).
     */
    private suspend fun resolveLineageViaSeriesIndex(
        subjectId: Int,
        originalName: String,
        nameCn: String,
    ): BgmLineage? = try {
        val relations = seriesIndexService.getSubjectRelationIndex(subjectId)
        val rootName = tmdbSeriesRootName(relations.seriesRootNames, originalName, nameCn)
        if (rootName == null) {
            null
        } else {
            logger.info { "Resolved lineage for $subjectId via series index: root=$rootName" }
            BgmLineage(rootName = rootName, isDerivative = null, viaAni = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to resolve lineage via Ani relations for subject $subjectId" }
        null
    }

    /**
     * 发 TMDB API 请求, 主域名不通时自动换备用域名重试一次. [path] 以 `/` 开头, 不含 base.
     *
     * 只对连不上/超时这类异常回退: 4xx 是 token 或参数的问题, 换域名结果一样, 直接抛出.
     */
    private suspend fun HttpClient.getApi(
        path: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val firstIndex = activeApiBaseIndex
        try {
            return get("${API_BASE_URLS[firstIndex]}$path", block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            throw e
        } catch (e: Exception) {
            val fallbackIndex = (firstIndex + 1) % API_BASE_URLS.size
            if (fallbackIndex == firstIndex) throw e
            logger.warn(e) {
                "TMDB API ${API_BASE_URLS[firstIndex]} unreachable, retrying on ${API_BASE_URLS[fallbackIndex]}"
            }
            val response = get("${API_BASE_URLS[fallbackIndex]}$path", block)
            // 成功了才记住, 免得一次偶发失败就把后续请求长期赶到备用域名上
            activeApiBaseIndex = fallbackIndex
            logger.info { "TMDB API base switched to ${API_BASE_URLS[fallbackIndex]}" }
            return response
        }
    }

    /**
     * Bangumi 关联接口连续失败的次数, 到 [LINEAGE_FAILURE_LIMIT] 就本次进程不再尝试.
     *
     * `api.bgm.tv` 在部分网络下被 DNS 投毒 (解析到无关 IP), 每次请求要 IPv4 + IPv6 各等满
     * 5 秒连接超时 = 10 秒; 而它卡在 backdrop 解析的关键路径上 (直搜没命中 → 回溯根条目名 →
     * 拿新名字重搜), 于是**每个**直搜未命中的条目都要白等这 10 秒 (issue #7 报告者日志实测).
     *
     * 这种不通是持续状态而不是偶发, 一直重试没有意义. 成功一次就清零 —— 临时抖动不该永久
     * 关掉这条兜底路径; 冷启动也重新计数, 免得用户换了网络还被上次的判定卡着.
     *
     * **原子量而不是普通 `var`**: 不同条目的解析是并发的 (只有同条目才合流, 见
     * [backdropInFlight]), 两个并发失败各读到 0 各写回 1 就丢掉一次计数, 熔断要多等一轮失败
     * 才跳; 非原子读还没有跨线程可见性保证. 清零与递增之间仍是"后写的赢" —— 那正是
     * "连续失败"该有的语义 (成功一次就该清零), 这里要修的只是别丢递增.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private val lineageFailureStreak = AtomicInt(0)

    /**
     * 在途的 backdrop 解析, **精确按 subjectId 合流** (single-flight).
     *
     * hero 的聚焦请求和网格邻居预取几乎同时进来时, 两边都会读到"缓存里还没有"然后各自打一遍
     * 网络 —— 实测同一条目的 search/alternative_titles 整组请求发了两轮 (issue #7 报告者日志),
     * 在 2 秒/请求的线路上等于白白翻倍.
     *
     * **不能用分桶锁代替** (原来是 `subjectId % 16` 的 16 把锁): 撞桶时前台聚焦请求会排在一条
     * **无关**条目的后台预取后面, 而这台机器上后台预取挂死 10 秒是常态, 最坏顶到外层 15 秒
     * 超时 —— 等于把上层 [TvHeroPrefetch] 排好的前台优先级又随机打乱一次. 概率也不低: 网格页
     * 一步发四个邻居预取, 前台撞上任一个约 1/4.
     *
     * 精确合流还多一层收益: 同条目的后来者直接 `await` 同一个结果, 而不是排队再跑一遍缓存检查.
     * 表不会无限增长 —— 任务完成即摘除, 里面只有此刻真在解析的那几条.
     */
    private val backdropInFlight = mutableMapOf<Int, Deferred<String?>>()

    private val backdropInFlightLock = Mutex()

    /**
     * 在途的分集剧照解析, 按 (subjectId, language) 合流 —— 与 [backdropInFlight] 同一套理由,
     * 只是那边漏了这一条链.
     *
     * 剧照是**按条目整季批量拉**的 (一季一个请求, 见 [getEpisodeStills]), 所以重复的代价不是
     * 一个请求而是一整组: search → 认领季 → 每季一个 season 请求. 实测同一部作品的
     * `search/tv` 在 0.6 秒内发了两遍 (2026-08-21 日志: `【推しの子】`), 因为详情页选集区与
     * TV 的"下一集剧照"两个消费端几乎同时问同一个条目, 而这里只有持久缓存、没有在途表 ——
     * 两边都读到"缓存里还没有"就各打一遍网络.
     *
     * 键里带 language: 缓存本身就是按语言记的 (`takeIf { it.language == language }`), 切了语言
     * 就该重取, 不能合流到旧语言那次上.
     */
    private val episodeStillsInFlight = mutableMapOf<EpisodeStillsKey, Deferred<TmdbEpisodeStills?>>()

    private val episodeStillsInFlightLock = Mutex()

    private data class EpisodeStillsKey(val subjectId: Int, val language: String)

    /**
     * 搜索结果的**进程内**记忆化 + 在途合流, 见 [searchAnime].
     *
     * 一次条目解析会把同一个查询串搜好几遍, 三个来源叠在一起:
     *  - **两条链各走一遍候选序列**: backdrop ([searchBackdropPath]) 与剧照 ([findTv]) 各自
     *    独立搜, 各自的 `tried` 集合互不可见;
     *  - **同一层里 tv 与 movie 都搜** ([searchAnimeRef] / [searchBackdropPath]);
     *  - **候选序列本身很长**: 逐字符回退会把拉丁后缀一格一格剥掉, 每一步都是一次真实请求
     *    (2026-08-21 实测「機動戦士ガンダムSEED DESTINY」一个条目打出 14 次 `/search/movie`:
     *    ガンダム → ガンダムS → …SE → …SEE → …SEED → …SEED D → … 一路到全名).
     * 而**同系列的兄弟条目共享其中绝大多数查询串** (根条目名那一层完全相同, 逐字符回退的前缀
     * 也大量重叠) —— 在同一系列里前后翻是最常见的浏览方式, 于是同样的搜索被反复打出去.
     *
     * 只记忆**搜索的原始结果**, 不记忆任何判定结果: 命中哪个条目、要不要扣住、季号怎么认, 全都照原样
     * 每次重算. 所以它不可能把一次错配传染给别的条目 —— 那正是跨条目共享 tv id 的风险, 这里绕开了.
     *
     * **不持久化**, 进程结束即失效: 持久化就要面对"查无结果"被永久钉住的老问题 (见
     * `TmdbImageService` 的负缓存约定), 而按条目的判定结果本来就已经持久缓存了, 这一层只需要
     * 覆盖"一次浏览会话内的重复搜索"。异常不写表 (网络错误下次仍要重试)。
     */
    private val searchMemo = mutableMapOf<SearchKey, List<TmdbSearchResult>>()

    private val searchInFlight = mutableMapOf<SearchKey, Deferred<List<TmdbSearchResult>>>()

    private val searchMemoLock = Mutex()

    /**
     * 只有查询串与 tv/movie 进键 —— 年份为什么不进, 见 [searchRawResults].
     */
    private data class SearchKey(val query: String, val type: String, val language: String? = null)

    /**
     * 承载合流任务的作用域: 任务不能挂在**发起者**的协程上 —— 发起者 (某次聚焦) 被取消时,
     * 合流到它身上的其他调用方不该跟着一起失败, 而且这条链跑完写进缓存对谁都有用.
     */
    private val resolveScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * 代理设置页的连通性探测 —— **接口那一半**.
     *
     * 接口与图片本体是两个域名 (`api.tmdb.org` / `image.tmdb.org`), 在墙内**各自独立被墙**,
     * 而且方向常常相反: `api.themoviedb.org` 对大陆默认不通, 图床走 CDN 却正常 (issue #7 定论).
     * 所以这两半**分成两项各自出结果** —— 合成一个红叉的话, 用户分不清"挂代理只需覆盖接口"
     * 和"整个 TMDB 都不通", 而电视上导不出日志, 设置页那一行是唯一的自助反馈途径.
     *
     * 未配置 `ani.tmdb.api.token` 时直接算失败: 那种情况下整个功能本来就是关的
     * ([getBackdropUrl] 直接返回 null), 报"通"只会让人以为图马上就要出来了.
     *
     * 逐个域名试, 通的那个记进 [activeApiBaseIndex], 之后取图直接走它.
     *
     * **一次探测把该说的都写进日志**: 每个域名各自的耗时与失败原因、最终用了哪个 —— 用户点一次
     * 测试, 能导日志的场合就不必再来第二轮 (见 [logApiOutcome]).
     */
    suspend fun testApiConnection(): Boolean = withContext(ioDispatcher) {
        val token = currentAniBuildConfig.tmdbApiToken
        if (token.isBlank()) {
            // 不打这条的话, "这个包没配 token" 和 "网络不通" 在日志里长得一模一样 (都是静默失败)
            logger.warn { "TMDB API test: FAILED — no API token in this build" }
            return@withContext false
        }
        val attempts = mutableListOf<String>()
        val reachableIndex = API_BASE_URLS.indices.firstOrNull { index ->
            val base = API_BASE_URLS[index]
            val mark = TimeSource.Monotonic.markNow()
            try {
                // /configuration 是最轻的鉴权端点, 顺带验证 token 有效 (token 不对是 401)
                val status = client.use {
                    get("$base/configuration") {
                        bearerAuth(token)
                        shortConnectTimeout()
                        expectSuccess = false
                    }.status
                }
                attempts += "$base ${if (status.isSuccess()) "ok" else "$status"} in ${mark.elapsedNow().inWholeMilliseconds}ms"
                status.isSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += "$base ${e::class.simpleName ?: "error"} in ${mark.elapsedNow().inWholeMilliseconds}ms"
                false
            }
        }
        if (reachableIndex == null) {
            logger.warn { "TMDB API test: FAILED — ${attempts.joinToString("; ")}" }
            return@withContext false
        }
        activeApiBaseIndex = reachableIndex
        // 用到备用域名时明确说出来: 主用的 api.tmdb.org 是 TMDB 不宣传的历史别名, 哪天下线或
        // 被墙, 这行日志是唯一能看出来的地方
        logger.info {
            "TMDB API test: ok via ${API_BASE_URLS[reachableIndex]}" +
                    "${if (reachableIndex > 0) " (fallback)" else ""} — ${attempts.joinToString("; ")}"
        }
        true
    }

    /**
     * 代理设置页的连通性探测 —— **图片 CDN 那一半**, 与 [testApiConnection] 各自独立出结果.
     *
     * 只看能否拿到 HTTP 响应, 不看状态码 (见 [IMAGE_PROBE_URL]); 被墙的表现是连不上或超时.
     * 不检查 token: 图床是公开 CDN, 没 token 也该照常通 —— 这样"没配 token"就只让接口那项变红,
     * 两项一对照就能看出是配置问题而不是网络问题.
     */
    suspend fun testImageConnection(): Boolean = withContext(ioDispatcher) {
        val mark = TimeSource.Monotonic.markNow()
        try {
            client.use {
                head(IMAGE_PROBE_URL) {
                    shortConnectTimeout()
                    expectSuccess = false
                }
            }
            logger.info { "TMDB image CDN test: ok in ${mark.elapsedNow().inWholeMilliseconds}ms" }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "TMDB image CDN test: FAILED in ${mark.elapsedNow().inWholeMilliseconds}ms" }
            false
        }
    }

    /**
     * 本进程内已解析出结果的 backdrop: subjectId -> URL, **值为 `null` = 已确认无图**,
     * 键不存在 = 还没解析过. 这是**全应用唯一的一层进程级 backdrop 热缓存**.
     *
     * 存在的意义有两个:
     *  - **同步可读** (见 [peekBackdropUrl]): [getBackdropUrl] 即使全部命中持久缓存,
     *    也要走一次 `withContext(ioDispatcher)` + DataStore 读盘, 耗时随磁盘/GC 抖动 ——
     *    详情页首帧等不到它, 只能先按"加载中"渲染, 之后再把图淡进来.
     *  - **快照可观察**: TV 各页的 hero 背景直接在组合里读它 (经 `tvHeroBackdropUrl`),
     *    预取写入后自动重组. 曾经页面层还各自养过薄映射 (最多时四份互不相认), 同一部作品
     *    从哪个页面进详情页首帧长什么样取决于哪张表恰好有货 —— 现在只有这一张.
     *
     * 有界: 超过 [RESOLVED_HOT_CACHE_MAX] 时**按写入顺序淘汰最老的一批**, 不整表清空
     * (清空会让长会话里早就解析过的条目成批退化回"先空着再淡入", 还会卡住探索页的跳转门控).
     * 淘汰只丢热缓存, 持久层还在, 代价是那些条目下次要多一次读盘.
     *
     * 写入可能来自多个 IO 线程, 淘汰簿记用 [resolvedLock] 保护; SnapshotStateMap 自身线程安全.
     */
    private val resolvedBackdropUrls = mutableStateMapOf<Int, String?>()
    private val resolvedLock = SynchronizedObject()

    /** 写入顺序簿记, 只在 [resolvedLock] 里碰; 与表一起构成"淘汰最老"而不是"整表清空". */
    private val resolvedInsertionOrder = ArrayDeque<Int>()

    /**
     * 同步读取本进程**已经解析过**的 backdrop URL, 不发请求也不读盘.
     *
     * 给"上一个页面早就查过同一条目"的场景做首帧初值用 (TV 探索/搜索/时间表页聚焦时会预取
     * 背景图, 点进详情页时结果就在这张表里). 首帧直接拿到 URL 意味着图还在 Coil 内存缓存里,
     * 详情页 Hero 一进场就是满的, 没有"先空着再淡入"那一下.
     *
     * 在组合里读是**快照订阅**: 结果落表时读方自动重组.
     *
     * @return URL; `null` = 没有图 —— 可能是已确认无图, 也可能是还没解析过,
     *   两者要区分时用 [peekBackdropResolved] (不再用空串混编在返回值里).
     */
    fun peekBackdropUrl(subjectId: Int): String? = resolvedBackdropUrls[subjectId]

    /** 该条目是否已解析过 (含"已确认无图"). 与 [peekBackdropUrl] 一起构成三态. */
    fun peekBackdropResolved(subjectId: Int): Boolean = resolvedBackdropUrls.containsKey(subjectId)

    private fun rememberResolvedBackdrop(subjectId: Int, url: String?) {
        synchronized(resolvedLock) {
            if (subjectId !in resolvedBackdropUrls) {
                resolvedInsertionOrder.addLast(subjectId)
                // 淘汰一批而不是一条: 均摊掉每次写入都要动表的开销
                if (resolvedInsertionOrder.size > RESOLVED_HOT_CACHE_MAX) {
                    repeat(RESOLVED_HOT_CACHE_EVICT_BATCH) {
                        resolvedInsertionOrder.removeFirstOrNull()?.let { resolvedBackdropUrls.remove(it) }
                    }
                }
            }
            resolvedBackdropUrls[subjectId] = url
        }
    }

    /**
     * 获取条目横版背景图 URL (w1280). [originalName] 为日文原名 (SubjectInfo.name).
     * 找不到或未配置 token 时返回 null.
     *
     * @param activeAsOfDate 该条目最新已播集的日期 (`YYYY-MM-DD`), 拿不到分集时可传开播日期.
     *   决定负缓存的有效期 (见 [negativeCacheTtl]); 不传则负缓存永久有效 (旧行为).
     * @param hints 条目侧的附加信息 (中文名 / 上映年度 / 是否影院放映), 见 [TmdbMatchHints].
     */
    suspend fun getBackdropUrl(
        subjectId: Int,
        originalName: String,
        activeAsOfDate: String? = null,
        hints: TmdbMatchHints = TmdbMatchHints.Empty,
    ): String? {
        // 本进程已解析出 URL 的直接给结果, 连 withContext 与读盘都省掉 (正缓存永久有效, 读盘只会
        // 拿到同一个 URL).
        //
        // 负缓存 (值为 null) 故意不在这里短路: 它该不该重取取决于 activeAsOfDate 与重取闸门,
        // 而这张表只记结果不记时间, 短路会把"传了更近播出日期本该重取一次"的条目钉死到进程结束.
        // `map[id]` 对"值为 null"与"没解析过"都返回 null, 正好只短路正缓存.
        resolvedBackdropUrls[subjectId]?.let { return it }
        return resolveBackdropUrl(subjectId, originalName, activeAsOfDate, hints)
    }

    /**
     * 读盘 / 走网络的慢路径, 仅由 [getBackdropUrl] 在进程内热缓存未命中时调用.
     * 同一条目的并发调用合流到同一个任务上, 见 [backdropInFlight].
     */
    private suspend fun resolveBackdropUrl(
        subjectId: Int,
        originalName: String,
        activeAsOfDate: String?,
        hints: TmdbMatchHints,
    ): String? {
        if (currentAniBuildConfig.tmdbApiToken.isBlank() || originalName.isBlank()) return null
        val task = backdropInFlightLock.withLock {
            backdropInFlight[subjectId] ?: resolveScope.async {
                try {
                    doResolveBackdropUrl(subjectId, originalName, activeAsOfDate, hints)
                } finally {
                    backdropInFlightLock.withLock { backdropInFlight.remove(subjectId) }
                }
            }.also { backdropInFlight[subjectId] = it }
        }
        return task.await()
    }

    private suspend fun doResolveBackdropUrl(
        subjectId: Int,
        originalName: String,
        activeAsOfDate: String?,
        hints: TmdbMatchHints,
    ): String? = withContext(ioDispatcher) {
        // 背景图慢不慢只能量, 不能猜: 这一行给出"这条目从开始解析到定下 URL 花了多久",
        // 以及其中有多少是系列名解析 (BFS 关系图) 的账 —— 后者是直连之后新增的开销
        // (Ani 那边 seriesMainSubjectIds 是随条目一起下发的, 零请求).
        val startMillis = currentTimeMillis()
        run {
            // 合流等待期间前一个任务可能已经把这条解析完了, 再看一眼热表, 命中就连读盘都省了
            resolvedBackdropUrls[subjectId]?.let { return@withContext it }

            val cache = readCache()
            cache.backdropUrls[subjectId]?.let { cached ->
                if (cached.isNotEmpty()) {
                    // 正缓存永久有效: URL 拿到就不会变
                    rememberResolvedBackdrop(subjectId, cached)
                    return@withContext cached
                }
                // 负缓存: 过期才重取, 且闸门保证进程内每条目只放行一次 ——
                // TMDB 侧确实没图时, 反复进出详情页不会反复空拉
                // 上次是列表页 (没有条目信息) 查空的, 这次带着 hints —— 无视 TTL 重查一次,
                // 见 [TmdbImageCache.backdropMissWithoutHints]
                val retryWithHints = hints != TmdbMatchHints.Empty &&
                        subjectId in cache.backdropMissWithoutHints
                val stale = negativeCacheStale(cache.backdropMissAt[subjectId], activeAsOfDate)
                if (!retryWithHints && !backdropRefreshGate.shouldRefresh(subjectId) { stale }) {
                    rememberResolvedBackdrop(subjectId, null)
                    return@withContext null
                }
                logger.info { "Retrying TMDB backdrop for subject $subjectId (negative cache expired)" }
            }

            // 年份否决用的基准年 (见 yearPlausible). activeAsOfDate 的语义是"最新已播集的
            // 日期", 对剧场版/OVA 就等于上映日; 对连载 TV 则晚于开播年 —— 而 tv 侧判的是下界,
            // 年份取大只会更宽松, 不会误杀. 调用方没传日期的路径 (如 TV 搜索页的邻居预取)
            // 拿不到年份, 该处退化为原行为 (不否决).
            val subjectYear = tmdbSubjectYear(activeAsOfDate.yearOrNull(), hints.screeningYear, hints.airYear)
            // 剧场版闸门比的是"本条目的候选串集合", 见 [TmdbSearchResult.matchesAnyCandidate]
            val candidateNames = if (hints.theatrical) {
                searchQueryCandidates(originalName).all.mapTo(mutableSetOf()) { normalizeForMatch(it) }
            } else {
                emptySet()
            }
            // 两次 searchLayered 共用一个 resolver: 各建各的会把 lineage 请求发两遍
            val resolveRootName = rootNameResolver(subjectId, originalName, hints.nameCn)
            val path = try {
                searchLayered(
                    originalName,
                    resolveRootName,
                    hints = hints,
                    thirdTier = { query -> searchThirdTierBackdrop(query, subjectYear) },
                    chineseTier = { name, isAlias ->
                        searchChineseBackdrop(name, subjectYear, isAlias, originalName)
                    },
                    collectionTier = {
                        searchExactCollection(originalName, hints, subjectYear, CHINESE_LANGUAGE)?.backdropPath
                    },
                ) { query, requireExactTitle ->
                    searchBackdropPath(
                        query, subjectYear, requireExactTitle,
                        theatrical = hints.theatrical, candidateNames = candidateNames,
                    )
                }
                    ?: searchLayered(
                        originalName,
                        resolveRootName,
                        hints = hints,
                    ) { query, requireExactTitle ->
                        // 海报兜底也走完整的层序: 匹配得上但那条 TMDB 记录只有 poster 的条目
                        // (Kanon 2002 东映版), 前面每一层都会空手而归.
                        // **逐字同名那道闸门要一起带过来**: 少了它, `ANGEL VOICE` 削出的 `ANGEL`
                        // 会在这一遍拿到 Angel Beats! 的海报 —— backdrop 那遍拦住的正是这个.
                        searchPosterPath(query, subjectYear, requireExactTitle)?.let { LayeredHit(it) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to search TMDB backdrop for subject $subjectId, will retry next time" }
                // 亮一下信标: 用户此刻正在浏览而背景图没出来, 下次打开动作面板就自动测一轮连通性
                NetworkTroubleBeacon.report("tmdb backdrop search failed for subject $subjectId")
                return@withContext null // 网络错误不写缓存, 下次进页面重试
            }

            val url = path?.let { "$IMAGE_BASE_URL$it" }
            logger.info {
                val elapsed = currentTimeMillis() - startMillis
                val lineage = seriesIndexService.lastStatsOf(subjectId)
                    ?.let { ", lineage ${it.requests} req/${it.millis}ms" }
                    ?: ""
                "TMDB backdrop for subject $subjectId: ${url ?: "not found"} (${elapsed}ms$lineage)"
            }
            dataStore.updateData {
                it.withBackdropResult(subjectId, url, hadHints = hints != TmdbMatchHints.Empty)
            }
            rememberResolvedBackdrop(subjectId, url)
            url
        }
    }

    /**
     * 获取条目在 TMDB 上的全部横版剧照 (backdrop) URL (w1280), 用于 TV 屏保轮播.
     *
     * 条目匹配与 [getBackdropUrl] 同一套三层搜索; 命中后再拉 `/images` 一次取全量
     * (不带 language 参数, backdrop 基本都是无语言图, 过滤反而会漏).
     * 找不到条目或未配置 token 时返回空列表, 调用方跳过该动画.
     * 结果按 subjectId 持久缓存 (空列表 = 已确认无图的负缓存); 网络错误不缓存.
     */
    suspend fun getAllBackdropUrls(
        subjectId: Int,
        originalName: String,
        activeAsOfDate: String? = null,
        /** 与 [getBackdropUrl] 喂同一份条目侧输入, 少喂一项就可能算出另一个结果. */
        hints: TmdbMatchHints = TmdbMatchHints.Empty,
    ): List<String> = withContext(ioDispatcher) {
        if (currentAniBuildConfig.tmdbApiToken.isBlank() || originalName.isBlank()) return@withContext emptyList()

        val cache = readCache()
        cache.allBackdrops[subjectId]?.let { cached ->
            if (cached.isNotEmpty()) return@withContext cached
            val stale = negativeCacheStale(cache.allBackdropsMissAt[subjectId], activeAsOfDate)
            if (!allBackdropsRefreshGate.shouldRefresh(subjectId) { stale }) return@withContext cached
            logger.info { "Retrying TMDB backdrops for subject $subjectId (negative cache expired)" }
        }

        // 见 doResolveBackdropUrl 里同名变量的说明
        val subjectYear = tmdbSubjectYear(activeAsOfDate.yearOrNull(), hints.screeningYear, hints.airYear)
        val urls = try {
            searchLayered(
                originalName,
                rootNameResolver(subjectId, originalName, hints.nameCn),
            ) { query, _ ->
                searchAnimeRef(query, subjectYear)
            }?.let { fetchBackdropPaths(it) }
                .orEmpty()
                .take(MAX_BACKDROPS_PER_SUBJECT)
                .map { "$IMAGE_BASE_URL$it" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch TMDB backdrops for subject $subjectId, will retry next time" }
            return@withContext emptyList() // 网络错误不写缓存, 下次重试
        }

        logger.info { "TMDB backdrops for subject $subjectId: ${urls.size}" }
        dataStore.updateData {
            it.copy(
                allBackdrops = it.allBackdrops + (subjectId to urls),
                allBackdropsMissAt = if (urls.isNotEmpty()) {
                    it.allBackdropsMissAt - subjectId
                } else {
                    it.allBackdropsMissAt + (subjectId to currentTimeMillis())
                },
            )
        }
        urls
    }

    /** 跨类型取匹配条目的引用 (type + id), 档次顺序与 [searchBackdropPath] 一致. */
    private suspend fun searchAnimeRef(query: String, subjectYear: Int?): LayeredHit<TmdbMediaRef>? {
        fun List<TmdbSearchResult>.firstWithId() = firstOrNull { it.id != null }
        fun TmdbSearchResult.hit(type: String) =
            LayeredHit(TmdbMediaRef(type, id!!), isTentativeSeasonHit(query, type, this))

        val tv = searchAnime(query, "tv", subjectYear)
        tv.primary.firstWithId()?.let { return it.hit("tv") }
        val movie = searchAnime(query, "movie", subjectYear)
        return movie.primary.firstWithId()?.hit("movie")
            ?: tv.fallback.firstWithId()?.hit("tv")
            ?: movie.fallback.firstWithId()?.hit("movie")
    }

    /** `/{type}/{id}/images` 的全部 backdrop 路径 (TMDB 已按投票排序). */
    private suspend fun fetchBackdropPaths(ref: TmdbMediaRef): List<String> = client.use {
        val body = getApi("/${ref.type}/${ref.id}/images") {
            bearerAuth(currentAniBuildConfig.tmdbApiToken)
            shortConnectTimeout()
        }.bodyAsText()
        json.decodeFromString(TmdbImagesResponse.serializer(), body).backdrops.mapNotNull { it.filePath }
    }

    /**
     * 获取条目所有分集数据 (缩略图 / 时长 / [language] 语言的简介) 索引.
     *
     * 主键是播出日期而非集号: TMDB 与 Bangumi 的季/集划分对不齐
     * (分割放送合并为一季、Bangumi 跨季连续编号), 播出日期是唯一可靠的对应关系.
     * 另存一份按集号的索引, 内容是**本条目对应的那一季** (按季首播日认领, 见
     * [tmdbOwnSeasonNumber]; 认不出来时退回旧口径 —— 单季剧的第 1 季), 供 Bangumi 无分集
     * 播出日期的条目兜底 (1997 剑风传奇全部分集无日期; みなみけ おかわり 13 集无日期而母番
     * 有 5 季, 旧口径下这类条目一张图都拿不到).
     *
     * 元数据按季一次性拉取 (一季一个请求) 并按 subjectId 持久缓存; 缓存记录抓取语言,
     * 用户切换 APP 语言后按新语言重取 (简介是本地化字段).
     * 图片本体由 UI 层 (LazyRow + coil) 惰性加载, 此处只返回 URL.
     *
     * **返回 null = 这次没拿到** (网络失败且没有可用旧缓存), 与"成功拉取但 TMDB 上确实没有
     * 剧照"(返回空的 [TmdbEpisodeStills]) 是两回事: 调用方若把前者也当成"确认无图"记进自己的
     * 缓存, 一次瞬时抖动就会让该条目在整个进程生命周期里再不重试.
     *
     * @param language TMDB 语言码 (如 `zh-CN`), 决定简介语言.
     * @param newestWantedAirDate 调用方希望缓存覆盖到的最新播出日期 (`YYYY-MM-DD`).
     *   缓存是按条目永久保存的, 连载番早先拉取的缓存不含之后新播的集; 传入此参数后,
     *   若缓存最新日期落后于它 (超出 ±1 天匹配容差), 经 [stillsRefreshGate] 放行
     *   (进程内每条目最多一次, 防 TMDB 自身滞后时反复空拉) 重取一次.
     */
    suspend fun getEpisodeStills(
        subjectId: Int,
        originalName: String,
        language: String,
        newestWantedAirDate: String? = null,
        subjectAirDate: String? = null,
        subjectEpisodeCount: Int? = null,
        /** bgm 分集原语言名, 供削字合集档按集标题认领 parts (SE/总集编条目, 见 [collectionAsEpisodes]). */
        subjectEpisodeNames: List<String> = emptyList(),
        hints: TmdbMatchHints = TmdbMatchHints.Empty,
    ): TmdbEpisodeStills? {
        if (currentAniBuildConfig.tmdbApiToken.isBlank() || originalName.isBlank()) {
            return TmdbEpisodeStills()
        }
        // 同 (subjectId, language) 的并发调用合流到同一个任务上, 见 [episodeStillsInFlight].
        // 任务挂在 resolveScope 而不是发起者身上: 发起者被取消 (划过卡片) 时, 合流上来的其他
        // 调用方不该跟着失败, 而且这条链跑完写进缓存对谁都有用.
        val key = EpisodeStillsKey(subjectId, language)
        val task = episodeStillsInFlightLock.withLock {
            episodeStillsInFlight[key] ?: resolveScope.async {
                try {
                    doGetEpisodeStills(
                        subjectId, originalName, language,
                        newestWantedAirDate, subjectAirDate, subjectEpisodeCount, subjectEpisodeNames, hints,
                    )
                } finally {
                    episodeStillsInFlightLock.withLock { episodeStillsInFlight.remove(key) }
                }
            }.also { episodeStillsInFlight[key] = it }
        }
        return task.await()
    }

    private suspend fun doGetEpisodeStills(
        subjectId: Int,
        originalName: String,
        language: String,
        newestWantedAirDate: String?,
        subjectAirDate: String?,
        subjectEpisodeCount: Int?,
        subjectEpisodeNames: List<String>,
        hints: TmdbMatchHints,
    ): TmdbEpisodeStills? =
        withContext(ioDispatcher) {
            val cached = readCache().episodeStills[subjectId]?.takeIf { it.language == language }
            if (cached != null) {
                val refresh = newestWantedAirDate != null &&
                    stillsRefreshGate.shouldRefresh(subjectId) { !cached.coversAirDate(newestWantedAirDate) }
                if (!refresh) return@withContext cached
            }

            val stills = try {
                fetchEpisodeStills(
                    subjectId, originalName, language,
                    subjectYear = tmdbSubjectYear(
                        (subjectAirDate ?: newestWantedAirDate).yearOrNull(),
                        hints.screeningYear,
                        hints.airYear,
                    ),
                    subjectAirDate = subjectAirDate,
                    subjectEpisodeCount = subjectEpisodeCount,
                    subjectEpisodeNames = subjectEpisodeNames,
                    hints = hints,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch TMDB episode stills for subject $subjectId, will retry next time" }
                NetworkTroubleBeacon.report("tmdb episode stills failed for subject $subjectId")
                // 网络错误不写缓存; 陈旧重取失败时继续用旧缓存, 首次拉取失败返回 null (见 KDoc)
                return@withContext cached
            }

            // 陈旧重取拿到空结果 (如 TMDB 瞬时搜索不中) 时保留旧缓存, 不用坏数据覆盖好数据
            if (cached != null && stills.isEmpty() && !cached.isEmpty()) {
                logger.info { "TMDB episode stills refresh for subject $subjectId returned empty, keeping cached" }
                return@withContext cached
            }

            logger.info {
                "TMDB episode stills for subject $subjectId (lang=$language): " +
                    "${stills.byAirDate.size} by air date, ${stills.byEpisodeNumber.size} by episode number"
            }
            dataStore.updateData {
                it.copy(episodeStills = it.episodeStills + (subjectId to stills))
            }
            stills
        }

    /**
     * 剧场版条目的"分集"数据: TMDB 的 movie 没有季/集, **整部就是一集** ——
     * 用它的 backdrop 当那一集的图, `runtime` 当时长, `overview` 当简介.
     *
     * 按上映日入 [TmdbEpisodeStills.byAirDate], 同时按集号 1 入索引: 前者对上有播出日期的
     * 分集 (以及"单集条目用条目开播日"那条兜底, 见 [matchToEpisodes]), 后者兜住集号为 1 的.
     *
     * 命中的图与详情页 hero 背景同源 (都是这部电影的 backdrop) —— 选集卡片与背景同一张图不算
     * 理想, 但比空着好, 而且时长与简介是实打实的新信息.
     */
    private suspend fun HttpClient.fetchMovieAsSingleEpisode(
        subjectId: Int,
        originalName: String,
        language: String,
        subjectYear: Int?,
        subjectAirDate: String?,
    ): TmdbEpisodeStills {
        val movieId = findOwnMovieId(originalName, subjectYear, subjectAirDate)
            ?: return TmdbEpisodeStills()
        logger.info { "TMDB movie match for subject $subjectId: https://www.themoviedb.org/movie/$movieId" }
        return buildMovieAsSingleEpisode(movieId, language)
    }

    /** 把一部电影按"整部就是一集"索引: backdrop 当图, `runtime` 当时长, `overview` 当简介. */
    private suspend fun HttpClient.buildMovieAsSingleEpisode(
        movieId: Int,
        language: String,
    ): TmdbEpisodeStills {
        val detail = json.decodeFromString(
            TmdbMovieDetail.serializer(),
            getApi("/movie/$movieId") {
                parameter("language", language)
                bearerAuth(currentAniBuildConfig.tmdbApiToken)
                shortConnectTimeout()
            }.bodyAsText(),
        )
        val overview = detail.overview?.trim()?.takeIf { it.isNotBlank() }
        val media = TmdbEpisodeMedia(
            stillUrl = detail.backdropPath?.let { "$STILL_IMAGE_BASE_URL$it" },
            runtimeMinutes = detail.runtime?.takeIf { it > 0 },
            overview = overview,
        )
        return TmdbEpisodeStills(
            byAirDate = detail.releaseDate?.takeIf { it.isNotBlank() }?.let { mapOf(it to listOf(media)) }.orEmpty(),
            byEpisodeNumber = mapOf(1 to media),
            language = language,
            showOverview = overview,
        )
    }

    /**
     * **hero 背景图 = `/{type}/{id}/images` 里第一张无字幕的**; 拿不到才退回搜索结果自带的
     * `backdrop_path`.
     *
     * 为什么不用 `backdrop_path`: 那是 TMDB **按语言各存一个**的"主图"字段, 同一条记录 en 与
     * zh/ja 可以指向不同的图 —— Re:Zero tv/65942 的 en 是 `/ai8bVS8…`, zh/ja 是 `/7ZruEnS…`。
     * 而**网页 (themoviedb.org/tv/65942) 三个语言页显示的都是 `/7ZruEnS…`**: 网页取的是
     * `/images` 按票数排序的结果, **与语言无关**。用户看的就是网页那张。
     *
     * **取"无字幕首张"而不是"票数首张"**: 票数最高的可能是印了标题/字幕的宣传图
     * (`iso_639_1` 非空), 而 hero 上还要叠标题与按钮。实测三例正好覆盖三种情形:
     * - Re:Zero tv/65942: 无字幕首张 = `/7ZruEnS…` = 网页那张 ⇒ 修好;
     * - ガンダムビルドダイバーズ tv/76821: backdrop **全都带字**, 这一档取不到 ⇒ 退回
     *   `backdrop_path`, 与 main 结果一致;
     * - 攻殻機動隊 movie/9323: 票数首张带字, 无字幕首张恰好就是 `backdrop_path` ⇒ 不变。
     *
     * 代价 = 每个条目**解析时多一个请求**, 而正缓存永久有效, 所以只有第一次。
     *
     * **别再去改 `backdrop_path` 取哪个语言**: 2026-09-06 在 en 与 zh 之间来回改过两轮,
     * 两个方向都不对 —— 网页显示的既不是 en 也不是 zh 的那个字段。
     */
    private suspend fun heroBackdropPath(result: TmdbSearchResult, type: String): String {
        val fallback = result.backdropPath!!
        val id = result.id ?: return fallback
        return runCatching {
            client.use {
                val body = getApi("/$type/$id/images") {
                    bearerAuth(currentAniBuildConfig.tmdbApiToken)
                    shortConnectTimeout()
                }.bodyAsText()
                json.decodeFromString(TmdbImagesResponse.serializer(), body).backdrops
                    // TMDB 已按票数排序, 取第一张无字幕的
                    .firstOrNull { it.language.isNullOrBlank() && !it.filePath.isNullOrBlank() }
                    ?.filePath
            }
        }.getOrNull() ?: fallback
    }

    /** 剧照链路的第三档 (只搜 tv), 判据同 [searchThirdTierBackdrop]. */
    private suspend fun searchThirdTierTv(query: String, subjectYear: Int?, rejected: Set<Int>): Int? {
        val tokens = tokenizeForMatch(query)
        val queryNormalized = normalizeForMatch(query)
        return searchRawResults(query, "tv").firstOrNull { result ->
            result.id?.let { it !in rejected } == true &&
                    result.genreIds.isNotEmpty() &&
                    GENRE_ANIMATION !in result.genreIds &&
                    result.yearPlausible("tv", subjectYear) &&
                    result.matchesTokens(tokens) &&
                    result.hasExactTitle(queryNormalized)
        }?.id
    }

    /** 剧照链路的中文候选 (只搜 tv), 判据同 [searchChineseBackdrop]. */
    private suspend fun searchChineseTv(nameCn: String, subjectYear: Int?, rejected: Set<Int>): Int? {
        val queryNormalized = normalizeForMatch(nameCn)
        for (result in searchRawResults(nameCn, "tv", language = CHINESE_LANGUAGE)) {
            val id = result.id ?: continue
            if (id in rejected) continue
            // genres 为空的条目放行: 变体档已有逐字闸门 + 对称年份双保险, 而 TMDB 冷门条目
            // 常缺 genre (「GUNDAM EVOLVE」的 tv/101719 genres 是空的, 按 16 过滤等于拒绝正主)
            if (result.genreIds.isNotEmpty() && GENRE_ANIMATION !in result.genreIds) continue
            if (!tmdbExactVariantYearPlausible(result.releaseYearOrNull(), subjectYear)) continue
            if (result.hasExactTitle(queryNormalized)) return id
            val alt = runCatching { fetchAlternativeTitles(id, "tv") }.getOrElse { emptyList() }
            if (alt.any { normalizeForMatch(it) == queryNormalized }) return id
        }
        return null
    }

    /**
     * 找"本条目自己那部电影". 分两档, 都是实测逼出来的:
     *
     *  - **primary 候选** (原名 / 同形字折叠形) 命中即用. 剧场版条目的正解几乎都落在这一档,
     *    而且 TMDB 的剧场版标题普遍带副标题 (`…キャメロット- 前編 Wandering; Agateram`),
     *    这一档要是也要求逐字同名, `劇場版 CLANNAD` / `映画 Yes!プリキュア5` / `映画 妖怪学園Y`
     *    这些会从有图退成无图.
     *  - **削字候选**要多过一道闸门: **上映日与条目日期差 <=1 天, 或与条目原名逐字同名**.
     *    削字这一档不能不要 —— `ジョジョ…ファントムブラッド` 的正解只有削掉副标题才搜得到
     *    (TMDB 那边写作 `ファントム ブラッド`, 连写串召回不到), `Heaven's Feel` 同理;
     *    但不加闸门的话, 放开削字守卫后新生的 `CLANNAD` 会让「智代編」(OVA) 拿到剧场版
     *    movie/16516 的图 (Δ=305 天), 而它本该落到母番的 season 0.
     *
     * **不走关联回溯的根条目名**: root 给的是系列名, 命中的 movie 必然是系列里的**另一部**
     * (`Kong — The Origin` 顺着根条目名 `悟空` 拿到了龙珠剧场版).
     */
    private suspend fun HttpClient.findOwnMovieId(
        originalName: String,
        subjectYear: Int?,
        subjectAirDate: String?,
    ): Int? {
        val candidates = searchQueryCandidates(originalName)
        for (query in candidates.primary) {
            val movie = searchAnime(query, "movie", subjectYear)
            (movie.primary.firstOrNull { it.id != null } ?: movie.fallback.firstOrNull { it.id != null })
                ?.let { return it.id }
        }
        val nameNormalized = normalizeForMatch(originalName)
        for (query in candidates.derived) {
            val movie = searchAnime(query, "movie", subjectYear)
            val hit = (movie.primary + movie.fallback).firstOrNull { it.id != null } ?: continue
            val sameName = hit.hasExactTitle(nameNormalized)
            val closeDate = subjectAirDate != null &&
                    hit.releaseDate?.daysFrom(subjectAirDate)?.let { it <= 1 } == true
            if (sameName || closeDate) return hit.id
            break
        }
        return null
    }

    /** 分集缓存的陈旧重取闸门: 进程内每条目最多放行一次, 见 [getEpisodeStills]. */
    private val stillsRefreshGate = StaleRefreshGate<Int>()

    /** backdrop 负缓存的重取闸门 (与 [allBackdropsRefreshGate] 分开计次), 见 [negativeCacheStale]. */
    private val backdropRefreshGate = StaleRefreshGate<Int>()

    /** 全量剧照负缓存的重取闸门. */
    private val allBackdropsRefreshGate = StaleRefreshGate<Int>()

    /**
     * 负缓存 ("TMDB 上没有这张图") 还能不能相信.
     *
     * 图和标题都是 TMDB 社区在开播后陆续补的, 所以新番的"没有"往往只是"还没有" ——
     * 一次空结果被永久缓存的后果是: 之后 TMDB 补了图, 这个条目也永远不会再查一次
     * (表现为"别人有图我没有", 而代理测试里 TMDB 全绿, 因为压根没发请求).
     *
     * @param missAt 负缓存写入时刻; null = 旧缓存没记时间, 给一次重取机会
     *   (这样就不必像匹配算法变更那样 bump [TmdbImageCache.CURRENT_VERSION] 作废整个缓存,
     *   代价从"所有条目重新搜索"降到"只重取负缓存那几条")
     */
    private fun negativeCacheStale(missAt: Long?, activeAsOfDate: String?): Boolean {
        if (missAt == null) return true
        val ttl = negativeCacheTtl(activeAsOfDate) ?: return false
        // 相减而非比较绝对值: 时钟回拨得到负数, 自然判为未过期, 不会因为系统时间乱跳而反复重取
        return currentTimeMillis() - missAt >= ttl.inWholeMilliseconds
    }

    /**
     * 负缓存有效期; null = 永久.
     *
     * 判据是"这部番有多活"而非"开播多久": 两年前开播但仍在连载的长番, 按开播日期会被误判成
     * 老番而拿到永久负缓存. 因此 [activeAsOfDate] 取最新已播集的日期 (口径同
     * [getEpisodeStills] 的 `newestWantedAirDate`), 调用方拿不到分集时退化为开播日期.
     */
    private fun negativeCacheTtl(activeAsOfDate: String?): Duration? {
        val aired = activeAsOfDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        return when (aired.daysUntil(today)) {
            // 还在播或刚完结: TMDB 正在陆续补图, 最坏等三天
            in Int.MIN_VALUE..NEGATIVE_CACHE_AIRING_DAYS -> NEGATIVE_CACHE_TTL_AIRING
            // 补图概率已低, 但不能说没有
            in (NEGATIVE_CACHE_AIRING_DAYS + 1)..NEGATIVE_CACHE_RECENT_DAYS -> NEGATIVE_CACHE_TTL_RECENT
            // 一年都没人补, 基本不会再有; 屏保轮播会扫全部收藏, 老番参与重试会明显放大请求
            else -> null
        }
    }

    private suspend fun fetchEpisodeStills(
        subjectId: Int,
        originalName: String,
        language: String,
        subjectYear: Int?,
        subjectAirDate: String?,
        subjectEpisodeCount: Int?,
        subjectEpisodeNames: List<String>,
        hints: TmdbMatchHints,
    ): TmdbEpisodeStills = client.use {
        val token = currentAniBuildConfig.tmdbApiToken
        // 血统判定在搜索前主动做 (搜索层只在直搜落空时才需要根条目名):
        // 建索引时要用"是否衍生作"决定 season 0 的取舍, 见下方季循环.
        val lineage = resolveLineageOrNull(subjectId, originalName, hints.nameCn)

        // **单集条目 (剧场版/OVA) 先搜 movie**: 这类条目在 TMDB 上就是一个 movie, 而 tv 搜索
        // 几乎总能撞上同系列的某部剧 (实测 君の名は。命中毫不相干的 tv「キミの名を呼べば」、
        // 逆襲のシャア 命中初代高达、イノセンス 与 2.0 都命中 SAC、Fate UBW 剧场版 命中 2006 年
        // 那部旧改编), 于是 movie 永远轮不到 —— 年份的下界判据也拦不住, 母番总比条目老.
        //
        // 只有"拿到了图"才直接采用: OVA 单集有时并没有独立 movie 条目, 数据在母番的 season 0
        // 里 (みなみけ べつばら/おまたせ), 那种情况要继续走下面的 tv 分层搜索.
        //
        // **闸门是 `只在影院放映 或 只有一集`**: 原先只看集数, 而「新世紀エヴァンゲリオン劇場版
        // Air/まごころを、君に」在 Bangumi 是 **2 集** (同日、沿用 TV 的集号), 于是它被 tv 搜索
        // 赢走、拿了 1995 年 TV 版的分集数据 —— 当初为此单写了一条"没有任何一季年份吻合就再找
        // movie"的启发式, 现在 infobox 判据一个字段就认出来了.
        val ownMovie = if (hints.theatrical || subjectEpisodeCount == 1) {
            fetchMovieAsSingleEpisode(subjectId, originalName, language, subjectYear, subjectAirDate)
        } else {
            null
        }
        if (ownMovie != null && ownMovie.byEpisodeNumber.values.any { it.stillUrl != null }) {
            return@use ownMovie
        }

        val nameCandidates = searchQueryCandidates(originalName)
        // 空壳条目重试时要排除的 tv id, 见下方 maxAttempts 处的说明.
        val rejectedTvIds = mutableSetOf<Int>()

        // root 档的第二手, 语义同 [rootNameResolver] 的 attempt 1. findTv 会被重试多次,
        // 结果记住不重发.
        var bgmRootResolved = false
        var bgmRootName: String? = null
        suspend fun bgmFallbackRootName(): String? {
            if (!bgmRootResolved) {
                bgmRootResolved = true
                bgmRootName = if (lineage?.viaAni == true) {
                    resolveLineageViaBgm(subjectId, originalName)?.rootName
                } else {
                    null
                }
            }
            return bgmRootName
        }

        // 逐层搜索 (层次同 searchLayered), 对确认正传的条目多一条规则: 首候选 (完整条目名)
        // 的非精确标题命中不可信 —— 超集标题常是拆分出的兄弟条目 (如 "進撃の巨人 The Final
        // Season" 命中单集条目 "…完結編(後編)"), 正传季应归属母条目; 暂存该命中, 根条目名
        // 与削字候选全落空时才回退采用. 衍生作不受影响 (正确条目常常正是超集标题, 如
        // デート・ア・バレット 前編, 必须直搜命中).
        //
        // 返回 (tv id, 命中它的候选词); 每次调用重跑整个候选序列 (已被 [rejectedTvIds] 排除的
        // id 视同没命中), 所以重试时会自动落到下一个候选词上.
        suspend fun findTv(): Triple<Int, String?, Boolean>? {
            val tried = mutableSetOf<String>()
            var acceptedId: Int? = null
            var matchedQuery: String? = null
            var tentativeId: Int? = null
            var truncatedId: Int? = null
            var truncatedQuery: String? = null
            suspend fun accept(id: Int, query: String?): Boolean {
                acceptedId = id
                matchedQuery = query
                return true
            }

            suspend fun searchOne(query: String): TmdbSearchResult? {
                val usable = { r: TmdbSearchResult -> r.id?.let { it !in rejectedTvIds } == true }
                return searchAnime(query, "tv", subjectYear)
                    .let { it.primary.firstOrNull(usable) ?: it.fallback.firstOrNull(usable) }
            }

            suspend fun trySearch(query: String): Boolean {
                if (!tried.add(query)) return false
                val result = searchOne(query) ?: return false
                val id = result.id ?: return false
                if (lineage?.isDerivative == false && query == nameCandidates.primary.firstOrNull() &&
                    !result.hasExactTitle(normalizeForMatch(query))
                ) {
                    tentativeId = id
                    return false
                }
                return accept(id, query)
            }
            run {
                nameCandidates.primary.forEach { if (trySearch(it)) return@run }
                // 第三档与中文候选的位置同 [searchLayered], 理由见那里
                nameCandidates.primary.forEach { candidate ->
                    val id = searchThirdTierTv(candidate, subjectYear, rejectedTvIds)
                    if (id != null && accept(id, candidate)) return@run
                }
                // **有 tentative 在手时, 削字类候选不得胜出**: tentative = 原名档已命中搜索
                // 第一名, 只是 TMDB 收录名与 Bangumi 名不逐字相等 (写法体系不同, 如
                // 「BanG Dream! ゆめ∞みた」vs TMDB 的「バンドリ！ ゆめ∞みた」); 而削字候选
                // 是原名的截断, 削掉的恰是区分子作品的部分, 它"逐字精确"命中的天然是更泛的
                // 本传/母作 ("BanG Dream!" 精确命中 39 集的本传 tv/69236, 挤掉了 tentative
                // 里那个才是正确答案的 tv/300112, 选集卡从此一张图都对不上). root 档在
                // rootName=(self) 时同样会削原名, 所以只用根名直搜, 不展开它的削字候选.
                // 中文档是独立名字体系, 照旧可胜出 (岩窟王那类 tentative 是错剧、中文救回
                // 的受益者不受影响).
                // **削字命中一律降为暂定, 不再当场定案**: 削掉的恰是区分子作品的部分, 它精确
                // 命中的天然是更泛的本传/母作 —— 「機動戦士ガンダムSEED C.E.73 STARGAZER」原名
                // 0 结果, 削出的「機動戦士ガンダムSEED」逐字命中本传, 而能逐字命中正确条目
                // tv/43142 的中文名排在后面, 从来轮不到. 先让中文档 (带逐字校验) 试, 它命中就
                // 顶掉暂定; miss 才回头用削字的命中 (与原先定案等价, 不会从错图变无图).
                if (tentativeId == null) {
                    for (candidate in nameCandidates.derived) {
                        if (!tried.add(candidate)) continue
                        val result = searchOne(candidate) ?: continue
                        val id = result.id ?: continue
                        // 纯拉丁削字候选仍须逐字同名 (ANGEL 不能拿 Angel Beats!)
                        if (candidate.none { ch -> ch.isCjkOrKana() } &&
                            !result.hasExactTitle(normalizeForMatch(candidate))
                        ) continue
                        truncatedId = id
                        truncatedQuery = candidate
                        break // 削字由长到短, 第一个命中的最具体; 暂定只收一个
                    }
                }
                // 中文名与别名分档, 同 searchLayered: 别名命中要过原名召回集的佐证
                if (hints.nameCn.isNotBlank()) {
                    val id = searchChineseTv(hints.nameCn, subjectYear, rejectedTvIds)
                    if (id != null && accept(id, hints.nameCn)) return@run
                }
                for (alias in hints.aliases) {
                    if (alias.isBlank() || alias == hints.nameCn) continue
                    val id = searchChineseTv(alias, subjectYear, rejectedTvIds)
                        ?.takeIf { it in ownNameRecallIds(originalName, "tv") }
                    if (id != null && accept(id, alias)) return@run
                }
                // 削字暂定在手时 root 档不再走: 削字候选派生自条目自己的名字, 优先级本就
                // 高于根条目名 (原先削字命中当场定案, root 同样走不到 —— 保持这个次序).
                if (truncatedId == null) {
                    lineage?.rootName?.let { root ->
                        val rootCandidates = searchQueryCandidates(root)
                        (if (tentativeId == null) rootCandidates.all else rootCandidates.primary)
                            .forEach { if (trySearch(it)) return@run }
                    }
                    bgmFallbackRootName()?.let { root ->
                        val rootCandidates = searchQueryCandidates(root)
                        (if (tentativeId == null) rootCandidates.all else rootCandidates.primary)
                            .forEach { if (trySearch(it)) return@run }
                    }
                }
            }
            var viaFallback = false
            if (acceptedId == null && tentativeId != null) {
                acceptedId = tentativeId
                matchedQuery = nameCandidates.primary.firstOrNull()
                viaFallback = true
            }
            if (acceptedId == null && truncatedId != null) {
                acceptedId = truncatedId
                matchedQuery = truncatedQuery
                viaFallback = true
            }
            return acceptedId?.let { Triple(it, matchedQuery, viaFallback) }
        }

        // **版本条目 (HDリマスター/完全版…) 在 TMDB 上常是个空壳**: 分集只有占位集名
        // (「第1話」) 与时长, 一张剧照都没有, 剧本身连海报背景都没有 —— 实测
        // tv/332355「機動戦士ガンダムSEED HDリマスター」32 集全无图, 而真数据在原作
        // tv/20111 上. 直搜条目名必然先命中这个空壳, 于是选集卡一张图都出不来.
        // 这种情况把该 id 排除再搜一轮, 让"剥掉版本后缀"的候选词命中原作 (背景图那条链路
        // 靠 firstWithBackdrop 天然跳过了空壳, 所以只有剧照这边需要补).
        //
        // **不能一律"没图就换下一个"**: 普通条目匹配到的正确剧集本来就可能一张剧照都没有
        // (冷门番常见), 那种情况换下一个候选只会把对的换成错的. 所以要么是版本条目 (上面那类),
        // 要么得有别的证据说明"这个不是它" —— 见 [looksLikeStubSeason] 的集数判据.
        val versionEntry = tmdbStripVersionSuffix(originalName) != originalName
        var firstBuilt: TmdbEpisodeStills? = null
        var collectionChecked = false
        var collectionEpisodes: TmdbEpisodeStills? = null
        // findTv 会被重试多次, 合集查询记住结果, 别重复发
        suspend fun collectionOrNull(): TmdbEpisodeStills? {
            if (!collectionChecked) {
                collectionChecked = true
                collectionEpisodes = collectionAsEpisodes(
                    subjectId, originalName, language, subjectYear, subjectEpisodeCount,
                    subjectEpisodeNames, hints,
                )
            }
            return collectionEpisodes
        }
        while (rejectedTvIds.size < STUB_TV_ATTEMPTS) {
            val found = findTv()
            if (found == null || found.third) {
                // **合集精确匹配优先于暂定兜底**: findTv 走到 tentative/削字暂定 (或全空) 说明
                // 没有任何精确命中 —— 此时条目名/中文名/别名能逐字命中一个 TMDB 合集的话
                // ("每集一部电影"的 OVA 系列, 如「機動戦士ガンダム THE ORIGIN」= 6 部 movie),
                // 按 parts 上映日当集表远比暂定条目可信 (暂定给的是更泛的本传, 集数日期全错).
                collectionOrNull()?.let { return@use it }
            }
            val (tvId, matchedQuery) = found ?: break
            // 排查错配时可据此人工核对 tvId 指向的剧对不对
            logger.info {
                "TMDB tv match for subject $subjectId: https://www.themoviedb.org/tv/$tvId (matched \"$matchedQuery\")"
            }
            val built = buildEpisodeStills(
                tvId, matchedQuery, subjectId, originalName, language, token,
                subjectYear, subjectAirDate, lineage, ownMovie,
            )
            if (built.hasAnyStill()) return@use built
            logger.info { "TMDB tv/$tvId has no still at all for subject $subjectId" }
            if (firstBuilt == null) firstBuilt = built
            rejectedTvIds += tvId
            // 换下一个候选要有理由; 没理由就守着第一个结果 (它多半是对的, 只是真没图)
            if (!versionEntry && !looksLikeStubSeason(built, subjectEpisodeCount)) break
        }
        // tv 一个都没匹配上时再找 movie: 剧场版条目在 TMDB 上就是一个 movie, 没有季/集 ——
        // **整部就是一集**. 原先这条链路只搜 tv, 所以剧场版条目永远拿不到自己的分集数据
        // (攻殻機動隊 1995 的背景图修好之后, 选集卡片仍然是空的).
        // 只在 tv 全落空时才走, 所以不会把本来对的 tv 结果挤掉; 命中的 movie 仍然要过
        // 同一套动画过滤/标题校验/年份否决.
        //
        // **中文候选的 movie 侧只能挂在这里** (整条 tv 链路之后): 放到前面去的话,
        // 「CLANNAD -クラナド-」这个 22 集的 TV 母条目会被同名的剧场版顶掉, 一次丢 22 集图.
        // 而 FGO ソロモン 正是靠它才拿到 movie/829920 —— 那条 TMDB 记录的四个标题字段全是
        // 罗马字/英文, 只有中文别名对得上.
        firstBuilt
            ?: ownMovie
            ?: fetchMovieAsSingleEpisode(subjectId, originalName, language, subjectYear, subjectAirDate)
                .takeIf { it.byEpisodeNumber.values.any { media -> media.stillUrl != null } }
            ?: hints.nameCn.takeIf { it.isNotBlank() }?.let { nameCn ->
                fetchChineseMovieAsSingleEpisode(subjectId, nameCn, language, subjectYear, false, originalName)
            }
            ?: hints.aliases.firstNotNullOfOrNull { alias ->
                if (alias.isBlank() || alias == hints.nameCn) {
                    null
                } else {
                    fetchChineseMovieAsSingleEpisode(subjectId, alias, language, subjectYear, true, originalName)
                }
            }
            ?: TmdbEpisodeStills()
    }

    /**
     * 中文候选的 movie 侧: 用条目中文名找那部电影, 判据同 [searchChineseBackdrop].
     * 找不到返回 `null` (而不是空结果), 让调用方能区分"没找到"与"找到了但没图".
     */
    private suspend fun HttpClient.fetchChineseMovieAsSingleEpisode(
        subjectId: Int,
        nameCn: String,
        language: String,
        subjectYear: Int?,
        isAlias: Boolean,
        originalName: String,
    ): TmdbEpisodeStills? {
        if (nameCn.isBlank()) return null
        val queryNormalized = normalizeForMatch(nameCn)
        // 别名候选要过原名召回集的佐证, 见 ownNameRecallIds
        val recall = if (isAlias) ownNameRecallIds(originalName, "movie") else null
        val movieId = searchRawResults(nameCn, "movie", language = CHINESE_LANGUAGE).firstOrNull { result ->
            val id = result.id
            id != null && (recall == null || id in recall) &&
                    (result.genreIds.isEmpty() || GENRE_ANIMATION in result.genreIds) &&
                    tmdbExactVariantYearPlausible(result.releaseYearOrNull(), subjectYear) &&
                    (
                            result.hasExactTitle(queryNormalized) ||
                                    runCatching { fetchAlternativeTitles(id, "movie") }.getOrElse { emptyList() }
                                        .any { normalizeForMatch(it) == queryNormalized }
                            )
        }?.id ?: return null
        logger.info { "TMDB movie match via Chinese name for subject $subjectId: movie/$movieId" }
        return buildMovieAsSingleEpisode(movieId, language)
    }

    /**
     * 拉取并索引 [tvId] 这部剧的全部分集数据.
     *
     * @param matchedQuery 命中这部剧的候选词, 用于判定衍生条目该不该只索引 season 0.
     * @param ownMovie 已拉到的"本条目作为 movie"的数据 (没拉过为 null), 供剧集装不下本条目时改用.
     */
    private suspend fun HttpClient.buildEpisodeStills(
        tvId: Int,
        matchedQuery: String?,
        subjectId: Int,
        originalName: String,
        language: String,
        token: String,
        subjectYear: Int?,
        subjectAirDate: String?,
        lineage: BgmLineage?,
        ownMovie: TmdbEpisodeStills?,
    ): TmdbEpisodeStills {
        // language: 顺带取整部剧的本地化简介 (Bangumi 简介为日文原文时整段替换用);
        // TMDB 无该语言翻译时 overview 为空串, 存 null 由 Bangumi 简介兜底
        val detailBody = getApi("/tv/$tvId") {
            parameter("language", language)
            bearerAuth(token)
            shortConnectTimeout()
        }.bodyAsText()
        val detail = json.decodeFromString(TmdbTvDetail.serializer(), detailBody)
        val seasons = detail.seasons
        // **命中的剧根本装不下这个条目时, 再给 movie 一次机会.**
        // 上面那个 subjectEpisodeCount == 1 的闸门太窄: 剧场版条目的"集数"不一定是 1 ——
        // 「新世紀エヴァンゲリオン劇場版 Air/まごころを、君に」在 Bangumi 是 2 集 (Air 与
        // まごころを、君に, 同一天、沿用 TV 的集号 25/26), 于是 tv 搜索赢下来, 拿了 1995 年
        // TV 版 (tv/890) 的分集数据; 而背景图那条链路是对的 (movie/18491).
        //
        // 判据: 没有任何一季的首播年落在条目年份 ±1 —— 那这个剧不可能包含本条目.
        // 但只有 movie 的**上映日与条目开播日相差 ≤1 天**才真的改用它: 单纯"年份相近"会误伤
        // 涼宮ハルヒの憂鬱 (2009 版) —— TMDB 只有一个条目、单季 28 集横跨 2006→2009,
        // tv 才是对的, 而同系列 2010 年的剧场版按年份看也"相近".
        if (subjectAirDate != null && seasons.isNotEmpty()) {
            val seasonYears = seasons.mapNotNull { it.airDate.yearOrNull() }
            val subjectYearOrNull = subjectAirDate.yearOrNull()
            val plausible = subjectYearOrNull == null || seasonYears.isEmpty() ||
                seasonYears.any { (it - subjectYearOrNull).absoluteValue <= YEAR_TOLERANCE }
            if (!plausible) {
                val byMovie = ownMovie
                    ?: fetchMovieAsSingleEpisode(subjectId, originalName, language, subjectYear, subjectAirDate)
                if (byMovie.byAirDate.keys.any { it.daysFrom(subjectAirDate).let { d -> d != null && d <= 1 } }) {
                    logger.info {
                        "TMDB tv/$tvId has no season near $subjectAirDate for subject $subjectId, using movie instead"
                    }
                    return byMovie
                }
            }
        }

        val singleSeason = seasons.count { it.seasonNumber > 0 } == 1
        // 按集号索引哪一季: 先认领"本条目对应的季"—— 按季首播日 (见 tmdbOwnSeasonNumber), 认不出
        // 来再按季名 (见 claimSeasonByName); 都认不出来时退回旧口径 —— 单季剧的第 1 季. 这个索引
        // 只在 Bangumi 分集**没有播出日期**时才被用到 (见 TmdbEpisodeMatcher), 但它同时决定
        // 下方**集名索引覆盖哪一季**, 所以认不出季的多季剧连集名兜底都没有.
        val numberedSeason = tmdbOwnSeasonNumber(seasons.map { it.seasonNumber to it.airDate }, subjectAirDate)
            ?: claimSeasonByName(tvId, detail, originalName, language, token)
            ?: 1.takeIf { singleSeason }

        // 确认正传的条目把 season 0 (特别篇) 排在正片之后入索引: TMDB 常把同期放送的
        // 衍生短篇挂在正传条目的特别篇下, 且与正片同日播出 (如 Re:ゼロ休憩時間 4th 与
        // 正传 4th season 喪失編 逐集同日), S0 先入索引会让正传分集错拿短篇的数据 ——
        // 殿后使同日对位优先取正片; 不整个跳过是因为正传的第0话这类特别篇只存在于 S0
        // (如 無職転生Ⅱ 第0集), 日期只在 S0 出现时仍要能命中. 判定失败
        // (关系数据缺失/请求失败) 时维持原顺序.
        val specialsLast = lineage?.isDerivative == false
        // 反向: 衍生条目靠根条目名才归并到本篇的 (直搜自己的名字落空), 说明它没有独立
        // TMDB 条目, 分集必然在本篇的 S0 里 —— 只索引 S0. 否则正片与短篇播出日差 ±1 天时
        // (如 休憩時間 3rd 比正传晚一天), 精确日期会先命中正片, 衍生分集错拿正片数据.
        // 直搜命中自己条目的衍生作 (如有独立条目的外传) 走正常全量索引.
        val matched = matchedQuery
        val rootName = lineage?.rootName
        val specialsOnly = lineage?.isDerivative == true && matched != null &&
            rootName != null && matched in searchQueryCandidates(rootName).all
        val indexedSeasons = when {
            specialsOnly -> seasons.filter { it.seasonNumber == 0 }
            specialsLast -> seasons.sortedBy { if (it.seasonNumber == 0) 1 else 0 }
            else -> seasons
        }
        val byAirDate = mutableMapOf<String, MutableList<TmdbEpisodeMedia>>()
        // 与 [byAirDate] 逐项对位的 (季号, 集号); 原语言集名要等下面那轮请求才拿到, 所以先记出处,
        // 最后再合成 [TmdbEpisodeStills.byAirDateOrigin].
        val airDateSlots = mutableMapOf<String, MutableList<Pair<Int, Int?>>>()
        val byEpisodeNumber = mutableMapOf<Int, TmdbEpisodeMedia>()
        val specialsByNumber = mutableMapOf<Int, TmdbEpisodeMedia>()
        for (season in indexedSeasons) {
            // language: 分集简介取该语言的翻译 (无翻译时 overview 为空, 由 Bangumi 简介兜底);
            // still/时长/日期与语言无关.
            val seasonBody = getApi("/tv/$tvId/season/${season.seasonNumber}") {
                parameter("language", language)
                bearerAuth(token)
                shortConnectTimeout()
            }.bodyAsText()
            for (ep in json.decodeFromString(TmdbSeasonDetail.serializer(), seasonBody).episodes) {
                val media = TmdbEpisodeMedia(
                    stillUrl = ep.stillPath?.let { "$STILL_IMAGE_BASE_URL$it" },
                    runtimeMinutes = ep.runtime?.takeIf { it > 0 },
                    overview = ep.overview?.trim()?.takeIf { it.isNotBlank() },
                )
                // 同一天可能有多集 (双集连播首播, 如 無職転生Ⅲ 第1+2话), 按集号顺序追加成列表,
                // 匹配侧按 Bangumi "当日第几集" 对位取用. 字段全空的集也要占位, 保持对位不错乱.
                ep.airDate?.let {
                    byAirDate.getOrPut(it) { mutableListOf() }.add(media)
                    airDateSlots.getOrPut(it) { mutableListOf() }.add(season.seasonNumber to ep.episodeNumber)
                }
                if (season.seasonNumber == numberedSeason) {
                    ep.episodeNumber?.let { byEpisodeNumber[it] = media }
                }
                if (season.seasonNumber == 0) {
                    ep.episodeNumber?.let { specialsByNumber[it] = media }
                }
            }
        }

        // 集名索引 (剧的原语言), 日期对不上时匹配侧用"集名精确一致"兜底. 覆盖两季:
        //  - **S0**: 特别篇在 Bangumi 与 TMDB 的播出日期记录常有出入 (如 転スラ
        //    "救われるラミリス 後編" 两边差 8 天), 差得超过 ±1 天容差.
        //  - **本条目对应的那一季**: Bangumi 偶有把某一集的播出日期录错 —— 实测
        //    ハイスクールD×D BorN 第 12 集记成 2016-06-20 (实为 2015-06-20, 年份错了一位).
        //    这种错法三条兜底一条都接不住: 按日期落空; 集号兜底只管"完全没有日期"的集;
        //    三明治插值要求前后**两个**锚点而它正好是最后一集. 集名是这里唯一还站得住的证据.
        //
        // 名字必须**按原语言**再取一次 —— 上面按 APP 语言取的是译名, 与 Bangumi 的中文名是
        // 不同来源的译文, 几乎必然对不上: 实测 DxD BorN 第 12 集 Bangumi 作「无论何时，无论
        // 多久！」而 TMDB zh-CN 作「无论何时，直到永远！」; 而两边的**原名**逐字相同
        // (いつでも、いつまでも！). 重名的集名整条丢弃, 保精度.
        val byName = mutableMapOf<String, TmdbEpisodeMedia?>()
        // (季号, 集号) -> 归一化**首段**原语言集名, 供匹配侧按集名投票认领季 (见 [tmdbEpisodeSegmentKey]).
        // 与 [byName] 同一轮请求里顺手取, 不额外发请求.
        val segmentKeys = mutableMapOf<Pair<Int, Int>, String>()
        val nameIndexedSeasons = buildMap {
            if (specialsByNumber.isNotEmpty()) put(0, specialsByNumber)
            if (numberedSeason != null && numberedSeason != 0 && byEpisodeNumber.isNotEmpty()) {
                put(numberedSeason, byEpisodeNumber)
            }
        }
        if (nameIndexedSeasons.isNotEmpty()) {
            val originalLanguage = detail.originalLanguage?.takeIf { it.isNotBlank() } ?: "ja"
            for ((seasonNumber, mediaByNumber) in nameIndexedSeasons) {
                val nameBody = getApi("/tv/$tvId/season/$seasonNumber") {
                    parameter("language", originalLanguage)
                    bearerAuth(token)
                    shortConnectTimeout()
                }.bodyAsText()
                for (ep in json.decodeFromString(TmdbSeasonDetail.serializer(), nameBody).episodes) {
                    val number = ep.episodeNumber ?: continue
                    val name = ep.name ?: continue
                    tmdbEpisodeSegmentKey(name).takeIf { it.isNotEmpty() }
                        ?.let { segmentKeys[seasonNumber to number] = it }
                    val media = mediaByNumber[number] ?: continue
                    val key = tmdbEpisodeNameKey(name).takeIf { it.isNotEmpty() } ?: continue
                    byName[key] = if (key in byName) null else media
                }
            }
        }

        // 出处**只对"同一天挤了不止一个季"的日期**留下: 那才是匹配侧要在候选间挑的场合, 其余日期
        // 只有唯一候选, 存出处纯属给缓存加体积 (绝大多数条目一条都不留).
        val byAirDateOrigin = airDateSlots
            .filterValues { slots -> slots.distinctBy { it.first }.size > 1 }
            .mapValues { (_, slots) ->
                slots.map { (seasonNumber, episodeNumber) ->
                    TmdbEpisodeOrigin(
                        seasonNumber = seasonNumber,
                        nameKey = episodeNumber?.let { segmentKeys[seasonNumber to it] }.orEmpty(),
                    )
                }
            }

        return TmdbEpisodeStills(
            byAirDate,
            byEpisodeNumber,
            language,
            showOverview = detail.overview?.trim()?.takeIf { it.isNotBlank() },
            byEpisodeName = byName.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
            byAirDateOrigin = byAirDateOrigin,
        )
    }

    /**
     * 认领"本条目对应哪一季"的第二判据: **条目名与某一季的季名逐字相同** (归一化后, 见
     * [tmdbSeasonNumberByName]). 按季首播日认不出来时才走 —— 两边日期差得超过容差的续季条目
     * 原先连集名兜底都没有 (集名索引只覆盖 S0 与"本条目对应的那一季").
     *
     * 实测「機動戦士ガンダムSEED DESTINY HDリマスター」(2013 复播) 归并到原作 tv/20111 后,
     * 没有任何一季的首播日挨着 2013 年, 而 S2 的季名正是「機動戦士ガンダムSEED DESTINY」;
     * 认下这一季后 50 集里 49 集靠集名对上了原作的剧照.
     *
     * 季名跟着 `language` 本地化 (实测 zh-CN 下 tv/20111 的 S1 叫「机动战士高达SEED 第 1 季」),
     * 与日文原名无从比较, 所以 APP 语言不是该剧原语言时要按原语言再取一次季名 —— 多花一个请求,
     * 但只发生在"按日期认不出季"的多季剧上, 那种条目现在本来一条兜底都落不下来.
     */
    private suspend fun HttpClient.claimSeasonByName(
        tvId: Int,
        detail: TmdbTvDetail,
        originalName: String,
        language: String,
        token: String,
    ): Int? {
        // 单季剧下面本来就退回第 1 季, 不必多花请求
        if (detail.seasons.count { it.seasonNumber > 0 } <= 1) return null
        val originalLanguage = detail.originalLanguage?.takeIf { it.isNotBlank() } ?: "ja"
        val seasons = if (language.substringBefore('-') == originalLanguage) {
            detail.seasons
        } else {
            val body = getApi("/tv/$tvId") {
                parameter("language", originalLanguage)
                bearerAuth(token)
                shortConnectTimeout()
            }.bodyAsText()
            json.decodeFromString(TmdbTvDetail.serializer(), body).seasons
        }
        return tmdbSeasonNumberByName(seasons.map { it.seasonNumber to it.name }, originalName)
            ?.also { logger.info { "TMDB tv/$tvId season $it claimed by name for \"$originalName\"" } }
    }

    /**
     * 三层搜索, 层内层间都短路 (命中即停, 已试过的词不重试):
     *
     * 1. 原名直搜 (含同形字折叠形, 见 [NameQueries.primary]) — 有独立 TMDB 条目的剧场版/
     *    衍生作 (如 デート・ア・バレット) 必须先命中自己的条目, 回溯放前面会把它们错误归并到母番;
     * 2. 削字规则 (见 [NameQueries.derived]) — 从条目**自己的名字**削出来的更短候选;
     * 3. 关联条目回溯到根条目再搜 — 数据驱动, 覆盖 "Re:ゼロから始める休憩時間" 这类换名短篇
     *    (任何削字规则都不可解); 根条目名也过一遍削字候选. 名字来源与尝试次序见 [rootNameResolver].
     *
     * **削字必须排在关联回溯之前**: 一切派生自条目自己名字的候选 (原名 / 折叠形 / 削字形) 都比
     * "另一部作品的名字"可信 —— 关联回溯给的是**系列主条目**, 精确命中它就短路, 于是自己那个
     * 独立条目再也搜不到. 实测三例都是这么错的: 「機動戦士ガンダム 閃光のハサウェイ 第3部」
     * 归并到初代高达 (1979) 而不是自己的 movie/685274; 「聖闘士星矢 ... Part 2」归到 1986 年的
     * 聖闘士星矢; 「刃牙道 第2クール」归到 グラップラー刃牙. 三例的削字候选本来都能命中自己.
     * 顺序反过来对已验证的 51 条语料零影响 (Re:ゼロから始める休憩時間 那类压根没有削字候选,
     * 名字以汉字结尾, 逐字回退立刻停, 照样落到第三层).
     */
    private suspend fun <R : Any> searchLayered(
        originalName: String,
        /** 按尝试次序给出根条目名, 见 [rootNameResolver]; 给完了返回 null. */
        resolveRootName: suspend (attempt: Int) -> String?,
        hints: TmdbMatchHints = TmdbMatchHints.Empty,
        /**
         * **第三档**: 动画过滤的两档 (genre 16 / genre 缺失+日语) 都接不住"genre 填了但漏了 16"
         * 的条目 —— `LEMON ANGEL PROJECT` 的 TMDB 条目 genre 只有 `18 Drama`, 直搜唯一命中就是
         * 它却被两档同时丢掉. 只用 **primary 候选**并要求逐字同名, 且排在 [derived][NameQueries.derived]
         * **之前** —— 放到最末尾的话, 放开削字守卫后新生的短拉丁候选 (`ANGEL` / `lost` /
         * `DEATH STRANDING`) 会抢先命中 Angel Beats! / Lost / 困獸 那些无关条目.
         */
        thirdTier: (suspend (query: String) -> R?)? = null,
        /**
         * **中文候选**: TMDB 那边只有罗马字/英文标题, 或与 Bangumi 用了不同的汉字异体时,
         * 日文原名怎么削都搜不到 (`巖窟王` vs TMDB 的 `巌窟王`; FGO ソロモン 的四个标题字段全是
         * 罗马字). 用条目中文名搜一次能解开这一类. 排在 root 之前 —— 实测 root 层给出的是
         * 同系列的**另一部** (FGO 拿到了キャメロット), 不比中文名可信.
         */
        chineseTier: (suspend (name: String, isAlias: Boolean) -> R?)? = null,
        /**
         * **合集精确匹配** (见 [searchExactCollection]): 单条目档全 miss 时, 条目名/变体
         * 逐字命中一个 movie 合集也算精确答案 —— 排在削字暂定与 root 之前.
         */
        collectionTier: (suspend () -> R?)? = null,
        search: suspend (query: String, requireExactTitle: Boolean) -> LayeredHit<R>?,
    ): R? {
        val tried = mutableSetOf<String>()
        suspend fun trySearch(query: String, requireExactTitle: Boolean = false): LayeredHit<R>? =
            if (tried.add(query)) search(query, requireExactTitle) else null

        val nameCandidates = searchQueryCandidates(originalName)
        // 第一层的可疑命中先扣住 (见 LayeredHit.tentative), 让后两层有机会给出更可信的答案;
        // 都落空才用它 —— 扣住不等于丢弃, 否则这类条目会从"错图"变成"无图".
        var tentative: R? = null
        nameCandidates.primary.forEach { candidate ->
            val hit = trySearch(candidate) ?: return@forEach
            if (!hit.tentative) return hit.value
            if (tentative == null) tentative = hit.value
        }
        if (thirdTier != null) {
            nameCandidates.primary.forEach { candidate ->
                thirdTier(candidate)?.let { return it }
            }
        }
        // **削字命中一律降为暂定** (与剧照链 findTv 同一条规则, 理由见那边): 削掉的恰是
        // 区分子作品的部分, 精确命中的天然是更泛的本传; 让中文档 (带逐字校验) 先胜出,
        // miss 才用削字的命中. 纯拉丁削字候选仍须逐字同名 (ANGEL 不能拿 Angel Beats!,
        // 那条闸门原样保留在 requireExactTitle 上).
        var truncated: R? = null
        for (candidate in nameCandidates.derived) {
            val hit = trySearch(candidate, requireExactTitle = candidate.none { it.isCjkOrKana() })
                ?: continue
            truncated = hit.value
            break // 削字由长到短, 第一个命中的最具体
        }
        if (chineseTier != null) {
            // 中文名与别名分成两档: 别名要过"原名召回集"佐证 (见 ownNameRecallIds), 中文名不要
            if (hints.nameCn.isNotBlank()) {
                chineseTier(hints.nameCn, false)?.let { return it }
            }
            for (alias in hints.aliases) {
                if (alias.isBlank() || alias == hints.nameCn) continue
                chineseTier(alias, true)?.let { return it }
            }
        }
        collectionTier?.invoke()?.let { return it }
        // 削字暂定在手时 root 档不再走 (原先削字命中当场定案, root 同样走不到).
        if (truncated == null) {
            for (attempt in 0..<ROOT_NAME_ATTEMPTS) {
                val rootName = resolveRootName(attempt) ?: continue
                searchQueryCandidates(rootName).all.forEach { candidate ->
                    trySearch(candidate)?.let { return it.value }
                }
            }
        }
        return truncated ?: tentative
    }

    /**
     * 一次搜索的命中, 附带"要不要先扣住"的判断.
     *
     * @param tentative 命中可疑, 只在后两层 (关联回溯 / 削字) 全落空时才采用.
     *   仅第一层的命中会被扣住 —— 后两层本身就是兜底, 再扣就没有下家了.
     *   判据见 [isTentativeSeasonHit].
     */
    private class LayeredHit<R : Any>(val value: R, val tentative: Boolean = false)

    /**
     * **"查询串带季号, 却命中了一个电影条目, 标题还对不上字"** —— 这种命中先扣住.
     *
     * 三个条件的交集缺一不可, 每一条都是实测逼出来的:
     *  - **带季号**: 「進撃の巨人 Season 3」的 tv 搜索是 0 结果 (TMDB 那边季号不入标题),
     *    于是转 movie 命中了合集剧场版《覚醒の咆哮》—— 而正确答案 tv/1429 就在下一层
     *    (根条目名 / 削字后的「進撃の巨人」). 不带季号的查询没有这个"下一层更可信"的前提.
     *  - **命中是电影**: 季号说明它是剧集的某一季, 落在电影条目上本身就是信号.
     *  - **标题非逐字一致**: 这条是防回归的关键. 「Batman: The Dark Knight Returns, Part 1」
     *    这类条目**自己就是某一 Part 的独立电影**, 名字天然带季号标记, 而正确答案正是它自己
     *    那个 movie 条目 —— 实测扣住它会被下一层的合并版 (或**另一 Part**) 挤掉, 从对变错.
     *    这类条目的 TMDB 原名与 Bangumi 名逐字一致, 所以"精确命中直接采用"正好把它们放行.
     *
     * 与 [fetchEpisodeStills] 里的 `tentativeId` 同一个思路 (第一层的非精确命中不可信),
     * 但**没法合并**: 那条链路只搜 tv, "命中是电影"这个条件在那里永远不成立.
     */
    private fun isTentativeSeasonHit(query: String, type: String, result: TmdbSearchResult): Boolean =
        tmdbTentativeSeasonHit(
            query,
            isMovie = type == "movie",
            exactTitle = result.hasExactTitle(normalizeForMatch(query)),
        )

    /**
     * 沿 Bangumi 关联条目回溯"血统": 每跳优先「主线故事」(从番外/短篇跳回本篇),
     * 其次「前传」(沿季链上溯), 走到没有出边为止 —— 通常是第一季, 名字最干净, 正对应
     * TMDB "一个剧条目含全部季"的组织方式. 带环路保护与跳数上限.
     *
     * 顺带判定正传/衍生: 链上任何一跳出现「主线故事」出边即为衍生 (衍生/番外条目才有
     * 这种指回本篇的边, 正传季只有前传/续集); 整链只走前传则确认正传. 时长比对方案
     * (正片 ~24min vs 短篇 ~2min) 曾作备选, 但未播出的集两边时长都缺, 关系判定不依赖
     * 播出数据, 更稳.
     *
     * 直接调 Bangumi v0 公开 API 而非 Ani API: 后者服务端会过滤掉「主线故事」关系
     * (实测 getRelatedSubjects 对 Re:ゼロ休憩時間只返回续集). 失败返回 null, 不影响兜底.
     */
    private suspend fun resolveLineageOrNull(
        subjectId: Int,
        originalName: String,
        nameCn: String = "",
    ): BgmLineage? {
        // 先走 Ani 的关系索引: 墙内可直连, 一次请求直接拿到名字 (见 [resolveLineageViaSeriesIndex]).
        // 它给不出系列主条目时才回落到下面的 Bangumi 逐跳回溯.
        resolveLineageViaSeriesIndex(subjectId, originalName, nameCn)?.let { return it }
        return resolveLineageViaBgm(subjectId, originalName)
    }

    /** [resolveLineageOrNull] 的 Bangumi 逐跳回溯那半, 单独拿出来给 [rootNameResolver] 再试一次用. */
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun resolveLineageViaBgm(subjectId: Int, originalName: String): BgmLineage? {
        if (lineageFailureStreak.load() >= LINEAGE_FAILURE_LIMIT) return null
        return try {
            var currentId = subjectId
            var rootName: String? = null
            var sawMainStoryEdge = false
            var usedSoftEdge = false
            val seen = mutableSetOf(subjectId)
            var hops = 0
            while (hops < MAX_RELATION_HOPS) {
                val body = client.use {
                    get("$BANGUMI_API_BASE_URL/v0/subjects/$currentId/subjects") {
                        lineageTimeout()
                    }.bodyAsText()
                }
                val relations = json.decodeFromString(ListSerializer(BgmRelatedSubject.serializer()), body)
                    .filter { it.type == BGM_SUBJECT_TYPE_ANIME }
                val mainStory = relations.firstOrNull { it.relation == "主线故事" }
                if (mainStory != null) sawMainStoryEdge = true
                val next = mainStory ?: relations.firstOrNull { it.relation == "前传" }
                if (next == null) {
                    // 硬边 (主线故事/前传) 走完了, 第一跳还可以试一次软边, 见 [softSeriesEdge]
                    if (hops == 0) {
                        relations.softSeriesEdge(originalName, seen)?.let {
                            rootName = it.name
                            usedSoftEdge = true
                        }
                    }
                    break
                }
                if (!seen.add(next.id)) break
                currentId = next.id
                if (next.name.isNotBlank()) rootName = next.name
                hops++
            }
            lineageFailureStreak.store(0)
            BgmLineage(
                rootName = rootName?.takeIf { it != originalName },
                // 软边条目本身就是番外/PV, 但那条边说明不了它是不是衍生 —— 给"未知"而不是
                // false, 免得分集索引拿"确认正传"去把 S0 殿后 (见 [BgmLineage.isDerivative])
                isDerivative = if (usedSoftEdge) null else sawMainStoryEdge,
            ).also {
                logger.info {
                    "Resolved lineage for $subjectId: root=${it.rootName ?: "(self)"}, " +
                        "derivative=${it.isDerivative} ($hops hops)"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 递增的结果留住自己用: 再 load 一次可能已经被别的条目改过, 日志与判定就对不上了
            val streak = lineageFailureStreak.fetchAndAdd(1) + 1
            logger.warn(e) {
                "Failed to resolve lineage via Bangumi relations for subject $subjectId " +
                    "($streak/$LINEAGE_FAILURE_LIMIT consecutive failures)"
            }
            if (streak >= LINEAGE_FAILURE_LIMIT) {
                logger.warn { "Bangumi relation lookups disabled for this session (api.bgm.tv unreachable)" }
            }
            null
        }
    }

    /**
     * root 档的名字来源, 按尝试顺序惰性给出 (见 [searchLayered] 的 `resolveRootName`).
     *
     * 0. [resolveLineageOrNull] —— Ani 关系索引优先, 它给不出时内部已回落 Bangumi;
     * 1. Ani 给了名字却搜不到图时, **再走一遍 Bangumi 逐跳**: Ani 只列系列内条目名, 衍生作的
     *    母条目 (「タチコマな日々」→ 攻殻機動隊 SAC) 只在「主线故事」出边上, 那条边只有
     *    Bangumi 有. 实测衍生/前导类 821 条上多救回 9 条, 其余情形不多发请求.
     *
     * 第 1 档只在第 0 档来自 Ani 时才有意义 —— 走过 Bangumi 的结果已经是最后一手.
     */
    private fun rootNameResolver(
        subjectId: Int,
        originalName: String,
        nameCn: String,
    ): suspend (attempt: Int) -> String? {
        var viaAni = false
        return { attempt ->
            when (attempt) {
                0 -> resolveLineageOrNull(subjectId, originalName, nameCn)
                    ?.also { viaAni = it.viaAni }?.rootName

                else -> if (viaAni) resolveLineageViaBgm(subjectId, originalName)?.rootName else null
            }
        }
    }

    /**
     * 跨类型按信号强弱取 backdrop: tv 动画 → movie 动画 → tv 兜底 → movie 兜底.
     * 兜底档 (genre 缺失 + 日语原声) 必须排在两个类型的动画档之后 —— 否则舞台剧/纪录片
     * 这类无 genre 条目会抢在真正的动画前面 (实测 "千と千尋の神隠し" 的 tv 搜索首位是
     * 舞台剧纪录片, 无 genre、日语、标题含全部查询词, 正确的 movie 条目反而排在了后面).
     * tv 动画档命中时不发 movie 请求 (最常见情形保持单请求).
     */
    private suspend fun searchBackdropPath(
        query: String,
        subjectYear: Int?,
        requireExactTitle: Boolean = false,
        theatrical: Boolean = false,
        candidateNames: Set<String> = emptySet(),
    ): LayeredHit<String>? {
        val queryNormalized = normalizeForMatch(query)
        // 取"档次里第一个有图的结果"而不是直接取图: 要拿结果本身去判 isTentativeSeasonHit
        fun List<TmdbSearchResult>.firstWithBackdrop() = firstOrNull {
            it.backdropPath != null && (!requireExactTitle || it.hasExactTitle(queryNormalized))
        }

        suspend fun TmdbSearchResult.hit(type: String) =
            LayeredHit(heroBackdropPath(this, type), isTentativeSeasonHit(query, type, this))

        // **只在影院放映的条目先搜 movie**: TMDB 上它们是独立的 movie 条目, 而 tv 搜索几乎总能
        // 撞上同系列的某部剧 —— 实测 `ジョジョの奇妙な冒険 ファントムブラッド` 拿的是 1993 年那部
        // OVA 剧集, `UP` 拿的是毫不相干的「All Grown Up!」. 命中**必须与本条目的某个候选串逐字
        // 同名**才采用: 否则 `怪獣8号 第1期総集編` 会拿到**同日上映**的兄弟作「保科の休日」
        // (同日, 日期判据分不开), `Kong — The Origin` 会顺着根条目名 `悟空` 拿到龙珠剧场版.
        if (theatrical) {
            val movieFirst = searchAnime(query, "movie", subjectYear)
            (movieFirst.primary + movieFirst.fallback).firstOrNull {
                it.backdropPath != null && it.matchesAnyCandidate(candidateNames)
            }?.let { return it.hit("movie") }
        }

        val tv = searchAnime(query, "tv", subjectYear)
        tv.primary.firstWithBackdrop()?.let { return it.hit("tv") }
        val movie = searchAnime(query, "movie", subjectYear)
        return movie.primary.firstWithBackdrop()?.hit("movie")
            ?: tv.fallback.firstWithBackdrop()?.hit("tv")
            ?: movie.fallback.firstWithBackdrop()?.hit("movie")
    }

    /**
     * **海报兜底**: 整条链路都没找到 backdrop 时, 退而取同一批候选里的竖版海报.
     *
     * TMDB 上"有条目但没有背景图"是实打实的一类 —— 757 条语料里 42 条无图, 其中 **8 条**属此
     * (`Kanon` 2002 东映版 tv/72540 就只有 poster). 这一档不发新请求: 前面几层已经把同样的
     * 查询串搜过一遍, [searchRawResults] 的进程内记忆化会直接命中.
     *
     * **动画过滤的两档与顺序都跟 [searchAnime] 一致**: 兜底档 (genre 缺失 + 日语原声) 原先漏在
     * 这里, 于是 backdrop 那边接得住的条目 poster 这边接不住 —— `ドラゴンリーグ` 的 tv/103336
     * 名字与年份都逐字吻合, 只因 genre 空且没有背景图, 整条链路空手而归.
     */
    private suspend fun searchPosterPath(
        query: String,
        subjectYear: Int?,
        requireExactTitle: Boolean = false,
    ): String? {
        val tokens = tokenizeForMatch(query)
        val queryNormalized = normalizeForMatch(query)
        fun List<TmdbSearchResult>.pick(type: String, fallback: Boolean) = firstOrNull { result ->
            result.posterPath != null &&
                    (if (fallback) {
                        result.genreIds.isEmpty() && result.originalLanguage == "ja"
                    } else {
                        GENRE_ANIMATION in result.genreIds
                    }) &&
                    result.yearPlausible(type, subjectYear) &&
                    result.matchesTokens(tokens) &&
                    (!requireExactTitle || result.hasExactTitle(queryNormalized))
        }

        val tv = searchRawResults(query, "tv")
        tv.pick("tv", fallback = false)?.let { return it.posterPath }
        val movie = searchRawResults(query, "movie")
        return movie.pick("movie", fallback = false)?.posterPath
            ?: tv.pick("tv", fallback = true)?.posterPath
            ?: movie.pick("movie", fallback = true)?.posterPath
    }

    /**
     * **动画过滤的第三档**: genre 非空但不含 16 的结果, 要求与查询词逐字同名.
     *
     * 两档动画过滤 (genre 16 / genre 缺失 + 日语原声) 中间漏了一种: **genre 填了, 只是没填 16**.
     * `LEMON ANGEL PROJECT` 的 tv/35812 原名与条目逐字相同、日期也相同, genre 却只有 `18 Drama`,
     * 于是两档同时丢掉它. 逐字同名这道闸门是必需的 —— 不要求同名时 `Kanon` 会命中一部希腊剧.
     */
    private suspend fun searchThirdTierBackdrop(query: String, subjectYear: Int?): String? {
        val tokens = tokenizeForMatch(query)
        val queryNormalized = normalizeForMatch(query)
        for (type in listOf("tv", "movie")) {
            val hit = searchRawResults(query, type).firstOrNull { result ->
                result.genreIds.isNotEmpty() &&
                        GENRE_ANIMATION !in result.genreIds &&
                        result.backdropPath != null &&
                        result.yearPlausible(type, subjectYear) &&
                        result.matchesTokens(tokens) &&
                        result.hasExactTitle(queryNormalized)
            }
            if (hit != null) return hit.backdropPath
        }
        return null
    }

    /**
     * **中文候选**: 用条目中文名搜一次, 命中须与中文名逐字同名.
     *
     * 比较对象是三处的并集, 缺一条就少修一个条目:
     *  - **`language=zh-CN` 的 `name`/`title`** —— TMDB 的中文名只在这里, **不在 alternative_titles**
     *    (tv/9543 的 alt 全是欧洲语言 + 韩语, 而 zh-CN name 正是 `岩窟王`, 与 bgm 的中文名逐字相同,
     *    日文原名那边是异体字 `巌`/`巖`, NFKC 折叠不管这个);
     *  - 原名 (`original_name`/`original_title`);
     *  - **`alternative_titles`** —— FGO ソロモン 的 zh-CN title 用的是官方译名「命运／冠位指定…」,
     *    对不上 bgm 的写法, 而 alt 里恰好有一条 `Fate/Grand Order - 终局特异点…` 逐字相同.
     *
     * 逐字同名这道闸门同样是必需的: `Kong — The Origin` 的 bgm 中文名是 `悟空`, 不加闸门会捞到
     * 乐高悟空小侠、悟空传、黑神话：悟空 一大串.
     */
    /**
     * @param isAlias 这个候选来自 infobox「别名」而非中文名 —— 命中要过原名召回集的佐证,
     *   见 [ownNameRecallIds].
     */
    /**
     * 搜索带 `language=zh-CN` (要拿 TMDB 的中文标题逐字比), 但**取图不看这一档的语言** ——
     * 统一走 [heroBackdropPath].
     */
    private suspend fun searchChineseBackdrop(
        name: String,
        subjectYear: Int?,
        isAlias: Boolean,
        originalName: String,
    ): String? {
        val queryNormalized = normalizeForMatch(name)
        for (type in listOf("tv", "movie")) {
            val results = searchRawResults(name, type, language = CHINESE_LANGUAGE)
            for (result in results) {
                if (result.backdropPath == null) continue
                // genres 空放行, 理由见 searchChineseTv
                if (result.genreIds.isNotEmpty() && GENRE_ANIMATION !in result.genreIds) continue
                if (!tmdbExactVariantYearPlausible(result.releaseYearOrNull(), subjectYear)) continue
                val id = result.id ?: continue
                if (isAlias && id !in ownNameRecallIds(originalName, type)) continue
                // 这一档本来就是 zh-CN 搜的, 结果里那张已经是中文版; 与主档口径一致
                // 与主档同一个取图出口: 否则"哪一档赢"决定拿哪张图, 同一部剧各季 hero 不一致
                if (result.hasExactTitle(queryNormalized)) return heroBackdropPath(result, type)
                val alt = runCatching { fetchAlternativeTitles(id, type) }.getOrElse { emptyList() }
                if (alt.any { normalizeForMatch(it) == queryNormalized }) {
                    return heroBackdropPath(result, type)
                }
            }
        }
        return null
    }

    /**
     * TMDB 搜索, 结果限定为动画且标题须与查询词逐词匹配.
     *
     * 动画过滤: TMDB 会把同名真人版排在动画前面 (如 ONE PIECE 首位是 Netflix 真人剧),
     * 必须按 genre 16 (Animation) 过滤; 个别条目缺失 genre 数据, 用日语原声兜底.
     * 全都不是动画时宁可不出图也不出真人版.
     *
     * 标题校验: TMDB 的模糊搜索对短查询词会返回貌似相关的错误条目 (实测 "うらおん!"
     * 返回 "うらみちお兄さん", "君の名は。" 的 tv 搜索返回 "君の魔名はリナ・ウィッチ..."),
     * 要求查询词的每个分词都作为子串出现在结果标题里 —— 标题多出词允许 (如
     * "デート・ア・バレット 前編 デッド・オア・バレット" 命中不带 "前編" 的查询词),
     * 插字/换字则拒绝. 校验失败宁可无结果, 交给下一层候选 (关联回溯/削字).
     */
    /**
     * 一次 `/search/{type}` 的**原始结果**, 按 (查询串, tv|movie) 记忆化 + 在途合流,
     * 理由与边界见 [searchMemo].
     *
     * **年份不进键**: 它不影响服务端返回什么 (URL 里就没有它), 只影响拿到结果之后的年份否决与
     * 重名就近排序 —— 那些留在 [searchAnime] 里按调用方各自的年份现算. 2026-08-21 实测过反面:
     * 把年份放进键、整个后处理一起缓存, 于是「機動戦士ガンダム」这串被两个不同年份的条目各搜了
     * 一遍 (同进程内相隔 26 秒), 记忆化在最该生效的兄弟条目场景上全落空.
     */
    private suspend fun searchRawResults(
        query: String,
        type: String,
        language: String? = null,
    ): List<TmdbSearchResult> {
        val key = SearchKey(query, type, language)
        val task = searchMemoLock.withLock {
            searchMemo[key]?.let { return it }
            searchInFlight[key] ?: resolveScope.async {
                try {
                    fetchSearchResults(query, type, language).also { result ->
                        searchMemoLock.withLock {
                            searchMemo[key] = result
                            // 定容: 超了按插入序摘最早的那些 (mutableMapOf 是 LinkedHashMap).
                            // 逐字符回退会一次塞十几条, 留够几个系列的量就行
                            while (searchMemo.size > SEARCH_MEMO_MAX_ENTRIES) {
                                searchMemo.remove(searchMemo.keys.first())
                            }
                        }
                    }
                } finally {
                    // 摘除放在 async 内: 调用方 (某次聚焦) 被取消时它的 await 直接抛,
                    // 外面的 finally 跑不到 —— 与 backdropInFlight 同一个理由
                    searchMemoLock.withLock { searchInFlight.remove(key) }
                }
            }.also { searchInFlight[key] = it }
        }
        return task.await()
    }

    private suspend fun fetchSearchResults(
        query: String,
        type: String,
        language: String? = null,
    ): List<TmdbSearchResult> = client.use {
        val body = getApi("/search/$type") {
            parameter("query", query)
            parameter("include_adult", "true")
            // 只有中文候选那一档会传 language: 拿的是 TMDB 的**中文标题**, 它不在
            // alternative_titles 里 (见 [searchChineseBackdrop]). 其余搜索一律不传,
            // 保持 name/title 为 en-US —— 现有的逐字同名判据依赖这个口径.
            language?.let { parameter("language", it) }
            bearerAuth(currentAniBuildConfig.tmdbApiToken)
            shortConnectTimeout()
        }.bodyAsText()
        json.decodeFromString(TmdbSearchResponse.serializer(), body).results
    }

    private suspend fun searchAnime(
        query: String,
        type: String,
        subjectYear: Int?,
    ): TmdbAnimeSearchResults {
        val tokens = tokenizeForMatch(query)
        val queryNormalized = normalizeForMatch(query)
        val results = searchRawResults(query, type)
        // 年份否决放在**标题校验与别名兜底之前**: 别名兜底只看最靠前的 2 个结果, 那两个名额
        // 不该浪费在年份上根本不可能的条目上 (攻殻機動隊 1995 剧场版的 movie 搜索里, 正确的
        // tv/9323 排在 2.0 重制版与 2026 新作之后, 年份先筛掉那两个它才进得了别名校验).
        val anime = results.filter { GENRE_ANIMATION in it.genreIds && it.yearPlausible(type, subjectYear) }
        val matched = anime.filter { it.matchesTokens(tokens) }
            .ifEmpty {
                // 主标题没匹配上时查别名再校验一次: TMDB 模糊搜索能命中而主标题不含查询词,
                // 通常是别名在起作用 (如 JoJo 主条目别名含 "スティール・ボール・ラン ジョジョの奇妙な冒険").
                // 只查最靠前的 2 个结果, 且仅发生在失败路径, 结果又按条目持久缓存, 成本一次性.
                anime.take(2).filter { result ->
                    val id = result.id ?: return@filter false
                    val altTitles = runCatching { fetchAlternativeTitles(id, type) }.getOrElse { emptyList() }
                    altTitles.isNotEmpty() && result.matchesTokens(tokens, altTitles)
                }
            }
        // 标题与查询完全一致的排最前: "标题多出词允许"会让外传/衍生作也通过校验
        // (如搜 DanMachi 正传名, TMDB 把外传 "ソード・オラトリア ...だろうか外伝" 排在
        // 正传前面, 外传标题包含完整正传名), 完全一致的正传必须优先; 稳定排序,
        // 无完全一致时保持 TMDB 原序 (前編/後編这类只有超集标题的场景不受影响).
        val exact = matched.filter { it.hasExactTitle(queryNormalized) }
        val inexact = matched.filterNot { it.hasExactTitle(queryNormalized) }
        // **精确同名有多个 = 同一作品的不同版本** (重制/复播): 名字里没有任何可区分的信息
        // (查询串对它们是同一个字符串), 年份是唯一判据 —— 按与条目年份的距离就近取.
        // 实测 TMDB 上这类重名条目相当常见: うる星やつら 1981/2022、HUNTER×HUNTER 1999/2011、
        // フルーツバスケット 2001/2019、キャプテン翼 1983/2001/2018、ゲゲゲの鬼太郎 六个
        // (1968/1971/1985/1996/2007/2018) —— 并列时听 TMDB 相关性排序, 一律被最老那个吃掉.
        //
        // **只在精确组内部就近排**: 全局按年份就近会把 進撃の巨人 Season 3 (2018) 从
        // tv/1429 (2013) 推到非精确但通过校验的「進撃の巨人 反撃の狼煙」(2015) 上去 —— 那更错.
        // 单个精确命中 (绝大多数情形) 走的还是原来那条路, 顺序一点不变.
        val orderedExact = if (subjectYear != null && exact.size > 1) {
            exact.sortedBy { tmdbYearProximity(it.releaseYearOrNull(), subjectYear) }
        } else {
            exact
        }
        return TmdbAnimeSearchResults(
            primary = orderedExact + inexact,
            // 兜底档只做主标题校验, 不值得为弱信号再发别名请求
            fallback = results
                .filter { it.genreIds.isEmpty() && it.originalLanguage == "ja" && it.yearPlausible(type, subjectYear) }
                .filter { it.matchesTokens(tokens) },
        )
    }

    private suspend fun fetchAlternativeTitles(id: Int, type: String): List<String> = client.use {
        val body = getApi("/$type/$id/alternative_titles") {
            bearerAuth(currentAniBuildConfig.tmdbApiToken)
            shortConnectTimeout()
        }.bodyAsText()
        val parsed = json.decodeFromString(TmdbAlternativeTitles.serializer(), body)
        (parsed.results + parsed.titles).mapNotNull { it.title }
    }

    /**
     * **合集 (collection) 的精确匹配**: TMDB 把"每集一部电影"的 OVA 系列记成 movie 合集
     * (「機動戦士ガンダム THE ORIGIN」= 6 部 movie 的合集, tv 侧没有本体), 单条目搜索怎么都
     * 够不到. 条目名/中文名/别名逐字命中合集名 (剥掉「（系列）/シリーズ/Collection」类惯例
     * 后缀, 见 [COLLECTION_SUFFIX_REGEX]) 才算, 且至少一部 part 的上映年须与条目年接近
     * (对称容差, 同 [tmdbExactVariantYearPlausible]) —— 合集没有 genre 字段, 这两道闸门就是
     * 全部防御.
     *
     * 搜索固定用中文召回并校验 (TMDB 的 query 跨语言, 中文合集名最全, 校验对全部变体做);
     * 详情按 [language] 拉, 让 parts 的简介本地化.
     */
    private suspend fun searchExactCollection(
        originalName: String,
        hints: TmdbMatchHints,
        subjectYear: Int?,
        language: String,
    ): TmdbCollectionDetail? = client.use {
        val variants = buildList {
            add(originalName)
            addAll(hints.exactNameVariants)
        }.filter { it.isNotBlank() }.distinct()
        if (variants.isEmpty()) return@use null
        val variantsNormalized = variants.mapTo(mutableSetOf()) { normalizeForMatch(it) }
        for (query in variants) {
            val response = json.decodeFromString(
                TmdbCollectionSearchResponse.serializer(),
                getApi("/search/collection") {
                    parameter("query", query)
                    parameter("language", CHINESE_LANGUAGE)
                    bearerAuth(currentAniBuildConfig.tmdbApiToken)
                    shortConnectTimeout()
                }.bodyAsText(),
            )
            for (result in response.results) {
                val id = result.id ?: continue
                val name = result.name ?: continue
                if (normalizeForMatch(COLLECTION_SUFFIX_REGEX.replace(name, "")) !in variantsNormalized) continue
                val detail = json.decodeFromString(
                    TmdbCollectionDetail.serializer(),
                    getApi("/collection/$id") {
                        parameter("language", language)
                        bearerAuth(currentAniBuildConfig.tmdbApiToken)
                        shortConnectTimeout()
                    }.bodyAsText(),
                )
                val years = detail.parts.mapNotNull { it.releaseDate?.take(4)?.toIntOrNull() }
                if (subjectYear != null && years.isNotEmpty() &&
                    years.none { (it - subjectYear).absoluteValue <= EXACT_VARIANT_YEAR_TOLERANCE }
                ) continue
                logger.info { "TMDB collection match: $id ($name) via query $query" }
                return@use detail
            }
        }
        null
    }

    /**
     * [searchExactCollection] 命中时把 parts 按上映日当集表 (语义同 [buildMovieAsSingleEpisode],
     * 但一部一"集"). 集数闸门: "合集当集表"只适用每集一部电影的形态, parts 数与条目集数差太多
     * 说明这是"正传剧 + 几部剧场版"的普通合集, 硬当集表只会全错 (那种条目的正解在 tv 侧).
     */
    private suspend fun collectionAsEpisodes(
        subjectId: Int,
        originalName: String,
        language: String,
        subjectYear: Int?,
        subjectEpisodeCount: Int?,
        subjectEpisodeNames: List<String>,
        hints: TmdbMatchHints,
    ): TmdbEpisodeStills? {
        searchExactCollection(originalName, hints, subjectYear, language)?.let { detail ->
            wholeCollectionAsEpisodes(subjectId, detail, language, subjectEpisodeCount)?.let { return it }
        }
        // **削字合集 + parts 按集标题认领**: SE/总集编条目的合集名往往是"系列基名 + シリーズ"
        // (機動戦士ガンダムSEED シリーズ), 与条目全名对不上, 只有削字候选剥后缀后逐字.
        // 单集条目走 movie 档, 两集起才有认领的意义.
        if (subjectEpisodeNames.size >= 2) {
            searchTruncatedCollection(originalName, language)?.let { detail ->
                claimCollectionPartsByEpisodeTitles(subjectId, detail, subjectEpisodeNames, language)
                    ?.let { return it }
            }
        }
        return null
    }

    /** 精确变体命中的合集整体当集表 (一部一"集"). */
    private fun wholeCollectionAsEpisodes(
        subjectId: Int,
        detail: TmdbCollectionDetail,
        language: String,
        subjectEpisodeCount: Int?,
    ): TmdbEpisodeStills? {
        val parts = detail.parts.filter { !it.releaseDate.isNullOrBlank() }.sortedBy { it.releaseDate }
        if (parts.isEmpty()) return null
        if (subjectEpisodeCount != null && (parts.size - subjectEpisodeCount).absoluteValue > 2) return null
        val medias = parts.map { it.toEpisodeMedia() }
        if (medias.none { it.stillUrl != null }) return null
        logger.info { "TMDB collection-as-episodes for subject $subjectId: ${parts.size} parts" }
        return TmdbEpisodeStills(
            byAirDate = partsByAirDate(parts, medias),
            byEpisodeNumber = List(medias.size) { it + 1 }.zip(medias).toMap(),
            language = language,
            showOverview = detail.overview?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 削字候选搜合集. 削字命中的合集**不能整体采信** (系列基名级的合集谁都能削出来), 只作为
     * [claimCollectionPartsByEpisodeTitles] 的输入. **不做年份判据**: TMDB 给 parts 记的常是
     * HD 重映日 (SEED SE 原发售 2004, TMDB 记 2023), 条目年对不上是常态, 认领闸门足够硬.
     * 搜索与校验用日文 (削字候选来自日文原名, 合集的中文名常是意译, 剥后缀后对不上).
     */
    private suspend fun searchTruncatedCollection(
        originalName: String,
        language: String,
    ): TmdbCollectionDetail? = client.use {
        val candidates = searchQueryCandidates(originalName)
        for (query in candidates.derived) {
            val queryNormalized = normalizeForMatch(query)
            val response = json.decodeFromString(
                TmdbCollectionSearchResponse.serializer(),
                getApi("/search/collection") {
                    parameter("query", query)
                    parameter("language", "ja-JP")
                    bearerAuth(currentAniBuildConfig.tmdbApiToken)
                    shortConnectTimeout()
                }.bodyAsText(),
            )
            for (result in response.results) {
                val id = result.id ?: continue
                val name = result.name ?: continue
                if (normalizeForMatch(COLLECTION_SUFFIX_REGEX.replace(name, "")) != queryNormalized) continue
                return@use json.decodeFromString(
                    TmdbCollectionDetail.serializer(),
                    getApi("/collection/$id") {
                        parameter("language", language)
                        bearerAuth(currentAniBuildConfig.tmdbApiToken)
                        shortConnectTimeout()
                    }.bodyAsText(),
                )
            }
        }
        null
    }

    /**
     * 从合集 parts 里按 **bgm 分集标题**认领"属于本条目的那几部": part 的原语言标题须**包含**
     * 集名 (「機動戦士ガンダムSEED スペシャルエディション 虚空の戦場」⊇「虚空の戦場」),
     * **每一集都认领到且各对到不同 part** 才算数 —— 一集对不上就整体放弃, 这是削字合集档的
     * 全部闸门. 集名归一化后短于 4 字符的不参与 (「第1話」类占位名会瞎撞).
     * 认领结果按集号填 byEpisodeNumber (SE 类条目的 bgm 分集常无日期, 日期轴不可用),
     * parts 有上映日的同时填 byAirDate.
     */
    private fun claimCollectionPartsByEpisodeTitles(
        subjectId: Int,
        detail: TmdbCollectionDetail,
        episodeNames: List<String>,
        language: String,
    ): TmdbEpisodeStills? {
        // 两侧都剥掉括号段再比: TMDB 的 part 标题常给汉字加注音括号
        // (「運命（さだめ）の業火」 vs bgm 集名「運命の業火」), 含匹配会因此漏掉.
        fun normalizeTitle(t: String) = normalizeForMatch(t.replace(PAREN_SEGMENT_REGEX, ""))
        val partTitles = detail.parts.map { normalizeTitle(it.originalTitle ?: it.title ?: "") }
        val used = mutableSetOf<Int>()
        val claimed = ArrayList<TmdbCollectionPart>(episodeNames.size)
        for (epName in episodeNames) {
            val n = normalizeTitle(epName)
            if (n.length < 4) return null
            val idx = partTitles.indices.firstOrNull { it !in used && partTitles[it].contains(n) }
                ?: return null
            used += idx
            claimed += detail.parts[idx]
        }
        val medias = claimed.map { it.toEpisodeMedia() }
        if (medias.none { it.stillUrl != null }) return null
        logger.info {
            "TMDB collection parts claimed by episode titles for subject $subjectId: " +
                    "${claimed.size}/${detail.parts.size}"
        }
        return TmdbEpisodeStills(
            byAirDate = partsByAirDate(claimed, medias),
            byEpisodeNumber = List(medias.size) { it + 1 }.zip(medias).toMap(),
            language = language,
            showOverview = detail.overview?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun TmdbCollectionPart.toEpisodeMedia() = TmdbEpisodeMedia(
        stillUrl = backdropPath?.let { "$STILL_IMAGE_BASE_URL$it" },
        overview = overview?.trim()?.takeIf { it.isNotBlank() },
    )

    private fun partsByAirDate(
        parts: List<TmdbCollectionPart>,
        medias: List<TmdbEpisodeMedia>,
    ): Map<String, List<TmdbEpisodeMedia>> = buildMap {
        parts.forEachIndexed { i, part ->
            val key = part.releaseDate?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            put(key, get(key).orEmpty() + medias[i])
        }
    }

    /**
     * 条目名派生出的搜索候选, 按**尝试时机**分成两层 (见 [searchLayered]):
     * 先试 [primary], 再试 [derived], 最后才轮到关联回溯给的根条目名.
     */

    /**
     * 生成搜索候选名, 依次尝试: 原名 → 同形字折叠形 → 去掉 OVA/OAD 类关键字 → 剥版本后缀 →
     * 从季标记处截断 → 去掉罗马数字季号 → 去掉尾部裸数字季号 → 逐词去尾 (从最后一个空格起,
     * 最多 [WORD_TRUNCATE_MAX_DEPTH] 个词) → 末尾非文字字符逐个回退.
     *
     * 前两个属 [NameQueries.primary] (关联回溯之前试), 其余属 [NameQueries.derived] (之后试).
     *
     * 候选是懒惰短路搜索的 (firstNotNullOfOrNull): 前面的候选命中后, 后面的不发请求;
     * 结果按条目持久缓存, 只有全部规则落空的条目才会把候选走到底, 多出的查询成本一次性.
     *
     * TMDB 把分割放送/续季并进同一个剧条目, 用 Bangumi 本季条目名常搜不到
     * (如 "無職転生 ～...～ 第2クール" 0 结果, 去后缀即命中); 季标记后面可能还跟着
     * 篇章名 (如 "Re:ゼロ... 4th season 喪失編"), 所以从标记处截断到串尾;
     * 序数词式 ("4th season") 与 "Season 4" 式都要认.
     *
     * OVA/OAD 在 TMDB 中是母番的特别篇 (season 0), 已被分集索引覆盖且按播出日期
     * (发售日) 可精确匹配 (实测 進撃の巨人 OAD、DanMachi 各季 OVA 均逐日对上),
     * 所以只需把条目名还原成母番名: 去掉关键字直接搜 (含副标题也常能命中, 如
     * "進撃の巨人 悔いなき選択"), 搜不到再逐词去掉尾部副标题.
     */
    private fun searchQueryCandidates(name: String): NameQueries =
        tmdbSearchQueryCandidates(name)

    /**
     * 条目**自己名字**在 TMDB 的搜索召回集 (primary 候选 × 一种 type 的全部结果 id).
     *
     * 这是**别名档的佐证**: 别名是本名的变体, 本名一定能把同一条 TMDB 记录召回来 —— 哪怕标题
     * 对不上字 (那才需要别名去逐字命中), TMDB 的模糊召回也会给出它. 召回不到, 说明这条别名
     * 指的是**另一部作品**.
     *
     * 病例 (2026-09-06): `Re:プチから始める異世界生活` (bgm 185837) 的 infobox「别名」写着
     * `Isekai Shokudou` —— 那是**異世界食堂**, 与本作毫无关系 (bangumi 上的数据错误). 别名档
     * 逐字命中 tv/72425 (2017-07-04, 24 集), 对称年份容差 3 拦不住 (Δ1 年), 于是 hero 背景和
     * 整排选集卡全成了異世界食堂的图. 而它自己的原名与中文名在 TMDB 上都是 **0 结果**,
     * 召回集是空的 —— 闸门据此拒掉, 条目回落到「主线故事」根 (Re:ゼロ 本传), 与同族的
     * 「休憩時間」一致.
     * 反面对照: `GUNDAM EVOLVE` (42789) 原名直搜就召回 tv/101719 + movie/411218, 正是两条
     * 别名逐字命中的那两条 —— 放行, 那条修复不受影响.
     *
     * **只管 aliases, 不碰 nameCn**: 中文名是条目的权威译名, 而 infobox「别名」是自由文本,
     * 谁都能往里写别的作品. 「巖窟王」(异体字) / 「FGO ソロモン」(TMDB 四个标题字段全是罗马字)
     * 这类正是"原名召回不到正主、只有中文名对得上"的条目, 给中文名加闸门会把它们打回无图.
     *
     * 零额外请求: [searchRawResults] 自带 memo, primary 候选在本次解析里早已被第一档搜过.
     */
    private suspend fun ownNameRecallIds(originalName: String, type: String): Set<Int> =
        searchQueryCandidates(originalName).primary
            .flatMap { searchRawResults(it, type) }
            .mapNotNullTo(mutableSetOf()) { it.id }

    /**
     * 读缓存; 版本不符时整体作废重建 —— 匹配算法变更后旧结果可能是错的
     * (如动画过滤加入前 ONE PIECE 缓存了真人剧的 backdrop).
     */
    private suspend fun readCache(): TmdbImageCache {
        val cache = dataStore.data.first()
        if (cache.version == TmdbImageCache.CURRENT_VERSION) return cache
        return dataStore.updateData { TmdbImageCache(version = TmdbImageCache.CURRENT_VERSION) }
    }

    private companion object {
        private val logger = logger<TmdbImageService>()
        /**
         * TMDB API 的两个域名, 按优先级排列, 都是官方的: `api.tmdb.org` 的证书主体是
         * `CN=*.tmdb.org` (Amazon ACM 签发), 与 `api.themoviedb.org` 同为 CloudFront 后端,
         * 同一个 token 通用, 响应逐字节一致.
         *
         * 主用别名的原因: `api.themoviedb.org` 在中国大陆基本连不上 (TCP 超时, 不是 DNS
         * 投毒 —— 报告者开了加密 DNS 也救不回来, 家宽和移动流量都一样), 而封锁按 SNI 域名
         * 粒度做, 换个域名就绕开了. 图床 `image.tmdb.org` 一直是通的, 所以只要 API 能通,
         * 图就能出来 (issue #7).
         *
         * 不删掉 `api.themoviedb.org`: 别名是官方不宣传的历史域名, 可能下线或以后也被墙,
         * 留作回退. 见 [getApi].
         */
        private val API_BASE_URLS = listOf(
            "https://api.tmdb.org/3",
            "https://api.themoviedb.org/3",
        )
        private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w1280"

        /**
         * 图床连通性探测用的真实图片 (w92 档, 约 9 KB).
         *
         * 不探裸目录 `t/p/w1280`: 那个路径边缘不缓存, 每次都回源, 实测要 2.5 秒才吐一个 404,
         * 还偶发 `Connection reset` —— 一次探测两个请求就占掉 5 秒, 探测本身成了设置页
         * 那一行慢的主因 (issue #7 报告者日志). 真实图片命中边缘缓存, 快且稳, 顺带验证了
         * 图片确实下得下来.
         *
         * 这张图哪天被换掉也不影响判定: 这里只看能否拿到 HTTP 响应, 不看状态码 —— 404
         * 同样说明域名是通的, 被墙才会连不上或超时.
         */
        private const val IMAGE_PROBE_URL = "https://image.tmdb.org/t/p/w92/rBOnrVlck7BIlGeWVlzYiZeg4l2.jpg"
        private const val GENRE_ANIMATION = 16
        private const val BANGUMI_API_BASE_URL = "https://api.bgm.tv"
        private const val BGM_SUBJECT_TYPE_ANIME = 2

        /** 关联回溯跳数上限 (实测常见链 1-2 跳, 上限只是环路/脏数据保险). */
        private const val MAX_RELATION_HOPS = 8

        /** root 档最多问几次名字, 见 [rootNameResolver]. */
        private const val ROOT_NAME_ATTEMPTS = 2


        /**
         * 关联回溯连续失败多少次后本次进程放弃 (见 [lineageFailureStreak]).
         *
         * 取 2 而不是 1: 单次失败可能只是抖动, 两次连续失败基本可以断定这条网络到
         * `api.bgm.tv` 不通. 再大就没意义了 —— 每多试一次就是白等 10 秒.
         */
        private const val LINEAGE_FAILURE_LIMIT = 2

        /**
         * 进程内 backdrop 热表的容量上限. 远高于一次浏览会掠过的条目数 (单条只是一个 URL 字符串);
         * 超限按写入顺序淘汰最老一批 —— 见 [resolvedBackdropUrls] 处不许整表清空的原因.
         */
        private const val RESOLVED_HOT_CACHE_MAX = 600

        /** 超限时一次淘汰的条数 (均摊淘汰开销, 不必每次写入都动表). */
        private const val RESOLVED_HOT_CACHE_EVICT_BATCH = 100

        /** 最新已播集在此天数内 = 还在播或刚完结, 负缓存按 [NEGATIVE_CACHE_TTL_AIRING] 失效. */
        private const val NEGATIVE_CACHE_AIRING_DAYS = 60

        /** 最新已播集在此天数内 = 近作, 负缓存按 [NEGATIVE_CACHE_TTL_RECENT] 失效; 更早则永久. */
        private const val NEGATIVE_CACHE_RECENT_DAYS = 365

        private val NEGATIVE_CACHE_TTL_AIRING = 3.days
        private val NEGATIVE_CACHE_TTL_RECENT = 30.days

        /** 单条目剧照上限 (屏保轮播用不到更多, 控制缓存体积). */
        private const val MAX_BACKDROPS_PER_SUBJECT = 20

        /** 逐词去尾最多剥几个词, 见 [searchQueryCandidates]. */

        /** 中文候选那一档搜索用的语言 (见 [searchChineseBackdrop]). */
        private const val CHINESE_LANGUAGE = "zh-CN"

        /** OVA/OAD/特别篇类关键字: 触发母番名还原 (这些内容在 TMDB 里是母番的 season 0 特别篇). */
        /**
         * 分集 still 官方档位只有 w92/w185/w300/original, w300 太糊, 存原图档 URL;
         * 消费端按用途降档: 选集卡片 [tmdbStillCardSizeUrl] (w780), 全屏 hero 背景
         * [tmdbStillHeroSizeUrl] (w1280) —— 都不直接解码原图.
         */
        private const val STILL_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/original"
    }
}

/**
 * 把 original 档的 TMDB still URL 降到卡片档: 选集卡片目标尺寸远小于全屏, 原图档
 * (1920 级, ~180KB) 的下载/解码是纯浪费. w780 不在 still 官方档位表里, 但 CDN
 * 实测同样支持 (~40KB), 且远高于卡片所需分辨率. 非 TMDB original URL 原样返回.
 */
fun tmdbStillCardSizeUrl(url: String): String = url.replace("/t/p/original/", "/t/p/w780/")

/**
 * 把 original 档的 TMDB still URL 降到全屏 hero 背景档 (探索/追番页"下一集剧照"背景):
 * w1280 铺 4K backdrop 约 2 倍放大, 经渐隐/压暗后 10-foot 距离不可辨; 原图档偶有 4K 级
 * (解码位图 8-33MB), 低端盒子上每次聚焦换卡都是一记下载+解码重锤 (2026-07-31 性能整改).
 * 非 TMDB original URL 原样返回.
 *
 * [fullQuality] 为真时原样返回 (设置里开了完整视觉效果, 见
 * [ThemeSettings.tvFullVisualEffects][me.him188.ani.app.data.models.preference.ThemeSettings]).
 * 因此**存缓存时不要降档**, 存原图档 URL, 由显示端按当前设置现降 —— 否则改设置要清缓存才生效.
 */
fun tmdbStillHeroSizeUrl(url: String, fullQuality: Boolean = false): String =
    if (fullQuality) url else url.replace("/t/p/original/", "/t/p/w1280/")

/**
 * 把整部 backdrop 的 URL **升**到原图档 —— 原生 4K UI 上**只给详情页那一张全屏 hero** 用
 * (见 `HeroBackdropSharpeningOverlay`). 列表/网格页恒 w1280, 理由就在下面那笔账里.
 *
 * backdrop 与 still 相反, 存进缓存的就是 w1280 档 (见 `IMAGE_BASE_URL`), 所以这里是升档而不是
 * 降档; 也因此**只能在显示端改写**: 已经解析过的条目缓存里躺的是 w1280 URL, 改存档常量对它们
 * 一律无效.
 *
 * 为什么 4K 下要升: TMDB 的 backdrop 档位只有 w300/w780/w1280/original, w1280 之上直接就是
 * 原图. 1080p UI 上 w1280 铺满屏是 1.5 倍放大 (可接受), 而原生 4K UI 下框是 3840×2175 ——
 * **3.0 倍**, 实测日志里 `SOURCE_LIMITED drawScale=x3.02` 就是它.
 *
 * **别拿下载体积衡量这件事** (2026-08-21 踩过): 按体积看它很便宜 —— 实测三张原图 351KB /
 * 1001KB / 354KB, 对应 w1280 的 188/210/271KB, 典型只有 1.7 倍. 但真正贵的是**解码后的位图**:
 * 1280×720 是 3.7MB, 3840×2160 是 **33MB**, 差 9 倍. 同一天把列表/网格页也一起升档, 4K
 * 实测净亏:
 * ```
 *                     只升前        全局升后
 * decode+queue 均值    355ms   →     495ms
 * decode+queue 峰值   1557ms   →    4419ms
 * 阻塞 GC              20 次   →     115 次
 * 最长掉帧            123 帧   →     351 帧 (约 5.8 秒)
 * 平均解码像素/次       428K   →      853K
 * ```
 * 而**收益不均且多半落空**: 同一份日志里 21 张 backdrop 原图有 17 张本身就只有 1280×720 ——
 * 升上去一点不赚 (`SOURCE_LIMITED drawScale=x3.02` 原样保留), 只白多下几十 KB; 真正变清的只有
 * 那 4 张 3840×2160 的, 每张要付 33MB 位图. 档位路径不带尺寸信息, 事先无从分辨.
 *
 * 于是收窄成"只给详情页那一张": 一次一张、停留久、没有邻居预取的乘数, 那 4/21 的收益只需付一次
 * 代价. 列表/网格页的 hero 跟着焦点换, 代价要乘上"每划过一张卡一次" —— 就是上表那笔亏账.
 *
 * 1080p UI 上**不要**开这个: 那里 w1280 已经够用, 升档纯粹多花流量和解码.
 */
fun tmdbBackdropOriginalSizeUrl(url: String): String = url.replace("/t/p/w1280/", "/t/p/original/")

/** 日文假名/汉字 (含中文): 候选名末尾回退时视为名字本体, 到此为止不再往前剥. */
internal fun Char.isCjkOrKana(): Boolean =
    this in '぀'..'ヿ' || // 平假名 + 片假名 (含长音符 ー)
        this in '一'..'鿿' || // CJK 统一汉字
        this == '々' // 々 (叠字符)

/**
 * 写入一条 backdrop 解析结果, **并把持久表压在上限内**.
 *
 * ## 为什么必须有上限
 *
 * `dataStore.updateData { it.copy(backdropUrls = map + entry) }` 是**整表复制 + 整个缓存重新
 * 序列化落盘**, 所以每解析一个新条目的写盘成本是 `O(已缓存条目数)`. 这张表原先没有任何上限
 * (旁边 [TmdbImageCache.backdropMissAt] 那句"免得随收藏量无限增长"说的是时间戳表, 主表漏了),
 * 于是成本随使用**单调上升且持久化** —— 重装前不会自愈.
 *
 * 从前增长速度是"用户真正聚焦过的条目", 一天几十条还能忍; 加了邻居预取之后每移动一格要解析
 * 最多四个条目 (聚焦 + 三个邻居), 增速直接翻几倍, 这个上限就成了必需品.
 *
 * ## 为什么是写入顺序而不是真 LRU
 *
 * 真 LRU 要在**每次读命中**时把条目移到队尾, 而读命中是最热的路径 (每次聚焦都查) —— 那等于
 * 把"零成本的读"变成"整表重写". 正缓存一旦写下就永久有效、后续全部走进程内热表短路, 所以
 * 写入顺序≈首次解析顺序, 淘汰最早解析的那批与 LRU 的差别在这里可以忽略.
 *
 * 淘汰按批 ([PERSISTED_BACKDROP_EVICT_BATCH]) 而不是每次挤掉一条, 免得到达上限之后每一次
 * 写入都要重算淘汰集。被淘汰的条目下次聚焦时重新走一次 TMDB, 只是慢一点, 不会出错。
 */
internal fun TmdbImageCache.withBackdropResult(
    subjectId: Int,
    url: String?,
    hadHints: Boolean = true,
): TmdbImageCache {
    val urls = backdropUrls + (subjectId to (url ?: ""))
    // 拿到图就清掉时间戳, 免得这个 map 随收藏量无限增长
    val missAt = if (url != null) {
        backdropMissAt - subjectId
    } else {
        backdropMissAt + (subjectId to currentTimeMillis())
    }
    // 只有"没带 hints 又没拿到图"才留记号; 带着 hints 查过一次之后就摘掉, 不再重查
    val noHints = if (url == null && !hadHints) {
        backdropMissWithoutHints + subjectId
    } else {
        backdropMissWithoutHints - subjectId
    }
    if (urls.size <= PERSISTED_BACKDROP_MAX) {
        return copy(backdropUrls = urls, backdropMissAt = missAt, backdropMissWithoutHints = noHints)
    }
    // Map.plus 返回 LinkedHashMap, 反序列化出来的也是 —— keys 的迭代顺序就是写入顺序
    val dropped = urls.keys.take(urls.size - PERSISTED_BACKDROP_MAX + PERSISTED_BACKDROP_EVICT_BATCH).toSet()
    return copy(
        backdropUrls = urls - dropped,
        backdropMissAt = missAt - dropped,
        backdropMissWithoutHints = noHints - dropped,
    )
}

/**
 * 持久 backdrop 表的条目上限. 一条约 60 字节 (URL) —— 2000 条约 120KB, 是每次新解析都要
 * 重新序列化的量, 再大就该换存储结构而不是抬上限了.
 */
private const val PERSISTED_BACKDROP_MAX = 2000

/** 到达上限后一次淘汰多少条 (均摊重算淘汰集的开销). */
private const val PERSISTED_BACKDROP_EVICT_BATCH = 200

@Serializable
data class TmdbImageCache(
    /** subjectId -> backdrop URL; 空串表示已确认 TMDB 无此条目图 (负缓存). */
    val backdropUrls: Map<Int, String> = emptyMap(),
    /** subjectId -> 分集缩略图 (按播出日期索引); 存在但为空 = 已确认无图 (负缓存). */
    val episodeStills: Map<Int, TmdbEpisodeStills> = emptyMap(),
    /** subjectId -> 全部横版剧照 URL (屏保轮播用); 空列表 = 已确认无图 (负缓存). 新字段有默认值, 不影响旧缓存. */
    val allBackdrops: Map<Int, List<String>> = emptyMap(),
    /**
     * subjectId -> [backdropUrls] 负缓存的写入时刻 (epoch millis), 决定它何时失效.
     * 新番的"没有 backdrop"通常只是"还没有" (TMDB 的图由社区在开播后陆续补), 见 [negativeCacheTtl].
     * 缺失 (旧缓存写下的负缓存) 视为已过期, 下次访问重取一次. 新字段有默认值, 不影响旧缓存.
     */
    val backdropMissAt: Map<Int, Long> = emptyMap(),
    /**
     * 负结果是**没带 [TmdbMatchHints] 时写下**的条目.
     *
     * TV 的时间表/搜索页只有 `subjectId + 名字`, 拿不到"是不是影院放映"与"上映年度",
     * 于是剧场版闸门与上映年度那两档在它们那里不生效. 老条目的负缓存又是**永久**的
     * (见 [negativeCacheTtl]), 列表页先解析失败的话, 之后带着完整信息进详情页也不会再查 ——
     * 那 9 条老剧场版 (エースをねらえ! / UP / 銀河鉄道999 映画版 / 彼女と彼女の猫 …) 就此钉死.
     * 记一笔, 让**第一次带着 hints 的调用**无视 TTL 重查一次; 重查后无论结果如何都会移出这张表,
     * 所以每个条目最多多查一次.
     */
    val backdropMissWithoutHints: Set<Int> = emptySet(),
    /**
     * 同 [backdropMissAt], 对应 [allBackdrops].
     * 必须与前者分开存: 共用一份时间戳会让"单图重取成功后清除时间戳"把全量剧照的负缓存
     * 变成永久有效, 屏保轮播从此不再重试.
     */
    val allBackdropsMissAt: Map<Int, Long> = emptyMap(),
    /** 匹配算法版本, 与 [CURRENT_VERSION] 不符时整个缓存作废 (旧算法结果可能有误). */
    val version: Int = 0,
) {
    companion object {
        val Empty = TmdbImageCache()

        /**
         * v1: 搜索加入动画过滤 + 季后缀降级, 之前缓存的结果可能命中真人版, 作废.
         * v2: 分集缩略图增加单季剧的按集号索引, 旧缓存缺该字段, 作废重取.
         * v3: 季标记改为截断式且支持 "4th season" 序数词, 此前搜不到的条目留有负缓存, 作废.
         * v4: OVA/OAD 条目还原母番名搜索, 此前这类条目全是负缓存, 作废.
         * v5: 分集缩略图索引增加时长 (runtime) 字段, 旧缓存缺该数据, 作废重取.
         * v6: 支持尾部裸数字季号 (如 "有頂天家族2"), 此前这类条目全是负缓存, 作废.
         * v7: 末尾非文字字符逐个回退 (如 "灼眼のシャナII"), 同上作废负缓存.
         * v8: 新增 Bangumi 关联条目回溯层 (主线故事/前传归根), 同上作废负缓存.
         * v9: 搜索结果加标题逐词校验, 此前模糊搜索可能缓存了错误条目的图 (如
         *     "うらおん!" 命中 "うらみちお兄さん"), 作废.
         * v10: 标题校验放宽为跨标题并集 + 别名 (alternative_titles) 兜底, 混写名
         *      (BanG Dream! ゆめ∞みた) 与仅别名命中 (スティール・ボール・ラン) 的
         *      条目此前是负缓存, 作废.
         * v11: "genre 缺失 + 日语"兜底档降到所有类型的动画档之后, 此前可能缓存了
         *      舞台剧/纪录片的图 (如 千と千尋の神隠し 的舞台剧纪录片), 作废.
         * v12: 标题完全一致的结果优先于"标题多出词"的结果, 此前正传名可能命中标题
         *      包含正传全名的外传 (如 DanMachi 命中 ソード・オラトリア 外伝, 分集
         *      日期全对不上导致选集卡片无图), 作废.
         * v13: 播出日期索引改为"日期 -> 当日多集列表" (双集连播首播时后一集不再覆盖
         *      前一集, 如 無職転生Ⅲ 第1话曾显示第2话的图), 结构变更, 作废.
         * v14: 每集整合为单条目 (图 + 时长 + 新增本地化简介, 语言跟随 APP 设置), 结构变更, 作废.
         * v15: 新增整部剧的本地化简介 (showOverview, Bangumi 日文简介整段替换用), 旧缓存缺该字段, 作废重取.
         * v16: 按 Bangumi 关系链判定正传/衍生后取舍 season 0 —— 正传跳过 S0 (同期衍生短篇
         *      与正片同日播出时占据同一日期键且排在正片前面, 如 Re:ゼロ 4th season 喪失編
         *      逐集错拿休憩時間 4th 的数据); 归并到本篇的衍生条目只索引 S0 (播出日差 ±1 天时
         *      精确日期会先命中正片, 如 休憩時間 3rd 错拿正传的数据), 作废重取.
         * v17: 正传不再整个跳过 S0, 改为殿后入索引 (同日对位仍正片优先) —— 只存在于 S0 的
         *      第0话特别篇找回图 (如 無職転生Ⅱ 第0集); 正传首候选的非精确标题命中降级为
         *      暂存 (進撃の巨人 The Final Season 曾命中拆分的 完結編(後編) 单集条目);
         *      新增 S0 原语言集名索引, 日期对不上时按集名精确一致兜底 (転スラ
         *      救われるラミリス 後編 两边日期差 8 天). 结构变更, 作废重取.
         * v18: 标题校验加 Unicode 兼容折叠 (康熙部首/全角/罗马数字), 此前 TMDB 上用这类字符
         *      录入原名的条目 (如 乙女ゲー世界はモブに厳しい世界です 的 ⼄⼥) 搜索命中却被校验
         *      判为不匹配, 留下负缓存, 作废.
         * v19: 三条匹配规则一起改, 此前的错配都存在**正缓存**里 (不是负缓存, 光等负缓存过期
         *      救不回来), 必须整表作废重取:
         *      - 同形字 (希腊/西里尔 → 拉丁) 折叠形加入搜索候选: 機動戦士ガンダムΖΖ (希腊 Ζ)
         *        直搜 0 结果后回落到关联根条目, 存的是初代高达 (1979) 的图;
         *      - 年份否决明显不可能的候选: 攻殻機動隊 1995 剧场版存着 2026 年新 TV 剧的图,
         *        Fate/stay night UNLIMITED BLADE WORKS 剧场版存着 2014 年 TV 版的图;
         *      - 季号查询命中电影且标题非精确时先扣住 (见 isTentativeSeasonHit):
         *        進撃の巨人 Season 3 存着合集剧场版《覚醒の咆哮》的图.
         * v20: 剧照链路三条规则一起改, 存的都是"空的正缓存", 同样必须整表作废重取:
         *      - 版本条目命中的剧一张剧照都没有时排除它再搜一轮 (機動戦士ガンダムSEED
         *        HDリマスター 直搜命中 TMDB 上的空壳 tv/332355, 48 集全无图);
         *      - 认不出季时按季名认领 (SEED DESTINY HDリマスター 复播日期离两季首播日都很远,
         *        50 集全无图; Fate/kaleid liner プリズマ☆イリヤ 差 7 天);
         *      - 集名索引去掉假名注音括号 (SEED 第 7/33/38 集两边注音方式不一致).
         * v21: 条目匹配整批改, 存的既有空的正缓存也有错的正缓存, 整表作废重取:
         *      - 剧场版优先匹配 movie 而不是同系列的电视版; 削字放开到「拉丁名 + 日文副标题」;
         *        动画过滤补一档 (genre 填了但漏了 16); 中文名与 infobox 上映年度兜底;
         *        没有横版背景图时回退竖版海报;
         *      - root 档的名字来源改了 (见 rootNameResolver): 此前拿到的是条目自己的中文名,
         *        うらおん! 因此搜不到任何东西 (空的正缓存), ハンター×ハンター・ザ・リアル 4-D
         *        撞上同名作品存了《魔晶猎人》的图 (错的正缓存).
         * v22: **别名档加了原名召回集佐证** (见 ownNameRecallIds), 存的是错的正缓存, 必须作废:
         *      bgm 的 infobox「别名」是自由文本, 写着别的作品时整条链路跟着错图 ——
         *      `Re:プチから始める異世界生活` (185837) 的别名字段是「Isekai Shokudou」(異世界食堂),
         *      hero 背景与整排选集卡都存成了那部的图 (2026-09-06 用户报的).
         */
        const val CURRENT_VERSION = 22
    }
}

/** TMDB 单个分集的展示数据: 缩略图 / 时长 / 简介, 均可缺失. */
@Serializable
data class TmdbEpisodeMedia(
    val stillUrl: String? = null,
    val runtimeMinutes: Int? = null,
    /** 分集简介 (按抓取语言本地化, 见 [TmdbEpisodeStills.language]; TMDB 无该语言翻译时为 null). */
    val overview: String? = null,
)

@Serializable
data class TmdbEpisodeStills(
    /**
     * 播出日期 `YYYY-MM-DD` -> 当日全部分集 (按集号升序).
     * 通常一天一集; 双集连播首播 (如 無職転生Ⅲ 第1+2话) 时一天多集,
     * 匹配侧按 Bangumi "当日第几集" 的序号对位.
     */
    val byAirDate: Map<String, List<TmdbEpisodeMedia>> = emptyMap(),
    /**
     * 集号 -> 分集数据, 取自**本条目对应的那一季** (见 `tmdbOwnSeasonNumber`);
     * 认不出是哪一季时只对单季剧非空 (多季时 Bangumi 连续编号与 TMDB 分季编号对不齐,
     * 按集号硬凑只会拿到错图). 供 Bangumi 分集无播出日期的条目兜底.
     */
    val byEpisodeNumber: Map<Int, TmdbEpisodeMedia> = emptyMap(),
    /** 抓取时用的 TMDB 语言码 (决定 overview 语言); 与当前 APP 语言不符时缓存不命中, 按新语言重取. */
    val language: String = "",
    /** 整部剧的本地化简介 ([language] 语言); TMDB 无该语言翻译或未匹配到剧时为 null. */
    val showOverview: String? = null,
    /**
     * "归一化原语言集名 -> 分集数据" 索引 (重名集已剔除), 覆盖 season 0 与**本条目对应的那一季**.
     *
     * 日期匹配落空时按集名精确一致兜底, 用 [findByEpisodeName] 查询. 两种落空都靠它:
     * 特别篇两边日期记录常有出入 (±1 天都够不着), 以及 Bangumi 把某一集的日期录错
     * (ハイスクールD×D BorN 第 12 集记成 2016-06-20).
     */
    val byEpisodeName: Map<String, TmdbEpisodeMedia> = emptyMap(),
    /**
     * 与 [byAirDate] **逐项对位**的出处 (季号 + 归一化首段原语言集名), 供匹配侧按集名投票认领
     * "本条目是哪一季", 见 `TmdbEpisodeMatcher` 里的 `preferredSeason`.
     *
     * **只收录"同一天挤了不止一个季"的日期** (TMDB 常把同期短篇挂在正传的 season 0 下且逐集同日,
     * 如 tv/283880 的 S0 与正片 12 集全同日): 其余日期唯一候选无从可挑, 存出处只是给缓存加体积.
     * 旧缓存没有这份数据 (默认空) —— 投票拿不到票就退回"当日第几集"的原口径.
     */
    val byAirDateOrigin: Map<String, List<TmdbEpisodeOrigin>> = emptyMap(),
) {
    /**
     * 按集名 (原名/中文名等, 依次尝试) 匹配; 名字归一化后比较, 见 [tmdbEpisodeNameKey].
     *
     * 先逐字, 再**后缀认领** —— 见 [findByEpisodeNameSuffix].
     */
    fun findByEpisodeName(vararg names: String?): TmdbEpisodeMedia? =
        names.firstNotNullOfOrNull { name ->
            name?.let { tmdbEpisodeNameKey(it) }?.takeIf { it.isNotEmpty() }?.let { byEpisodeName[it] }
        } ?: names.firstNotNullOfOrNull { name ->
            name?.let { tmdbEpisodeNameKey(it) }?.let(::findByEpisodeNameSuffix)
        }

    /**
     * **后缀认领**: TMDB 集名以本集名结尾且**全表唯一**时算命中.
     *
     * TMDB 把一个系列的全部衍生短篇混装进正传的 season 0, 于是**必须靠前缀区分**, 集名写成
     * 「作品名 + 本集名」; 而 Bangumi 那边分集只有本集名. 实测 tv/65942 (Re:Zero) 的 S0 有 81 集,
     * 混了四部衍生:
     *
     * ```
     * E12  Re:プチから始める異世界生活 ぷち1 再来の学校      <- bgm 185837 ep1 = 「ぷち①再来の学校」
     * E51  Re:ゼロから始める休憩時間 3rd season 眠れる鬼の夜話 <- bgm 516311 ep1 = 「眠れる鬼の夜話」
     * ```
     *
     * 这两条日期轴也救不了: 两边记的播出日差 3 天 (超出 ±1 容差) 或差 1 天但**正片当天也有一集**,
     * 于是要么整部无图, 要么整排拿到正片的图. 而集号轴被"有日期却对不上说明条目可疑"那道闸门关着
     * (见 `TmdbEpisodeMatcher`) —— 那条判据在这里不成立: 条目是对的, 只是两边日期记法不同.
     *
     * **三道闸门**, 每一道都为了别把"名字短又常见"的集认错:
     * - 归一化后**至少 4 个字符**: 与关系软边那条判据同源 (最长公共子串 >= 4, 见
     *   `project-tv-tmdb-backdrop` 第 26 节), 实测那条界线是硬的;
     * - **全表唯一** (`singleOrNull`): 两条以上就说明这个后缀分不出来, 宁可无图;
     * - 只认**后缀**不认任意位置的包含: 前缀是作品名, 本集名一定在末尾。
     */
    private fun findByEpisodeNameSuffix(key: String): TmdbEpisodeMedia? {
        if (key.length < MIN_SUFFIX_CLAIM_LENGTH) return null
        return byEpisodeName.entries
            .filter { it.key.length > key.length && it.key.endsWith(key) }
            .singleOrNull()
            ?.value
    }

    private companion object {
        /** 后缀认领的最短集名, 见 [findByEpisodeNameSuffix]. */
        const val MIN_SUFFIX_CLAIM_LENGTH = 4
    }

    /**
     * 是否拿到了任何一张剧照. 用来识别 TMDB 上的**空壳条目** —— 占位分集只有集号与时长,
     * 一张图都没有 (如 tv/332355), 见 `fetchEpisodeStills` 里的重试.
     */
    fun hasAnyStill(): Boolean =
        byAirDate.values.any { list -> list.any { it.stillUrl != null } } ||
            byEpisodeNumber.values.any { it.stillUrl != null }

    /**
     * 缓存是否已覆盖到播出日期 [date] (`YYYY-MM-DD`) 的分集.
     * 留 1 天余量 (两边日期常差一天, 匹配侧容差 ±1): 缓存最新日期 >= date-1 即视为覆盖.
     * 无任何按日期数据 (未匹配到剧/纯老番) 视为未覆盖, 由调用方的闸门限制重取频率.
     */
    fun coversAirDate(date: String): Boolean {
        val newestCached = byAirDate.keys.maxOrNull() ?: return false
        val wantedMinusSlack = runCatching {
            LocalDate.parse(date).minus(1, DateTimeUnit.DAY).toString()
        }.getOrElse { date }
        return newestCached >= wantedMinusSlack
    }

    /** 是否完全没有任何分集/剧集数据 (匹配失败或空剧). */
    fun isEmpty(): Boolean =
        byAirDate.isEmpty() && byEpisodeNumber.isEmpty() && byEpisodeName.isEmpty() && showOverview == null
}

/**
 * [TmdbEpisodeStills.byAirDate] 里一条分集的出处: 它来自哪一季, 以及那一集的**首段原语言集名**.
 *
 * **不把这两项塞进 [TmdbEpisodeMedia]**: 那个类被当 map 键用 (`byEpisodeNumber` 反查与集名索引
 * 去重都靠它的相等性实现"内容完全相同的占位集一律弃用"), 加了出处字段, 两个字段全空但出处不同的
 * 占位集就不再相等, 那条不变量会静默失效.
 */
@Serializable
data class TmdbEpisodeOrigin(
    val seasonNumber: Int = 0,
    /** 归一化的首段原语言集名 (见 [tmdbEpisodeSegmentKey]); 没取到原语言集名时为空串. */
    val nameKey: String = "",
)

/** TMDB 条目引用: 搜索命中的类型 (tv/movie) + id, 供 `/images` 等后续请求用. */
private class TmdbMediaRef(val type: String, val id: Int)

/** TMDB `/{type}/{id}/images` 响应 (只取 backdrops). */
@Serializable
private data class TmdbImagesResponse(
    val backdrops: List<TmdbImageFile> = emptyList(),
)

@Serializable
private data class TmdbImageFile(
    @SerialName("file_path") val filePath: String? = null,
    /** 图上印的字幕/标题语言; null 或空 = 无字幕. 见 [TmdbImageService.heroBackdropPath]. */
    @SerialName("iso_639_1") val language: String? = null,
)

/** 条目所属系列的回溯结果, 见 `resolveLineageOrNull`. */
private class BgmLineage(
    /** 根条目名 (通常是第一季); 与原名相同或没走到别的条目时为 null. */
    val rootName: String?,
    /**
     * 是否衍生/番外条目 (回溯链上出现过「主线故事」出边).
     * true = 衍生, false = 确认正传 (整链只有前传边), 建分集索引时可放心跳过 TMDB season 0 特别篇.
     *
     * **null = 未知**, 必须与 false 区分开: 走 Ani 关系索引那条路时拿不到「主线故事」出边
     * (见 `resolveLineageViaSeriesIndex`), 若把未知当成"确认正传", 衍生条目的分集就会因为 S0 被殿后
     * 而错拿正片数据 —— 正是各处判定注释里警告的那种错序. 三处判定都写成 `== false` /
     * `== true` 的显式比较, null 自然落到"两边都不成立", 即维持原顺序.
     */
    val isDerivative: Boolean?,
    /**
     * 这条结果是不是 Ani 关系索引给的. Ani 的 `seriesMainSubjectNames` 只列**系列内**条目名,
     * 拿不到「主线故事」出边指向的母条目, 所以它给的名字搜不到时还得再走一遍 Bangumi 逐跳
     * (见 `rootNameResolver`); Bangumi 那条已经是最后一手, 不必再回落.
     */
    val viaAni: Boolean = false,
)

/** Bangumi v0 `/subjects/{id}/subjects` 关联条目; relation 是中文关系名 ("前传"/"主线故事"...). */
@Serializable
private data class BgmRelatedSubject(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    val relation: String = "",
)

@Serializable
private data class TmdbSearchResponse(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
private data class TmdbSearchResult(
    val id: Int? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    /** 竖版海报. 只在整条链路都找不到 backdrop 时兜底用, 见 [TmdbImageService.searchPosterPath]. */
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("original_name") val originalName: String? = null, // tv
    @SerialName("original_title") val originalTitle: String? = null, // movie
    val name: String? = null, // tv 本地化标题
    val title: String? = null, // movie 本地化标题
    /** tv: 整部剧**第一季**的首播日 (不是本季的), 见 [yearPlausible]. */
    @SerialName("first_air_date") val firstAirDate: String? = null,
    /** movie: 上映日. */
    @SerialName("release_date") val releaseDate: String? = null,
)

/** 查询词分词: 兼容折叠后按非字母/数字切开, 小写. 用于 [TmdbSearchResult.matchesTokens]. */
private fun tokenizeForMatch(query: String): List<String> =
    foldCompatibility(query).lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }

/**
 * 关系里指向本传的**软边**: 番外/总集编/联动 PV 这类条目, Bangumi 上常常既没有「主线故事」
 * 也没有「前传」出边, 本传挂在「其他」或「相同世界观」上 (`苺ましまろプロローグ` → `苺ましまろ`,
 * `ちはやふる番外編` → `ちはやふる3`, `荒野のコトブキ飛行隊外伝` → `荒野のコトブキ飛行隊`).
 *
 * 但「其他」是个杂物筐, 也会挂**同档播出/同场上映的兄弟作** —— 直接采用会把 `King Kong` 顺着
 * `TOM of T.H.U.M.B.` 匹配到猫和老鼠, 把 `世紀末リーダー外伝たけし!` 顺着同场上映的
 * `ONE PIECE 倒せ!海賊ギャンザック` 匹配到海贼王剧场版, **两条都是从"命中自己"变成错图**.
 *
 * 判据是**与本条目名的最长公共子串**: 番外/外传/总集编的名字里一定含本传名, 兄弟作没有共同词干.
 * 实测这条界线很干净 —— 两条错的是 0 和 1 个字, 六条对的最短也有 5 个字, 中间没有任何取值,
 * 所以 [SOFT_EDGE_MIN_STEM] 取多少都一样 (实测 3/4/5 结果完全相同), 不是调参凑出来的阈值.
 */
private fun List<BgmRelatedSubject>.softSeriesEdge(
    originalName: String,
    seen: Set<Int>,
): BgmRelatedSubject? {
    return firstOrNull {
        it.relation in SOFT_SERIES_RELATIONS && it.name.isNotBlank() && it.id !in seen &&
                tmdbSoftEdgeUsable(it.name, originalName)
    }
}

/** [softSeriesEdge] 的判据: 这条软边指向的名字与本条目名有没有共同词干. */
internal fun tmdbSoftEdgeUsable(edgeName: String, originalName: String): Boolean =
    longestCommonSubstringLength(
        normalizeForMatch(edgeName),
        normalizeForMatch(originalName),
    ) >= SOFT_EDGE_MIN_STEM

/** 见 [softSeriesEdge]. 「续集」不在内 —— 前导片的"续集"才是本传, 但那条已由别处覆盖. */
private val SOFT_SERIES_RELATIONS = setOf("其他", "相同世界观")

/** 软边要求的最短公共词干, 见 [softSeriesEdge]. */
private const val SOFT_EDGE_MIN_STEM = 4

/** 最长公共子串长度; 只用来判两个标题有没有共同词干, 见 [softSeriesEdge]. */
private fun longestCommonSubstringLength(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var previous = IntArray(b.length + 1)
    var current = IntArray(b.length + 1)
    var best = 0
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else 0
            if (current[j] > best) best = current[j]
        }
        val swap = previous
        previous = current
        current = swap
    }
    return best
}

/**
 * 从 Ani 关系索引的 `seriesMainSubjectNames` 里挑系列主条目名 —— **条目自己的每一个名字都要排除**.
 *
 * 那个列表是系列内全部条目名 (原名与中文名各算一个), 只比原名的话「うらおん!」会把紧跟其后的
 * 自身中文名「K-On!:Ura-On!」当成主条目名, 而它在 TMDB 上什么都搜不到 —— 真正的母条目
 * 「けいおん！」只在 Bangumi 的「主线故事」出边上, 回落就此被这个假结果挡死.
 *
 * 归一化后比: 同系列条目名常只差标点 (`うらおん!` / `うらおん!!`).
 * 一个都不剩时返回 null, 调用方回落到 Bangumi 逐跳.
 */
internal fun tmdbSeriesRootName(names: List<String>, originalName: String, nameCn: String): String? {
    val own = listOf(originalName, nameCn)
        .filter { it.isNotBlank() }
        .mapTo(mutableSetOf()) { normalizeForMatch(it) }
    return names.firstOrNull { it.isNotBlank() && normalizeForMatch(it) !in own }
}

/**
 * **匹配到的那一季集数远少于条目集数** —— 配合"一张剧照都没有"用, 说明命中的多半是个占位壳.
 *
 * TMDB 上同一部番会有重复条目 (社区新建的与已有的还没合并), 而**日期逐字相同的那个未必是有
 * 数据的那个**: 实测「同じゼミの染谷さんがセクシー女優だった話。」有三条同名记录, 首播日与
 * Bangumi 一模一样的 tv/331481 只有 1 集 0 剧照, 真数据在差一天的 tv/325473 (8 集 7 张).
 *
 * **判据用集数而不是"有没有图"**: 冷门番匹配到的正确剧集本来就可能一张图都没有, 只看图会把
 * 对的换成错的. 集数对得上就认它"真没图"; 差一半以上才认为找错了剧.
 * 太短的条目 (< [STUB_MIN_SUBJECT_EPISODES] 集) 不判 —— 单集 OVA 与占位壳天然分不开.
 *
 * 换下一个也不会更差: 调用方留着第一个结果兜底 (见 `firstBuilt`).
 */
private fun looksLikeStubSeason(built: TmdbEpisodeStills, subjectEpisodeCount: Int?): Boolean {
    if (subjectEpisodeCount == null || subjectEpisodeCount < STUB_MIN_SUBJECT_EPISODES) return false
    return built.byEpisodeNumber.size * 2 <= subjectEpisodeCount
}

/** 见 [looksLikeStubSeason]: 短于这个集数的条目不做"集数对不上"的判定. */
private const val STUB_MIN_SUBJECT_EPISODES = 3

/** 标题归一化: 兼容折叠后只保留字母/数字 (假名/汉字也是字母), 小写 —— 忽略标点/空白差异. */
private fun normalizeForMatch(s: String): String =
    foldCompatibility(s).lowercase().filter { it.isLetterOrDigit() }

/**
 * 字符折叠 (KMP 无标准库 NFKC, 只覆盖标题里实际见过的类别):
 * 康熙部首 → 汉字, 全角字母/数字 → 半角, 罗马数字字符 → 拉丁字母, 同形字 → 拉丁字母.
 *
 * 前三类是 NFKC 的子集, 最后一类**不是** —— 见 [HOMOGLYPH_MAP], NFKC 不会把希腊 Ζ 折成拉丁 Z.
 *
 * TMDB 标题由社区录入, 偶有用"看着一样但码位不同"的字符写的 —— 实测
 * "乙女ゲー世界はモブに厳しい世界です" 的原名开头是康熙部首 ⼄(U+2F04)⼥(U+2F25) 而非
 * 汉字 乙(U+4E59)女(U+5973). 搜索本身能命中 (TMDB 内部做了归一), 但标题校验逐字比较,
 * 不折叠就会把命中的正确条目判为不匹配, 表现为详情页无 backdrop、选集卡片无缩略图,
 * 且结果被负缓存. 查询词与标题两侧都要折叠才能对上.
 */
internal fun foldCompatibility(s: String): String {
    if (s.none { it.compatibilityFoldOrNull() != null }) return s // 绝大多数标题无需折叠, 免去分配
    return buildString(s.length) {
        for (ch in s) append(ch.compatibilityFoldOrNull() ?: ch)
    }
}

private fun Char.compatibilityFoldOrNull(): String? = when (code) {
    in 0x2F00..0x2FD5 -> KANGXI_RADICALS[code - 0x2F00].toString()
    // 全角字母/数字与半角相差固定偏移 (全角标点会被 normalizeForMatch 直接滤掉, 无需折叠)
    in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> (code - 0xFEE0).toChar().toString()
    in 0x2160..0x2169 -> ROMAN_NUMERALS[code - 0x2160] // Ⅰ..Ⅹ
    in 0x2170..0x2179 -> ROMAN_NUMERALS[code - 0x2170] // ⅰ..ⅹ
    // 圈数字 ①..⑳ -> 1..20 (NFKC 等价, 同前三类).
    // normalizeForMatch 只留 isLetterOrDigit, 而 ① 是 No 类不是 Nd —— 不折叠的话它被**整个滤掉**,
    // 于是 Bangumi 的「ぷち①再来の学校」归一成「ぷち再来の学校」而 TMDB 的「ぷち1 再来の学校」
    // 留着那个 1, 两边永远对不上 (Re:プチから始める異世界生活 14 集全无图, 2026-09-06 用户报的).
    in 0x2460..0x2473 -> (code - 0x2460 + 1).toString()
    else -> HOMOGLYPH_MAP[this]?.toString()
}

/**
 * 同形字 (confusables) → 拉丁字母: 希腊/西里尔里那些与拉丁字母**看起来完全一样**的码位.
 *
 * 与上面三类不同, 这一类**不是 NFKC 等价** —— 就算接一个完整的 NFKC 实现, 希腊大写 Ζ (U+0396)
 * 也不会变成拉丁 Z, 所以只能单独列表.
 *
 * 起因 (实测): Bangumi 的「機動戦士ガンダムΖΖ」末两字是希腊 Ζ, 而 TMDB 录的是拉丁 ZZ ——
 * **TMDB 搜索对希腊写法直接 0 结果**, 于是回落到关联根条目, 详情页拿了初代高达 (1979) 的
 * 背景图与分集索引, 47 集的剧照/时长/单集简介一条都对不上. 同族的「機動戦士Ζガンダム」只是
 * 运气好: TMDB 恰好给它录了含希腊 Ζ 的别名, 搜索能命中.
 *
 * 所以折叠形要作为**额外的搜索候选**而不是替换原名 (见 [searchQueryCandidates]): 哪种写法
 * 搜得到取决于 TMDB 那边录了什么别名, 两种都得试.
 *
 * 只收"肉眼无法区分"的那些, 不含 Δ/Λ/Ω/Σ 这类没有拉丁对应的字母 —— 把它们折掉反而会毁了
 * 「マクロスΔ」这种标题里真的用希腊字母的条目.
 */
private val HOMOGLYPH_MAP: Map<Char, Char> = run {
    // 逐位对应, 长度不等就是表写错了 —— 静默错位会变成"个别条目莫名匹配不上"的暗坑
    check(HOMOGLYPH_SOURCE.length == HOMOGLYPH_LATIN.length) {
        "homoglyph table misaligned: ${HOMOGLYPH_SOURCE.length} vs ${HOMOGLYPH_LATIN.length}"
    }
    HOMOGLYPH_SOURCE.indices.associate { HOMOGLYPH_SOURCE[it] to HOMOGLYPH_LATIN[it] }
}

/** 同形字源字符, 与 [HOMOGLYPH_LATIN] 逐位对应. 用转义写死: 源码里肉眼分不出希腊 Ε 和拉丁 E. */
private const val HOMOGLYPH_SOURCE =
    "\u0391\u0392\u0395\u0396\u0397\u0399\u039A\u039C\u039D\u039F\u03A1\u03A4\u03A5\u03A7" + // 希腊大写 ΑΒΕΖΗΙΚΜΝΟΡΤΥΧ
        "\u03B1\u03B2\u03B5\u03B6\u03B7\u03B9\u03BA\u03BC\u03BD\u03BF\u03C1\u03C4\u03C5\u03C7" + // 希腊小写 αβεζηικμνορτυχ
        "\u0410\u0412\u0415\u041A\u041C\u041D\u041E\u0420\u0421\u0422\u0423\u0425" + // 西里尔大写 АВЕКМНОРСТУХ
        "\u0430\u0432\u0435\u043A\u043C\u043E\u0440\u0441\u0443\u0445" // 西里尔小写 авекморсух

/** @see HOMOGLYPH_SOURCE */
private const val HOMOGLYPH_LATIN =
    "ABEZHIKMNOPTYX" + // 希腊大写
        "abezhikmnoptyx" + // 希腊小写
        "ABEKMHOPCTYX" + // 西里尔大写
        "abekmopcyx" // 西里尔小写

/** 康熙部首 (U+2F00..U+2FD5) 按码位顺序对应的 CJK 统一汉字, 214 个. */
private const val KANGXI_RADICALS =
    "一丨丶丿乙亅二亠人儿入八冂冖冫几凵刀力勹匕匚匸十卜卩厂厶又口囗土士夂夊夕大女子宀" +
        "寸小尢尸屮山巛工己巾干幺广廴廾弋弓彐彡彳心戈戶手支攴文斗斤方无日曰月木欠止歹殳毋" +
        "比毛氏气水火爪父爻爿片牙牛犬玄玉瓜瓦甘生用田疋疒癶白皮皿目矛矢石示禸禾穴立竹米糸" +
        "缶网羊羽老而耒耳聿肉臣自至臼舌舛舟艮色艸虍虫血行衣襾見角言谷豆豕豸貝赤走足身車辛" +
        "辰辵邑酉釆里金長門阜隶隹雨靑非面革韋韭音頁風飛食首香馬骨高髟鬥鬯鬲鬼魚鳥鹵鹿麥麻" +
        "黃黍黑黹黽鼎鼓鼠鼻齊齒龍龜龠"

/** 罗马数字字符 (Ⅰ..Ⅹ / ⅰ..ⅹ) 的拉丁写法: 季号常见这种写法 (如 "無職転生Ⅱ" vs TMDB 的 "II"). */
private val ROMAN_NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

/**
 * 查询词的每个分词都出现在该条目的某个标题 (原名/本地化名/[extraTitles] 别名) 里才算匹配.
 *
 * 是"分词 → 标题集合"的并集校验, 不要求单一标题全含: 混写名只能这样匹配 —— 如
 * "BanG Dream! ゆめ∞みた", TMDB 原名是假名写法 (バンドリ！ ゆめ∞みた)、英文名是
 * 罗马字写法 (BanG Dream! YUME∞MITA), 每个标题各覆盖一半分词. 每个分词仍必须
 * 能在官方标题集里找到, 插字/换字的错误条目 (如 "君の魔名は...") 依然会被拒.
 */
private fun TmdbSearchResult.matchesTokens(tokens: List<String>, extraTitles: List<String> = emptyList()): Boolean {
    if (tokens.isEmpty()) return false
    val titles = (listOfNotNull(originalName, originalTitle, name, title) + extraTitles)
        .map(::normalizeForMatch)
    return tokens.all { token -> titles.any { it.contains(token) } }
}

/**
 * 季号标记 (CJK 写法): 第2クール / 第3期 / 第2部 / 第2シーズン / 第2季.
 *
 * 与 [SEASON_MARKER_LATIN] 一起有两个用途 —— 削字 (从标记处截断到串尾) 与
 * [isTentativeSeasonHit] 判"这个查询串带季号". 同源, 改一处两边同时跟着变.
 */
private const val SEASON_MARKER_CJK = """第\s*\d+\s*(?:クール|期|部|シーズン|季)"""

/**
 * 季号标记 (拉丁写法): Season 2 / Part 2 / Cour 2 / 4th Season / 2ndシーズン.
 *
 * 最后那种序数词 + 片假名的混写要单独列一支: 「Fate/stay night [Unlimited Blade Works]
 * 2ndシーズン」此前一个削字候选都生成不出来, 直接落到关联回溯, 归并到 2006 年那部**完全不同的
 * 改编** (tv/37858) 而不是它自己的 tv/61415 (第2季 2015-04-05, 与条目开播日只差一天).
 *
 * 两个都踩过的坑, 别改回去:
 *  - **新分支必须加在 `(?:…)` 组里面**: 这个常量会被拼上 `.*$` 用 (见 [tmdbStripSeasonSuffix]),
 *    加成顶层的第三个分支会让 `.*$` 只绑到最后一支上 —— 于是 "Inside Job Season 1 Part.1"
 *    从削成 "Inside Job" 退化成削成 "Inside Job Part.1", 整条无图.
 *  - **`\b` 只能贴在拉丁/数字那两支后面, 片假名那支不能带**: JVM 的 `\b` 默认按 ASCII 单词字符
 *    判定 (没开 UNICODE_CHARACTER_CLASS), 「ン」不算单词字符, 所以 "…2ndシーズン" 结尾根本没有
 *    单词边界, 带上 `\b` 这一支永远匹配不上. (Python 的 `\b` 是 Unicode 感知的, 拿脚本对照验证
 *    时不会暴露这个差异 —— 这条是靠单测抓出来的.)
 *
 * @see SEASON_MARKER_CJK
 */
private const val SEASON_MARKER_LATIN =
    """\s(?:(?:Part|Season|Cour)\s*\d+\b|\d+(?:st|nd|rd|th)\s+Season\b|\d+(?:st|nd|rd|th)\s*シーズン)"""

/** 名字里是否带季号标记 (两种写法之一), 见 [isTentativeSeasonHit]. */
private val SEASON_MARKER_REGEX =
    Regex(SEASON_MARKER_CJK + "|" + SEASON_MARKER_LATIN, RegexOption.IGNORE_CASE)

/**
 * 逐词去尾的停止条件: 削剩的部分**只是个形态词**吗 (劇場版 / 剧场版 / 総集編 / 序章 …).
 *
 * 拿一个光秃秃的形态词当查询串必然命中随便一部同类作品 —— 实测「劇場版」命中
 * tv/154779「龙珠剧场版」(genre 16 + 日语原声, 过得了动画过滤, 标题又含这三个字), 于是
 * 「劇場版 魔法科高校の劣等生 四葉継承編」从正确的母番退化成「劇場版 チェンソーマン レゼ篇」,
 * 「映画 ラブライブ！…」退化成「映画 聲の形」.
 *
 * **必须同时收简体写法**: 关联回溯给的"根条目名"常常就是条目自己的**中文名**
 * (`seriesMainSubjectNames[0]` 的老问题), 于是削出来的是简体「剧场版」—— 四个不相干的剧场版
 * 条目正是这么一起拿到「龙珠剧场版」的剧照的.
 *
 * 判据是"去掉全部形态词与数字后什么都不剩", 而不是逐个等值比较: 「劇場版3D」「総集編 前編」
 * 这类拼起来的也要拦住.
 */
internal fun String.isMediaFormWordOnly(): Boolean = MEDIA_FORM_WORD_REGEX.replace(this, "").isBlank()

/** @see isMediaFormWordOnly */
private val MEDIA_FORM_WORD_REGEX = Regex(
    "劇場版|剧场版|映画|电影|総集編|総集篇|总集篇|总集编|特別編|特別篇|特别篇|特别版" +
        "|前編|前篇|後編|后篇|中編|中篇|序章|完全版|完整版|新?編集版|剪辑版|OVA|OAD|スペシャル|3D|[0-9０-９]",
    RegexOption.IGNORE_CASE,
)

/**
 * 版本/媒介后缀: HD 重制、(新)编集版、导演剪辑版、完全版.
 *
 * 与 [OVA_KEYWORD_REGEX] 同一个思路 —— 都是"把条目名还原成 TMDB 认得的作品名".
 * 区别在于 OVA 关键字标记的是**内容形态** (母番的特别篇), 这里标记的是**同一部作品的
 * 另一个版本** (重制/重剪), 数据同样落在原作品的条目上.
 */
private val VERSION_SUFFIX_REGEX = Regex(
    """\s*(?:HD)?\s*リマスター(?:版|・エディション)?|\s*新?編集版|\s*ディレクターズカット版?|\s*完全版""",
)

/**
 * 剥掉版本/媒介后缀, 得到"原作品名". 抽成独立函数是为了能直接测: 这类后缀恰好躲过所有削字
 * 规则 (尾词含片假名 → 逐词不去; 末字是假名 → 逐字符立刻停), 一旦漏收就是整条无匹配.
 */
internal fun tmdbStripVersionSuffix(name: String): String =
    name.replace(VERSION_SUFFIX_REGEX, " ").replace(Regex("""\s+"""), " ").trim()

/**
 * 从季号标记处截断到串尾 (并去掉尾部空白) —— 削字候选里的"去季号"那一步.
 *
 * 末尾 [String.trim] 是因为 [SEASON_MARKER_CJK] 不含前导 `\s` (季号可能直接跟在汉字后面),
 * 削完会留下一个尾部空格: "…本気だす～ 第2クール" → "…本気だす～ ". 调用方的 `addCandidate`
 * 本来也会规整空白, 这里一并做掉只是让这个函数自己的契约干净.
 *
 * 抽成独立函数是为了能直接测: 这里的拼接 (`标记 + ".*$"`) 对常量的分组方式敏感,
 * 分组写错的症状是"某些名字只削掉了季号本身、后面的副标题还留着", 不看测试很难发现.
 *
 * **不带编号**的季/续篇标记 (次篇 / 続編 / 第一季 / 第1シリーズ …) 不在这里处理 —— 那类后缀
 * 花样列不完, 由候选层的"逐词去尾"统一兜住 (见 `searchQueryCandidates`).
 */
internal fun tmdbStripSeasonSuffix(name: String): String = name
    .replace(Regex(SEASON_MARKER_CJK + ".*$"), "")
    .replace(Regex(SEASON_MARKER_LATIN + ".*$", RegexOption.IGNORE_CASE), "")
    .trim()

/** [TmdbImageService.isTentativeSeasonHit] 的纯函数内核, 抽出来是为了能直接测三条件的交集. */
internal fun tmdbTentativeSeasonHit(query: String, isMovie: Boolean, exactTitle: Boolean): Boolean =
    isMovie && !exactTitle && SEASON_MARKER_REGEX.containsMatchIn(query)

/** `YYYY-MM-DD` 形式日期里的年份; null 或格式异常返回 null. */
private fun String?.yearOrNull(): Int? = this?.take(4)?.toIntOrNull()

/** 两个 `YYYY-MM-DD` 日期相差的天数 (绝对值); 任一侧解析不出来返回 null. */
private fun String.daysFrom(other: String): Int? {
    val a = runCatching { LocalDate.parse(this) }.getOrNull() ?: return null
    val b = runCatching { LocalDate.parse(other) }.getOrNull() ?: return null
    return a.daysUntil(b).absoluteValue
}

/** 首播 (tv) / 上映 (movie) 年份; 字段缺失或格式异常返回 null. */
private fun TmdbSearchResult.releaseYearOrNull(): Int? =
    (firstAirDate ?: releaseDate)?.take(4)?.toIntOrNull()

/**
 * 年份上是否**可能**是这个条目 —— 只用来否决明显不可能的候选; 任一侧年份未知就放行.
 *
 * 两侧判据必须不同, 这是这条规则能不能用的关键:
 *  - `movie`: 剧场版就是一个上映日, 两边直接比, 容差 ±[YEAR_TOLERANCE] 年.
 *  - `tv`: **只查下界**. TMDB 把续季并进同一个剧条目, `first_air_date` 是**第一季**的日期,
 *    所以"条目比剧首播晚很多"完全正常 (進撃の巨人 Season 3 归属 2013 年的 tv/1429、
 *    有頂天家族2 归属 2013 年的 tv/61338). 反过来"条目年份早于剧首播"才不可能 —— 那说明
 *    命中的是**后来的作品** (攻殻機動隊 1995 剧场版命中 2026 年的新 TV 剧).
 *
 * 别把 tv 也改成对称的 ±1: 对 51 个条目的对照实测中, 对称判据误杀三例 ——
 * 進撃の巨人 The Final Season 退化成合集电影、灼眼のシャナII 退化成剧场版、有頂天家族2 直接无图.
 * 同理容差不能收成 0: 两边跨年记录常差一年 (デート・ア・バレット 前編 Bangumi 2021 / TMDB 2020).
 */
private fun TmdbSearchResult.yearPlausible(type: String, subjectYear: Int?): Boolean =
    tmdbYearPlausible(releaseYearOrNull(), subjectYear, isMovie = type == "movie")

/**
 * 条目侧参与 TMDB 匹配的附加信息. 都是可选的 —— 拿不到时退化为原行为.
 *
 * @param nameCn 条目中文名, 作为**补充候选**用 (排在日文原名与削字候选之后, 见 [TmdbImageService.searchLayered]).
 *   注意这不违背"搜索必须传日文原名"那条: 中文名只是最后的补位, 不是主候选.
 * @param screeningYear infobox 「上映年度」里最早的年份. 与条目开播年**不同**时说明
 *   `airDate` 记的是发售日 (剧场上映的 OVA 常见), 年份判据改用它 —— 实测「彼女と彼女の猫」
 *   发售 2002 / 上映 2000 而 TMDB 记首映 1999, 按发售年算会被 movie 的 `|Δ|<=1` 误伤.
 *   **只取最早那个**: 老片的 infobox 会把 4K 重映年也列上 (攻殻機動隊 是 `[1995, 2025]`),
 *   全盘接受会让 2026 年的新片也过判据.
 * @param theatrical 是否**只在影院放映** (infobox 有上映日期而无「放送开始」). 为 true 时
 *   先搜 movie —— TMDB 上剧场版是独立的 movie 条目, 而 tv 搜索几乎总能撞上同系列的某部剧.
 */
data class TmdbMatchHints(
    val nameCn: String = "",
    val screeningYear: Int? = null,
    val theatrical: Boolean = false,
    /**
     * 条目自己的开播/上映年, **年份判据的最后一手**.
     *
     * 调用方传的 `activeAsOfDate` 是"最新已播集的日期", 搜索结果这类列表里根本没有分集数据,
     * 只能传 null —— 于是年份判据整个失效, 而失效的代价是实打实的错图: `攻殻機動隊` (1995
     * 剧场版) 的 tv 搜索**首位**就是同系列 2026 年的新剧, 没有年份否决就直接赢下来, 还会被
     * 写进正缓存污染其它页面. 条目年份是列表项一定拿得到的 (卡片本来就要显示), 补上它就够.
     */
    val airYear: Int? = null,
    /**
     * 条目的别名 (服务端 aliases + bgm infobox「别名」, 见 SubjectInfo.aliases). 别名是完整
     * 名字的变体, 与中文名同一档: **逐字命中即定案, 优先于削字暂定** —— 「GUNDAM EVOLVE」的
     * 原名/削字候选全都撞错条目, 而别名「机动战士高达 进化」与 TMDB 的 zh 名逐字相同.
     * 别名质量参差没有关系, 这一档带逐字闸门, 对不上字的别名只是白发一次搜索.
     * (时间表页这类拿不到别名的列表调用方退化为原行为 —— 那里只有当季新番, 原名档就够.)
     */
    val aliases: List<String> = emptyList(),
) {
    /** 中文名 + 别名: "完整名字的精确变体"档的查询序列, 每一个都要求逐字命中. */
    val exactNameVariants: List<String>
        get() = buildList {
            if (nameCn.isNotBlank()) add(nameCn)
            for (alias in aliases) {
                if (alias.isNotBlank() && alias !in this) add(alias)
            }
        }

    companion object {
        val Empty = TmdbMatchHints()
    }
}

/**
 * 年份判据用的基准年, 按可信度取: [screeningYear] > 最新已播集年 > 条目开播年 ([subjectAirYear]).
 *
 * [screeningYear] 只在"条目的 `airDate` 年份不在 infobox 上映年度里"时才有值 (见
 * `AniInfobox.screeningYearOrNull`), 也就是说它非空就等于"`airDate` 记的是发售日, 别拿它判年份".
 *
 * [subjectAirYear] 垫底, 见 [TmdbMatchHints.airYear] —— 缺了它整条年份判据会在列表页失效.
 */
internal fun tmdbSubjectYear(
    activeYear: Int?,
    screeningYear: Int?,
    subjectAirYear: Int? = null,
): Int? = screeningYear ?: activeYear ?: subjectAirYear

/** [yearPlausible] 的纯函数内核, 抽出来是为了能直接测两侧判据的不对称性. */
internal fun tmdbYearPlausible(candidateYear: Int?, subjectYear: Int?, isMovie: Boolean): Boolean {
    if (subjectYear == null || candidateYear == null) return true
    return if (isMovie) {
        (candidateYear - subjectYear).absoluteValue <= YEAR_TOLERANCE
    } else {
        subjectYear >= candidateYear - YEAR_TOLERANCE
    }
}

/** @see yearPlausible */
private const val YEAR_TOLERANCE = 1

/**
 * **精确变体档 (中文名/别名/合集) 的年份判据, 对称容差**: 这一档命中的语义是"这就是条目自己
 * 在 TMDB 的名字", 年份理应接近 —— 与 tv 档"条目晚于首播多少都行"的宽松方向不同 (那是给
 * 续季/衍生挂多季母条目用的场景, 变体档不承担, 挂母剧走 root 档).
 * 不对称的代价实测过: 「GTO」是 THE ORIGIN 的缩写别名, 经 alternative_titles 逐字命中了
 * 1999 年的麻辣教师动画 (Δ16 年), tv 宽松方向拦不住. 容差 3 是给 TMDB 数据不全留的:
 * 「GUNDAM EVOLVE」条目 2001 年开始, TMDB 那条从 2003 年的 EVOLVE../9 才记起 (Δ2).
 */
internal fun tmdbExactVariantYearPlausible(candidateYear: Int?, subjectYear: Int?): Boolean {
    if (subjectYear == null || candidateYear == null) return true
    return (candidateYear - subjectYear).absoluteValue <= EXACT_VARIANT_YEAR_TOLERANCE
}

private const val EXACT_VARIANT_YEAR_TOLERANCE = 3

/**
 * 认领"本条目对应 TMDB 的哪一季" —— 按**季首播日与条目开播日相差 ≤[SEASON_MATCH_TOLERANCE_DAYS] 天**.
 *
 * 用途: Bangumi 分集**没有播出日期**的条目 (老番、以及不少续季条目) 只能按集号对位, 而按集号
 * 索引原先要求"TMDB 上只有一季正片", 多季剧就彻底没有出路. 实测「みなみけ おかわり」(bgm 890)
 * 13 集全无日期, 而母番 tv/56354 有 5 季 —— 于是剧照/简介一条都落不下来, 尽管 TMDB 的
 * season 2 正是这个条目且 13 集图全齐.
 *
 * **为什么用季首播日而不是季名**: TMDB 的季名会跟着 `language` 本地化, 实测 ja-JP 下正好是
 * 「みなみけ おかわり」、zh-CN 下是「南家三姐妹～再来一碗～」, 归一化后与 Bangumi 条目名相等 ——
 * 看着比日期更直接. 但季名认不出特别篇 (TMDB 的 S0 叫「特別編」/「Specials」, 而 Bangumi 那条
 * 叫「みなみけ べつばら」), 而季首播日把它也认出来了; 季名能认的场合季首播日全都能认. 另外季名
 * 要跟哪个语言的条目名比也是个麻烦 (服务层只拿到日文原名).
 *
 * 判据要求**唯一命中**, 含糊就放弃 (返回 null, 退回旧口径), 认错一季的代价是整季分集全拿错数据.
 * 正片优先于 S0: 特别篇常与当季正片同期首播, 两边都落在容差内时该取正片.
 */
internal fun tmdbOwnSeasonNumber(seasons: List<Pair<Int, String?>>, subjectAirDate: String?): Int? {
    val target = subjectAirDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val hits = seasons.mapNotNull { (number, airDate) ->
        val date = airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        number.takeIf { date.daysUntil(target).absoluteValue <= SEASON_MATCH_TOLERANCE_DAYS }
    }
    return hits.filter { it > 0 }.singleOrNull() ?: hits.singleOrNull()
}

/** 季首播日与条目开播日的容差: 两边记录常差一天 (深夜档跨日), 口径同分集匹配. */
private const val SEASON_MATCH_TOLERANCE_DAYS = 1

/**
 * 认领"本条目对应哪一季"的第二判据: **条目名 (剥掉版本后缀) 与季名归一化后逐字相同**.
 *
 * 只在 [tmdbOwnSeasonNumber] 认不出来时用 (见 `claimSeasonByName`), 且要求**唯一命中** ——
 * 认错一季的代价是整季分集全拿错数据. 逐字相同是很强的证据: TMDB 的多季剧就是按作品名给季
 * 命名的 (tv/20111 的 S1/S2 分别叫「機動戦士ガンダムSEED」「機動戦士ガンダムSEED DESTINY」,
 * tv/56354 的 S2 叫「みなみけ おかわり」), 而没有独立命名的季叫「シーズン1」, 不会撞上条目名.
 *
 * 剥版本后缀是为了让复播/重制条目对上原作那一季 (「…SEED DESTINY HDリマスター」→ S2);
 * 比较用的是条目名派生出的这**一个**串, 不用搜索那套削字候选 —— 削到「進撃の巨人」这种程度再去
 * 撞季名, 会把「進撃の巨人 The Final Season」认成第 1 季.
 *
 * @param seasons (季号, 季名) —— 季名须是该剧**原语言**的 (与 Bangumi 原名同语言).
 */
internal fun tmdbSeasonNumberByName(seasons: List<Pair<Int, String?>>, subjectName: String): Int? {
    val wanted = normalizeForMatch(tmdbStripVersionSuffix(subjectName)).takeIf { it.isNotEmpty() } ?: return null
    return seasons.mapNotNull { (number, name) ->
        number.takeIf { name != null && normalizeForMatch(name) == wanted }
    }.singleOrNull()
}

/**
 * 集名索引的键: 归一化 (见 [normalizeForMatch]) 之前先去掉**假名注音括号**.
 *
 * 两边都可能给汉字标读音, 标的位置还不一样 —— 实测 SEED 第 7 集 Bangumi 作「宇宙(そら)の傷跡」
 * 而 TMDB 作「宇宙の傷跡」, 第 33 集反过来 TMDB 作「舞い降りる剣（つるぎ）」而 Bangumi 没有,
 * 第 38 集两边都标但内容不同 (「暁の宇宙(そら)へ」vs「暁の宇宙へ（そらへ）」). 这三集的图原先
 * 都因为逐字比较不相等而落空.
 *
 * 只去**括号里全是假名**的那种: 注音必然是假名, 而「(前編)」「(TV)」这类括号是真内容, 去掉会让
 * 前後編 撞成同一个键 (撞键的条目会被索引侧整条丢弃, 反而更差).
 */
internal fun tmdbEpisodeNameKey(name: String): String =
    normalizeForMatch(RUBY_ANNOTATION_REGEX.replace(name, ""))

/**
 * 集名的**首段** key: 在 [tmdbEpisodeNameKey] 之上再按 `／`/`｜` 切段只取第一段.
 *
 * 只给"按集名投票认领季"用 (见 `TmdbEpisodeMatcher`), **不给 [TmdbEpisodeStills.byEpisodeName]
 * 那条逐集兜底用** —— 切段会让「A／その1」「A／その2」撞成同一个键, 而那条索引撞键即整条弃用,
 * 放进去只会让它变差.
 *
 * 为什么要切段: 正片与同期短篇合播时, 两边**谁把两段并进一个集名**并不一致, 逐字比较两头都落空.
 * 实测 tv/283880 第 1 集 TMDB 作「さわらないで小手指くん／ミニアニメ劇場　その１」而 Bangumi 只有
 * 前半; tv/271003 反过来, Bangumi 作「異世界で出会った女の子／ミニアニメ劇場 その1」而 TMDB 的
 * 正片季只有前半. 分隔符前面那段是正片, 所以取首段两种写法都能对上 —— 后者若不切段,
 * S0/正片两季都是 0 票, 整个投票判不出来.
 */
internal fun tmdbEpisodeSegmentKey(name: String): String =
    tmdbEpisodeNameKey(name.substringBefore('／').substringBefore('｜').substringBefore('/').substringBefore('|'))

/** 假名注音括号 (全角/半角括号 + 纯假名内容), 见 [tmdbEpisodeNameKey]. */
private val RUBY_ANNOTATION_REGEX = Regex("[(（][\\u3041-\\u309F\\u30A0-\\u30FF]+[)）]")

/**
 * 版本条目 (见 [tmdbStripVersionSuffix]) 允许试几部剧: 首选命中的剧一张剧照都没有 (TMDB 上的
 * 空壳条目) 时排除它再搜一轮, 见 `fetchEpisodeStills`. 只多给一次机会 —— 再往下就是越来越短的
 * 削字候选, 命中的剧与本条目的关系越来越弱.
 */
internal class NameQueries(
    /** 与条目名同义的写法 (原名 / 同形字折叠形): 必须先于一切削短过的候选. */
    val primary: List<String>,
    /** 削字规则派生的更短候选: [primary] 全落空后才轮到, 但仍先于关联回溯. */
    val derived: List<String>,
) {
    /** 两层顺序拼接. 根条目名自己的候选不分层, 按这个顺序全试. */
    val all: List<String> get() = primary + derived
}

internal fun tmdbSearchQueryCandidates(name: String): NameQueries {
    val primary = mutableListOf<String>()
    val derived = mutableListOf<String>()

    fun normalized(candidate: String) = candidate.replace(Regex("""\s+"""), " ").trim()

    /** 与条目名同义的候选, 属第一层 (必须先于削字候选). */
    fun addPrimary(candidate: String) {
        val n = normalized(candidate)
        if (n.isNotBlank() && n !in primary) primary += n
    }

    /** 削字规则派生的候选, 属第二层 (排在关联回溯之前). */
    fun addCandidate(candidate: String) {
        val n = normalized(candidate)
        if (n.isNotBlank() && n !in primary && n !in derived) derived += n
    }
    addPrimary(name)
    // 同形字折叠后的写法与原名同义, 所以**同属第一层**: 哪种写法搜得到取决于 TMDB 那边录了
    // 什么别名 —— 「機動戦士ガンダムΖΖ」(希腊 Ζ) 直搜 0 结果、折叠成拉丁 ZZ 才命中, 而
    // 「機動戦士Ζガンダム」反过来靠希腊写法的别名命中. 两种都试, 原名在前.
    // 放进第一层而不是追加到末尾是关键 —— 它必须先于**削字候选**: ZZ 的削字候选是
    // 「機動戦士ガンダムΖ」和「機動戦士ガンダム」(末尾希腊 Ζ 不是假名/汉字, 被逐字回退剥掉),
    // 折叠形排在它们后面的话, 先命中的会是 Z 高达或初代高达.
    addPrimary(foldCompatibility(name))

    // 「劇場版 」**前缀**剥除也是同义写法: TMDB 的剧场版原题多数不带这个前缀
    // (「劇場版 BanG Dream! It's MyGO!!!!! 後編 うたう、…」在 TMDB 是
    // 「BanG Dream! It's MyGO!!!!! 後編：うたう、…」), 带着搜连逐词校验都过不了,
    // 削字则一路撞到前篇被日期闸门拦掉, 全链空手. 只剥前缀不动中/后缀的「劇場版」
    // (「うたの☆プリンスさまっ♪ … 劇場版」不是这个形状); 懒惰短路保证
    // 「劇場版 CLANNAD」这类带前缀就能命中的条目不会发这个候选.
    val theatricalPrefixStripped = name.removePrefix("劇場版").trim()
    if (theatricalPrefixStripped != name && theatricalPrefixStripped.isNotBlank()) {
        addPrimary(theatricalPrefixStripped)
        addPrimary(foldCompatibility(theatricalPrefixStripped))
    }

    val ovaMode = OVA_KEYWORD_REGEX.containsMatchIn(name)
    val base = if (ovaMode) name.replace(OVA_KEYWORD_REGEX, " ") else name
    addCandidate(base)

    // 版本/媒介后缀 (HD 重制、新编集版、导演剪辑版): 这是"同一部作品的另一个版本",
    // TMDB 多数不给它独立条目, 数据都在原作品那条上. 「機動戦士ガンダムSEED DESTINY
    // HDリマスター」整串直搜是 0 结果, 剥掉后命中 tv/20111 (它的 S2 正是 DESTINY, 50 集
    // 逐集对得上).
    //
    // 下面的"逐词去尾"如今也能去掉这类尾词, 这一步仍然单列, 因为它 **① 不依赖空格**
    // (后缀直接贴在名字上时逐词去尾无从下手), **② 排在前面、只削这一处**, 比逐词那几步精确;
    // 而且 [tmdbStripVersionSuffix] 本身还被另外两处用到 (按季名认领条目对应的季、判断
    // "这是不是个版本条目"), 不是纯为候选而存在.
    //
    // 少数确实有独立条目的 (機動戦士ガンダムSEED HDリマスター = tv/332355) 不受影响:
    // 原名直搜在第一层就命中了, 轮不到这里.
    val versionStripped = tmdbStripVersionSuffix(base)
    addCandidate(versionStripped)

    val suffixStripped = tmdbStripSeasonSuffix(versionStripped)
    addCandidate(suffixStripped)
    val romanStripped = suffixStripped.replace(Regex("""[ⅡⅢⅣⅤⅥⅦⅧⅨⅩ]"""), "")
    addCandidate(romanStripped)
    // 裸数字季号: 续季常直接在名字尾部跟数字 (如 "有頂天家族2" — TMDB 只有 "有頂天家族" 一个剧条目).
    // 只认 1-2 位, 3 位以上视为名字本体 (如 "モブサイコ100"); 且作为末位候选,
    // 仅在前面候选全部落空时才轮到, 名字本体恰好以数字结尾的条目会先被原名命中.
    // (下面的逐字符回退不适用纯拉丁名, 这条规则保留给它们, 如 "STEINS;GATE 0".)
    addCandidate(romanStripped.replace(Regex("""\s*[0-9０-９]{1,2}$"""), ""))

    // 逐词去尾: 从**最后一个空格**处一次去掉一个尾词, 由长到短依次作候选. 三种用途:
    //  - OVA 条目: 还原母番名 (如 "進撃の巨人 悔いなき選択" → "進撃の巨人");
    //  - 剥掉**拉丁副标题** —— 下面那条逐字符回退对长拉丁尾巴无效. 实测
    //    「劇場版 Fate/stay night [Heaven's Feel] I.presage flower」: 尾巴 17 个字符,
    //    逐字符剥满 12 步只到 "…I.p", 每一步在 TMDB 上都是 0 结果; 而逐词去掉一到两个
    //    就命中 movie/283984 (TMDB 那边写作「」+ 弯引号 + 罗马数字 Ⅰ, 整串直搜 0 结果);
    //  - 剥掉**日文/中文的尾词** —— 这类后缀花样太多, 逐条列关键字列不完: 次篇 / 続編 /
    //    特別版 / 真生版 / 70mm版 / 第1シリーズ / 第一季 (中文数字, 季号正则的 `\d` 不认)
    //    /総集編 / 序章 / 第一章…, 而它们全都是"母番名 + 一个尾词"的形状. 实测 350 条语料里
    //    这一步救回 21 个条目的背景图与 31 集剧照 (如「ベルセルク 次篇」→ 2016 年那部的 S2、
    //    「機動戦士ガンダムSEED FREEDOM 特別版」→ 自己那部电影、「凡人修仙传 星海飞驰篇 序章」
    //    → 母番), 且一处换图/丢图都没有.
    //
    // 守卫只剩一个 (只对非 OVA): 剩下的部分不能只是个**形态词** (见 [String.isMediaFormWordOnly]).
    //
    // **原先还有一条"剩下的部分仍须含日文/中文"**, 2026-08-25 去掉了 —— 它拦的不只是
    // "BanG Dream! It's MyGO!!!!!" 这一种, 而是**「拉丁本体 + 日文副标题」整整一类**:
    // 「CLANNAD もうひとつの世界 智代編」削不出 `CLANNAD`, 于是背景图与剧照全空.
    // 去掉它的代价由调用侧的一条规则兜住: **削出来的候选若不含日文/中文, 它的命中必须
    // 逐字同名, 否则跳过继续下一个候选** (见 [searchLayered])。实测 284 条触发面上,
    // 裸放开是 18 对 9 错 (ANGEL VOICE→Angel Beats! 这类), 加上逐字同名后是 12 对 1 错;
    // 而"跳过而不是整条放弃"是必需的 —— 杏編正是靠它从 `CLANNAD 〜AFTER` 落到 `CLANNAD`.
    //
    // 排在逐字符回退**之前**: 两者很少同时适用 (逐字符主要服务无空格的名字), 而同时适用时
    // 逐词那几步远比逐字符的十几步有意义, 早一步命中就少十几个白发的请求.
    var truncated = romanStripped.replace(Regex("""\s+"""), " ").trim()
    var depth = 0
    while (depth < WORD_TRUNCATE_MAX_DEPTH && truncated.contains(' ')) {
        truncated = truncated.substringBeforeLast(' ').trim()
        if (truncated.isEmpty()) break
        if (!ovaMode && truncated.isMediaFormWordOnly()) break
        addCandidate(truncated)
        depth++
    }

    // 末尾非文字字符逐个回退: 尾部季号/副标题形态繁多 (ASCII 罗马数字 "灼眼のシャナII"、
    // "R2"、"III -Final-" 等), 枚举不完; 从末尾逐字符去掉非日文/中文的字符, 每一步都
    // 作为候选 (先长后短, 更具体的先试). 要求剩余部分仍含日文/中文字符, 避免把
    // "BLEACH" 这类纯拉丁名逐字拆碎; 限最多回退 12 字符, 防病态长尾.
    var walked = romanStripped
    var steps = 0
    while (steps < 12) {
        val trimmed = walked.trimEnd()
        val last = trimmed.lastOrNull() ?: break
        if (last.isCjkOrKana()) break
        walked = trimmed.dropLast(1)
        steps++
        if (walked.none { it.isCjkOrKana() }) break
        addCandidate(walked)
    }
    return NameQueries(primary, derived)
}

private const val WORD_TRUNCATE_MAX_DEPTH = 3

private val OVA_KEYWORD_REGEX =
    Regex("""(?i)\b(?:OVA|OAD)S?\b|特別[編篇]|特别篇|スペシャル""")

private const val STUB_TV_ATTEMPTS = 2

/**
 * 搜索记忆化表的容量上限, 见 `TmdbImageService.searchMemo`.
 *
 * 256: 一个条目的候选序列最坏能塞十几条 (逐字符回退 × tv/movie 两种), 所以这个数大约是"十几个
 * 条目的量" —— 覆盖"在一个系列里前后翻"这个主要场景足够, 而单条只是一页搜索结果解析后的
 * 两个短列表, 内存可以忽略.
 */
private const val SEARCH_MEMO_MAX_ENTRIES = 256

/**
 * 精确同名并列时的排序键: 候选年份与条目年份的距离; 任一侧年份未知排到最后.
 *
 * 只用于打破"两个候选标题逐字相同"的平手 (同一作品的重制/复播), 不参与其他排序 ——
 * 见 [searchAnime] 里的说明.
 */
internal fun tmdbYearProximity(candidateYear: Int?, subjectYear: Int?): Int =
    if (candidateYear == null || subjectYear == null) Int.MAX_VALUE
    else (candidateYear - subjectYear).absoluteValue

/** 是否有标题与查询完全一致 (归一化后). 用于在多个通过校验的结果中把正主排到外传/衍生作之前. */
/**
 * 结果的任一标题, 归一化后是否落在**本条目的候选串**集合里.
 *
 * 剧场版闸门用它挡住"同系列的另一部": `怪獣8号 第1期総集編` 与「保科の休日」**同日上映**,
 * 日期判据分不开, 只有名字分得开. 而 `ジョジョ…ファントムブラッド` (TMDB 写作
 * `ファントム ブラッド`, 中间多个空格) 与 `銀河鉄道999 映画版` (削掉形态词后的候选是
 * `銀河鉄道999`) 都能通过 —— 所以比的是**候选串集合**而不是单个原名.
 */
private fun TmdbSearchResult.matchesAnyCandidate(candidateNames: Set<String>): Boolean =
    candidateNames.isNotEmpty() &&
            listOfNotNull(originalName, originalTitle, name, title)
                .any { normalizeForMatch(it) in candidateNames }

private fun TmdbSearchResult.hasExactTitle(queryNormalized: String): Boolean =
    listOfNotNull(originalName, originalTitle, name, title)
        .any { normalizeForMatch(it) == queryNormalized }

/**
 * 动画搜索结果分两档: [primary] 确认为动画 (genre 16, 标题/别名校验通过);
 * [fallback] genre 数据缺失但日语原声的弱信号兜底 —— 调用方须把它排在所有类型的
 * [primary] 之后 (见 `searchBackdropPath`), 否则舞台剧/纪录片会抢在真正的动画前面.
 */
private class TmdbAnimeSearchResults(
    val primary: List<TmdbSearchResult>,
    val fallback: List<TmdbSearchResult>,
)

/** TMDB `/{type}/{id}/alternative_titles` 响应: tv 用 `results` 字段, movie 用 `titles`. */
@Serializable
private data class TmdbAlternativeTitles(
    val results: List<TmdbAltTitle> = emptyList(),
    val titles: List<TmdbAltTitle> = emptyList(),
)

@Serializable
private data class TmdbAltTitle(val title: String? = null)

@Serializable
private data class TmdbTvDetail(
    val seasons: List<TmdbSeasonRef> = emptyList(),
    /** 整部剧的简介 (按请求的 language 本地化; 无该语言翻译时 TMDB 返回空串). */
    val overview: String? = null,
    /** 剧的原语言 (如 "ja"); S0 集名索引按原语言取名, 与 Bangumi 原名可逐字比较. */
    @SerialName("original_language") val originalLanguage: String? = null,
)

/** TMDB `/movie/{id}`: 剧场版条目按"整部就是一集"取用, 见 [TmdbImageService.fetchMovieAsSingleEpisode]. */
@Serializable
private data class TmdbCollectionSearchResult(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
private data class TmdbCollectionSearchResponse(
    val results: List<TmdbCollectionSearchResult> = emptyList(),
)

@Serializable
private data class TmdbCollectionPart(
    val title: String? = null,
    /** 原语言标题 (语言无关字段): parts 按集标题认领时必须用它与 bgm 原语言集名比. */
    @SerialName("original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
private data class TmdbCollectionDetail(
    val name: String? = null,
    val overview: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val parts: List<TmdbCollectionPart> = emptyList(),
)

/** 全半角括号段 (注音/补注), parts 按集标题认领前两侧都剥掉. */
private val PAREN_SEGMENT_REGEX = Regex("""（[^）]*）|\([^)]*\)""")

/** TMDB 合集名的惯例后缀 (「機動戦士ガンダム THE ORIGIN（系列）」), 逐字比较前剥掉. */
private val COLLECTION_SUFFIX_REGEX =
    Regex("""[（(]?\s*(系列|合集|シリーズ|Collection|Series)\s*[）)]?\s*${'$'}""", RegexOption.IGNORE_CASE)

@Serializable
private data class TmdbMovieDetail(
    val overview: String? = null,
    /** 片长 (分钟). */
    val runtime: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
private data class TmdbSeasonRef(
    @SerialName("season_number") val seasonNumber: Int = 0,
    /** 该季首播日 (`YYYY-MM-DD`); 用于认领"本条目是哪一季", 见 [tmdbOwnSeasonNumber]. */
    @SerialName("air_date") val airDate: String? = null,
    /**
     * 季名, **跟着请求的 `language` 本地化**; 按季名认领条目对应的季时必须按剧的原语言取,
     * 见 [tmdbSeasonNumberByName].
     */
    val name: String? = null,
)

@Serializable
private data class TmdbSeasonDetail(
    val episodes: List<TmdbEpisodeRef> = emptyList(),
)

@Serializable
private data class TmdbEpisodeRef(
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    /** 集名 (按请求的 language 本地化); 仅 S0 原语言二次请求时用于建集名索引. */
    val name: String? = null,
)
