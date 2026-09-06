/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.intl.Locale
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.source.DataFrom
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.toTmdbMatchHints
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.settings.NetworkTroubleBeacon
import me.him188.ani.app.data.network.BangumiSummaryService
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbMatchHints
import me.him188.ani.app.data.network.matchToEpisodes
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.network.tmdbStillHeroSizeUrl
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.datasources.api.toLocalDateOrNull
import me.him188.ani.utils.logging.info
import kotlin.time.TimeSource

/**
 * "继续观看"下一集的 TMDB 媒体: 单集剧照 + 单集简介. 字段为 null = 查过确认没有.
 *
 * 存的是**原图档 URL**, 显示时才按设置降档 (见 [tvHeroBackdropUrl]) —— 存降档结果的话用户
 * 打开"完整视觉效果"得清缓存才生效.
 */
@Immutable
data class TvNextEpisodeMedia(
    val episodeId: Int,
    val stillUrl: String?,
    val overview: String?,
)

/**
 * **TV 各页 hero 附属数据的进程级缓存** (下一集剧照 + 简介兜底).
 *
 * **backdrop 不在这里**: 整部 backdrop 的进程级热缓存只有服务层那一张
 * ([TmdbImageService.peekBackdropUrl], 快照可观察), 页面层不再养第二份 —— 曾经两层并存时
 * "已确认无图"一边编码成 `""` 一边编码成 `null`, 容量策略也各不相同.
 *
 * ## 为什么是进程级而不是 `remember`
 *
 * 导航离开时页面退出组合, `remember` 的解析结果一并销毁; 返回本页要从头再解析一遍
 * (读 DataStore 缓存 -> 出 URL -> Coil 取图), 约 300ms. 而背景图外面套着一层 `Crossfade`
 * (换聚焦条目时用的), URL 迟到就意味着**这层 crossfade 在页面入场动画中途才起步** ——
 * 屏幕上同时跑着两条互不相干的透明度曲线, 相乘出来的观感就是"alpha 走着走着突然跳一档".
 *
 * 真机逐帧实测 (2026-08-12, 从详情页返回探索页, 60fps 录屏): 页面自身的入场淡入在第 219 帧
 * 就已跑完 (页面其余内容此后完全静止), 背景图那一层却在第 218 帧只有最终亮度的 **37%**、
 * 下一帧跳到 **81%**、再花 150ms 才爬到 100%; 中途整屏平均亮度一度掉到起始值的 43%.
 * 这就是那下"闪"与"折线感"的来源, 与文字/排版无关.
 *
 * 提到进程级之后返回时首帧就有 URL, `Crossfade` 的初值即终值 (初值不触发动画), 背景图与页面
 * 其余部分共用同一条入场淡入曲线.
 *
 * ## 为什么四个页面共用一张表
 *
 * 探索页、追番页、搜索页、时间表原本各自 `remember { mutableStateMapOf() }` 一份, 于是
 * **同一部作品换个页面进详情页, 首帧观感就不一样**: 从预取过的那页进有图, 从没预取过的那页
 * 进要等一次网络. 现在四页写同一张表, 谁先聚焦过, 其余三页与详情页都直接命中.
 *
 * ## 容量
 *
 * 超过 [MAX_ENTRIES] 时**按写入顺序淘汰最老的一批**, 不整表清空 —— 清空会让长会话里早就
 * 解析过的条目成批退化回"先空着再淡入". 写入都发生在页面协程 (主线程), 簿记不需要加锁.
 */
object TvHeroMediaCache {
    private const val MAX_ENTRIES = 300

    /** 一次淘汰的条数 (均摊开销, 不必每次写入都动表). */
    private const val EVICT_BATCH = 50

    /** subjectId -> "继续观看"下一集的 TMDB 剧照与简介. */
    val nextEpisodeMedia: SnapshotStateMap<Int, TvNextEpisodeMedia> = mutableStateMapOf()
    private val nextEpisodeOrder = ArrayDeque<Int>()

    /**
     * subjectId -> bgm.tv 简介兜底 (Ani 服务器部分条目 summary 为空, 直连 bgm.tv 补; "" = 也没有).
     */
    val summaryFallbacks: SnapshotStateMap<Int, String> = mutableStateMapOf()
    private val summaryOrder = ArrayDeque<Int>()

    /**
     * subjectId -> 条目信息 (原名/分集/收藏状态), hero 解析链第一跳的结果.
     *
     * **故意是普通 map 而不是 [SnapshotStateMap]**: 预取会为用户**还没看到**的条目写入, 而
     * SnapshotStateMap 没有按键订阅粒度 —— 任何一次写入都让读过表的作用域整体失效, 于是
     * "用户发呆时后台悄悄预取邻居"就变成"用户发呆时 hero 文字块被反复重组", 正好抵消掉
     * 预取想换来的流畅. 页面要响应式更新的那一份 (只有**聚焦**那个条目) 由页面自己的表持有.
     */
    private val subjectInfos = mutableMapOf<Int, SubjectCollectionInfo>()
    private val subjectInfoOrder = ArrayDeque<Int>()

    /**
     * 保护普通容器 (subjectInfos 与各淘汰顺序队列): 预取跑在 Dispatchers.Default (并行度=核数),
     * 多个后台任务与主线程并发读写. SnapshotStateMap 自身线程安全, 但普通 map/ArrayDeque 不是 ——
     * HashMap 并发写扩容会丢数据甚至坏链.
     */
    private val cacheLock = SynchronizedObject()

    /** 同步取条目信息; null = 还没解析过. */
    fun peekSubjectInfo(subjectId: Int): SubjectCollectionInfo? =
        synchronized(cacheLock) { subjectInfos[subjectId] }

