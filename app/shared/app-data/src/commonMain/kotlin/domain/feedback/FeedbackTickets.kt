/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.feedback.FeedbackDevice
import me.him188.ani.app.data.models.feedback.FeedbackDraft
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackLogSpec
import me.him188.ani.app.data.models.feedback.FeedbackPhoto
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecording
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.models.feedback.FeedbackUpload
import me.him188.ani.app.data.models.feedback.FeedbackWindow
import me.him188.ani.app.data.network.FeedbackTicketService
import me.him188.ani.app.data.network.FeedbackTicketService.CreateResult
import me.him188.ani.app.data.network.FeedbackTicketService.FileSpec
import me.him188.ani.app.data.network.FeedbackTicketService.Outcome
import me.him188.ani.app.data.network.SubjectFeedbackService.IssueStatus
import me.him188.ani.app.data.network.TmdbSubjectMapEntry
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 故障工单 (Web 控制台「设置 → 反馈」): 写反馈、附上数据、提交、查处理结果、接着补充. 请求见 [FeedbackTicketService], 记录存在 [FeedbackRecordRepository].
 *
 * - 一条反馈 (新工单, 或给一张单的补充) 的附件先攒在草稿里 ([FeedbackDraft], 按 target 各一份):
 *   - 现场录制: [startRecording] 记下开始, 复现完 [stopRecording]; 最长 [MAX_RECORDING], 到点按到点那一刻算结束 ([expireRecording]).
 *     日志默认只截录制期间, 可以往前多截几分钟 ([setLogLead], 最多 [MAX_LOG_LEAD_MINUTES]).
 *   - 性能诊断报告 ([attachPerf], 控制台「维护 → 性能诊断」里已有的体检 / 录制), 照片 ([addPhoto]).
 * - 新工单必须带现场录制 ([submit]); 补充 ([reply]) 可以只有文字或照片. 附了录制或报告就带一份日志 (`log.gz`: 报告原文在前, 录制那段的日志在后).
 * - 附件的字节由调用方给 ([Sources], 平台相关): 开单 / 补充时先向服务声明, 之后在后台逐个传 ([uploading]);
 *   没传上去 (限频、连不上) 隔一会儿自动再试 ([UPLOAD_ATTEMPTS]), 还不行留在 [FeedbackRecord.uploads], 用户在控制台点重传 ([retryUploads]).
 * - 处理结果每天查一次 ([start] 起的后台循环, 只查开着的单); 有新的维护者回复、单关了时标成未读, 控制台上提示.
 *   用户在控制台点「检查回复」时也查 ([syncNow], 一分钟内只查一次).
 */
