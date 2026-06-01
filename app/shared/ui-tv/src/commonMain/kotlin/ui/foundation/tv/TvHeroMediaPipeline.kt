/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.github.panpf.sketch.LocalPlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.ui.foundation.LocalSketch

/**
 * 从当前聚焦位置出发的预取目标. **方向无关**: 行区 (探索页) 与网格 (追番/搜索) 的可达方向
 * 不同 —— 行区只有顺方向+下, 网格中间卡上下左右四个方向都可达且都可能是冷的 (蛇形浏览、
 * 切 tab 锚定落点都会让左/上没被聚焦过), 所以由页面按自己的几何来填.
 *
 * 分成两档是因为两种预取的代价差一个数量级: URL 只有几 KB, 多取一个几乎不要钱; 图片是
 * 150~350KB 且要挤占 300MB 的共享磁盘缓存, 只值得押在**单步真能走到**的方向上.
 */
/**
 * 一个预取目标. 带**自己的**剧照偏好, 不能抄当前聚焦卡的.
 *
 * 追番页的网格里两种卡是混着的: "在看"的条目 hero 显示单集剧照, 其余显示整部 backdrop.
 * 抄聚焦卡的旗标会让预热押错档 —— 热好的是它不显示的那张, 真走过去时该显示的那张还得现下,
 * 那 3.7MB 的预解码也押空 (2026-08-14 评审发现: 解析链那侧按邻居自己的状态取, 观察 URL
 * 这侧却抄了 spec, 两边自相矛盾).
 */
@Immutable
data class TvHeroNeighbor(
    val subjectId: Int,
    /** 该条目自己是否该用"下一集"单集剧照当 hero 背景, 见 [TvHeroMediaSpec.preferNextEpisodeStill]. */
    val preferNextEpisodeStill: Boolean = false,
)

@Immutable
data class TvHeroNeighbors(
    /**
     * 单步可达的方向, **按可能性从高到低排序**: 图片预热全做, 第一个 ([decodeTarget])
     * 还会预解码进内存. 顺序同时是后台 URL 预取的开工顺序 (后台槽只有 1 个).
     *
     * 实测 (2026-08-14 Shield): 只押顺方向时, 用户"有时往右有时往下", 往下那几步全部落空 ——
     * 命中的 103~108ms 与落空的 364~592ms 泾渭分明. 单步方向要押全.
     */
    val singleStep: List<TvHeroNeighbor> = emptyList(),
    /** 两步才到的 (如同向第二格): 只做 URL 预取. */
    val urlOnly: List<TvHeroNeighbor> = emptyList(),
) {
    /** URL 预取: 全都要, 单步的排前面. */
    val all: List<TvHeroNeighbor> get() = (singleStep + urlOnly).distinctBy { it.subjectId }

    /** 预解码进内存的那一个: 可能性最高的单步方向 (代价 3.7MB, 只值得押一个). */
    val decodeTarget: TvHeroNeighbor? get() = singleStep.firstOrNull()
}

/**
 * 网格页 (追番/搜索) 的邻居计算: 中间卡四个方向都单步可达, 按 右 -> 下 -> 左 -> 上 排序
 * (浏览主方向是右/下, 预解码押右). 右键不越行 (行尾右侧没有卡), 上下 = 同列跨行.
 *
 * @param neighborAt 平铺下标 -> 该位置的预取目标 (含它自己的剧照偏好, 见 [TvHeroNeighbor]);
 *   越界/占位返回 null (LazyPagingItems 用 peek, 别用 get).
 */
fun tvGridNeighborsOf(index: Int, columns: Int, neighborAt: (Int) -> TvHeroNeighbor?): TvHeroNeighbors {
    val rowStart = index / columns * columns
    val rowEnd = rowStart + columns - 1
    return TvHeroNeighbors(
        singleStep = listOfNotNull(
            (index + 1).takeIf { it <= rowEnd }?.let(neighborAt), // 右
            neighborAt(index + columns), // 下
            (index - 1).takeIf { it >= rowStart }?.let(neighborAt), // 左
            (index - columns).takeIf { it >= 0 }?.let(neighborAt), // 上
        ),
        urlOnly = listOfNotNull((index + 2).takeIf { it <= rowEnd }?.let(neighborAt)),
    )
}