    internal fun putSubjectInfo(subjectId: Int, info: SubjectCollectionInfo) {
        synchronized(cacheLock) {
            if (subjectId !in subjectInfos) {
                subjectInfoOrder.addLast(subjectId)
                if (subjectInfoOrder.size > MAX_ENTRIES) {
                    repeat(EVICT_BATCH) { subjectInfoOrder.removeFirstOrNull()?.let(subjectInfos::remove) }
                }
            }
            subjectInfos[subjectId] = info
        }
    }

    internal fun putNextEpisodeMedia(subjectId: Int, media: TvNextEpisodeMedia) =
        putEvicting(nextEpisodeMedia, nextEpisodeOrder, subjectId, media)

    internal fun putSummaryFallback(subjectId: Int, summary: String) =
        putEvicting(summaryFallbacks, summaryOrder, subjectId, summary)

    private fun <V> putEvicting(
        map: SnapshotStateMap<Int, V>,
        order: ArrayDeque<Int>,
        key: Int,
        value: V,
    ) {
        synchronized(cacheLock) {
            if (key !in map) {
                order.addLast(key)
                if (order.size > MAX_ENTRIES) {
                    repeat(EVICT_BATCH) { order.removeFirstOrNull()?.let(map::remove) }
                }
            }
            map[key] = value
        }
    }
}

/**
 * **hero 材料预取的调度器**: 在途去重 + 前台优先 + 后台单槽.
 *
 * 解决三件事:
 *
 * 1. **在途去重**. 预取右邻居的请求还没回来, 用户就按了右键 —— 从前这会**再发一次一模一样的
 *    请求**(服务层只短路"已解析出结果"的正缓存, 不认在途), 于是等待时间从"剩余"退回"全程".
 *    现在两条路径按 subjectId 合流到同一个 [Deferred], 后到的那个只等剩下的时间.
 * 2. **前台优先**. 后台任务开工前先等前台归零 ([foreground] 计数), 所以预取永远不会和"用户
 *    正在看的那张"抢带宽. 后台并发上限 1, 任何时刻最多一个后台请求可能与前台重叠, 而且只
 *    重叠它的尾巴.
 * 3. **提升而不是排队**. 用户走到了一个正在后台排队的条目上时, 直接把那个任务提为前台放行 ——
 *    否则它会卡在"等前台归零"上, 而那个前台正是它自己, 死等.
 *
 * **合流的两条边界** (都是实测踩出来的, 症状一样: 继续观看行某张卡等好几秒不出图, 走开再回来
 * 立刻就有):
 *
 * - **只合流"已开工"的任务** ([Task.started]). 还没开工的多半正排在后台槽 (只有 1 个) 的队里,
 *   而提升只改得动 [Task.promoted], 改不动信号量的队列 —— 等它等于跟着排在别人的网络后面,
 *   队列最深 [BACKGROUND_QUEUE_MAX] 个 × 每个几百毫秒. 它一个字节都还没下, 取消换成前台版
 *   重来什么都不亏.
 * - **合流之后仍要补跑自己那份 `load`**. 合流到的可能是**预取版**的 lambda, 它的跳数更少
 *   (预取不取"下一集"剧照, 见 [resolveTvHeroMedia] 的 `settingsRepository`), 直接等它跑完
 *   等于把前台多出来的那一跳静默丢掉. 补跑的代价接近零: 该做的都做过时整条链全是缓存命中.
 *
 * **取消策略: 前台调用方被取消 (焦点又动了) 不会杀掉任务本身.** 任务挂在本对象的作用域上,
 * 会把剩下的路跑完并落缓存 —— 它已经在途, 取消不会把带宽还回来, 只是把已花的时间扔掉,
 * 而用户按返回键回到那张卡时正好是热的.
 *
 * 但"跑完"有硬上限 ([LOAD_TIMEOUT_MILLIS]): 网络断供期间发出的请求可以**无限期无响应**
 * (2026-08-14 实测), 没有上限的话挂死的任务会永远赖在 [running] 里 —— 后续每次聚焦同一
 * 条目都合流到它身上陪等 (表现: 返回页面后 hero 文字迟迟不出), 后台任务挂死还独占唯一的
 * 后台槽, 整个预取队列跟着饿死. 配套地, [foreground] 等合流对象也封顶
 * ([MERGE_AWAIT_MILLIS]), 超时就自己重发 —— 新请求建的是新连接, 网络恢复后立即能成.
 *
 * **只适合小请求 (JSON)**. 图片本体那种几百 KB 的下载不能用这套"让它跑完"的策略, 它的尾巴
 * 长到必须给前台让路, 要另走一条可取消的通道.
 */
object TvHeroPrefetch {
    /** 任务的宿主: 必须独立于调用方, 见类文档的取消策略. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** subjectId -> 在途任务. 只在 [mutex] 里读写. */
    private val running = mutableMapOf<Int, Task>()

    /**
     * 还在等闸门、**尚未开工**的后台任务, 按入队顺序. 只在 [mutex] 里读写.
     *
     * 有上限的理由: 后台槽只有 1 个, 而信号量大致公平 (FIFO). 网络比浏览慢时队列只进不出,
     * 于是**当前位置的邻居排在几十个陈旧目标后面**, 预取静默失效 —— 网络好的时候完全测不出来.
     * 超限丢最老的那个: 它必然是最陈旧的目标, 而丢弃投机预取是安全的 (用户真走过去时前台
     * 路径会重新发起, 还能命中在途去重).
     */
    private val parked = ArrayDeque<Int>()

    /** 前台在途数. 后台任务要等它归零才开工. */
    private val foregroundCount = MutableStateFlow(0)

