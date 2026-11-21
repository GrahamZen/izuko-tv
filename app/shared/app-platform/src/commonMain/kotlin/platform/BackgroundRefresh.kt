/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 应用在后台被系统叫醒时跑的活 (iOS 的后台刷新, 见 application 的 IosBackgroundRefresh): 各处按名字登记一件 ([register]),
 * 一次叫醒把登记的按 [Stage] 先后跑完 (同一段的同时跑), 整次限时. 每一次跑都记一行 ([runs], 存在 [install] 给的文件里),
 * 用来看系统多久叫一次、每件事成没成.
 *
 * 系统什么时候叫、一天叫几次全由系统定 (看用户平时用得多不多), 这里只管被叫到时做什么.
 */
object BackgroundRefresh {
    /** 先后: 先取东西, 再按取到的发通知. */
    enum class Stage { Fetch, Notify }

    /**
     * 一件活. [run] 回一句给记录看的话 (如「新 2 条」), 没什么可说回 null; 抛异常记成失败.
     */
    class Job(val name: String, val stage: Stage, val run: suspend () -> String?)

    /** 一次跑的记录. [reason]: system = 系统叫醒, debug = 调试接口, 等等. [results]: 每件活一句 (名字: ok / 那句话 / failed / timeout). */
    data class Run(val atMillis: Long, val reason: String, val durationMillis: Long, val results: List<String>)

    /** 系统给一次叫醒约 30 秒, 留出收尾的时间 */
    val DEFAULT_BUDGET: Duration = 25.seconds

    private const val MAX_RUNS = 100

    private val logger = logger<BackgroundRefresh>()
    private val jobs = MutableStateFlow<List<Job>>(emptyList())
    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    private val lock = Mutex()

    @Volatile
    private var logFile: SystemPath? = null

    /** 向系统要叫醒的那一层 (iOS 的 IosBackgroundRefresh); 没有后台刷新的平台为 null. 调试接口用它改间隔、马上再要一次. */
    interface Scheduler {
        /** 跑完之后最早过多久再叫 */
        val interval: Duration

        fun setInterval(value: Duration)

        /** 向系统要一次叫醒; 系统收下为 true */
        fun schedule(): Boolean

        /** 上一次要叫醒的情况 (什么时候要的、最早几点、收没收), 给人看的一句; 还没要过为 null */
        val lastSubmitText: String?
    }

    @Volatile
    var scheduler: Scheduler? = null

    /** 最近几次跑的记录, 新的在前. */
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    /** 登记过的活的名字 (调试用). */
    val jobNames: List<String> get() = jobs.value.map { "${it.stage}:${it.name}" }

    /** 记录存在 [file] 里 (读出以前的). 一个进程一次, 启动时调. */
    fun install(file: SystemPath) {
        logFile = file
        _runs.value = runCatching { file.readText() }.getOrNull()?.let(::decode).orEmpty()
    }

    /** 登记一件活; 同名的替换. */
    fun register(job: Job) {
        jobs.value = jobs.value.filter { it.name != job.name } + job
    }

    internal fun unregister(name: String) {
        jobs.value = jobs.value.filter { it.name != name }
    }

    /**
     * 把登记的活跑一遍, 整次最多 [budget]; 同一时间只跑一次 (后来的等前一次跑完再跑). 回这一次的记录.
     */
    suspend fun runAll(reason: String, budget: Duration = DEFAULT_BUDGET): Run = lock.withLock {
        val start = currentTimeMillis()
        val all = jobs.value
        // 跑完一件记一件: 整次超时时, 已经跑完的照样记下
        val done = MutableStateFlow<List<Pair<String, String>>>(emptyList())
        val finished = withTimeoutOrNull(budget) {
            for (stage in Stage.entries) {
                coroutineScope {
                    all.filter { it.stage == stage }.map { job ->
                        async {
                            val result = runJob(job)
                            done.update { it + (job.name to result) }
                        }
                    }.awaitAll()
                }
            }
        }
        val results = done.value.map { (name, result) -> "$name: $result" }.toMutableList()
        if (finished == null) {
            val unfinished = all.map { it.name } - done.value.map { it.first }.toSet()
            results += "timeout after ${budget.inWholeSeconds}s: ${unfinished.joinToString()}"
        }
        val run = Run(start, reason, currentTimeMillis() - start, results)
        logger.info { "Background refresh ($reason) done in ${run.durationMillis}ms: $results" }
        _runs.value = (listOf(run) + _runs.value).take(MAX_RUNS)
        logFile?.let { file -> runCatching { file.writeText(encode(_runs.value)) } }
        run
    }

    private suspend fun runJob(job: Job): String = try {
        job.run() ?: "ok"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.info { "Background job ${job.name} failed: $e" }
        "failed (${e::class.simpleName}: ${e.message?.take(80)})"
    }

    /** 一行一次: 时刻、原因、用时、各件活 (用 | 隔开). 字段里不会有制表符与换行. */
    internal fun encode(runs: List<Run>): String = runs.joinToString("\n") { run ->
        listOf(run.atMillis.toString(), run.reason, run.durationMillis.toString(), run.results.joinToString(" | ") { it.clean() })
            .joinToString("\t")
    }

    internal fun decode(text: String): List<Run> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4) return@mapNotNull null
        Run(
            atMillis = parts[0].toLongOrNull() ?: return@mapNotNull null,
            reason = parts[1],
            durationMillis = parts[2].toLongOrNull() ?: 0,
            results = parts[3].split(" | ").filter { it.isNotEmpty() },
        )
    }.take(MAX_RUNS).toList()

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').replace(" | ", " / ")
}