/**
 * hero 媒体流水线的输入: **聚焦目标的最小描述**. data class —— snapshotFlow 按结构相等去重,
 * 别把 lambda 塞进来.
 */
@Immutable
data class TvHeroMediaSpec(
    val subjectId: Int,
    /** hero 背景优先用"下一集"单集剧照 (继续观看行 / 在看的条目), 见 [tvHeroBackdropUrl]. */
    val preferNextEpisodeStill: Boolean = false,
    /**
     * 竖版封面: 封面兜底与垫底图用. 空 = 该条目不做封面兜底
     * (如搜索页打码/隐藏的条目 —— 判据必须照抄卡片, 卡片不出图这里也不能出).
     */
    val coverUrl: String = "",
    val neighbors: TvHeroNeighbors = TvHeroNeighbors(),
)

/**
 * [rememberTvHeroMediaPipeline] 的输出: 展示层取 URL 的两个帮手 (自带封面兜底状态).
 *
 * 两个函数都设计成**在 backdrop 层的 lambda 里调用** (读的都是快照可观察状态: 服务层热表 +
 * 兜底计时位), 在页面 body 里读会把整页做成热重组.
 */
@Stable
class TvHeroMediaPipelineState internal constructor(
    private val tmdb: TmdbImageService,
    private val fullVisualEffects: Boolean,
) {
    /**
     * 解析超时兜底 (见 [tvHeroBackdropUrl] 的 coverFallbackNow): 聚焦超过
     * [tvHeroCoverFallbackMillis] 还没横版图结论就先上封面.
     * 记 subjectId 而不是 Boolean: 焦点换卡后旧值天然失配, 不必赶在下一帧前清零.
     */
    internal var coverFallbackFor: Int? by mutableStateOf(null)

    /** 展示层主图: 单集剧照 -> 整部 backdrop -> 竖版封面, 三级回落见 [tvHeroBackdropUrl]. */
    fun backdropUrl(spec: TvHeroMediaSpec?): String? = spec?.let { s ->
        tmdb.tvHeroBackdropUrl(
            s.subjectId,
            fullVisualEffects,
            preferNextEpisodeStill = s.preferNextEpisodeStill,
            coverUrl = s.coverUrl,
            coverFallbackNow = coverFallbackFor == s.subjectId,
        )
    }

    /**
     * 垫底图 (给 [TvPageBackdropLayer] 的 underlayUrl): URL 早就解析出来了、但**图片本体**
     * 下载卡住 (实测单流假死 9s, 期间 hero 黑着) —— 这种情形封面兜底帮不上忙 (它只管 URL
     * 未解析). 到点后把封面垫在主图下面, 主图解码完成自然盖住, 不需要"已上屏"信号.
     */
    fun underlayUrl(spec: TvHeroMediaSpec?): String? = spec?.let { s ->
        s.coverUrl.takeIf {
            it.isNotBlank() && coverFallbackFor == s.subjectId &&
                // 只垫"有真实主图在路上"的情形; URL 未解析时封面已经是主图, 不必重复画
                tmdb.tvHeroBackdropUrl(
                    s.subjectId, fullVisualEffects,
                    preferNextEpisodeStill = s.preferNextEpisodeStill,
                ) != null
        }
    }
}

/**
 * **hero 媒体流水线** —— 四个 TV 页共用的机械部分, 从探索页抽出 (那边是原型战场,
 * 机理与实测数据见各协作对象):
 *
 * - 连发合并 ([TvNavigationSettle]): 空闲后的单次移动立即取数据, 连发期间只保留最终目标;
 * - 聚焦解析走调度器 ([TvHeroPrefetch.foreground]): 在途去重 + 前台优先 + 挂死自愈;
 * - 邻居后台预取 ([TvHeroPrefetch.background]): 单槽, 给前台让路;
 * - 邻居图片预热 ([TvHeroImagePrefetch]): 每个邻居独立观察 URL 落表, 谁先到谁先热;
 *   `next` 预解码进内存, 其余只落磁盘; 换焦点保留仍有用的在途任务 (retain);
 * - 封面兜底计时: 2.5s 无横版图结论先上封面 ([TvHeroMediaPipelineState.backdropUrl]),
 *   URL 已有但图片卡住则垫底 ([TvHeroMediaPipelineState.underlayUrl]).
 *
 * 页面提供**数据链**: [resolve]/[resolveNeighbor] 是该页从 subjectId 到"剧照/backdrop 落表"
 * 的完整解析 (探索/追番页 = [resolveTvHeroMedia]; 搜索页只有 backdrop 一跳). 两个钩子给页面
 * 塞私有事: [beforeResolve] 在连发合并**之前**跑 (种缓存, 让 hero 文字不等媒体链);
 * [afterResolve] 在聚焦解析完成后跑 (简介兜底/长驻刷新收集器), 返回 false 表示这次连基础
 * 信息都没拿到, 跳过邻居预取 (下次聚焦重试).
 *
 * @param restartKey 流水线整体重启的键: 分页实例会换的页面 (追番切 tab / 搜索重搜) 传 items,
 *   否则闭包里捕获的是旧实例; 探索页传 Unit.
 * @param spec 快照可观察的聚焦目标; null = 还没有目标.
 */