    /** 后台并发上限. */
    private val backgroundSlot = Semaphore(1)

    private class Task(
        /** 被提为前台: 不再等待、也不占后台槽. */
        val promoted: MutableStateFlow<Boolean>,
        val job: Deferred<Unit>,
    ) {
        /** `load` 已经开跑 (闸门与后台槽都过了). 只在 [mutex] 里读写, 与合流判定同一把锁. */
        var started: Boolean = false
    }

    /**
     * 前台加载: 立即开工, 与在途的同条目任务合流.
     *
     * 调用方被取消时只是不再等待, 任务照跑 (见类文档).
     */
    suspend fun foreground(subjectId: Int, load: suspend () -> Unit) {
        var joined = false
        val task = mutex.withLock {
            val existing = running[subjectId]
            val resolved = when {
                existing == null -> start(subjectId, true, load)
                // 已开工 (或已提为前台, 那它马上就会开工): 合流, 只等它剩下的时间
                existing.started || existing.promoted.value -> {
                    joined = true
                    existing.promoted.value = true
                    parked.remove(subjectId) // 提为前台就不再算排队, 免得被"丢最老"误伤
                    existing
                }
                // 还没开工的后台任务: 换成前台版重来, 见类文档"合流的两条边界"
                else -> {
                    running.remove(subjectId)
                    parked.remove(subjectId)
                    existing.job.cancel()
                    start(subjectId, true, load)
                }
            }
            // 计数在锁内加一: 放锁外的话, "任务已建好、计数还没加"的空窗里后台闸门 (等前台归零)
            // 会看到 0 而放行一个本该等待的任务
            foregroundCount.update { it + 1 }
            resolved
        }
        try {
            // 等待封顶: 合流对象可能已挂死在断掉的连接上 (见类文档), 超时按"没等到"处理
            val completed = withTimeoutOrNull(MERGE_AWAIT_MILLIS) { task.job.await() } != null
            // 合流到的可能是跳数更少的预取版 (补跑才不丢活), 也可能压根没等到 (自己重发才有救).
            // 异常处理与任务体一致: 调用方只关心"跑完了", 失败与否看缓存里有没有值
            if (joined || !completed) {
                try {
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        } finally {
            foregroundCount.update { it - 1 }
        }
    }

    /** 后台预取: 前台空闲时才开工; 已在途 (无论前后台) 就什么都不做. */
    fun background(subjectId: Int, load: suspend () -> Unit) {
        scope.launch {
            mutex.withLock { if (subjectId !in running) start(subjectId, false, load) }
        }
    }

    /**
     * 跑一次解析链并封顶.
     *
     * 异常不外泄: `await()` 的调用方只关心"跑完了", 失败与否看缓存里有没有值.
     * 硬上限的理由见类文档 —— 挂死的任务不能永远赖在表里.
     *
     * **等满上限那一下要亮网络故障信标**: 那正是"用户在浏览时撞上等待超时"的现场, 而他接下来
     * 打开动作面板多半就是想知道是不是网络的问题 (见 [NetworkTroubleBeacon]). 只认超时而不认
     * 一般失败: 后者包含"TMDB 上确实没这部作品"之类的正常结果.
     */
    private suspend fun runLoadCapped(subjectId: Int, load: suspend () -> Unit) {
        val result = runCatching { withTimeout(LOAD_TIMEOUT_MILLIS) { load() } }
        if (result.exceptionOrNull() is TimeoutCancellationException) {
            NetworkTroubleBeacon.report("tv hero load timed out after ${LOAD_TIMEOUT_MILLIS}ms (subject=$subjectId)")
        }
    }

    /** 只在 [mutex] 里调用. */
    private fun start(subjectId: Int, promoted: Boolean, load: suspend () -> Unit): Task {
        val flag = MutableStateFlow(promoted)
        lateinit var self: Task
        // LAZY: finally 里要按身份认自己, 不能在 self 赋值之前就有机会跑
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                // 后台任务让路: 前台归零, 或自己在等待期间被提升
                if (!flag.value) {
                    combine(foregroundCount, flag) { fg, p -> p || fg == 0 }.first { it }
                }
                // 异常不外泄: await() 的调用方只关心"跑完了", 失败与否看缓存里有没有值.
                // withTimeout 的硬上限见类文档 —— 挂死的任务不能永远赖在表里
                if (flag.value) {
                    markStarted(subjectId, self) // 开工了, 不再占排队名额, 也可以被合流了
                    runLoadCapped(subjectId, load)
                } else {
                    backgroundSlot.withPermit {
                        // 拿到槽再查一次闸门: 排队等槽的这段时间里前台可能已经来了
                        combine(foregroundCount, flag) { fg, p -> p || fg == 0 }.first { it }
                        markStarted(subjectId, self)
                        runLoadCapped(subjectId, load)
                    }
                }
                Unit
            } finally {
                mutex.withLock {
                    // 只清自己: 未开工的任务会被 foreground 取消换成前台版, 这时表里已经是
                    // 新任务, 迟到的 finally 不能把它删掉 (删掉就没人认得出在途, 去重失效)
                    if (running[subjectId] === self) {
                        running.remove(subjectId)
                        parked.remove(subjectId)
                    }
                }
            }
        }
        self = Task(flag, job)
        running[subjectId] = self
        if (!promoted) {
            parked.addLast(subjectId)
            if (parked.size > BACKGROUND_QUEUE_MAX) {
                // 丢最老的那个 (见 parked 的说明). cancel 不会同步跑它的 finally,
                // 所以在这里持锁调用不会死锁
                parked.removeFirst().let { oldest -> running.remove(oldest)?.job?.cancel() }
            }
        }
        job.start()
        return self
    }