class FeedbackTickets(
    private val records: FeedbackRecordRepository,
    private val service: FeedbackTicketService,
    private val device: FeedbackDevice,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { currentTimeMillis() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** 详情页提交的标题 logo / 对应作品修正的处理结果 (经那边的中转, 见 SubjectFeedbackService.status); `null` = 不查. */
    private val subjectStatus: (suspend (List<Int>) -> List<IssueStatus>?)? = null,
    /** 对应表里一个条目 (TmdbSubjectMapRepository.lookup); 跟着别人提的修正请求看它推断结果 (见 [JoinedCorrectionOutcome]). `null` = 不推断, 都去查. */
    private val subjectMap: (suspend (subjectId: Int) -> TmdbSubjectMapEntry?)? = null,
) {
    /** 有工单服务可用 (见 [FeedbackTicketService.available]). */
    val available: Boolean get() = service.available

    /** 附件的来源 (平台相关): 性能报告怎么写进 issue、日志怎么打包、照片的字节. */
    interface Sources {
        /** 报告 [id] 写进 issue 的那一行 (键, 值); 报告不在了为 `null`. */
        fun perfRow(id: String): Pair<String, String>?

        /** 按 [spec] 打包日志 (gzip), 最前面加 [header] 几行; 打包不了为 `null`. */
        suspend fun buildLog(spec: FeedbackLogSpec, header: List<Pair<String, String>>): ByteArray?

        /** 照片 [id] 的字节; 不在了为 `null`. */
        suspend fun photo(id: String): ByteArray?

        /** 照片 [id] 传上去了或不要了: 删掉电视上那份. */
        fun deletePhoto(id: String)
    }

    sealed interface SubmitResult {
        /** 开了单 / 发了补充 [issue], 附件在后台传. */
        data class Submitted(val issue: Int) : SubmitResult

        /** 新工单没有现场录制: 先录一段复现. */
        data object NoRecording : SubmitResult

        /** 现场录制还在录: 先结束. */
        data object StillRecording : SubmitResult

        /** 补充什么都没有 (没字也没附件). */
        data object Empty : SubmitResult

        /** 没开成 ([reason]). */
        data class NotCreated(val reason: CreateResult) : SubmitResult

        /** 补充没发出去 ([outcome]). */
        data class NotSent(val outcome: Outcome) : SubmitResult
    }

    private var lastManualSync = 0L

    private val uploadingIssues = MutableStateFlow<Set<Int>>(emptySet())

    /** 正在后台传附件的单. */
    val uploading: StateFlow<Set<Int>> get() = uploadingIssues

    // ------------------------------------------------------------------ 草稿

    private suspend fun editDraft(target: Int, transform: (FeedbackDraft) -> FeedbackDraft) = records.edit {
        val updated = transform(draft(target))
        copy(drafts = drafts.filterNot { it.target == target } + listOfNotNull(updated.takeUnless { it.isEmpty && it.logLeadMinutes == 0 }))
    }

    /** 开始给 [target] 现场录制 (那份草稿里录好的那段作废); 别处正在录时不开, 回 `false`. */
    suspend fun startRecording(target: Int): Boolean {
        expireRecording()
        var started = false
        records.edit {
            if (recording != null && recording.target != target) return@edit this
            started = true
            val draft = draft(target).copy(recorded = null)
            copy(
                recording = FeedbackRecording(target, clock()),
                drafts = drafts.filterNot { it.target == target } + draft,
            )
        }
        return started
    }

    /** 结束现场录制: 录好的那段记进它的草稿; 没在录时不动. */
    suspend fun stopRecording() = records.edit { stopped(clock()) }

    /** 放弃正在录的这段. */
    suspend fun cancelRecording() = records.edit { copy(recording = null) }

    /** 录满 [MAX_RECORDING] 的按到点那一刻结束. */
    suspend fun expireRecording() {
        val rec = records.current().recording ?: return
        if (clock() - rec.since >= MAX_RECORDING.inWholeMilliseconds) records.edit { stopped(clock()) }
    }

    private fun FeedbackRecords.stopped(now: Long): FeedbackRecords {
        val rec = recording ?: return this
        val end = minOf(now, rec.since + MAX_RECORDING.inWholeMilliseconds)
        val draft = draft(rec.target).copy(recorded = FeedbackWindow(rec.since, end))
        return copy(recording = null, drafts = drafts.filterNot { it.target == rec.target } + draft)
    }

    /** 去掉 [target] 录好的那段. */
    suspend fun removeRecorded(target: Int) = editDraft(target) { it.copy(recorded = null) }

    /** 日志从录制开始前多少分钟截起 (0 ~ [MAX_LOG_LEAD_MINUTES]). */
    suspend fun setLogLead(target: Int, minutes: Int) = editDraft(target) { it.copy(logLeadMinutes = minutes.coerceIn(0, MAX_LOG_LEAD_MINUTES)) }

    /** 把性能报告 [reportId] 附到 / 移出 [target]. */
    suspend fun attachPerf(target: Int, reportId: String, attach: Boolean) = editDraft(target) { d ->
        val rest = d.perfReports - reportId
        d.copy(perfReports = if (attach) (rest + reportId).takeLast(MAX_PERF_REPORTS) else rest)
    }

    /** 加一张照片; 已经 [MAX_PHOTOS] 张了回 `false` (调用方删掉那份文件). */
    suspend fun addPhoto(target: Int, photo: FeedbackPhoto): Boolean {
        var added = false
        editDraft(target) { d ->
            if (d.photos.size >= MAX_PHOTOS) d else d.copy(photos = d.photos + photo).also { added = true }
        }
        return added
    }

    /** 去掉照片 [id] (调用方删文件). */
    suspend fun removePhoto(target: Int, id: String) = editDraft(target) { d -> d.copy(photos = d.photos.filterNot { it.id == id }) }

    /** 记下 [target] 输入框 [field] (见 [TEXT_FIELDS]) 写到一半的文字; 空的去掉. 发出去时整份草稿一起清掉. */
    suspend fun setText(target: Int, field: String, value: String) {
        if (field !in TEXT_FIELDS) return
        val text = value.take(MAX_DRAFT_TEXT)
        editDraft(target) { d -> d.copy(texts = if (text.isBlank()) d.texts - field else d.texts + (field to text)) }
    }

    /**
     * 草稿里引用的东西不在了就去掉 (控制台读草稿时调): 删掉的性能报告 ([reportExists])、文件没了的照片 ([photoExists])、
     * 那段日志已经滚动删掉的现场录制 ([recordingGone]). 没变化时不写.
     */
    suspend fun pruneDrafts(
        reportExists: (String) -> Boolean,
        photoExists: (String) -> Boolean,
        recordingGone: (FeedbackWindow) -> Boolean,
    ) {
        fun FeedbackDraft.pruned() = copy(
            recorded = recorded?.takeUnless(recordingGone),
            perfReports = perfReports.filter(reportExists),
            photos = photos.filter { photoExists(it.id) },
        )
        if (records.current().drafts.all { it.pruned() == it }) return
        records.edit { copy(drafts = drafts.map { it.pruned() }.filterNot { it.isEmpty && it.logLeadMinutes == 0 }) }
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 开新工单: 草稿 0 必须有录好的现场录制. 环境取 [FeedbackDevice.environment], issue 里的时间段是日志截的那段;
     * 记下, 清掉草稿, 在后台传附件.
     */
    suspend fun submit(summary: String, description: String, sources: Sources): SubmitResult {
        expireRecording()
        val current = records.current()
        if (current.recording?.target == NEW) return SubmitResult.StillRecording
        val draft = current.draft(NEW)
        val recorded = draft.recorded ?: return SubmitResult.NoRecording
        val (files, uploads) = attachments(draft, prefix = "")
        val from = recorded.from - draft.logLeadMinutes * 60_000L
        val created = service.create(
            FeedbackTicketService.Draft(
                device.id, summary, description, device.environment, isoTime(from), isoTime(recorded.to),
                perf = draft.perfReports.mapNotNull(sources::perfRow).toMap().takeIf { it.isNotEmpty() },
                files = files,
            ),
        )
        if (created !is CreateResult.Created) {
            logger.info { "ticket not created: $created" }
            return SubmitResult.NotCreated(created)
        }
        val record = FeedbackRecord(
            kind = FeedbackKind.TICKET,
            issue = created.issue,
            summary = summary,
            description = description,
            files = uploads.map { it.name },
            submittedAt = clock(),
            token = created.token,
            uploads = uploads,
        )
        records.edit {
            // 同号的旧记录 (换过工单服务, 如调试时连过本机的) 换成这条
            val rest = items.filterNot { it.kind == FeedbackKind.TICKET && it.issue == record.issue }
            copy(items = (listOf(record) + rest).take(FeedbackRecords.MAX_ITEMS), drafts = drafts.filterNot { it.target == NEW })
        }
        startUpload(record.issue, sources)
        return SubmitResult.Submitted(created.issue)
    }

    /** 给单 [issue] 补充: 文字 [text] 加上它那份草稿里的附件 (都可以没有, 但不能全没有). */
    suspend fun reply(issue: Int, text: String, sources: Sources): SubmitResult {
        expireRecording()
        val current = records.current()
        if (current.recording?.target == issue) return SubmitResult.StillRecording
        val record = current.items.firstOrNull { it.kind == FeedbackKind.TICKET && it.issue == issue }
        val token = record?.token ?: return SubmitResult.NotSent(Outcome.FAILED)
        val draft = current.draft(issue)
        if (text.isBlank() && !draft.hasAttachments) return SubmitResult.Empty
        val (files, _) = attachments(draft, prefix = "")
        val result = service.reply(issue, token, text, files)
        if (result.outcome != Outcome.OK) {
            // 单已经关了: 下次查时拿全量
            if (result.outcome == Outcome.CONFLICT) edit(issue) { it.copy(remoteUpdated = null) }
            return SubmitResult.NotSent(result.outcome)
        }
        val (_, uploads) = attachments(draft, prefix = result.prefix?.let { "$it-" }.orEmpty())
        records.edit {
            copy(
                items = items.map { r ->
                    if (r.kind != FeedbackKind.TICKET || r.issue != issue) r
                    else r.copy(
                        replies = r.replies + FeedbackReply(fromMaintainer = false, isoTime(clock()), text, uploads.map { it.name }),
                        uploads = r.uploads + uploads,
                    )
                },
                drafts = drafts.filterNot { it.target == issue },
            )
        }
        startUpload(issue, sources)
        return SubmitResult.Submitted(issue)
    }

    /** 草稿里的附件 → 向服务声明的文件 (名字不带前缀) 与本机的上传队列 (名字带 [prefix]). */
    private fun attachments(draft: FeedbackDraft, prefix: String): Pair<List<FileSpec>, List<FeedbackUpload>> {
        val files = mutableListOf<FileSpec>()
        val uploads = mutableListOf<FeedbackUpload>()
        if (draft.recorded != null || draft.perfReports.isNotEmpty()) {
            files += FileSpec("log.gz", 0)
            uploads += FeedbackUpload("${prefix}log.gz", log = FeedbackLogSpec(draft.recorded, draft.logLeadMinutes, draft.perfReports))
        }
        draft.photos.forEachIndexed { index, photo ->
            val name = "photo-${index + 1}.${photo.type}"
            files += FileSpec(name, photo.size)
            uploads += FeedbackUpload("$prefix$name", photoId = photo.id)
        }
        return files to uploads
    }

    /** 在后台重传 [issue] 还没传上去的附件; 没有要传的回 `false`. */
    suspend fun retryUploads(issue: Int, sources: Sources): Boolean {
        if (ticket(issue)?.uploads.isNullOrEmpty()) return false
        startUpload(issue, sources)
        return true
    }

    private fun startUpload(issue: Int, sources: Sources) {
        if (issue in uploadingIssues.getAndUpdate { it + issue }) return
        scope.launch {
            try {
                for (wait in UPLOAD_ATTEMPTS) {
                    delay(wait)
                    if (uploadPending(issue, sources)) break
                }
            } finally {
                uploadingIssues.update { it - issue }
            }
        }
    }

    /** 把 [issue] 还没传的附件挨个传一遍; 都传完了 (或再传也没用) 回 `true`. */
    private suspend fun uploadPending(issue: Int, sources: Sources): Boolean {
        val record = ticket(issue) ?: return true
        val token = record.token ?: return true
        var retry = false
        for (upload in record.uploads) {
            val bytes = try {
                upload.log?.let { sources.buildLog(it, header(record)) } ?: upload.photoId?.let { sources.photo(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "preparing ${upload.name} for #$issue failed" }
                null
            }
            val outcome = if (bytes == null) Outcome.REJECTED else service.uploadFile(issue, token, upload.name, bytes)
            logger.info { "ticket #$issue ${upload.name} (${bytes?.size ?: 0} bytes): $outcome" }
            when (outcome) {
                // 传上去了 / 已经传过 / 再传也一样 (文件没了、服务不收): 从队列里拿掉
                Outcome.OK, Outcome.CONFLICT, Outcome.REJECTED -> {
                    edit(issue) { r -> r.copy(uploads = r.uploads.filterNot { it.name == upload.name }) }
                    upload.photoId?.let(sources::deletePhoto)
                }

                Outcome.RATE_LIMITED, Outcome.FAILED, Outcome.UNREACHABLE -> retry = true
            }
        }
        return !retry
    }

    private fun header(record: FeedbackRecord) = listOf(
        "issue" to "#${record.issue}",
        "app" to currentAniBuildConfig.versionName,
        "device" to device.shortId,
        "summary" to record.summary.orEmpty(),
    )

    // ------------------------------------------------------------------ 处理结果

    /**
     * 删掉一条已结束的记录 (控制台左滑); 还开着的不删 (要靠它查回复), 回 `false`. 故障工单那份补充草稿与给它的现场录制一起丢掉,
     * 草稿里的照片下次读状态时清 (没人用的照片).
     */
    suspend fun delete(kind: FeedbackKind, issue: Int): Boolean {
        var deleted = false
        records.edit {
            val record = items.firstOrNull { it.kind == kind && it.issue == issue }
            if (record == null || record.state == FeedbackState.OPEN) return@edit this
            deleted = true
            val ownDraft = kind == FeedbackKind.TICKET
            copy(
                items = items - record,
                drafts = if (ownDraft) drafts.filterNot { it.target == issue } else drafts,
                recording = recording?.takeUnless { ownDraft && it.target == issue },
            )
        }
        return deleted
    }

    /** 看过了 (控制台的「反馈」页、电视设置里的「我的反馈」): 清掉未读. */
    suspend fun markRead() = records.edit {
        if (items.none { it.unread }) this else copy(items = items.map { if (it.unread) it.copy(unread = false) else it })
    }

    /** 用户点「检查回复」: 一分钟内点多次只查一次. 查到了 (或刚查过) 回 `true`. */
    suspend fun syncNow(): Boolean {
        val now = clock()
        if (now - lastManualSync < MANUAL_SYNC_INTERVAL.inWholeMilliseconds) return true
        lastManualSync = now
        return sync()
    }

    /**
     * 查所有开着的反馈的处理结果: 故障工单经工单服务, 标题 logo / 对应作品修正经 [subjectStatus], 跟着别人提的修正请求看对应表推断
     * ([JoinedCorrectionOutcome]). 有一边连不上回 `false` (查到的那边照样记下); 一个都没查成时记下失败次数, 后台隔得越来越久再试 ([due]).
     */
    suspend fun sync(): Boolean {
        val now = clock()
        val items = records.current().items
        val tickets = items.filter { it.isOpenTicket() }
        val subjects = items.filter { it.isOpenSubject() && !it.isDerived() }
        val derived = items.filter { it.isOpenSubject() && it.isDerived() }.associate { (it.kind to it.issue) to derive(it, now) }
        val ticketStatuses = if (tickets.isEmpty()) emptyMap() else ticketStatuses(tickets)
        val subjectStatuses = if (subjects.isEmpty() || subjectStatus == null) emptyMap()
        else subjectStatus.invoke(subjects.map { it.issue })?.associateBy { it.issue }
        val ticketsFailed = tickets.isNotEmpty() && ticketStatuses == null
        val subjectsFailed = subjects.isNotEmpty() && subjectStatuses == null
        val checkedAny = (tickets.isNotEmpty() && !ticketsFailed) || (subjects.isNotEmpty() && !subjectsFailed)
        // 该查的一个都没查成: 记下失败 (查的时刻不记), 后台过一阵再试; 推断出来的照样记
        if (!checkedAny && (ticketsFailed || subjectsFailed)) {
            records.edit {
                copy(
                    items = items.map { r -> derived[r.kind to r.issue] ?: r },
                    syncFailures = syncFailures + 1,
                    syncFailedAt = now,
                )
            }
            return false
        }
        records.edit {
            copy(
                items = items.map { r ->
                    derived[r.kind to r.issue]
                        ?: if (r.kind == FeedbackKind.TICKET) ticketStatuses?.get(r.issue)?.let { merge(r, it, now) } ?: r
                        else subjectStatuses?.get(r.issue)?.let { mergeSubject(r, it, now) } ?: r
                },
                ticketsCheckedAt = now,
                syncFailures = 0,
                syncFailedAt = null,
            )
        }
        logger.info { "feedback synced: ${ticketStatuses?.size} tickets, ${subjectStatuses?.size} corrections checked" }
        return !ticketsFailed && !subjectsFailed
    }

    /** 开着的故障工单的处理结果, 按 issue; 连不上为 `null`. */
    private suspend fun ticketStatuses(open: List<FeedbackRecord>): Map<Int, FeedbackTicketService.Status>? {
        if (!available) return null
        val statuses = mutableMapOf<Int, FeedbackTicketService.Status>()
        for (chunk in open.chunked(FeedbackTicketService.MAX_STATUS)) {
            val answered = service.status(chunk.map { FeedbackTicketService.Query(it.issue, it.token!!, it.remoteUpdated) }) ?: return null
            for (s in answered) statuses[s.issue] = s
        }
        return statuses
    }

    private fun FeedbackRecord.isOpenTicket() = kind == FeedbackKind.TICKET && state == FeedbackState.OPEN && token != null

    private fun FeedbackRecord.isOpenSubject() = kind != FeedbackKind.TICKET && state == FeedbackState.OPEN

    /** 结果看对应表推断, 不去中转查. */
    private fun FeedbackRecord.isDerived() = subjectMap != null && JoinedCorrectionOutcome.derivable(this)

    /** 跟着别人提的修正请求: 表里已经是要的样子 = 采纳; 满 [JoinedCorrectionOutcome.TIMEOUT] 还没变 = 结束; 否则不动. */
    private suspend fun derive(r: FeedbackRecord, now: Long): FeedbackRecord {
        val entry = try {
            subjectMap?.invoke(r.subjectId ?: return r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "subject map lookup for ${r.subjectId} failed" }
            return r
        }
        val state = when {
            JoinedCorrectionOutcome.applied(r, entry) -> FeedbackState.ACCEPTED
            now - r.submittedAt >= JoinedCorrectionOutcome.TIMEOUT.inWholeMilliseconds -> FeedbackState.CLOSED
            else -> return r.copy(checkedAt = now)
        }
        return r.copy(state = state, checkedAt = now, unread = true)
    }

    /** issue 的状态 → 本机记录的状态. */
    private fun stateOf(state: String?, reason: String?) = when {
        state != "closed" -> FeedbackState.OPEN
        reason == "completed" -> FeedbackState.ACCEPTED
        reason == "not_planned" -> FeedbackState.REJECTED
        else -> FeedbackState.CLOSED
    }

    /** 标题 logo / 对应作品修正: 状态变了标成未读; 维护者写了不采纳理由就记成一条维护者回复. */
    private fun mergeSubject(r: FeedbackRecord, s: IssueStatus, now: Long): FeedbackRecord {
        if (s.error != null) {
            logger.warn { "${r.kind} #${r.issue}: ${s.error}" }
            // issue 被删了: 再查也查不到, 当作结束; 别的 (这次没查成) 下次再查
            return if (s.error == "not_found") r.copy(state = FeedbackState.CLOSED, checkedAt = now, unread = true) else r
        }
        val state = stateOf(s.state, s.reason)
        val note = s.note?.takeIf { it.isNotBlank() && r.replies.none { reply -> reply.fromMaintainer && reply.body == it } }
        val replies = if (note != null) r.replies + FeedbackReply(true, s.noteAt ?: isoTime(now), note) else r.replies
        return r.copy(state = state, checkedAt = now, replies = replies, unread = r.unread || state != r.state || note != null)
    }

    private fun merge(r: FeedbackRecord, s: FeedbackTicketService.Status, now: Long): FeedbackRecord {
        if (s.error != null) {
            logger.warn { "ticket #${r.issue}: ${s.error}" }
            // 单被删了 / 凭证失效 (服务换了密钥): 再查也查不到, 当作结束; 别的 (这次没查成) 下次再查
            return if (s.error == "not_found" || s.error == "forbidden") r.copy(state = FeedbackState.CLOSED, checkedAt = now, unread = true) else r
        }
        val state = stateOf(s.state, s.reason)
        if (s.unchanged) return r.copy(state = state, checkedAt = now, unread = r.unread || state != r.state)
        val replies = s.replies ?: r.replies
        val newReplies = replies.count { it.fromMaintainer } > r.replies.count { it.fromMaintainer }
        return r.copy(
            state = state,
            checkedAt = now,
            remoteUpdated = s.updated,
            replies = replies,
            apks = s.apks ?: r.apks,
            apkSha256 = s.digests ?: r.apkSha256,
            unread = r.unread || newReplies || state != r.state,
        )
    }

    /** 启动每天查一次的后台循环. */
    fun start() {
        scope.launch {
            delay(STARTUP_DELAY)
            while (true) {
                if (due()) {
                    try {
                        sync()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "tickets sync failed" }
                    }
                }
                delay(CHECK_EVERY)
            }
        }
    }

    /** 后台该不该查了: 有开着的要查; 上次没查成的按失败次数隔 1、2、4… 小时 (最长 [SYNC_INTERVAL]), 否则每 [SYNC_INTERVAL] 一次. */
    internal suspend fun due(): Boolean {
        val current = records.current()
        val checkable = current.items.any {
            (available && it.isOpenTicket()) || (it.isOpenSubject() && (subjectStatus != null || it.isDerived()))
        }
        if (!checkable) return false
        if (current.syncFailures > 0) {
            val wait = (FAILED_RETRY * (1 shl (current.syncFailures - 1).coerceAtMost(10))).coerceAtMost(SYNC_INTERVAL)
            return clock() - (current.syncFailedAt ?: 0L) >= wait.inWholeMilliseconds
        }
        return clock() - (current.ticketsCheckedAt ?: 0L) >= SYNC_INTERVAL.inWholeMilliseconds
    }

    private suspend fun ticket(issue: Int): FeedbackRecord? =
        records.current().items.firstOrNull { it.kind == FeedbackKind.TICKET && it.issue == issue }

    private suspend fun edit(issue: Int, transform: (FeedbackRecord) -> FeedbackRecord) = records.edit {
        copy(items = items.map { if (it.kind == FeedbackKind.TICKET && it.issue == issue) transform(it) else it })
    }

    /** 带时区的 ISO 时间, 精确到秒 (写进 issue 的时间段; 日志里是本地时间, 对得上). */
    internal fun isoTime(millis: Long): String {
        val instant = Instant.fromEpochMilliseconds(millis)
        val zone = timeZone()
        val t = instant.toLocalDateTime(zone)
        fun Int.pad() = toString().padStart(2, '0')
        return "${t.date}T${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}${zone.offsetAt(instant)}"
    }

    companion object {
        private val logger = logger<FeedbackTickets>()

        /** 新工单的草稿 (其余草稿的 target 是 issue 号). */
        const val NEW = 0

        /** 现场录制最长多久, 到点自动结束: 一般的问题几分钟里都能复现. */
        val MAX_RECORDING = 5.minutes

        /** 日志最多从录制开始前多少分钟截起. */
        const val MAX_LOG_LEAD_MINUTES = 5

        /** 一条最多几张照片 (同服务端). */
        const val MAX_PHOTOS = 4

        /** 一条最多附几份性能报告 (再附就丢最早附上的). */
        const val MAX_PERF_REPORTS = 4

        /** 传附件: 每轮之前等多久 (第一轮立即传; 服务限频是按分钟算的). */
        private val UPLOAD_ATTEMPTS = listOf(0.minutes, 1.minutes, 5.minutes)

        /** 两次自动查之间至少隔多久 (比一天短一点: 后台每小时看一次, 不至于拖到第二天晚上). */
        val SYNC_INTERVAL = 20.hours
        private val CHECK_EVERY = 1.hours

        /** 草稿里存文字的输入框 (见 [setText]). */
        val TEXT_FIELDS = setOf("summary", "description", "body")

        /** 草稿里一个输入框最多存多少字 (同描述的上限). */
        const val MAX_DRAFT_TEXT = 4000

        /** 后台没查成后第一次重试隔多久, 之后每次翻倍. */
        private val FAILED_RETRY = 1.hours
        private val STARTUP_DELAY = 1.minutes
        private val MANUAL_SYNC_INTERVAL = 60.seconds
    }
}
