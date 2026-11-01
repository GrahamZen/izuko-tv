/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.feedback.FeedbackLogSpec
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * 故障工单附件的来源 (见 [FeedbackTickets.Sources]): 性能报告 ([FeedbackPlatform.perfReport]), 日志 ([ReproLogBundle], 日志里是本地时间),
 * 照片 ([FeedbackPhotoStore]). 应用内的反馈弹窗与 Web 控制台共用.
 */
internal object FeedbackSources : FeedbackTickets.Sources {
    private val logger = logger<FeedbackSources>()

    @Volatile
    private var swept = false

    override fun perfRow(id: String) = FeedbackPlatform.perfReport(id)?.row

    override suspend fun buildLog(spec: FeedbackLogSpec, header: List<Pair<String, String>>): ByteArray? = withContext(Dispatchers.IO_) {
        val reports = spec.perfReports.mapNotNull(FeedbackPlatform::perfReport)
        val dir = FeedbackPlatform.logsDir()
        if (dir == null && reports.isEmpty()) return@withContext null
        val preface = reports.takeIf { it.isNotEmpty() }
            ?.joinToString("\n", postfix = "\n# ---- 应用日志 ----") { "# ---- 性能诊断报告 ${it.id} ----\n${it.text}" }
        val windows = listOfNotNull(spec.recorded).map { w -> localTime(w.from - spec.leadMinutes * 60_000L) to localTime(w.to) }
        val bundle = ReproLogBundle.build(dir, windows, header, preface = preface)
        logger.info { "ticket log: ${reports.size} reports, ${bundle.lines} log lines, ${bundle.gzip.size} bytes gzipped, truncated=${bundle.truncated}" }
        bundle.gzip
    }

    override suspend fun photo(id: String): ByteArray? = withContext(Dispatchers.IO_) { FeedbackPhotoStore.read(id) }

    override fun deletePhoto(id: String) = FeedbackPhotoStore.delete(id)

    /**
     * 读状态前的整理: 草稿里引用的东西不在了 (报告删了、照片没了、那段日志已经滚动删掉) 就去掉;
     * 进程里第一次读时把没人用的照片 (草稿去掉了、工单删了) 清一次.
     */
    suspend fun tidy(tickets: FeedbackTickets, current: suspend () -> FeedbackRecords) {
        val earliestLogDay = FeedbackPlatform.logsDir()?.let(ReproLogBundle::earliestRolledDay)
        tickets.pruneDrafts(
            reportExists = { FeedbackPlatform.perfReport(it) != null },
            photoExists = { FeedbackPhotoStore.file(it) != null },
            recordingGone = { w -> earliestLogDay != null && localTime(w.to).date < earliestLogDay },
        )
        if (swept) return
        swept = true
        val all = current()
        FeedbackPhotoStore.sweep(
            all.drafts.flatMap { d -> d.photos.map { it.id } }.toSet() + all.items.flatMap { r -> r.uploads.mapNotNull { it.photoId } },
        )
    }

    fun localTime(millis: Long): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
}