@Composable
fun rememberTvHeroMediaPipeline(
    tmdb: TmdbImageService,
    fullVisualEffects: Boolean,
    restartKey: Any?,
    spec: () -> TvHeroMediaSpec?,
    resolve: suspend (TvHeroMediaSpec) -> Unit,
    resolveNeighbor: suspend (TvHeroMediaSpec, TvHeroNeighbor) -> Unit,
    beforeResolve: ((TvHeroMediaSpec) -> Unit)? = null,
    afterResolve: (CoroutineScope.(TvHeroMediaSpec) -> Boolean)? = null,
): TvHeroMediaPipelineState {
    val sketch = LocalSketch.current
    val platformContext = LocalPlatformContext.current
    val state = remember(tmdb, fullVisualEffects) { TvHeroMediaPipelineState(tmdb, fullVisualEffects) }

    // 离开本页 (进详情页/播放页): 撤掉还在途的邻居图预热, 别跟目的页的首图抢带宽.
    // 已知边界: 主页 tab 过渡期间两页短暂共存, 旧页的 onDispose 会把新页刚发的预热一并撤掉 ——
    // 影响只有那一个焦点的邻居图不预热, 下次移动自愈, 不值得为此给 retain 加租约.
    DisposableEffect(Unit) {
        onDispose { TvHeroImagePrefetch.retain(emptySet()) }
    }

    // 页面每次重组都会换 lambda 实例; 流水线常驻, 用 rememberUpdatedState 拿最新的
    val currentResolve by rememberUpdatedState(resolve)
    val currentResolveNeighbor by rememberUpdatedState(resolveNeighbor)
    val currentBeforeResolve by rememberUpdatedState(beforeResolve)
    val currentAfterResolve by rememberUpdatedState(afterResolve)

    // 异步加载聚焦条目的 hero 媒体: 焦点换卡时 collectLatest 取消在途等待, 不会卡 UI
    LaunchedEffect(restartKey) {
        val settle = TvNavigationSettle(TV_HERO_MEDIA_DEBOUNCE_MILLIS)
        snapshotFlow { spec() }.filterNotNull().collectLatest { s ->
            // 前台马上要用带宽: 过期的邻居图预热取消让路. 但**新目标自己/仍是邻居**的在途预热
            // 要留下 —— Coil 对并发的相同请求不做在途合流, 把它们取消再从零重下, 等于把快到手
            // 的字节全扔掉 (网络慢于停留节奏时, 被取消的恰恰是马上要显示的那张)
            TvHeroImagePrefetch.retain(
                buildSet {
                    val targets = listOf(TvHeroNeighbor(s.subjectId, s.preferNextEpisodeStill)) +
                        s.neighbors.singleStep
                    targets.forEach { t ->
                        tmdb.tvHeroBackdropUrl(
                            t.subjectId, fullVisualEffects,
                            preferNextEpisodeStill = t.preferNextEpisodeStill,
                        )?.let(::add)
                    }
                },
            )
            coroutineScope {
                // 超时兜底计时: 到点无条件置位即可 —— tvHeroBackdropUrl 的优先级保证剧照/backdrop
                // 一旦有值仍顶掉封面, 所以不必检查"是否已解析". 焦点换卡 collectLatest 取消本计时
                launch {
                    delay(tvHeroCoverFallbackMillis())
                    state.coverFallbackFor = s.subjectId
                }
                currentBeforeResolve?.invoke(s)
                settle.awaitTurn()
                // 慢网络就等 (焦点换卡时 collectLatest 会取消, 等待无害; 挂死自愈见调度器).
                // 列表自带 info 的页面也走这条, 不各自直呼预取: 直呼绕过调度器 —— 不占前台计数
                // (后台预取不给它让路)、不合流在途任务 (与邻居预取重复发同一条链)、不受连发
                // 合并约束 (长按划过的每张卡都发起媒体链)
                TvHeroPrefetch.foreground(s.subjectId) { currentResolve(s) }
                if (currentAfterResolve?.invoke(this, s) == false) return@coroutineScope
                // 聚焦这一张全部就绪之后, 才后台预热最可能走到的下几张. 排在这里而不是
                // onFocused 里: 那边每划过一张卡都会触发, 长按连发时会堆出一串排队任务;
                // 放在这条被 collectLatest 管着的流水线末尾, 连发时压根走不到.
                s.neighbors.all.forEach { neighbor ->
                    TvHeroPrefetch.background(neighbor.subjectId) { currentResolveNeighbor(s, neighbor) }
                }
                // 图片预热: 邻居的 URL 谁先落表谁先热, 互不等待.
                //
                // 等**全员**到齐 (first { size == n }) 的老做法: 有一个邻居确认没图时它的 URL
                // 恒为 null, 条件永不满足, 等满超时后连已解析好的那份也一起丢掉 (2026-08-14
                // 实测: 等满 5.003s 后 urlsResolved=0, 整批 skipped). 独立观察后不存在"陪葬".
                s.neighbors.singleStep.forEach { neighbor ->
                    launch {
                        val url = withTimeoutOrNull(TV_NEIGHBOR_IMAGE_URL_WAIT) {
                            snapshotFlow {
                                // 偏好取**邻居自己的**: 抄 spec 的会热错档, 见 TvHeroNeighbor
                                tmdb.tvHeroBackdropUrl(
                                    neighbor.subjectId, fullVisualEffects,
                                    preferNextEpisodeStill = neighbor.preferNextEpisodeStill,
                                )
                            }.filterNotNull().first()
                        }
                        when {
                            url == null -> Unit // 多半是 TMDB 上真没图, 放弃预热
                            // 原图档 (完整视觉效果下的剧照) 不投机下载: 单张可达数 MB, 十倍于
                            // w1280. 代价是那一档继续观看行的邻居图不预热 —— 明知的取舍.
                            //
                            // 原生 4K UI 上也照排除: 本页的 hero 恒 w1280 (见 tvHeroBackdropUrl),
                            // 走到这条的只剩"完整视觉效果下的剧照"那一档
                            url.isOriginalSizeTmdbUrl() -> Unit
                            // 可能性最高的那个方向完整解码进内存 (~3.7MB, 每次聚焦最多一张),
                            // 其余只落磁盘 —— 收益与代价见 TvHeroImagePrefetch 类文档
                            else -> TvHeroImagePrefetch.prefetch(
                                url, sketch, platformContext,
                                likelyTarget = neighbor.subjectId == s.neighbors.decodeTarget?.subjectId,
                            )
                        }
                    }
                }
            }
        }
    }
    return state
}

/**
 * TMDB 原图档 URL (`/t/p/original/`). 用于图片预热的排除项: 原图偶有 4K 级、几 MB 一张,
 * 投机预热不划算.
 *
 * **不能拿"完整视觉效果开着"当判据**: 推荐行走的是整部 backdrop, 服务层固定 w1280,
 * 这个开关对它没有任何影响. 早先按开关整段跳过, 结果把用户主要浏览的推荐行也一起跳了,
 * 图片预热一次都没执行过 (2026-08-14 实测日志 26 条全是"跳过").
 */
fun String.isOriginalSizeTmdbUrl(): Boolean = contains("/t/p/original/")

/**
 * 等邻居 backdrop URL 落表的上限. 正常解析 <1s; 等这么久还没有的多半是 TMDB 上确实没图
 * (负缓存要等聚焦时才写), 放弃预热.
 */
private const val TV_NEIGHBOR_IMAGE_URL_WAIT = 5_000L
