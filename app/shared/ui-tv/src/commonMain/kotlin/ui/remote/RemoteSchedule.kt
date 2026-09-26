/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.displayName
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import me.him188.ani.app.domain.episode.EpisodeWithAiringTime
import me.him188.ani.app.domain.episode.GetAnimeScheduleFlowUseCase
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * `api/schedule?day=1..7`: 挑番面板「新番时间表」, 电视所在这一周 (周一到周日) 每天播出的番.
 *
 * 数据与电视上的新番时间表同一个来源 ([GetAnimeScheduleFlowUseCase]): 先按落盘缓存出一版, 再从今天往两边一天天补齐.
 * 读取挂在自己的作用域上, 不跟着 HTTP 请求取消: 请求等到手机正在看的那天 (`day`) 补完就回, 其余几天在后台接着补,
 * 还没补完的那天带 `pending`, 手机过一会儿再来要. 这一轮读完之后再来的请求重新读一轮 (缓存齐全时第一版就是全的).
 * 行的格式同「在看 / 想看」(见 [RemoteCollections]), 手机上用同一个行样式; 自己在看 / 想看的番在小字里标出来.
 */
internal object RemoteSchedule {
    private val logger = logger<RemoteSchedule>()
    private val scheduleUseCase: GetAnimeScheduleFlowUseCase get() = KoinPlatform.getKoin().get()
    private val collectionRepository: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteSchedule"))
    private val lock = Any()

    /** 一轮读取到哪了: [days] 为 `null` = 名册还没回来; [error] = 这一轮中途失败 (没补完的那几天补不上了). */
    private data class Progress(
        val days: List<AiringScheduleForDate>? = null,
        val finished: Boolean = false,
        val error: Throwable? = null,
    )

    private class Load(val today: LocalDate, val timeZone: TimeZone, val job: Job, val progress: MutableStateFlow<Progress>)

    private var current: Load? = null

    fun schedule(request: LanHttpRequest): JsonObject {
        val timeZone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(timeZone).date
        val week = (1..7).map { today.plus(it - today.dayOfWeek.isoDayNumber, DateTimeUnit.DAY) }
        val weekday = request.query.split('&').firstOrNull { it.startsWith("day=") }?.removePrefix("day=")?.toIntOrNull()
        val wanted = week.firstOrNull { it.dayOfWeek.isoDayNumber == weekday } ?: today
        val progress = start(today, timeZone)
        val state = runBlocking {
            withTimeoutOrNull(WAIT) {
                progress.first { p -> p.finished || p.days?.firstOrNull { it.date == wanted }?.pending == false }
            }
        } ?: progress.value
        val days = state.days.orEmpty()
        val followed = followedTypes()
        return buildJsonObject {
            put("ok", true)
            put("today", today.dayOfWeek.isoDayNumber)
            state.error?.let { put("failed", tr("读取失败：{0}", it.message ?: it::class.simpleName)) }
            putJsonArray("days") {
                for (date in week) {
                    val day = days.firstOrNull { it.date == date }
                    addJsonObject {
                        put("weekday", date.dayOfWeek.isoDayNumber)
                        put("date", date.toString())
                        put("pending", day?.pending ?: true)
                        putJsonArray("items") {
                            // 同一天播两集的只列一行 (行按条目认, 点进去是同一部)
                            for (entry in day?.list.orEmpty().distinctBy { it.subject.subjectId }) {
                                add(item(entry, followed[entry.subject.subjectId], timeZone))
                            }
                        }
                    }
                }
            }
        }
    }

    /** 同一天的这一轮还在读就接着等它; 已经读完 (或换了一天 / 时区) 就重新读一轮. */
    private fun start(today: LocalDate, timeZone: TimeZone): MutableStateFlow<Progress> = synchronized(lock) {
        current?.let { load ->
            if (load.today == today && load.timeZone == timeZone && !load.progress.value.finished) return load.progress
            load.job.cancel()
        }
        val progress = MutableStateFlow(Progress())
        val job = scope.launch {
            try {
                scheduleUseCase(today, timeZone).collect { days -> progress.update { it.copy(days = days) } }
                progress.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Remote schedule: loading failed" }
                progress.update { it.copy(finished = true, error = e) }
            }
        }
        current = Load(today, timeZone, job, progress)
        progress
    }

    private fun item(entry: EpisodeWithAiringTime, type: UnifiedCollectionType?, timeZone: TimeZone): JsonObject {
        val subject = entry.subject
        val time = entry.airingTime.toLocalDateTime(timeZone).time
        val line = listOfNotNull(
            if (entry.timeKnown) time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0') else null,
            episodeText(entry.episode),
            when (type) {
                UnifiedCollectionType.DOING -> tr("在看")
                UnifiedCollectionType.WISH -> tr("想看")
                else -> null
            },
        ).joinToString(" · ")
        return buildJsonObject {
            put("id", subject.subjectId)
            put("title", subject.displayName)
            put("line", line)
            // 自己在看 / 想看的番用主色标出来, 在一整天的列表里一眼找到
            if (type != null) put("fresh", true)
            putJsonArray("cover") { remoteCoverCandidates(subject.subjectId, subject.imageLarge).forEach { add(it) } }
        }
    }

    /** 同电视时间表 (ScheduleItem.Episode): 本季序号与总序号不同时括号里带上总序号. */
    private fun episodeText(episode: LightEpisodeInfo): String {
        val sort = episode.sort.toString().removePrefix("0")
        val ep = episode.ep?.takeIf { it != episode.sort }?.toString()?.removePrefix("0")
            ?: return tr("第 {0} 话", sort)
        return tr("第 {0} 话", ep) + " ($sort)"
    }

    /** subjectId → 在看 / 想看. 本地库, 不联网 (同电视时间表页). */
    private fun followedTypes(): Map<Int, UnifiedCollectionType> = runBlocking {
        withTimeoutOrNull(READ_TIMEOUT) {
            buildMap {
                for (type in listOf(UnifiedCollectionType.DOING, UnifiedCollectionType.WISH)) {
                    for (id in collectionRepository.getSubjectIdsByCollectionType(listOf(type)).first()) putIfAbsent(id, type)
                }
            }
        }.orEmpty()
    }

    /** 一个请求最多等这么久 (第一次没有落盘缓存时要逐部去 bangumi 拿分集), 超过就先回已有的, 后台接着补. */
    private val WAIT = 8.seconds
    private val READ_TIMEOUT = 5.seconds
}