    private suspend fun markStarted(subjectId: Int, task: Task) {
        mutex.withLock {
            task.started = true
            if (running[subjectId] === task) parked.remove(subjectId)
        }
    }

    /**
     * 排队等开工的后台任务上限.
     *
     * 8 ≈ 两三步移动的邻居量: 再多的目标在用户走到之前必然已被更新的邻居取代, 留着只会挡路.
     */
    private const val BACKGROUND_QUEUE_MAX = 8

    /**
     * 单个任务 `load` 的硬上限. 正常整条链 (条目信息 + TMDB 搜索 + lineage + 剧照) 慢网络下
     * 也就 5~8s; 到 15s 还没完的必然是挂死在断掉的连接上, 留着只会毒化合流与后台槽.
     */
    private const val LOAD_TIMEOUT_MILLIS = 15_000L

    /**
     * [foreground] 等合流对象的封顶: 超过就当它挂死了, 自己重发.
     *
     * **必须明显小于封面兜底门槛**: 合流对象一挂死, 这段等待就是纯粹的
     * 空转, 等满了才重发. 早先是 8s, 而重发之后 0.5~1.2s 就拿到结果 (2026-08-14 日志里两次
     * merge-timeout 都是 info=+8.6s / url=+9.2s) —— 等于每碰上一次挂死的在途任务就必然翻出
     * 一次封面兜底. 2.5s 重发, 加上重发本身的耗时仍留了一倍余量给 6s 的兜底门槛.
     *
     * 代价是慢网络下可能对同一条链发两次请求: 挂死时那是唯一的活路, 没挂死时第二次基本走
     * 缓存, 都可以接受.
     *
     * **从兜底门槛推导而不是各写一个常数**: 兜底改成按网络档位取值之后 (慢档 2.5s), 写死的
     * 2.5s 会让"明显小于"这条不变式当场失效 —— 等待时长等于兜底时长, 合流一挂死就必然翻出
     * 兜底. 取 2/5 保持原先 2.5s : 6s 的比例, 不变式由代码结构保证, 不靠两个魔数各自不飘.
     */
    private val MERGE_AWAIT_MILLIS get() = tvHeroCoverFallbackMillis() * 2 / 5
}

/**
 * **背景图本体的预热通道** —— 与 [TvHeroPrefetch] 分开, 因为取消策略正好相反.
 *
 * [TvHeroPrefetch] 跑的是几 KB 的 JSON; 背景图是 150~350KB (实测 Shield 上 255~538ms,
 * 且 `url` 到 `image` 之间就是全部剩余等待). 任务**按 URL 记账**做在途去重 —— Coil 对并发
 * 的相同请求不做合流, 得自己挡. **在途任务一律让它跑完, 不取消** (原因见 [retain]);
 * 堆积由 [MAX_INFLIGHT] 封顶 (按网络档位取值): 超限丢弃新请求而不是取消旧的.
 *
 * 各 URL **并行**下载, 不排队: 串行时排头的一旦卡住, 后面的永远轮不到 (2026-08-14 实测两次
 * 落空都是这么来的). 但并行度必须压住 —— 与显示中的主图共用同一 host 的连接额度, 见
 * [MAX_INFLIGHT].
 *
 * **只落磁盘不进内存**: `memoryCachePolicy(DISABLED)` + 1×1 的目标尺寸 —— 下载缓存存的是网络
 * 原始字节 (按 URL 索引, 与显示时的请求同一条记录), 所以显示端照样能拿到全尺寸图, 而预热本身
 * 不解码出可用位图. 一张 w1280 解码后是 1280×720×4 ≈ 3.7MB, 预热几张就把 4K UI 下本就紧张的
 * 内存吃掉了.
 *
 * 曾经有个"最可能的那一个目标预解码进内存"的档 (实测省 ~70ms), 迁到 sketch 后删掉了: 它依赖
 * **coil 的内存缓存键只按 URL、对任何请求尺寸都判有效**, 而 sketch 的键含请求尺寸, 显示端是按
 * 各自的布局尺寸请求的 (见 `configureAniImageRequest`) —— 预解码出来的条目显示端永远命不中,
 * 等于白解一次. 预热真正省下的从来是网络那几百毫秒到几秒; 解码只有几十毫秒, 且显示后就进内存
 * 缓存, 来回走不会再解.
 */
/** 一条在途的图片预热, 见 [TvHeroImagePrefetch.inFlight]. */
class TvImagePrefetchInFlight internal constructor(
    val job: Job,
    /** 已经跑了多久 —— 判断"还值不值得等"用的就是它, 不是任务存不存在. */
    val elapsedMillis: Long,
)

object TvHeroImagePrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * url -> 在途任务. **只在主线程读写** (聚焦流水线的两个入口 + 展示层的 [inFlight] 查询,
     * 都在主线程), 任务体不碰它, 跑完的条目由下次任一入口惰性清掉 —— 所以不需要锁.
     */
    private val jobs = mutableMapOf<String, InFlight>()

    private class InFlight(val job: Job, val since: TimeSource.Monotonic.ValueTimeMark)

    /**
     * 槽位满时排队的目标. **与 [jobs] 一样只在主线程读写**.
     *
     * 持有 [PlatformContext]: 每次焦点变化 (retain) 都会整批清掉, 所以最长只活一格导航,
     * 不构成泄漏; 别把它改成长生命周期的缓存.
     */
    private class Pending(
        val url: String,
        val sketch: Sketch,
        val context: PlatformContext,
        val likelyTarget: Boolean,
    )

    private val pending = ArrayDeque<Pending>()

    /** 排队上限: 一次聚焦最多四个邻居, 超了说明积压, 见 [enqueuePending]. */
    private const val MAX_PENDING = 4

    private fun pruneFinished() {
        jobs.entries.removeAll { !it.value.job.isActive }
    }

    /**
     * 该 URL 的在途预热任务, 没有则 null. 给展示层"接管"用: 同一张图正在预热时, 显示端
     * 等它跑完再自己请求, 比另开一条新连接重下一遍划算 (Coil 不合流并发的相同请求).
     *
     * 带上**已经跑了多久**: 值不值得等取决于它还剩多少, 而不是它存不存在 —— 正常预热
     * 250~600ms, 已经跑了两三秒还没完的基本就是挂死了, 那种等下去纯亏.
     */
    fun inFlight(url: String): TvImagePrefetchInFlight? = jobs[url]
        ?.takeIf { it.job.isActive }
        ?.let { TvImagePrefetchInFlight(it.job, it.since.elapsedNow().inWholeMilliseconds) }

    /**
     * 焦点换了: 清掉已完成的记账. **刻意不取消在途下载** —— 2026-08-14 实测取消风暴会撞上
     * Android 11 Conscrypt 的并发关闭竞态 (native SIGSEGV in BIO_ctrl_pending,
     * ConscryptEngineSocket.close 两线程同走), 网格页四方向邻居每步导航要取消最多 4 个
     * 在途请求, 掷骰子频率太高. 一张 w1280 只有 150~350KB、~300ms 跑完, 让它落盘的代价
     * 远小于取消: 带宽最多晚 300ms 让路, 而这些图本来就是马上可能要用的邻居.
     *
     * [keepUrls] 保留参数位: "哪些在途不必重发"的去重语义仍在 [prefetch] 里, 这里只是不再取消.
     */
    fun retain(@Suppress("UNUSED_PARAMETER") keepUrls: Set<String>) {
        pruneFinished()
        // 焦点换了: **排队中**的目标属于上一格, 整批作废 (在途的仍然不取消).
        // 不清的话连发导航会攒下一串过期卡片的预热, 挤掉真正该热的那几张
        pending.clear()
    }

    /**
     * 预热一张: 同 URL 已在途就不重发.
     *
     * @param likelyTarget true = 单步里最可能真正走到的那个方向. 只影响**排队优先级**
     *   (插到队首, 积压时不被淘汰), 不影响请求本身 —— 预热一律只落磁盘, 见类文档.
     */
    fun prefetch(
        url: String,
        sketch: Sketch,
        context: PlatformContext,
        likelyTarget: Boolean = false,
    ) {
        pruneFinished()
        if (url in jobs || pending.any { it.url == url }) return
        if (jobs.size >= MAX_INFLIGHT) {
            enqueuePending(Pending(url, sketch, context, likelyTarget))
            return
        }
        start(url, sketch, context)
    }

    /**
     * 槽位满时排队, 而不是直接丢弃 (2026-08-16 改).
     *
     * 旧行为是超限即永久丢弃, 有三个问题: ① 四个邻居各自异步等 URL, **入场顺序是 URL 返回顺序
     * 而不是优先级**, 所以"丢掉可能性最低的那个方向"根本不成立; ② 最可能真正用到的
     * `decodeTarget` 完全可能是被丢的那个; ③ 旧任务跑完后槽位空出来也不会补跑 —— 网络慢于按键
     * 节奏时, 上一批占着槽把新一批全挡掉, 然后连接就闲着, 该摊到浏览时间里的开销一点没摊上.
     *
     * 慢档把并发降到 1 之后这条从"偶发"变成"常态" (四个邻居只进得去一个), 所以必须补.
     *
     * [likelyTarget] 的那个**插队到最前**: 它是本次聚焦最可能真正用到的一张.
     */
    private fun enqueuePending(entry: Pending) {
        if (pending.size >= MAX_PENDING) {
            // 一次聚焦最多四个邻居, 到这儿说明积压了; 最可能用到的那张优先, 其余丢最老的
            if (!entry.likelyTarget) return
            pending.removeLastOrNull()
        }
        if (entry.likelyTarget) pending.addFirst(entry) else pending.addLast(entry)
        // 不记日志: 实测连续导航时排队/补跑约 20 行/分钟, 而 logback 的 root level 是 TRACE ——
        // 无论 DEBUG 还是 INFO 都照样落进 app.log, 会把真正有用的历史顶掉 (电视上那是唯一的
        // 诊断通道). 需要看队列行为时临时加回来即可, 行为本身已在 2026-08-16 实测验证过
    }

    /** 槽位空出来就把排队的接上. 只在主线程调 (与 [jobs]/[pending] 同一约束). */
    private fun drainPending() {
        pruneFinished()
        while (pending.isNotEmpty() && jobs.size < MAX_INFLIGHT) {
            val next = pending.removeFirst()
            if (next.url in jobs) continue
            start(next.url, next.sketch, next.context)
        }
    }

    private fun start(
        url: String,
        sketch: Sketch,
        context: PlatformContext,
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val job = scope.launch {
            try {
                val result = sketch.execute(
                    ImageRequest(context, url) {
                        // 一律只落磁盘: 下载缓存按 URL 存网络原始字节, 与解码尺寸无关, 所以显示端
                        // 无论按什么尺寸请求都能命中. 1×1 让这次几乎不解码 —— 预解码进内存对显示端
                        // 没用 (sketch 的内存缓存键含请求尺寸), 详见类文档.
                        downloadCachePolicy(CachePolicy.ENABLED)
                        memoryCachePolicy(CachePolicy.DISABLED)
                        size(1, 1)
                    },
                )
                val elapsed = startedAt.elapsedNow().inWholeMilliseconds
                // 冷启动未知期到此为止 (见 tvHeroImagePrefetchConcurrency). 缓存命中不进 EWMA
                // 却照样算"跑完了" —— 磁盘全热时它是唯一能结束未知期的信号
                TvImageNetworkSpeed.noteProbeDone()
                // 网络档位的信号源 (见 TvImageNetworkTier). 成功只记**真正走了网络的**:
                // 磁盘/内存命中是 20~140ms, 图一热起来就会把慢网读成快网.
                // 失败也要记 —— 否则最该进慢档的网络 (连续超时/重置) 一个样本都产生不了,
                // 系统会一直停在快档, 继续开 3 条投机请求, 正好在坏网络上加剧竞争
                when {
                    result is ImageResult.Success && result.dataFrom == DataFrom.NETWORK ->
                        TvImageNetworkSpeed.record(elapsed)

                    result is ImageResult.Error -> TvImageNetworkSpeed.recordFailure(elapsed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 预热失败本身无所谓 (显示端自己还会再请求一次), 但耗时是慢网信号.
                // 快速失败 (404 / 立即 reset) 不进 EWMA, 未知期照样该结束
                TvImageNetworkSpeed.noteProbeDone()
                TvImageNetworkSpeed.recordFailure(startedAt.elapsedNow().inWholeMilliseconds)
            }
        }
        jobs[url] = InFlight(job, startedAt)
        // 跑完就把排队的接上. 回主线程再动 jobs/pending
        job.invokeOnCompletion { scope.launch(Dispatchers.Main) { drainPending() } }
    }

    /**
     * 同时在途的预热上限.
     *
     * **不是防内存/防流量, 是防抢槽**: 图片走的是全应用共享的那个 HTTP 客户端
     * (`ScopedHttpClientUserAgent.ANI`), OkHttp 对**同一 host** 默认只放 5 个并发, 而预热图和
     * 正在显示的 hero 主图都是 `image.tmdb.org`. 预热占满槽位时, 新聚焦那张的主图只能排队
     * —— 2026-08-14 实测 url 已在 +181ms 拿到、图却等到 +9752ms, 期间封面兜底顶了 9 秒.
     *
     * 快档 3 = 给主图至少留两个槽. 网格页一步四个邻居会丢掉最后一个 (方向可能性最低的那个),
     * 是明知的取舍: 主图晚 9 秒的代价远大于少热一张邻居图.
     *
     * **慢档降到 1** (见 [tvHeroImagePrefetchConcurrency]): 窄带宽下 5 个槽本身就不够用, 预热
     * 每多占一个就是从正在显示的那张嘴里抢 —— 而预热的是"可能要用"的邻居, 优先级本来更低.
     *
     * **挂死的任务不豁免**. 一度按"在途超过 5s 就不计入额度"放行新预热, 想的是别让挂死的
     * 连接把预热通道堵死; 但那条豁免恰好背叛了这个常量的目的 —— 挂死的请求仍然占着 host 的
     * 连接额度, 每 5 秒再放 3 个进去, 等于亲手把新聚焦的主图挤到队伍第六位. 宁可在网络出事
     * 期间**完全停掉投机预热** (那种时候预热本来也热不出什么), 也要给前台留下槽位.
     *
     * 挂死的任务不会永远赖着: 共享客户端 (DefaultClient.kt) 的请求超时是 300s, 引擎的 socket
     * 超时约 10s —— 实测背景图假死正好是 9.7~10.7s 一档, 到点自己就死了.
     */
    private val MAX_INFLIGHT get() = tvHeroImagePrefetchConcurrency()
}

/**
 * **hero 材料的完整解析链**: 条目信息 (拿原名) -> 整部 backdrop URL -> (在看的条目) 单集剧照.
 *
 * 聚焦路径与预取路径**必须走同一个入口**, [TvHeroPrefetch] 的在途去重才能把两者合流 ——
 * 各写各的话, "预取正卡在第一跳、用户走过去"就会从第一跳重新开始.
 *
 * 三跳是串行的, 省不掉: TMDB 只能按**日文原名**匹配 (中文译名命中率低且失败写持久负缓存),
 * 而原名要先拉条目信息才有。搜索页是例外 —— 它的列表项自带 `originalName`, 只有两跳.
 *
 * @param preferNextEpisodeStill 该条目在"继续观看"行 (hero 背景用单集剧照而非整部 backdrop).
 * @param settingsRepository 取剧照要用; 传 null 则跳过剧照那一跳 (预取邻居时不必).
 * @return 条目信息; null = 这次没拿到 (下次聚焦重试, 不写任何负缓存).
 */
suspend fun resolveTvHeroMedia(
    subjectId: Int,
    collectionRepo: SubjectCollectionRepository,
    tmdb: TmdbImageService,
    preferNextEpisodeStill: Boolean = false,
    settingsRepository: SettingsRepository? = null,
): SubjectCollectionInfo? {
    // 第一跳: 条目信息. 进程级普通缓存命中就不走网络 (见 TvHeroMediaCache.peekSubjectInfo).
    // 取消异常必须重抛 —— runCatching 会吞掉它, 让已取消的协程继续往下跑
    val chainStart = currentTimeMillis()
    val cachedInfo = TvHeroMediaCache.peekSubjectInfo(subjectId)
    val info = cachedInfo
        ?: try {
            collectionRepo.subjectCollectionFlow(subjectId).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }?.also { TvHeroMediaCache.putSubjectInfo(subjectId, it) }
        ?: return null
    // 三跳分开计时: "hero 背景慢"要能答出慢在哪一跳 —— 第一跳 (条目信息, 直连之后可能连着
    // /p1/subjects + /v0/episodes 两个请求) 还是第三跳 (TMDB 匹配). 服务层那行只盖第三跳.
    val hop1Millis = currentTimeMillis() - chainStart
    // 第二跳: "继续观看"的单集剧照 (只有那一行用). 放在整部 backdrop 之前: 它才是那一行 hero
    // 真正要显示的图, 不该排在兜底后面
    if (preferNextEpisodeStill && settingsRepository != null) {
        info.progressInfo.nextEpisodeIdToPlay?.let { nextEpisodeId ->
            tmdb.prefetchTvNextEpisodeMedia(
                subjectId, info.subjectInfo, info.episodes, nextEpisodeId, settingsRepository,
            )
        }
    }
    // 第三跳: 整部 backdrop. **有剧照也照拉** —— 详情页 Hero 一律用整部 backdrop, 它同时是
    // 进详情页的门控条件 (见各页 navigateToSubject), 跳过等于让那条路冷启
    val beforeBackdrop = currentTimeMillis()
    tmdb.prefetchTvBackdrop(
        subjectId,
        info.subjectInfo.name,
        activeAsOfDate = info.episodes.newestAiredDateStringOrNull(),
        hints = info.subjectInfo.toTmdbMatchHints(),
    )
    logger.info {
        "tvhero: subject $subjectId 解析完 ${currentTimeMillis() - chainStart}ms " +
                "(条目 ${if (cachedInfo != null) "热缓存" else "取了 ${hop1Millis}ms"}, " +
                "backdrop ${currentTimeMillis() - beforeBackdrop}ms)"
    }
    return info
}

private val logger = logger<TvHeroMediaCache>()

/**
 * **整部 backdrop 的预取**: 已解析过就直接返回, 否则解析一次 —— 结果自动落进服务层热表
 * ([TmdbImageService.peekBackdropUrl] 同步可读且快照可观察), 页面不必再自己记.
 *
 * 与详情页 Hero 同源, 所以这同时是**详情页的预取** —— 进详情页的门控条件就是问它有没有值
 * (见各页 `navigateToSubject`), 拿到剧照也不能跳过这一步.
 *
 * 正缓存永久有效, 每个条目全生命周期只会真的请求一次. **请求失败不写缓存**, 下次聚焦重试 ——
 * 不把瞬时断网当成"确认没有".
 *
 * @param originalName 必须传**原名 (日文)**: 中文译名在 TMDB 上命中率低, 而失败会写持久负缓存.
 * @param activeAsOfDate 该条目最新已播出集的日期. 新番刚播时 TMDB 往往还没有 backdrop,
 *   负缓存据此限期失效. 拿不到就传 null.
 */
suspend fun TmdbImageService.prefetchTvBackdrop(
    subjectId: Int,
    originalName: String,
    activeAsOfDate: String? = null,
    hints: TmdbMatchHints = TmdbMatchHints.Empty,
) {
    // **只短路正缓存**: peekBackdropResolved 对"已确认无图"也为真, 而服务层特意让负结果继续
    // 走 getBackdropUrl —— 是否该重取由 activeAsOfDate 与重取闸门判定 (见 TmdbImageService
    // 里那段注释). 在这里按"解析过就跳过"短路, 会把"搜索页先写了无图 (没有播出日期), 随后
    // 追番/时间表带着最新播出日期本该重试一次"的条目钉死到进程结束 —— 正是连载新番补图的场景.
    // 代价只有一次 dispatcher 跳转: 负缓存那条路读的是 DataStore 的内存副本, 且闸门保证
    // 每条目每进程最多放行一次网络重取.
    if (peekBackdropUrl(subjectId) != null) return
    // 取消必须重抛: runCatching 吞掉它的话, collectLatest 已取消的流水线会带着旧目标继续跑
    // 后面的邻居提交 (三个 prefetch 兄弟函数同此)
    try {
        getBackdropUrl(subjectId, originalName, activeAsOfDate = activeAsOfDate, hints = hints)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/**
 * **"下一集"单集剧照的预取**: 观看中的条目用它当 hero 背景, 直观提示播放进度节点.
 *
 * 连载番的永久缓存可能不含新播集, 传已播出最新集日期触发陈旧重取 (服务层闸门限频).
 *
 * 已经存着同一集的结果就跳过 —— 但看完一集后 `nextEpisodeId` 会变, 那时要重取.
 */
suspend fun TmdbImageService.prefetchTvNextEpisodeMedia(
    subjectId: Int,
    subjectInfo: SubjectInfo,
    episodes: List<EpisodeCollectionInfo>,
    nextEpisodeId: Int,
    settingsRepository: SettingsRepository,
) {
    if (TvHeroMediaCache.nextEpisodeMedia[subjectId]?.episodeId == nextEpisodeId) return
    val media = try {
        val language = (settingsRepository.uiSettings.flow.first().appLanguage ?: Locale.current)
            .toTmdbLanguage()
        // null = 这次没拿到 (网络失败). **不能当成"这一集没有剧照"** —— 写进下面那张进程缓存
        // 之后, 入口第一行的去重会让本进程再不重试, 一次抖动就把该条目的剧照永久降级成整部
        // backdrop, 而且毫无痕迹
        val stills = getEpisodeStills(
            subjectId, subjectInfo.name, language,
            newestWantedAirDate = episodes.newestAiredDateStringOrNull(),
            // 见 SubjectDetailsStateFactory 同名参数
            subjectAirDate = subjectInfo.airDate.toLocalDateOrNull()?.toString(),
            subjectEpisodeCount = episodes.size,
            subjectEpisodeNames = episodes.map { it.episodeInfo.name },
            hints = subjectInfo.toTmdbMatchHints(),
        ) ?: return
        stills.matchToEpisodes(episodes, subjectInfo.airDate.toLocalDateOrNull()?.toString())[nextEpisodeId]
    } catch (e: CancellationException) {
        throw e // 见 prefetchTvBackdrop: 吞掉取消会让已取消的流水线继续跑
    } catch (_: Exception) {
        return
    }
    TvHeroMediaCache.putNextEpisodeMedia(
        subjectId,
        TvNextEpisodeMedia(nextEpisodeId, media?.stillUrl, media?.overview),
    )
}

/**
 * **简介兜底**: Ani 服务器部分条目 summary 为空, 直连 bgm.tv 补 (仅替代不合并).
 *
 * 网络错误不写缓存 (getSummary 抛出): 下次聚焦该条目重试, 不把瞬时断网当"确认没有".
 */
suspend fun BangumiSummaryService.prefetchTvSummaryFallback(subjectId: Int) {
    if (subjectId in TvHeroMediaCache.summaryFallbacks) return
    val summary = try {
        getSummary(subjectId)
    } catch (e: CancellationException) {
        throw e // 见 prefetchTvBackdrop: 吞掉取消会让已取消的流水线继续跑
    } catch (_: Exception) {
        return
    }
    TvHeroMediaCache.putSummaryFallback(subjectId, summary.orEmpty())
}

/**
 * **hero 背景图的统一取值**: 单集剧照优先 (只有"继续观看/在看"的条目才有), 缺失回退整部
 * backdrop —— 后者直接读服务层热表 ([TmdbImageService.peekBackdropUrl], 快照可观察,
 * 预取落表即重组).
 *
 * 剧照按设置降档: 默认 w1280. 原图偶有 4K 级, 解码 8-33MB 是低端盒子每次换卡的重锤,
 * 而铺满后经渐隐压暗在 10-foot 距离不可辨. backdrop 那路服务层已是 w1280 档.
 *
 * 两级都没有时再回退竖版封面 ([coverUrl]), 与详情页同一套 —— 见那边 `heroBackdropUrl` 的
 * 说明: `ContentScale.Crop` 默认居中, 取的是海报中间那条横带; 封面用 Bangumi 的 l 档
 * (实测 1400~2700 px 宽), 压着 scrim 与底缘渐隐, 4K 面板上放大看不出来.
 */
fun TmdbImageService.tvHeroBackdropUrl(
    subjectId: Int?,
    fullVisualEffects: Boolean,
    /** true 时优先用"下一集"剧照 (继续观看行 / 在看的条目). */
    preferNextEpisodeStill: Boolean = false,
    /**
     * 前两级都没有时的回退: 该条目的竖版封面. null/空 = 不回退 (背景留空).
     *
     * **只在已确认没有横版图之后才用**: 解析途中就顶上去的话, 会先闪一下封面再被 TMDB 图
     * 换掉 —— 详情页踩过这个, 那边同样要等 `backdropResolved`.
     */
    coverUrl: String? = null,
    /**
     * **解析超时兜底**: true = 不再等"已确认没有"的结论, 前两级为空就直接上封面.
     *
     * 上一条"等结论"的规则在网络断掉时的代价是 hero 一直黑着 (2026-08-14 实测 24 秒) ——
     * 明明封面就在手边. 调用方在聚焦后计时, 超过 [tvHeroCoverFallbackMillis] 仍无结论
     * 才置位; 置位后剧照/backdrop 一旦到达仍按优先级顶掉封面.
     *
     * **这条路要尽量少走**: 竖版封面 Crop 进 16:9 是几倍上采样, 与真 backdrop 的清晰度差
     * 一眼可辨, 用户明确表态"除非真的没有 TMDB 图, 否则别出这个兜底". 所以门槛按"网络已经
     * 出事"来定 (见常量), 而不是按"稍慢一点"; 真·无图那条路 (peekBackdropResolved 为真而
     * URL 为空) 不受影响, 仍然立刻上封面 —— 那本来就是该条目的最终形态.
     */
    coverFallbackNow: Boolean = false,
): String? {
    if (subjectId == null) return null
    val stillEntry = if (preferNextEpisodeStill) TvHeroMediaCache.nextEpisodeMedia[subjectId] else null
    // **本页 (列表/网格) 恒 w1280 档, 原生 4K UI 上也不升**: 4K 的升档只给详情页那一张全屏
    // hero (在那边之上叠一层原图, 见 SubjectDetailsTvPage 的 HeroBackdropSharpeningOverlay).
    // 这里的 hero 跟着焦点换, 还带邻居预取, 升档的代价要乘上"每划过一张卡一次" —— 2026-08-21
    // 在 Shield 4K 上连本页一起升过, 实测净亏, 账记在 tmdbBackdropOriginalSizeUrl.
    // "完整视觉效果"那个开关不受影响: 它管的是剧照那一路要不要上原图, 与分辨率档位无关
    stillEntry?.stillUrl?.let { return tmdbStillHeroSizeUrl(it, fullVisualEffects) }
    peekBackdropUrl(subjectId)?.let { return it }
    // 剧照那一路也得先有结论: 只查过 backdrop 就回退, 剧照随后到达还是会闪一下
    val stillResolved = !preferNextEpisodeStill || stillEntry != null
    return coverUrl?.takeIf {
        it.isNotBlank() && (coverFallbackNow || (stillResolved && peekBackdropResolved(subjectId)))
    }
}

/**
 * 已经能拿到 hero 背景图 (或已确认没有) —— 进详情页的门控条件.
 * "已确认无图"也算备齐: 那种条目等下去也不会有图.
 */
fun TmdbImageService.tvHeroBackdropReady(subjectId: Int): Boolean = peekBackdropResolved(subjectId)
