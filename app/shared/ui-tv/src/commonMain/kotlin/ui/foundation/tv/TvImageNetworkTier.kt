/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/*
 * hero 背景图各档等待时长的分档依据.
 *
 * ## 为什么要分档
 *
 * 这几个阈值原本是在千兆局域网 + Shield 上调的, 而它们在快网与慢网上的**最优解正好相反**:
 *
 * | | 快网 | 慢网 |
 * |---|---|---|
 * | 封面兜底 | 等久点 (6s): 2.5s 还没图基本是单流假死, 上一张糊封面纯属添乱, 用户报过"兜底太频繁" | 早点上 (2.5s): 6s 是真的会等到, 但盯 6 秒空背景比看糊图更难受 |
 * | 背景图重发 | 4s: 卡这么久就是挂死了, 重发白捡一次机会 | 12s: 4s 只是"还在下", 这一发是在跟第一发抢同一个 host 的连接额度 |
 * | 预热并发 | 3: 不影响可见图 | 1: 等于从正在显示的那张嘴里抢带宽 |
 *
 * 所以**只有这三个需要分档**; 其余改动 (补下队列丢新不丢旧、1×1 预热、TMDB 单飞) 两种网络下
 * 都只有好处, 保持统一, 别为了对称而分档.
 *
 * ## 分档信号: 实测, 不猜
 *
 * 不读 `ConnectivityManager` 那套标称带宽 (它在电视盒子上基本是摆设), 而是拿
 * [TvHeroImagePrefetch] 自己已经在记的耗时做 EWMA —— 量的就是"这台机器上下一张 hero 背景图
 * 实际要多久", 与要调的东西是同一件事, 而且会在会话中途跟着网络变化自己走。
 *
 * **只记真正走网络的那些** ([record] 的调用点已按 `DataSource.NETWORK` 过滤): 磁盘命中是
 * 20~140ms, 图一热起来就会把慢网压成快网的读数.
 *
 * 基线取自代码里既有的实测: 正常预热 250~600ms (类文档写的 255~538ms). 因此进慢档定在
 * [SLOW_ENTER_MILLIS], 回快档定在 [SLOW_EXIT_MILLIS], 中间留一段迟滞, 免得在边界上反复横跳
 * —— 阈值一跳, 用户看到的就是兜底时机忽长忽短.
 *
 * **没有样本时默认快档**, 但**预热并发是例外**: 见 [tvHeroImagePrefetchConcurrency].
 */

/** 一次网络取图的耗时超过这个数就往慢档走. 正常是 250~600ms, 留了两倍以上余量. */
private const val SLOW_ENTER_MILLIS = 1_500L

/** 已在慢档时, 要回到这个数以下才判回快档 (迟滞, 防止边界抖动). */
private const val SLOW_EXIT_MILLIS = 800L

/**
 * 失败耗时低于这个数就不记: 404 / 立即 reset / DNS 失败都是"没传成字节"的快速失败, 不是带宽信号.
 */
private const val FAILURE_IGNORE_BELOW_MILLIS = 1_000L

/**
 * 失败样本的封顶值 (= 进慢档门槛的两倍).
 *
 * 一次 10s 假死若原样记进 EWMA, 按 1/4 权重要**十几个**快样本才能降回快档 —— 等于一次抖动就把
 * 档位钉死好几分钟. 封顶之后: 冷启动第一条就是失败 → 直接进慢档 (符合"一次明显超时就该保守");
 * 已经在快档时要连着两次失败才翻, 而回快档只需四五张正常图.
 */
private const val FAILURE_SAMPLE_CAP_MILLIS = SLOW_ENTER_MILLIS * 2

/** EWMA 权重: 新样本占 1/4, 大约四五张图就能跟上网络变化, 又不会被单张抖动带偏. */
private const val EWMA_NEW_WEIGHT = 1
private const val EWMA_OLD_WEIGHT = 3

/**
 * 当前网络档位. 由 [TvImageNetworkSpeed] 按实测耗时判定, 见本文件顶部说明.
 */
enum class TvImageNetworkTier { FAST, SLOW }

private val logger = logger("TvImageNetworkSpeed")

object TvImageNetworkSpeed {
    /**
     * 预热跑在 `Dispatchers.Default` 上写, 组合在主线程读 —— 与 [TvHeroMediaCache] 同一套加锁
     * 方式 (读一个 Long 在 JVM 上本就是原子的, 加锁是为了 EWMA 的读-算-写不被穿插).
     */
    private val lock = SynchronizedObject()

    /** 网络取图耗时的 EWMA, 毫秒; -1 = 还没有样本. */
    private var ewmaMillis = -1L
    private var slow = false

    /** 有没有任何一次预热**跑完过** (含缓存命中: 那也说明这次不必等网络). 见 [probed]. */
    private var anyProbeDone = false

    val tier: TvImageNetworkTier
        get() = synchronized(lock) { if (slow) TvImageNetworkTier.SLOW else TvImageNetworkTier.FAST }

