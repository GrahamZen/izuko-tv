/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tv

import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.R
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.subject.displayName
import me.him188.ani.app.data.repository.subject.SubjectNsfw
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import me.him188.ani.app.domain.episode.EpisodeWithAiringTime
import me.him188.ani.app.domain.episode.GetAnimeScheduleFlowUseCase
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 系统主屏自建的「今日更新」预览频道: 新番时间表今天那一格, 按播出时刻排, 卡片写「20:00 更新 · 第 8 集 · 分集名」, 点了进条目详情页.
 *
 * 数据与 app 内的时间表同源 ([GetAnimeScheduleFlowUseCase], 先出磁盘缓存再逐部补分集): 等今天那一格补完再写;
 * 超时还没补完就先写已有的, 过 [RETRY_INTERVAL] 再来. 进程起来后写一次, app 一直开着的话每过零点重写. 内容没变不动 TvProvider.
 */
internal object TvHomeTodayChannel {
    private val logger = logger<TvHomeTodayChannel>()

    private const val CHANNEL_INTERNAL_ID = "animeko_today"

    /** 启动后先让开: 冷启动时各页自己的请求更要紧. */
    private val START_DELAY = 15.seconds
    private val SCHEDULE_TIMEOUT = 2.minutes
    private val RETRY_INTERVAL = 30.minutes

    /** 两个 activity 交替时只跑一份. */
    private val running = AtomicBoolean(false)

    /** 查找或创建频道; 第一次创建时请求 launcher 显示它, 要 activity 的 context. */
    fun ensureChannel(context: Context): Long =
        TvHomeChannels.ensureChannel(context, CHANNEL_INTERNAL_ID, context.getString(R.string.home_channel_today_name))

    /** 不会返回 (同一进程已经有一份在跑时直接返回). */
    suspend fun keepUpdated(context: Context, koin: Koin, channelId: Long) {
        if (!running.compareAndSet(false, true)) return
        try {
            delay(START_DELAY)
            while (true) {
                val timeZone = TimeZone.currentSystemDefault()
                val today = Clock.System.now().toLocalDateTime(timeZone).date
                val complete = try {
                    update(context, koin, channelId, today, timeZone)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update today channel" }
                    false
                }
                val untilTomorrow = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone) - Clock.System.now() + 1.minutes
                delay(if (complete) untilTomorrow else minOf(RETRY_INTERVAL, untilTomorrow))
            }
        } finally {
            running.set(false)
        }
    }

    /** 写今天那一格; 返回它是不是补全了. */
    private suspend fun update(context: Context, koin: Koin, channelId: Long, today: LocalDate, timeZone: TimeZone): Boolean {
        var latest: AiringScheduleForDate? = null
        withTimeoutOrNull(SCHEDULE_TIMEOUT) {
            koin.get<GetAnimeScheduleFlowUseCase>()(today, timeZone)
                .mapNotNull { days -> days.firstOrNull { it.date == today } }
                .onEach { latest = it }
                .first { !it.pending }
        }
        val day = latest ?: return false
        // 没有海报的不放 (卡片会是一块空白); NSFW 作品在设置不是「显示」时不放 (主屏打不了码, 见 SubjectNsfw)
        val programs = day.list
            .filter { it.subject.imageLarge.isNotBlank() && SubjectNsfw.modeOf(it.subject.subjectId) == NsfwMode.DISPLAY }
            .map { it.toTodayProgram(context, timeZone) }
        withContext(Dispatchers.IO_) { write(context, channelId, programs) }
        return !day.pending
    }

    /** 频道里的一张卡; [key] 是 `条目:分集`, 写进 internalProviderId. */
    private data class TodayProgram(
        val subjectId: Int,
        val key: String,
        val title: String,
        val episodeTitle: String,
        val episodeNumber: EpisodeSort.Normal?,
        val description: String,
        val posterUri: String,
    )

    private fun EpisodeWithAiringTime.toTodayProgram(context: Context, timeZone: TimeZone): TodayProgram {
        val airs = if (timeKnown) {
            val time = airingTime.toLocalDateTime(timeZone)
            context.getString(R.string.home_channel_today_airs_at, "%02d:%02d".format(time.hour, time.minute))
        } else {
            context.getString(R.string.home_channel_today_airs_today)
        }
        // 与 app 内时间表一致: 先写季内集号
        val number = (episode.ep ?: episode.sort) as? EpisodeSort.Normal
        val episodeName = episode.nameCn.ifBlank { episode.name }
        return TodayProgram(
            subjectId = subject.subjectId,
            key = "${subject.subjectId}:${episode.episodeId}",
            title = subject.displayName,
            episodeTitle = episodeName,
            episodeNumber = number,
            description = listOfNotNull(
                airs,
                number?.let { context.getString(R.string.home_channel_episode, it.plainNumber()) },
                episodeName.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            posterUri = subject.imageLarge,
        )
    }

    /** 全量重写本频道的节目 (先删再插); 与现有的一样就不动. */
    private fun write(context: Context, channelId: Long, programs: List<TodayProgram>) {
        val resolver = context.contentResolver
        val existing = ArrayList<PreviewProgram>()
        resolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = PreviewProgram.fromCursor(cursor)
                if (program.channelId == channelId) existing += program
            }
        }
        val existingShape = existing.sortedByDescending { it.weight }
            .map { listOf(it.internalProviderId, it.title, it.description, it.posterArtUri?.toString()) }
        val wantedShape = programs.map { listOf(it.key, it.title, it.description, it.posterUri) }
        if (existingShape == wantedShape) {
            logger.info { "Today channel unchanged (${programs.size} programs), skipped" }
            return
        }

        for (program in existing) {
            resolver.delete(TvContractCompat.buildPreviewProgramUri(program.id), null, null)
        }
        programs.forEachIndexed { index, program ->
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                .setTitle(program.title)
                .setDescription(program.description)
                .setPosterArtUri(Uri.parse(program.posterUri))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setIntentUri(TvHomeChannels.subjectDeepLink(program.subjectId))
                .setInternalProviderId(program.key)
                // launcher 按 weight 降序排, 保持播出时刻的先后
                .setWeight(programs.size - index)
            if (program.episodeTitle.isNotBlank()) builder.setEpisodeTitle(program.episodeTitle)
            program.episodeNumber?.let { builder.setEpisodeNumber(it.plainNumber(), it.number.toInt()) }
            resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, builder.build().toContentValues())
        }
        logger.info { "Today channel updated: ${programs.size} programs" }
    }
}
