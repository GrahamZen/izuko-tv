/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.fetch.isFinal
import me.him188.ani.app.domain.media.selector.MatchMetadata.SubjectMatchKind
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 一次自动选源在等什么 (测开播耗时用): 每次输入变了, 记下当时的阶段、决定、选源上下文还缺哪些字段、
 * 在线源查完几个、条目名精确匹配的候选有几个 (其中来自已查完的源的几个). 与上一条相同就不记.
 * 同开播时间线 ([PlayTimeline]), 只在开了调试开关时记.
 */
internal class AutoSelectTrace {
    private val start = TimeSource.Monotonic.markNow()
    private var last: String? = null
    private var sourcesSeen = false

    /** 各数据源的状态与结果第一次一起到了 (快照要等每个源都发过一次) */
    fun sourcesEmitted(sourceCount: Int) {
        if (!PlayTimeline.enabled || sourcesSeen) return
        sourcesSeen = true
        logger.info { "Auto select trace +${start.elapsedNow().inWholeMilliseconds}ms: first source snapshot ($sourceCount sources)" }
        PlayTimeline.mark("auto_first_snapshot", "$sourceCount sources")
    }

    fun observe(snapshot: MediaAutoSelectSnapshot, stage: String, decision: String) {
        if (!PlayTimeline.enabled) return
        val context = snapshot.context
        val missing = buildList {
            if (context.subjectFinished == null) add("subjectFinished")
            if (context.mediaSourcePrecedence == null) add("precedence")
            if (context.subtitlePreferences == null) add("subtitles")
            if (context.subjectSeriesInfo == null) add("seriesInfo")
            if (context.subjectInfo == null) add("subjectInfo")
            if (context.episodeInfo == null) add("episodeInfo")
            if (context.mediaSourceTiers == null) add("tiers")
        }
        val web = snapshot.sources.filter { it.kind == MediaSourceKind.WEB }
        val finished = web.filter { it.state.isFinal }.mapTo(HashSet()) { it.mediaSourceId }
        val exact = snapshot.candidates.filter {
            it.result.kind == MediaSourceKind.WEB && it.metadata.subjectMatchKind == SubjectMatchKind.EXACT
        }
        val summary = "stage=$stage decision=$decision missing=$missing web=${finished.size}/${web.size} " +
                "exact=${exact.size} exactFinished=${exact.count { it.result.mediaSourceId in finished }}"
        if (summary == last) return
        last = summary
        logger.info { "Auto select trace +${start.elapsedNow().inWholeMilliseconds}ms: $summary" }
        PlayTimeline.mark("auto_decide", summary)
    }

    companion object {
        private val logger = logger<AutoSelectTrace>()

        /** 进自动选源之前的一步做完了 (接着播放线索、等本地缓存、回放浏览记忆、读设置). */
        fun step(label: String) {
            if (!PlayTimeline.enabled) return
            logger.info { "Auto select step: $label" }
            PlayTimeline.mark("auto_step", label)
        }

        /**
         * 在 [scope] 里量 [duration] 这么久的 `Dispatchers.Default` 排队: 每秒从 IO 线程往 Default 投一个空任务, 记它等了多久才开始跑;
         * 超过 300 毫秒的逐条记, 最后记一条总的. 没开调试开关时不量, 返回 null.
         */
        fun probeDefaultDispatcher(scope: CoroutineScope, duration: Duration = 60.seconds): Job? = if (!PlayTimeline.enabled) null else scope.launch(Dispatchers.IO) {
            val start = TimeSource.Monotonic.markNow()
            var count = 0
            var worst = Duration.ZERO
            var total = Duration.ZERO
            while (start.elapsedNow() < duration) {
                val queued = TimeSource.Monotonic.markNow()
                withContext(Dispatchers.Default) {}
                val waited = queued.elapsedNow()
                count++
                total += waited
                if (waited > worst) worst = waited
                if (waited >= 300.milliseconds) {
                    logger.info { "Default dispatcher +${start.elapsedNow().inWholeMilliseconds}ms: a task waited ${waited.inWholeMilliseconds}ms" }
                    PlayTimeline.mark("default_queue", "${waited.inWholeMilliseconds}ms")
                }
                delay(1.seconds)
            }
            logger.info { "Default dispatcher over $duration: $count probes, mean ${(total / count).inWholeMilliseconds}ms, worst ${worst.inWholeMilliseconds}ms" }
        }

        /** 算一份快照 (筛选 + 排序全部资源) 用了多久; 不到 200 毫秒不记. */
        fun snapshotComputed(mediaCount: Int, elapsed: Duration) {
            if (PlayTimeline.enabled && elapsed >= 200.milliseconds) {
                logger.info { "Auto select snapshot: $mediaCount media in ${elapsed.inWholeMilliseconds}ms" }
                PlayTimeline.mark("auto_snapshot_slow", "$mediaCount media ${elapsed.inWholeMilliseconds}ms")
            }
        }
    }
}