    /**
     * 冷启动的"未知期"已经结束 = 至少有一次预热跑完了.
     *
     * 与"有没有 EWMA 样本"不是一回事: 缓存命中不产生 EWMA 样本 (会把慢网压成快网的读数),
     * 但它同样证明这条路当下不堵. 用 EWMA 判未知期的话, 磁盘全热的场景会永远停在未知期.
     */
    val probed: Boolean get() = synchronized(lock) { anyProbeDone }

    /** 一次预热跑完了 (成功/失败/缓存命中都算; 被取消的不算). 只用来结束未知期. */
    fun noteProbeDone() {
        synchronized(lock) { anyProbeDone = true }
    }

    /**
     * 记一次**走了网络**的取图耗时. 磁盘/内存命中不要记 (见文件头).
     */
    fun record(durationMillis: Long) = record(durationMillis, failed = false)

    /**
     * 记一次**失败**的取图 (ErrorResult / 异常 / 超时).
     *
     * 没有这条的话, 最该进慢档的网络 —— 连续超时、连接重置 —— 一个样本都产生不了, 分档器会
     * 永远停在快档并继续开 3 条投机请求, 恰好在坏网络上加剧竞争和重复请求.
     */
    fun recordFailure(durationMillis: Long) {
        if (durationMillis < FAILURE_IGNORE_BELOW_MILLIS) return
        record(minOf(durationMillis, FAILURE_SAMPLE_CAP_MILLIS), failed = true)
    }

    private fun record(durationMillis: Long, failed: Boolean) {
        if (durationMillis <= 0) return
        synchronized(lock) {
            anyProbeDone = true
            val old = ewmaMillis
            if (old < 0) {
                // 打一次首样本: 否则"没有翻档日志"有两种解释 —— 网确实快, 或者这里压根没被调到
                // (比如 DataSource.NETWORK 那个判断写错了). 验证手段必须能证伪.
                logger.info { "Image network tier: first sample ${durationMillis}ms${if (failed) " (failed)" else ""} (staying FAST until >${SLOW_ENTER_MILLIS}ms)" }
            }
            ewmaMillis = if (old < 0) {
                durationMillis
            } else {
                (old * EWMA_OLD_WEIGHT + durationMillis * EWMA_NEW_WEIGHT) /
                        (EWMA_OLD_WEIGHT + EWMA_NEW_WEIGHT)
            }
            // 迟滞: 进慢档与回快档用不同的门槛
            val next = if (slow) ewmaMillis > SLOW_EXIT_MILLIS else ewmaMillis > SLOW_ENTER_MILLIS
            if (next != slow) {
                slow = next
                // 只在翻档时记一行: 这几个阈值全是暗的, 没有它就无从判断线上到底跑在哪一档
                logger.info {
                    "Image network tier -> ${if (next) "SLOW" else "FAST"} " +
                            "(ewma=${ewmaMillis}ms, sample=${durationMillis}ms${if (failed) " failed" else ""}): " +
                            "coverFallback=${tvHeroCoverFallbackMillis()}ms " +
                            "prefetch=${tvHeroImagePrefetchConcurrency()}"
                }
            }
        }
    }

    /** 仅供日志/诊断. */
    fun peekEwmaMillis(): Long = synchronized(lock) { ewmaMillis }
}

/**
 * 聚焦后等这么久还没有任何横版图结论, 就先拿竖版封面当背景, 见 `tvHeroBackdropUrl`.
 *
 * 快档 6s / 慢档 2.5s, 理由见文件头的对照表.
 */
fun tvHeroCoverFallbackMillis(): Long = when (TvImageNetworkSpeed.tier) {
    TvImageNetworkTier.FAST -> 6_000L
    TvImageNetworkTier.SLOW -> 2_500L
}

/**
 * 背景图预热的在途上限, 见 `TvHeroImagePrefetch`.
 *
 * 快档 3 / 慢档 1. 与显示中的主图共用同一 host 的连接额度 —— 慢档上多开一条就是从可见的
 * 那张嘴里抢, 而预热的是"可能要用"的邻居, 优先级本来就更低.
 *
 * **未知期 (还没有任何一次预热跑完) 也按 1**, 这是唯一一个不跟随"无样本默认快档"的:
 * 网络越差, 第一条样本回来得越晚 —— 而档位正是靠它定的. 冷启动那几秒里按快档开 3 条投机
 * 请求, 恰好发生在最不该拥塞的时刻 (什么都没缓存, 主图也在下). 未知期只放一条探针, 首样本
 * 一到就由 `drainPending` 自动补到 3.
 *
 * 代价只有快网上首批邻居图晚几百毫秒 (探针本身 250~600ms 就回来); 换来的是坏网上冷启动
 * 不会自己把带宽先切成四份.
 */
fun tvHeroImagePrefetchConcurrency(): Int = when {
    !TvImageNetworkSpeed.probed -> 1
    TvImageNetworkSpeed.tier == TvImageNetworkTier.FAST -> 3
    else -> 1
}
