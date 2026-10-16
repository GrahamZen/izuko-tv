/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.feedback.FeedbackDevice
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackLogSpec
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.network.FeedbackTicketService.CreateResult
import me.him188.ani.app.data.network.FeedbackTicketService.Outcome
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.domain.feedback.FeedbackTickets.SubmitResult
import me.him188.ani.app.ui.diagnostics.TvPerfDiagnostics
import me.him188.ani.app.ui.feedback.FeedbackPerfReport
import me.him188.ani.app.ui.feedback.FeedbackPhotoStore
import me.him188.ani.app.ui.feedback.ReproLogBundle
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.mp.KoinPlatform
import java.io.File
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「设置 → 反馈」: 写反馈 (故障工单) 与这台电视提交过的反馈 (故障工单、详情页里的标题 logo / 对应作品修正请求).
 * 规则见 [FeedbackTickets]: 一条反馈的附件攒在草稿里 (新工单与每张单的补充各一份, target 0 = 新工单, 其余 = issue 号) —
 * 现场录制 (日志按录制期间截, 可以往前多截几分钟)、控制台「维护 → 性能诊断」里已有的报告 ([FeedbackPerfReport])、照片 ([FeedbackPhotoStore]).
 * 新工单必须带现场录制. 列表一条一行, 点开是会话式的详情, 能接着补充 (同样能带附件).
 *
 * - `GET api/feedback`: 本机反馈编号、工单服务开没开、各项上限、正在录的那段、各份草稿的附件、可附的性能报告、记录列表.
 * - `POST api/feedback/record` (`target`, `action` = `start` / `stop` / `cancel` / `remove`): 现场录制 开始 / 结束 / 放弃 / 去掉录好的.
 * - `POST api/feedback/lead` (`target`, `minutes`): 日志从录制开始前几分钟截起.
 * - `POST api/feedback/attach` (`target`, `report`, `on` = 1 / 0): 附上 / 去掉一份性能报告.
 * - `POST api/feedback/photo?target=` (请求体是图片): 附上一张照片 (手机上压缩好, 不超过 [FeedbackPhotoStore.MAX_BYTES]);
 *   `GET api/feedback/photo/<id>`: 照片原图 (网页上的缩略); `POST api/feedback/photo/remove` (`target`, `id`): 去掉.
 * - `POST api/feedback/ticket` (`summary`, `description`): 开新工单. `POST api/feedback/reply` (`issue`, `body`): 补充.
 * - `POST api/feedback/retry` (`issue`): 重传没传上去的附件.
 * - `POST api/feedback/sync`: 现在查一次处理结果, 回同 `GET api/feedback`. `POST api/feedback/read`: 看过了, 清掉「有新回复」.
 * - `POST api/feedback/install` (`issue`, `url`): 把工单回复里的测试包交给「应用更新」卡片 ([RemoteAppUpdate.offer]), 网页随即转过去,
 *   授权、下载、安装都在那里. 只认那张单回复里列出的链接.
 */
internal object RemoteFeedback {
    private val logger = logger<RemoteFeedback>()
    private val records: FeedbackRecordRepository get() = KoinPlatform.getKoin().get()
    private val device: FeedbackDevice get() = KoinPlatform.getKoin().get()
    private val tickets: FeedbackTickets get() = KoinPlatform.getKoin().get()

    /** 同工单服务的下限 (tools/ticket-worker), 提交前先在这里挡一道, 提示得具体些. */
    private const val MIN_SUMMARY = 4
    private const val MAX_SUMMARY = 80
    private const val MIN_DESCRIPTION = 5
    private const val MAX_DESCRIPTION = 4000

    private const val PHOTO_PATH = "api/feedback/photo"

    /** 网页上「＋」里列出的性能报告最多几份 (新的在前). */
    private const val LISTED_REPORTS = 12

    @Volatile
    private var swept = false

    /** 照片那个接口收一整张照片 (再留点余量); 其余不放宽. */
    fun maxBodyBytes(path: String): Long? = if (path == PHOTO_PATH) FeedbackPhotoStore.MAX_BYTES + 64L * 1024 else null

    /** 附件的来源: 性能报告 ([FeedbackPerfReport]), 日志 (日志目录见 [TvRemoteControl.logsDir], 里面是本地时间), 照片 ([FeedbackPhotoStore]). */
    private val sources = object : FeedbackTickets.Sources {
        override fun perfRow(id: String) = FeedbackPerfReport.read(id)?.row

        override suspend fun buildLog(spec: FeedbackLogSpec, header: List<Pair<String, String>>): ByteArray? = withContext(Dispatchers.IO) {
            val reports = spec.perfReports.mapNotNull(FeedbackPerfReport::read)
            val dir = TvRemoteControl.logsDir()
            if (dir == null && reports.isEmpty()) return@withContext null
            val preface = reports.takeIf { it.isNotEmpty() }
                ?.joinToString("\n", postfix = "\n# ---- 应用日志 ----") { "# ---- 性能诊断报告 ${it.id} ----\n${it.text}" }
            val windows = listOfNotNull(spec.recorded).map { w -> localTime(w.from - spec.leadMinutes * 60_000L) to localTime(w.to) }
            val bundle = ReproLogBundle.build(dir ?: File("missing"), windows, header, preface = preface)
            logger.info { "ticket log: ${reports.size} reports, ${bundle.lines} log lines, ${bundle.gzip.size} bytes gzipped, truncated=${bundle.truncated}" }
            bundle.gzip
        }

        override suspend fun photo(id: String): ByteArray? = withContext(Dispatchers.IO) { FeedbackPhotoStore.read(id) }

        override fun deletePhoto(id: String) = FeedbackPhotoStore.delete(id)
    }

    /** 收发文件的那几个接口 (照片的上传与原图); 别的回 `null`, 交给 [handle]. */
    fun handleFile(request: LanHttpRequest): LanHttpResponse? {
        val get = request.method == "GET" || request.method == "HEAD"
        if (get && request.path.startsWith("$PHOTO_PATH/")) {
            val file = FeedbackPhotoStore.file(request.path.removePrefix("$PHOTO_PATH/")) ?: return LanHttpResponse.status(404, "Not Found")
            val type = when (FeedbackPhotoStore.typeOf(file.inputStream().use { it.readNBytes(12) })) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            return LanHttpResponse.fileSnapshot(file, type)
        }
        if (request.method == "POST" && request.path == PHOTO_PATH) {
            return LanHttpResponse.bytes(addPhoto(request).toString().toByteArray(), "application/json; charset=utf-8")
        }
        return null
    }

    fun handle(request: LanHttpRequest): JsonObject? {
        if (request.method == "GET" || request.method == "HEAD") return if (request.path == "api/feedback") state() else null
        if (request.method != "POST") return null
        val fields = request.formFields()
        val target = fields["target"]?.toIntOrNull() ?: FeedbackTickets.NEW
        return when (request.path) {
            "api/feedback/record" -> record(target, fields["action"])
            "api/feedback/lead" -> ok { tickets.setLogLead(target, fields["minutes"]?.toIntOrNull() ?: 0) }
            "api/feedback/text" -> ok { tickets.setText(target, fields["field"].orEmpty(), fields["value"].orEmpty()) }
            "api/feedback/attach" -> attach(target, fields["report"].orEmpty(), fields["on"] == "1")
            "api/feedback/photo/remove" -> fields["id"].orEmpty().let { id ->
                ok { tickets.removePhoto(target, id) }.also { FeedbackPhotoStore.delete(id) }
            }

            "api/feedback/ticket" -> submit(fields["summary"].orEmpty().trim(), fields["description"].orEmpty().trim())
            "api/feedback/reply" -> reply(fields["issue"]?.toIntOrNull(), fields["body"].orEmpty().trim())
            "api/feedback/retry" -> retry(fields["issue"]?.toIntOrNull())
            "api/feedback/sync" -> sync()
            "api/feedback/read" -> ok { tickets.markRead() }
            "api/feedback/install" -> offerApk(fields["issue"]?.toIntOrNull(), fields["url"].orEmpty())
            "api/feedback/delete" -> delete(FeedbackKind.entries.firstOrNull { it.name == fields["kind"] }, fields["issue"]?.toIntOrNull())
            else -> null
        }
    }

    private fun record(target: Int, action: String?): JsonObject {
        if (!tickets.available) return result(false, tr("故障反馈还没开放"))
        when (action) {
            "start" -> if (blocking(5.seconds) { tickets.startRecording(target) } != true) {
                return result(false, tr("另一条反馈正在现场录制，先结束那一段"))
            }

            "stop" -> blocking(5.seconds) { tickets.stopRecording() }
            "cancel" -> blocking(5.seconds) { tickets.cancelRecording() }
            "remove" -> blocking(5.seconds) { tickets.removeRecorded(target) }
            else -> return result(false, "")
        }
        return result(true, "")
    }

    private fun attach(target: Int, reportId: String, on: Boolean): JsonObject {
        if (on && FeedbackPerfReport.read(reportId) == null) return result(false, tr("这份报告已经不在了"))
        return ok { tickets.attachPerf(target, reportId, on) }
    }

    private fun addPhoto(request: LanHttpRequest): JsonObject {
        val target = request.query.split('&').firstNotNullOfOrNull { kv ->
            kv.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "target" }?.get(1)?.let { URLDecoder.decode(it, "UTF-8").toIntOrNull() }
        } ?: FeedbackTickets.NEW
        val bytes = request.body
        if (bytes.size > FeedbackPhotoStore.MAX_BYTES) {
            return result(false, tr("这张照片太大（{0}），上传不了，最多 {1}", sizeText(bytes.size.toLong()), sizeText(FeedbackPhotoStore.MAX_BYTES.toLong())))
        }
        val photo = FeedbackPhotoStore.save(bytes) ?: return result(false, tr("只能附 JPEG、PNG 或 WebP 图片"))
        if (blocking(5.seconds) { tickets.addPhoto(target, photo) } != true) {
            FeedbackPhotoStore.delete(photo.id)
            return result(false, tr("一条最多附 {0} 张照片", FeedbackTickets.MAX_PHOTOS))
        }
        return result(true, "")
    }

    private fun submit(summary: String, description: String): JsonObject {
        if (!tickets.available) return result(false, tr("故障反馈还没开放"))
        if (summary.length < MIN_SUMMARY) return result(false, tr("问题至少写 {0} 个字", MIN_SUMMARY))
        if (summary.length > MAX_SUMMARY) return result(false, tr("问题最多 {0} 个字，细节写到描述里", MAX_SUMMARY))
        if (description.codePointCount(0, description.length) < MIN_DESCRIPTION) {
            return result(false, tr("描述至少写 {0} 个字：怎么操作会出问题、出问题时看到了什么", MIN_DESCRIPTION))
        }
        if (description.length > MAX_DESCRIPTION) return result(false, tr("描述最多 {0} 个字", MAX_DESCRIPTION))
        return when (val submitted = blocking(30.seconds) { tickets.submit(summary, description, sources) }) {
            is SubmitResult.Submitted -> buildJsonObject {
                put("ok", true)
                put("issue", submitted.issue)
                put("message", tr("已提交，工单 #{0}。附件正在上传。", submitted.issue))
            }

            else -> result(false, failure(submitted))
        }
    }

    private fun reply(issue: Int?, body: String): JsonObject {
        issue ?: return result(false, "")
        if (body.length > MAX_DESCRIPTION) return result(false, tr("最多 {0} 个字", MAX_DESCRIPTION))
        return when (val sent = blocking(30.seconds) { tickets.reply(issue, body, sources) }) {
            is SubmitResult.Submitted -> result(true, tr("已补充"))
            else -> result(false, failure(sent))
        }
    }

    private fun failure(result: SubmitResult?): String = when (result) {
        null -> tr("连不上工单服务，检查电视的网络后再试")
        is SubmitResult.Submitted -> ""
        SubmitResult.NoRecording -> tr("新反馈要带一段现场录制：点「＋」选「现场录制」，在电视上把问题复现一遍再结束")
        SubmitResult.StillRecording -> tr("还在现场录制，先点「结束」")
        SubmitResult.Empty -> tr("先写点什么，或者附上东西")
        is SubmitResult.NotSent -> when (result.outcome) {
            Outcome.CONFLICT -> tr("这张工单已经结束，不能再补充")
            Outcome.RATE_LIMITED -> tr("提交太频繁，过一会儿再试")
            Outcome.UNREACHABLE -> tr("连不上工单服务，检查电视的网络后再试")
            Outcome.OK, Outcome.REJECTED, Outcome.FAILED -> tr("工单服务出错了，稍后再试")
        }

        is SubmitResult.NotCreated -> when (val reason = result.reason) {
            CreateResult.Blocked -> tr("这台电视暂时不能提交故障反馈")
            is CreateResult.TooManyOpen -> tr("还有 {0} 张工单在处理中，处理完再提交新的", reason.issues.size)
            CreateResult.RateLimited -> tr("提交太频繁，过一会儿再试")
            is CreateResult.Rejected -> when (reason.error) {
                "summary_too_short" -> tr("问题至少写 {0} 个字", MIN_SUMMARY)
                "description_too_short" -> tr("描述至少写 {0} 个字：怎么操作会出问题、出问题时看到了什么", MIN_DESCRIPTION)
                "file_too_large", "too_many_photos" -> tr("附件太大或太多，去掉一些再试")
                else -> tr("工单服务不收这次的内容（{0}）", reason.error)
            }

            CreateResult.Unreachable -> tr("连不上工单服务，检查电视的网络后再试")
            CreateResult.Failed, is CreateResult.Created -> tr("工单服务出错了，稍后再试")
        }
    }

    private fun retry(issue: Int?): JsonObject {
        issue ?: return result(false, "")
        val started = blocking(5.seconds) { tickets.retryUploads(issue, sources) } == true
        return if (started) result(true, tr("正在重新上传附件")) else result(false, tr("这张工单没有要上传的附件"))
    }

    private fun offerApk(issue: Int?, url: String): JsonObject {
        val record = blocking(3.seconds) { records.current() }?.items
            ?.firstOrNull { it.kind == FeedbackKind.TICKET && it.issue == issue && url in it.apks }
            ?: return result(false, tr("这个测试包已经不在工单的回复里了"))
        RemoteAppUpdate.offer(url, record.apkSha256[url], record.issue)
        return result(true, tr("测试包已交给「应用更新」，在那里下载并安装"))
    }

    private fun delete(kind: FeedbackKind?, issue: Int?): JsonObject {
        if (kind == null || issue == null) return result(false, "")
        val deleted = blocking(5.seconds) { tickets.delete(kind, issue) } == true
        return if (deleted) result(true, "") else result(false, tr("只能删除已结束的反馈"))
    }

    private fun sync(): JsonObject {
        val ok = blocking(30.seconds) { tickets.syncNow() } == true
        return buildJsonObject {
            for ((k, v) in state()) put(k, v)
            if (!ok) put("message", tr("有的处理结果没查到，检查电视的网络后再试"))
        }
    }

    private fun state(): JsonObject {
        blocking(3.seconds) { tickets.expireRecording() }
        // 草稿里引用的东西不在了 (报告删了、照片没了、那段日志滚动删掉了) 就去掉
        val earliestLogDay = TvRemoteControl.logsDir()?.let(ReproLogBundle::earliestRolledDay)
        blocking(3.seconds) {
            tickets.pruneDrafts(
                reportExists = { TvPerfDiagnostics.reportFile(it) != null },
                photoExists = { FeedbackPhotoStore.file(it) != null },
                recordingGone = { w -> earliestLogDay != null && localTime(w.to).toLocalDate().isBefore(earliestLogDay) },
            )
        }
        val all = blocking(3.seconds) { records.current() } ?: FeedbackRecords.Empty
        if (!swept) {
            // 没人用的照片 (草稿去掉了、工单删了) 清一次
            swept = true
            FeedbackPhotoStore.sweep(
                all.drafts.flatMap { d -> d.photos.map { it.id } }.toSet() + all.items.flatMap { r -> r.uploads.mapNotNull { it.photoId } },
            )
        }
        val uploading = tickets.uploading.value
        val now = System.currentTimeMillis()
        return buildJsonObject {
            put("ok", true)
            put("device", device.shortId)
            put("tickets", tickets.available)
            putJsonObject("limits") {
                put("recordMs", FeedbackTickets.MAX_RECORDING.inWholeMilliseconds)
                put("maxLead", FeedbackTickets.MAX_LOG_LEAD_MINUTES)
                put("maxPhotos", FeedbackTickets.MAX_PHOTOS)
                put("photoBytes", FeedbackPhotoStore.MAX_BYTES)
                put("minSummary", MIN_SUMMARY)
                put("minDescription", MIN_DESCRIPTION)
            }
            all.recording?.let { rec ->
                putJsonObject("recording") {
                    put("target", rec.target)
                    put("elapsed", (now - rec.since).coerceAtLeast(0))
                }
            }
            putJsonObject("drafts") {
                for (d in all.drafts) putJsonObject(d.target.toString()) {
                    d.recorded?.let { w ->
                        putJsonObject("recorded") {
                            put("from", w.from)
                            put("to", w.to)
                        }
                    }
                    put("lead", d.logLeadMinutes)
                    putJsonArray("reports") {
                        for (r in d.perfReports.mapNotNull(FeedbackPerfReport::read)) addJsonObject { putReport(r) }
                    }
                    putJsonArray("photos") {
                        for (p in d.photos) addJsonObject {
                            put("id", p.id)
                            put("size", p.size)
                        }
                    }
                    putJsonObject("texts") { for ((field, text) in d.texts) put(field, text) }
                }
            }
            putJsonArray("reports") {
                for (r in TvPerfDiagnostics.reports().take(LISTED_REPORTS).mapNotNull(FeedbackPerfReport::summary)) addJsonObject { putReport(r) }
            }
            put("checkedAt", all.ticketsCheckedAt)
            putJsonArray("items") {
                for (r in all.items) addJsonObject {
                    put("kind", r.kind.name)
                    put("issue", r.issue)
                    put("subjectId", r.subjectId)
                    put("name", r.subjectName)
                    put("language", r.language)
                    put("summary", r.summary)
                    put("state", r.state.name)
                    put("at", r.submittedAt)
                    put("joined", r.joinedExisting)
                    put("url", r.kind.publicRepository?.let { "https://github.com/$it/issues/${r.issue}" })
                    put("unread", r.unread)
                    putJsonArray("replies") {
                        for (reply in r.replies) addJsonObject {
                            put("maintainer", reply.fromMaintainer)
                            put("at", reply.at)
                            put("body", reply.body)
                            putJsonArray("files") { for (f in reply.files) add(f) }
                        }
                    }
                    if (r.kind != FeedbackKind.TICKET) return@addJsonObject
                    put("description", r.description)
                    putJsonArray("files") { for (f in r.files) add(f) }
                    putJsonArray("pending") { for (u in r.uploads) add(u.name) }
                    put("uploading", r.issue in uploading)
                    putJsonArray("apks") { for (apk in r.apks) add(apk) }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putReport(r: FeedbackPerfReport) {
        put("id", r.id)
        put("kind", r.kind)
        put("seconds", r.seconds)
        put("page", r.page)
        put("time", r.time)
        put("headline", r.headline)
    }

    private fun sizeText(bytes: Long): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0) else "${(bytes + 1023) / 1024} KB"

    private fun ok(block: suspend () -> Unit): JsonObject {
        blocking(5.seconds) { block() }
        return result(true, "")
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun <T> blocking(timeout: Duration, block: suspend () -> T): T? = runBlocking { withTimeoutOrNull(timeout) { block() } }

    private fun localTime(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
}

/** 「设置 → 常规」分类列表里「反馈」那一行. */
internal const val FEEDBACK_SET_ROW =
    """<button type="button" class="set-nav-row" data-group="feedback"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 12h-2v-2h2v2zm0-4h-2V6h2v4z"/></svg><span class="set-nav-txt"><b>反馈</b><small>故障反馈，以及标题 logo、对应作品修正的处理进度</small><small class="set-nav-note" hidden></small></span><span class="set-nav-go">›</span></button>"""

/** 「反馈」那一组的容器, 卡片由 [FEEDBACK_SCRIPT] 画 (详情页、「＋」菜单、录制条挂在 body 下). */
internal const val FEEDBACK_SET_GROUP =
    "<div class=\"set-group\" data-group=\"feedback\" hidden>\n<h2 class=\"set-group-h\">反馈</h2>\n<div id=\"set-feedback\"></div>\n</div>"

internal val FEEDBACK_STYLE = """
.fb-list { display: flex; flex-direction: column; }
.fb-item { position: relative; display: flex; align-items: flex-start; gap: 10px; padding: 10px 0; cursor: pointer; -webkit-tap-highlight-color: transparent; }
/* 每行包在可左滑的 .sw.flat 里 (贴满卡片左右): 分隔线画在行上、缩回卡片内边距, 跟着行一起滑 */
.fb-list > .sw + .sw > .fb-item::before { content: ""; position: absolute; top: 0; left: 16px; right: 16px; border-top: 1px solid var(--line); }
.fb-list > .sw.gone { margin-top: 0; }
.fb-kind { flex: none; font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 999px; background: var(--chip); color: var(--sub); margin-top: 1px; }
.fb-kind.ticket { background: var(--err-bg); color: var(--err-fg); }
.fb-main { flex: 1; min-width: 0; }
.fb-main > b { display: block; font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fb-main > small { display: block; font-size: 12px; color: var(--mute); margin-top: 2px; }
.fb-state { flex: none; font-size: 12px; color: var(--sub); margin-top: 2px; text-align: right; }
.fb-state.open { color: var(--p); }
.fb-new { display: block; margin-top: 4px; font-size: 11px; color: var(--on-p); background: var(--p); padding: 1px 6px; border-radius: 8px; }
.fb-go { flex: none; align-self: center; width: 20px; height: 20px; background: var(--mute);
  -webkit-mask: var(--chev) center / contain no-repeat; mask: var(--chev) center / contain no-repeat; transform: rotate(-90deg); }
.fb-device { margin-top: 12px; }
.fb-head { display: flex; align-items: center; gap: 8px; }
.fb-head .set-title { flex: 1; }
.fb-head small { font-size: 12px; color: var(--mute); }
/* 「刷新」: 同订阅卡片的「立即更新」; 刷过一次后一分钟内灰着 (电视上也是一分钟最多真查一次) */
.fb-refresh { flex: none; box-sizing: border-box; height: 30px; background: var(--chip); color: var(--on-chip); padding: 0 10px; border-radius: 9px; font-size: 13px; }
.fb-refresh > svg { width: 16px; height: 16px; }
.fb-refresh:disabled { opacity: .5; }
.fb-link { background: none; color: var(--p); padding: 0; font-size: 13px; font-weight: 600; }
#set-feedback button:disabled, #fb-sheet button:disabled { opacity: .45; }
/* 写反馈: 文字 + 附件 + 「＋」 */
.fb-atts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.fb-chip { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; min-height: 30px; box-sizing: border-box; font-size: 13px; padding: 2px 2px 2px 10px; border-radius: 15px; background: var(--chip); color: var(--on-chip); }
.fb-chip > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fb-chip > button { flex: none; width: 26px; height: 26px; border-radius: 13px; background: none; color: var(--sub); font-size: 17px; line-height: 1; padding: 0; }
.fb-chip.rec { padding-right: 10px; }
.fb-chip.rec::before { content: ""; flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--err); animation: fb-blink 1.4s ease-in-out infinite; }
.fb-chip.photo { padding-left: 2px; }
.fb-chip.photo img { width: 26px; height: 26px; border-radius: 13px; object-fit: cover; }
.fb-atts select { height: 30px; font: inherit; font-size: 13px; border-radius: 15px; padding: 0 8px; border: 1px solid var(--outline); background: var(--field); color: var(--fg); max-width: 100%; }
.fb-bar { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.fb-plus { flex: none; width: 42px; height: 42px; border-radius: 21px; background: var(--chip); color: var(--on-chip); font-size: 26px; font-weight: 400; line-height: 1; padding: 0; }
.fb-bar small { flex: 1; min-width: 0; font-size: 12px; color: var(--mute); }
.fb-bar .primary { flex: none; padding: 11px 22px; }
@keyframes fb-blink { 50% { opacity: .25; } }
/* 「＋」菜单: 从底下弹出 */
.fb-menu { position: fixed; inset: 0; z-index: 70; background: rgba(0,0,0,.35); display: flex; align-items: flex-end; justify-content: center; }
.fb-menu[hidden] { display: none; }
.fb-menu-card { width: 100%; max-width: 560px; max-height: 80vh; overflow-y: auto; box-sizing: border-box; background: var(--card); border-radius: 18px 18px 0 0; padding: 8px 16px calc(16px + env(safe-area-inset-bottom)); }
.fb-menu-h { display: flex; align-items: center; gap: 8px; font-size: 16px; font-weight: 700; padding: 8px 0; }
.fb-menu-h > span { flex: 1; }
.fb-menu-i { display: flex; align-items: center; gap: 12px; width: 100%; text-align: left; background: none; color: var(--fg); padding: 12px 0; border-top: 1px solid var(--line); font-size: 15px; }
.fb-menu-i > span { flex: 1; min-width: 0; }
.fb-menu-i small { display: block; font-size: 12px; color: var(--mute); margin-top: 2px; overflow-wrap: anywhere; }
.fb-menu-i > b { flex: none; font-size: 13px; color: var(--mute); font-weight: 400; }
.fb-menu-i > b.on { color: var(--p); font-weight: 700; }
.fb-menu-i:disabled { opacity: .45; }
.fb-menu .hint { margin: 8px 0; }
/* 现场录制的悬浮条: 切到别的标签也在 */
.fb-recbar { position: fixed; left: 12px; right: 12px; bottom: calc(66px + env(safe-area-inset-bottom)); z-index: 60; display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-radius: 16px; background: var(--raised); color: var(--fg); box-shadow: 0 4px 18px var(--shadow-lg); }
.fb-recbar[hidden] { display: none; }
.fb-recbar::before { content: ""; flex: none; width: 10px; height: 10px; border-radius: 50%; background: var(--err); animation: fb-blink 1.4s ease-in-out infinite; }
.fb-recbar > span { flex: 1; min-width: 0; font-size: 14px; font-weight: 600; }
.fb-recbar > span small { display: block; font-size: 12px; font-weight: 400; color: var(--mute); }
.fb-recbar button { flex: none; background: none; color: var(--p); font-weight: 700; padding: 6px 4px; font-size: 15px; }
/* 详情: 会话 */
.fb-chat { display: flex; flex-direction: column; gap: 10px; padding: 8px 0 4px; }
.fb-msg { max-width: 86%; box-sizing: border-box; padding: 8px 12px; border-radius: 16px; font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }
.fb-msg.them { align-self: flex-start; background: var(--chip); border-bottom-left-radius: 4px; }
.fb-msg.me { align-self: flex-end; background: color-mix(in srgb, var(--p) 16%, var(--card)); border-bottom-right-radius: 4px; }
.fb-msg > small { display: block; font-size: 12px; color: var(--mute); margin-bottom: 2px; }
.fb-msg > div { white-space: pre-wrap; }
.fb-msg a { color: var(--p); }
.fb-files { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.fb-files > span { font-size: 12px; padding: 1px 8px; border-radius: 999px; background: var(--card); color: var(--sub); }
.fb-files > span.pending { color: var(--err); }
.fb-apks { align-self: flex-start; display: flex; flex-direction: column; gap: 6px; max-width: 86%; }
.fb-apks button { text-align: left; }
.fb-status { margin: 4px 0 0; font-size: 13px; color: var(--sub); }
.fb-reply-box { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--line); }
""".trimIndent()

/**
 * 「反馈」那一组: 写反馈 (文字 + 「＋」里的附件: 现场录制、性能报告、照片) 与「我的反馈」列表 (一条一行, 点开是会话式的详情页,
 * 能接着补充). 现场录制时底部浮一条录制条, 切到别的标签也在, 录满上限自动结束. 照片在手机上压缩后再传, 太大的提示传不了.
 * 进这一组时读一次 (`window.loadFeedback`, 由 SETTINGS_SCRIPT 调), 读完清掉「有新回复」; 页面打开时也读一次 (分类行的提示、录制条);
 * 录制中、附件在传时隔几秒再读.
 */
internal val FEEDBACK_SCRIPT = """
(function () {
  var box = document.getElementById('set-feedback');
  if (!box) return;
  var KIND = { TICKET: T('故障工单'), LOGO: T('标题 logo'), ENTRY: T('对应作品') };
  var STATE = { OPEN: T('处理中'), ACCEPTED: T('已采纳'), REJECTED: T('未采纳'), CLOSED: T('已结束') };
  var TICKET_STATE = { OPEN: T('处理中'), ACCEPTED: T('已解决'), REJECTED: T('不处理'), CLOSED: T('已结束') };
  var LANG = { ja: T('日文'), zh: T('中文'), en: T('英文') };
  var LEADS = [0, 1, 3, 5];
  // 各条反馈里打到一半的字 (按 target: 0 = 新工单, 其余 = issue 号): 重画时填回去
  var texts = {};
  // last: 上次读到的状态; detail: 打开的详情 {kind, issue}; menu: 开着的「＋」菜单 {target, view}
  var last = null, seen = false, detail = null, menu = null, pollTimer = 0, recTick = 0, recBase = null, pickTarget = 0;
  /** 「刷新」: 正在查 / 这一刻之前别再点 (同电视上的一分钟限制) */
  var syncing = false, syncUntil = 0;
  var SYNC_COOLDOWN = 60000;

  function pad(n) { return ('0' + n).slice(-2); }
  function day(ms) { var d = new Date(ms); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function clock(ms) { var d = new Date(ms); return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  function mmss(ms) { var s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ':' + pad(s % 60); }
  function size(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB'; }
  function linkify(s) {
    return esc(s).replace(/https?:\/\/[^\s<>"']+/g, function (u) { return '<a href="' + u + '" target="_blank" rel="noopener">' + u + '</a>'; });
  }
  function title(x) {
    if (x.kind === 'TICKET') return x.summary || KIND.TICKET;
    if (x.kind === 'LOGO') {
      return (x.name ? T('《{0}》的标题 logo', x.name) : T('条目 {0} 的标题 logo', x.subjectId)) +
        (x.language ? T('（{0}）', LANG[x.language] || x.language) : '');
    }
    return x.name ? T('《{0}》对应的 TMDB 作品', x.name) : T('条目 {0} 对应的 TMDB 作品', x.subjectId);
  }
  function draftOf(d, target) { return (d.drafts || {})[target] || { lead: 0, reports: [], photos: [] }; }
  function reportLabel(r) {
    return (r.kind === 'health' ? T('设备体检') : T('录制 {0} 秒', r.seconds) + (r.page ? ' · ' + r.page : '')) + ' · ' + r.time;
  }
  // 附件名 → 给人看的: log.gz = 日志, photo-2.jpg = 照片 2 (补充里的带 r<k>- 前缀)
  function fileLabel(name) {
    var n = name.replace(/^r\d+-/, ''), m = /^photo-(\d)/.exec(n);
    return m ? T('照片 {0}', m[1]) : T('日志');
  }

  // ---------------- 写反馈 (新工单与补充共用)
  function chip(text, act, target, id) {
    return '<span class="fb-chip"><span>' + esc(text) + '</span><button type="button" data-act="' + act + '" data-target="' + target + '"' +
      (id ? ' data-id="' + esc(id) + '"' : '') + ' aria-label="' + T('去掉') + '">×</button></span>';
  }
  function attachments(d, target) {
    var dr = draftOf(d, target), h = '';
    if (d.recording && d.recording.target == target) h += '<span class="fb-chip rec">' + T('现场录制中') + '</span>';
    if (dr.recorded) {
      h += chip(T('现场录制 {0}', mmss(dr.recorded.to - dr.recorded.from)), 'unrec', target) +
        '<select data-lead="' + target + '" aria-label="' + T('日志范围') + '">' + LEADS.filter(function (n) { return n <= (d.limits || {}).maxLead; }).map(function (n) {
          return '<option value="' + n + '"' + (n === dr.lead ? ' selected' : '') + '>' +
            (n ? T('日志：再加录制前 {0} 分钟', n) : T('日志：只要录制期间')) + '</option>';
        }).join('') + '</select>';
    }
    dr.reports.forEach(function (r) { h += chip(reportLabel(r), 'detach', target, r.id); });
    dr.photos.forEach(function (p) {
      h += '<span class="fb-chip photo"><img src="api/feedback/photo/' + esc(p.id) + '" alt=""><span>' + size(p.size) + '</span>' +
        '<button type="button" data-act="unphoto" data-target="' + target + '" data-id="' + esc(p.id) + '" aria-label="' + T('去掉') + '">×</button></span>';
    });
    return h ? '<div class="fb-atts">' + h + '</div>' : '';
  }
  function composer(d, target) {
    var isNew = target == 0, dr = draftOf(d, target), recHere = !!(d.recording && d.recording.target == target);
    var h = isNew
      ? '<label class="f"><span>' + T('问题') + '</span><input type="text" maxlength="80" data-text="0.summary" placeholder="' +
        T('一句话说明，如：播放 4K 片源十分钟后黑屏') + '"></label>' +
        '<label class="f"><span>' + T('描述') + '</span><textarea rows="4" maxlength="4000" data-text="0.description" placeholder="' +
        T('怎么操作会出问题、出问题时看到了什么、是不是每次都这样') + '"></textarea></label>'
      : '<textarea rows="3" maxlength="4000" data-text="' + target + '.body" placeholder="' + T('补充说明：试过的办法、新发现的情况…') + '"></textarea>';
    h += attachments(d, target);
    if (isNew && !dr.recorded && !recHere) {
      h += '<p class="hint">' + T('新反馈要带一段现场录制：点「＋」选「现场录制」，在电视上把问题复现一遍再结束。') + '</p>';
    }
    return h + '<div class="fb-bar"><button type="button" class="fb-plus" data-act="plus" data-target="' + target + '" aria-label="' + T('附上') + '">+</button>' +
      '<small>' + T('现场录制、性能报告、照片') + '</small>' +
      '<button type="button" class="primary" data-act="send" data-target="' + target + '"' + (recHere ? ' disabled' : '') + '>' +
      (isNew ? T('提交') : T('发送')) + '</button></div>';
  }

  // ---------------- 列表
  function gist(x) {
    if (x.kind !== 'TICKET') return '';
    var parts = [], n = (x.replies || []).filter(function (r) { return r.maintainer; }).length;
    if (n) parts.push(T('{0} 条回复', n));
    if ((x.apks || []).length) parts.push(T('有测试包'));
    if ((x.pending || []).length) parts.push(x.uploading ? T('附件上传中…') : T('附件没传上去'));
    return parts.length ? ' · ' + parts.join(' · ') : '';
  }
  // 已结束的可以左滑删除 (只删这台电视上的记录); 处理中的要靠它查回复, 滑不动
  function row(x) {
    var ticket = x.kind === 'TICKET', states = ticket ? TICKET_STATE : STATE;
    var del = x.state === 'OPEN' ? '' : '<button type="button" class="sw-btn del" data-act="del" data-kind="' + x.kind + '" data-issue="' + x.issue + '">' +
      window.ICONS.trash + T('删除') + '</button>';
    return window.swRow('', del, '<div class="fb-item sw-row" data-act="open" data-kind="' + x.kind + '" data-issue="' + x.issue + '" role="button" tabindex="0">' +
      '<span class="fb-kind' + (ticket ? ' ticket' : '') + '">' + esc(KIND[x.kind] || x.kind) + '</span>' +
      '<div class="fb-main"><b>' + esc(title(x)) + '</b><small>' + esc('#' + x.issue + ' · ' + day(x.at) + gist(x)) + '</small></div>' +
      '<span class="fb-state' + (x.state === 'OPEN' ? ' open' : '') + '">' + esc(states[x.state] || x.state) +
      (x.unread ? '<span class="fb-new">' + T('新回复') + '</span>' : '') + '</span><span class="fb-go"></span></div>', 'flat');
  }
  function mainHtml(d) {
    var items = d.items || [], tickets = items.some(function (x) { return x.kind === 'TICKET' ? d.tickets : x.state === 'OPEN'; });
    var h = '<div class="card set-card"><div class="set-title">' + T('写反馈') + '</div><p class="hint">' +
      T('遇到闪退、播放出错、卡顿这类问题时，写清楚问题，附上一段现场录制（还可以附性能报告和照片）交给维护者；回复会出现在下面「我的反馈」里。') + '</p>';
    h += d.tickets ? composer(d, 0)
      : '<div class="row"><button type="button" class="primary" disabled>' + T('提交故障反馈') + '</button></div><p class="hint">' + T('即将开放') + '</p>';
    h += '</div><div class="card set-card"><div class="fb-head"><div class="set-title">' + T('我的反馈') + '</div>' +
      (tickets ? '<small>' + (d.checkedAt ? T('{0} 更新', clock(d.checkedAt)) : '') + '</small>' +
        '<button type="button" class="fb-refresh ic" data-act="sync"' + (syncing || Date.now() < syncUntil ? ' disabled' : '') + '>' +
        window.ICONS.refresh + (syncing ? T('刷新中…') : T('刷新')) + '</button>' : '') + '</div>' +
      (items.length ? '<div class="fb-list">' + items.map(row).join('') + '</div>'
        : '<p class="hint">' + T('还没有提交过反馈。在详情页「反馈」里改标题 logo、对应作品，也会记在这里。') + '</p>') +
      '<p class="hint fb-device">' + T('本机反馈编号：{0}', esc(d.device || '')) + '</p></div>';
    return h;
  }

  // ---------------- 详情页 (会话)
  var sheet = document.createElement('div');
  sheet.className = 'sheet';
  sheet.id = 'fb-sheet';
  sheet.hidden = true;
  sheet.innerHTML = '<div class="sheet-head"><button type="button" class="sheet-btn" data-act="close" aria-label="' + T('返回') + '">' +
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg></button>' +
    '<div class="sheet-title"></div></div><div class="sheet-body"></div>';
  document.body.appendChild(sheet);
  var sheetTitle = sheet.querySelector('.sheet-title'), sheetBody = sheet.querySelector('.sheet-body');
  sheet.addEventListener('sheetclose', function () { detail = null; });
  function msg(mine, meta, html, files, pending) {
    var f = (files || []).map(function (n) {
      return '<span' + (pending.indexOf(n) >= 0 ? ' class="pending"' : '') + '>' + esc(fileLabel(n)) + '</span>';
    }).join('');
    return '<div class="fb-msg ' + (mine ? 'me' : 'them') + '"><small>' + esc(meta) + '</small>' + (html ? '<div>' + html + '</div>' : '') +
      (f ? '<div class="fb-files">' + f + '</div>' : '') + '</div>';
  }
  function detailHtml(d, x) {
    var states = x.kind === 'TICKET' ? TICKET_STATE : STATE, state = states[x.state] || x.state;
    if (x.kind !== 'TICKET') {
      return '<div class="fb-chat">' + msg(true, T('我') + ' · ' + day(x.at), esc(title(x)), [], []) +
        (x.replies || []).map(function (r) { return msg(false, T('维护者') + ' · ' + clock(Date.parse(r.at)), linkify(r.body), [], []); }).join('') + '</div>' +
        '<p class="fb-status">' + T('状态：{0}', esc(state)) + (x.joined ? ' · ' + T('同一处已有人反馈过') : '') + '</p>' +
        (x.url ? '<p class="fb-status"><a href="' + esc(x.url) + '" target="_blank" rel="noopener">' + T('在 GitHub 上看') + '</a></p>' : '');
    }
    var pending = x.pending || [];
    var h = '<div class="fb-chat">' + msg(true, T('我') + ' · ' + clock(x.at), '<b>' + esc(x.summary || '') + '</b>' + (x.description ? '\n' + linkify(x.description) : ''), x.files, pending);
    (x.replies || []).forEach(function (r) {
      h += msg(!r.maintainer, (r.maintainer ? T('维护者') : T('我')) + ' · ' + clock(Date.parse(r.at)), r.body ? linkify(r.body) : '', r.files, pending);
      var apks = r.maintainer ? (x.apks || []).filter(function (u) { return r.body.indexOf(u) >= 0; }) : [];
      if (apks.length) {
        h += '<div class="fb-apks">' + apks.map(function (u) {
          return '<button type="button" class="ghost" data-act="install" data-issue="' + x.issue + '" data-url="' + esc(u) + '">' +
            T('把测试包装到电视：{0}', esc(u.split('/').pop())) + '</button>';
        }).join('') + '</div>';
      }
    });
    h += '</div><p class="fb-status">' + T('状态：{0}', esc(state)) + '</p>';
    if (pending.length) {
      h += '<p class="fb-status">' + (x.uploading ? T('附件上传中…')
        : T('有 {0} 个附件没传上去。', pending.length) + ' <button type="button" class="fb-link" data-act="retry" data-issue="' + x.issue + '">' + T('重新上传') + '</button>') + '</p>';
    }
    h += x.state === 'OPEN' ? '<div class="fb-reply-box">' + composer(d, x.issue) + '</div>' : '<p class="hint">' + T('这张工单已经结束，不能再补充。') + '</p>';
    return h;
  }
  function find(d, kind, issue) {
    return (d.items || []).filter(function (x) { return x.kind === kind && x.issue == issue; })[0];
  }

  // ---------------- 「＋」菜单
  var menuEl = document.createElement('div');
  menuEl.className = 'fb-menu';
  menuEl.hidden = true;
  document.body.appendChild(menuEl);
  function menuHtml(d) {
    var t = menu.target, dr = draftOf(d, t), lim = d.limits || {}, maxPhotos = lim.maxPhotos || 4;
    var head = function (text, back) {
      return '<div class="fb-menu-h">' + (back ? '<button type="button" class="sheet-btn" data-act="menu-back" aria-label="' + T('返回') + '">‹</button>' : '') +
        '<span>' + text + '</span><button type="button" class="sheet-btn" data-act="menu-close" aria-label="' + T('关闭') + '">×</button></div>';
    };
    if (menu.view === 'root') {
      var reports = d.reports || [], health = reports.filter(function (r) { return r.kind === 'health'; }).length;
      var item = function (act, text, sub, extra, disabled) {
        return '<button type="button" class="fb-menu-i" data-act="' + act + '"' + (disabled ? ' disabled' : '') + '><span>' + text +
          (sub ? '<small>' + sub + '</small>' : '') + '</span>' + (extra ? '<b>' + extra + '</b>' : '') + '</button>';
      };
      return '<div class="fb-menu-card">' + head(T('附上')) +
        item('rec-start', T('现场录制'), T('最长 {0} 分钟：开始后在电视上把问题复现一遍，再点「结束」', Math.round((lim.recordMs || 300000) / 60000)),
          dr.recorded ? T('重新录') : '', !!d.recording) +
        item('view-health', T('设备体检报告'), T('「维护 → 性能诊断」里做过的体检'), String(health)) +
        item('view-record', T('性能录制报告'), T('「维护 → 性能诊断」里录过的'), String(reports.length - health)) +
        item('photo', T('照片'), T('最多 {0} 张，每张压缩后不超过 {1}', maxPhotos, size(lim.photoBytes || 5242880)),
          dr.photos.length ? dr.photos.length + '/' + maxPhotos : '', dr.photos.length >= maxPhotos) + '</div>';
    }
    var healthView = menu.view === 'health';
    var list = (d.reports || []).filter(function (r) { return (r.kind === 'health') === healthView; });
    var on = {};
    dr.reports.forEach(function (r) { on[r.id] = true; });
    return '<div class="fb-menu-card">' + head(healthView ? T('设备体检报告') : T('性能录制报告'), true) +
      (list.length ? list.map(function (r) {
        return '<button type="button" class="fb-menu-i" data-act="toggle-report" data-id="' + esc(r.id) + '" data-on="' + (on[r.id] ? '0' : '1') + '"><span>' +
          esc(reportLabel(r)) + (r.headline ? '<small>' + esc(r.headline) + '</small>' : '') + '</span>' +
          (on[r.id] ? '<b class="on">' + T('已附上') + '</b>' : '<b>' + T('附上') + '</b>') + '</button>';
      }).join('') : '<p class="hint">' + T('还没有这类报告：先在「设置 → 维护 → 性能诊断」里做一次。') + '</p>') + '</div>';
  }

  // ---------------- 现场录制的悬浮条
  var bar = document.createElement('div');
  bar.className = 'fb-recbar';
  bar.hidden = true;
  bar.innerHTML = '<span></span><button type="button" data-act="rec-stop">' + T('结束') + '</button><button type="button" data-act="rec-cancel">' + T('放弃') + '</button>';
  document.body.appendChild(bar);
  var barText = bar.querySelector('span');
  function paintBar() {
    if (!recBase) { bar.hidden = true; clearInterval(recTick); recTick = 0; return; }
    var ms = Math.min(recBase.elapsed + Date.now() - recBase.at, recBase.max);
    barText.innerHTML = esc(T('正在现场录制 {0} / {1}', mmss(ms), mmss(recBase.max))) + '<small>' +
      esc(recBase.target == 0 ? T('新反馈：在电视上把问题复现一遍，然后点「结束」') : T('工单 #{0} 的补充：复现完点「结束」', recBase.target)) + '</small>';
    bar.hidden = false;
    // 到点了服务端会按到点结束: 再读一次
    if (ms >= recBase.max && !recBase.ended) { recBase.ended = true; load(); }
  }

  // ---------------- 画
  function render(d) {
    if (!d || !d.ok) return;
    last = d;
    var items = d.items || [];
    var open = items.filter(function (x) { return x.state === 'OPEN'; }).length;
    var unread = items.filter(function (x) { return x.unread; }).length;
    window.setNote('feedback', 0, unread ? T('有新回复') : d.recording ? T('正在现场录制') : open ? T('{0} 条处理中', open) : '', !!unread || !!d.recording);
    recBase = d.recording ? { target: d.recording.target, elapsed: d.recording.elapsed, at: Date.now(), max: (d.limits || {}).recordMs || 300000 } : null;
    // 写到一半的文字存在电视的草稿里: 这个页面还没取过的那几张单从电视上取 (刷新、换手机都接得上); 取过之后以这边输入的为准
    Object.keys(d.drafts || {}).forEach(function (t) {
      if (!(t in texts)) texts[t] = Object.assign({}, d.drafts[t].texts || {});
    });
    if (recBase && !recTick) recTick = setInterval(paintBar, 1000);
    paintBar();
    var focused = document.activeElement, focusKey = focused && focused.getAttribute ? focused.getAttribute('data-text') : null;
    // 有行左滑开着 / 正在拖时先不重画列表 (上传中、录制中每 4 秒读一次), 不然行被弹回去
    var changed = window.swBusy(box) ? false : window.setHtml(box, mainHtml(d));
    window.swPeek(box, 'feedback', '.sw-acts.r + .sw-row');
    if (detail) {
      var x = find(d, detail.kind, detail.issue);
      if (x) {
        sheetTitle.textContent = (x.kind === 'TICKET' ? '#' + x.issue + ' ' : '') + title(x);
        if (window.setHtml(sheetBody, detailHtml(d, x))) changed = true;
      }
    }
    if (menu) menuEl.innerHTML = menuHtml(d);
    if (changed) {
      document.querySelectorAll('[data-text]').forEach(function (el) {
        var k = el.getAttribute('data-text').split('.');
        el.value = (texts[k[0]] || {})[k[1]] || '';
      });
      if (focusKey) {
        var again = document.querySelector('[data-text="' + focusKey + '"]');
        if (again) again.focus();
      }
    }
    clearTimeout(pollTimer);
    if (d.recording || items.some(function (x) { return x.uploading; })) pollTimer = setTimeout(load, 4000);
    if (seen && unread) window.post('api/feedback/read', {}).catch(function () {});
  }
  function load() { return window.getJson('api/feedback').then(render).catch(function () {}); }
  function after(r) {
    if (r && r.message) window.toast(r.message);
    return load();
  }

  // ---------------- 照片: 手机上压缩 (长边 2048, JPEG), 太大的不传
  var picker = document.createElement('input');
  picker.type = 'file';
  picker.accept = 'image/*';
  picker.multiple = true;
  picker.hidden = true;
  document.body.appendChild(picker);
  function compress(file, side, quality) {
    return new Promise(function (resolve, reject) {
      if (!/^image\//.test(file.type || '')) { reject(T('「{0}」不是图片', file.name)); return; }
      var url = URL.createObjectURL(file), img = new Image();
      img.onload = function () {
        var k = Math.min(1, side / Math.max(img.naturalWidth, img.naturalHeight));
        var c = document.createElement('canvas');
        c.width = Math.max(1, Math.round(img.naturalWidth * k));
        c.height = Math.max(1, Math.round(img.naturalHeight * k));
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url);
        c.toBlob(function (b) { b ? resolve(b) : reject(T('「{0}」打不开', file.name)); }, 'image/jpeg', quality);
      };
      img.onerror = function () { URL.revokeObjectURL(url); reject(T('「{0}」打不开', file.name)); };
      img.src = url;
    });
  }
  function addPhotos(target, files) {
    var lim = (last && last.limits) || {}, max = lim.maxPhotos || 4, maxBytes = lim.photoBytes || 5242880;
    var room = max - draftOf(last || {}, target).photos.length;
    if (files.length > room) { window.toast(T('一条最多附 {0} 张照片', max)); files = files.slice(0, Math.max(0, room)); }
    var chain = Promise.resolve();
    files.forEach(function (f) {
      chain = chain.then(function () {
        return compress(f, 2048, 0.85).then(function (b) { return b.size > maxBytes ? compress(f, 1600, 0.7) : b; }).then(function (b) {
          if (b.size > maxBytes) throw T('「{0}」压缩后还有 {1}，超过 {2}，上传不了', f.name, size(b.size), size(maxBytes));
          return window.fetchT('api/feedback/photo?target=' + target, { method: 'POST', body: b, headers: { 'Content-Type': 'image/jpeg' } }, 60000)
            .then(function (r) { return r.json(); }).then(function (r) { if (!r.ok) throw r.message; });
        }).catch(function (e) { window.toast(typeof e === 'string' ? e : T('照片没传上去，请确认手机与电视在同一网络后重试')); });
      });
    });
    chain.then(load);
  }
  picker.addEventListener('change', function () {
    var files = [].slice.call(picker.files || []);
    picker.value = '';
    if (files.length) addPhotos(pickTarget, files);
  });

  // ---------------- 操作
  function send(target, btn) {
    var t = texts[target] || {};
    if (target == 0) {
      var s = (t.summary || '').trim(), desc = (t.description || '').trim(), dr = draftOf(last, 0), lim = last.limits || {};
      var minS = lim.minSummary || 4, minD = lim.minDescription || 5;
      if (s.length < minS) { window.toast(T('问题至少写 {0} 个字', minS)); return; }
      if (Array.from(desc).length < minD) { window.toast(T('描述至少写 {0} 个字：怎么操作会出问题、出问题时看到了什么', minD)); return; }
      if (!dr.recorded) { window.toast(T('新反馈要带一段现场录制：点「＋」选「现场录制」，在电视上把问题复现一遍再结束')); return; }
      btn.disabled = true;
      window.post('api/feedback/ticket', { summary: s, description: desc }).then(function (r) {
        btn.disabled = false;
        if (r && r.ok) dropTexts(0);
        after(r);
      }).catch(function () { btn.disabled = false; window.fail(); });
      return;
    }
    btn.disabled = true;
    window.post('api/feedback/reply', { issue: target, body: (t.body || '').trim() }).then(function (r) {
      btn.disabled = false;
      if (r && r.ok) dropTexts(target);
      after(r);
    }).catch(function () { btn.disabled = false; window.fail(); });
  }
  function closeMenu() { menu = null; menuEl.hidden = true; }
  function onClick(e) {
    if (e.currentTarget === menuEl && e.target === menuEl) { closeMenu(); return; }
    var el = e.target.closest('[data-act]');
    if (!el || el.disabled) return;
    var act = el.getAttribute('data-act'), target = el.getAttribute('data-target'), issue = el.getAttribute('data-issue');
    var call = function (path, data) { return window.post(path, data).then(after).catch(function () { window.fail(); }); };
    if (act === 'plus') { menu = { target: target, view: 'root' }; menuEl.innerHTML = menuHtml(last); menuEl.hidden = false; }
    else if (act === 'menu-close') closeMenu();
    else if (act === 'menu-back') { menu.view = 'root'; menuEl.innerHTML = menuHtml(last); }
    else if (act === 'view-health' || act === 'view-record') { menu.view = act === 'view-health' ? 'health' : 'record'; menuEl.innerHTML = menuHtml(last); }
    else if (act === 'toggle-report') call('api/feedback/attach', { target: menu.target, report: el.getAttribute('data-id'), on: el.getAttribute('data-on') });
    else if (act === 'rec-start') {
      var to = menu.target;
      closeMenu();
      call('api/feedback/record', { target: to, action: 'start' });
    } else if (act === 'photo') {
      pickTarget = menu.target;
      closeMenu();
      picker.click();
    } else if (act === 'rec-stop' || act === 'rec-cancel') call('api/feedback/record', { action: act === 'rec-stop' ? 'stop' : 'cancel' });
    else if (act === 'unrec') call('api/feedback/record', { target: target, action: 'remove' });
    else if (act === 'detach') call('api/feedback/attach', { target: target, report: el.getAttribute('data-id'), on: '0' });
    else if (act === 'unphoto') call('api/feedback/photo/remove', { target: target, id: el.getAttribute('data-id') });
    else if (act === 'send') send(target, el);
    else if (act === 'open') {
      detail = { kind: el.getAttribute('data-kind'), issue: issue };
      window.sheets.open(sheet);
      sheetBody.scrollTop = 0;
      render(last);
    } else if (act === 'close') window.sheets.close(sheet);
    else if (act === 'del') {
      var sw = el.closest('.sw');
      window.post('api/feedback/delete', { kind: el.getAttribute('data-kind'), issue: issue }).then(function (r) {
        if (!r.ok) { if (r.message) window.toast(r.message); window.swClose(sw); load(); return; }
        sw.style.height = sw.offsetHeight + 'px';
        void sw.offsetHeight;
        sw.classList.add('gone');
        setTimeout(load, 260);
      }).catch(function () { window.swClose(sw); window.fail(); });
    }
    else if (act === 'retry') call('api/feedback/retry', { issue: issue });
    else if (act === 'sync') {
      syncing = true;
      render(last);
      var done = function (r) {
        syncing = false;
        syncUntil = Date.now() + SYNC_COOLDOWN;
        setTimeout(function () { render(last); }, SYNC_COOLDOWN + 50);
        render(r && r.ok ? r : last);
      };
      window.post('api/feedback/sync', {}).then(function (r) { if (r && r.message) window.toast(r.message); done(r); }).catch(function () { done(null); window.fail(); });
    } else if (act === 'install') {
      el.disabled = true;
      window.post('api/feedback/install', { issue: issue, url: el.getAttribute('data-url') }).then(function (r) {
        el.disabled = false;
        if (r && r.message) window.toast(r.message);
        if (r && r.ok) {
          window.sheets.close(sheet);
          if (window.openSetGroup) window.openSetGroup('maintain');
        }
      }).catch(function () { el.disabled = false; window.fail(); });
    }
  }
  [box, sheet, menuEl, bar].forEach(function (root) {
    root.addEventListener('click', onClick);
    root.addEventListener('input', function (e) {
      var k = e.target.getAttribute && e.target.getAttribute('data-text');
      if (!k) return;
      k = k.split('.');
      (texts[k[0]] = texts[k[0]] || {})[k[1]] = e.target.value;
      saveText(k[0] + '.' + k[1]);
    });
    root.addEventListener('change', function (e) {
      var target = e.target.getAttribute && e.target.getAttribute('data-lead');
      if (target != null) window.post('api/feedback/lead', { target: target, minutes: e.target.value }).then(after).catch(function () { window.fail(); });
    });
    root.addEventListener('keydown', function (e) {
      var el = e.target;
      if (el.getAttribute && el.getAttribute('data-act') === 'open' && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); el.click(); }
    });
  });
  // 输入停下 0.8 秒存一次到电视的草稿; 切走页面 / 关掉时把还没存的立刻送出去 (sendBeacon 在页面关掉后也会发)
  var saveTimers = {};
  function sendText(key) {
    var k = key.split('.'), data = { target: k[0], field: k[1], value: (texts[k[0]] || {})[k[1]] || '' };
    if (document.hidden && navigator.sendBeacon && navigator.sendBeacon('api/feedback/text', new URLSearchParams(data))) return;
    window.post('api/feedback/text', data).catch(function () {});
  }
  function saveText(key) {
    clearTimeout(saveTimers[key]);
    saveTimers[key] = setTimeout(function () { delete saveTimers[key]; sendText(key); }, 800);
  }
  function flushTexts() {
    Object.keys(saveTimers).forEach(function (key) { clearTimeout(saveTimers[key]); delete saveTimers[key]; sendText(key); });
  }
  /** 发出去了: 这张单还没存的文字不再存 (电视上的草稿已经清掉). */
  function dropTexts(target) {
    texts[target] = {};
    Object.keys(saveTimers).forEach(function (key) {
      if (key.split('.')[0] === String(target)) { clearTimeout(saveTimers[key]); delete saveTimers[key]; }
    });
  }
  document.addEventListener('visibilitychange', function () { if (document.hidden) flushTexts(); });
  window.addEventListener('pagehide', flushTexts);
  window.loadFeedback = function () { seen = true; load(); };
  load();
})();
""".trimIndent()

/** 「反馈」页的文案 (见 [RemoteFeedback], 译文表见 [REMOTE_I18N_TABLE]). */
internal fun feedbackTexts() = listOf(
    RemoteText("反馈", "Feedback", "反饋", "回饋"),
    RemoteText("故障反馈，以及标题 logo、对应作品修正的处理进度", "Bug reports, and progress on title logo and matched-title corrections", "故障反饋，以及標題 logo、對應作品修正的處理進度", "故障回饋，以及標題 logo、對應作品修正的處理進度"),
    RemoteText("故障工单", "Bug ticket", "故障工單", "故障工單"),
    RemoteText("标题 logo", "Title logo", "標題 logo", "標題 logo"),
    RemoteText("对应作品", "Matched title", "對應作品", "對應作品"),
    RemoteText("处理中", "In progress", "處理中", "處理中"),
    RemoteText("已采纳", "Accepted", "已採納", "已採納"),
    RemoteText("未采纳", "Not accepted", "未採納", "未採納"),
    RemoteText("已结束", "Closed", "已結束", "已結束"),
    RemoteText("已解决", "Fixed", "已解決", "已解決"),
    RemoteText("不处理", "Won't fix", "不處理", "不處理"),
    RemoteText("《{0}》的标题 logo", "Title logo of “{0}”", "《{0}》的標題 logo", "《{0}》的標題 logo"),
    RemoteText("条目 {0} 的标题 logo", "Title logo of subject {0}", "條目 {0} 的標題 logo", "條目 {0} 的標題 logo"),
    RemoteText("《{0}》对应的 TMDB 作品", "TMDB title matched to “{0}”", "《{0}》對應的 TMDB 作品", "《{0}》對應的 TMDB 作品"),
    RemoteText("条目 {0} 对应的 TMDB 作品", "TMDB title matched to subject {0}", "條目 {0} 對應的 TMDB 作品", "條目 {0} 對應的 TMDB 作品"),
    RemoteText("同一处已有人反馈过", "Already reported by someone", "同一處已有人反饋過", "同一處已有人回饋過"),
    RemoteText("在 GitHub 上看", "View on GitHub", "在 GitHub 上看", "在 GitHub 上看"),
    RemoteText("{0} 条处理中", "{0} in progress", "{0} 條處理中", "{0} 條處理中"),
    RemoteText("有新回复", "New reply", "有新回覆", "有新回覆"),
    RemoteText("新回复", "New", "新回覆", "新回覆"),
    RemoteText("{0} 条回复", "{0} replies", "{0} 條回覆", "{0} 條回覆"),
    RemoteText("有测试包", "Test build available", "有測試包", "有測試包"),
    RemoteText("附件上传中…", "Uploading attachments…", "附件上傳中…", "附件上傳中…"),
    RemoteText("附件没传上去", "Attachments not uploaded", "附件沒傳上去", "附件沒傳上去"),
    // 写反馈
    RemoteText("写反馈", "Report a problem", "寫反饋", "寫回饋"),
    RemoteText(
        "遇到闪退、播放出错、卡顿这类问题时，写清楚问题，附上一段现场录制（还可以附性能报告和照片）交给维护者；回复会出现在下面「我的反馈」里。",
        "When the app crashes, playback fails or things stutter, describe the problem and attach a live recording (performance reports and photos too, if useful) for the maintainer. Replies show up under My feedback below.",
        "遇到閃退、播放出錯、卡頓這類問題時，寫清楚問題，附上一段現場錄製（還可以附性能報告和照片）交給維護者；回覆會出現在下面「我的反饋」裡。",
        "遇到閃退、播放出錯、卡頓這類問題時，寫清楚問題，附上一段現場錄製（還可以附效能報告和照片）交給維護者；回覆會出現在下面「我的回饋」裡。",
    ),
    RemoteText("提交故障反馈", "Report a bug", "提交故障反饋", "提交故障回饋"),
    RemoteText("即将开放", "Coming soon", "即將開放", "即將開放"),
    RemoteText("问题", "Problem", "問題", "問題"),
    RemoteText("一句话说明，如：播放 4K 片源十分钟后黑屏", "In one line, e.g. the screen goes black ten minutes into a 4K video", "一句話說明，如：播放 4K 片源十分鐘後黑屏", "一句話說明，如：播放 4K 片源十分鐘後黑屏"),
    RemoteText(
        "怎么操作会出问题、出问题时看到了什么、是不是每次都这样",
        "What you did, what you saw when it went wrong, and whether it happens every time",
        "怎麼操作會出問題、出問題時看到了甚麼、是不是每次都這樣",
        "怎麼操作會出問題、出問題時看到了什麼、是不是每次都這樣",
    ),
    RemoteText("补充说明：试过的办法、新发现的情况…", "Add a note: what you tried, anything new you noticed…", "補充說明：試過的辦法、新發現的情況…", "補充說明：試過的辦法、新發現的情況…"),
    RemoteText(
        "新反馈要带一段现场录制：点「＋」选「现场录制」，在电视上把问题复现一遍再结束。",
        "A new report needs a live recording: tap “+”, choose Live recording, reproduce the problem on the TV, then stop.",
        "新反饋要帶一段現場錄製：點「＋」選「現場錄製」，在電視上把問題重現一遍再結束。",
        "新回饋要帶一段現場錄製：點「＋」選「現場錄製」，在電視上把問題重現一遍再結束。",
    ),
    RemoteText("附上", "Attach", "附上", "附上"),
    RemoteText("现场录制、性能报告、照片", "Live recording, performance reports, photos", "現場錄製、性能報告、照片", "現場錄製、效能報告、照片"),
    RemoteText("提交", "Submit", "提交", "提交"),
    RemoteText("去掉", "Remove", "去掉", "移除"),
    RemoteText("现场录制中", "Recording", "現場錄製中", "現場錄製中"),
    RemoteText("现场录制 {0}", "Live recording {0}", "現場錄製 {0}", "現場錄製 {0}"),
    RemoteText("日志范围", "Log range", "日誌範圍", "日誌範圍"),
    RemoteText("日志：只要录制期间", "Log: recording only", "日誌：只要錄製期間", "日誌：只要錄製期間"),
    RemoteText("日志：再加录制前 {0} 分钟", "Log: plus {0} min before", "日誌：再加錄製前 {0} 分鐘", "日誌：再加錄製前 {0} 分鐘"),
    RemoteText("照片 {0}", "Photo {0}", "照片 {0}", "照片 {0}"),
    // 「＋」菜单
    RemoteText("现场录制", "Live recording", "現場錄製", "現場錄製"),
    RemoteText("最长 {0} 分钟：开始后在电视上把问题复现一遍，再点「结束」", "Up to {0} min: after it starts, reproduce the problem on the TV, then tap “Stop”", "最長 {0} 分鐘：開始後在電視上把問題重現一遍，再點「結束」", "最長 {0} 分鐘：開始後在電視上把問題重現一遍，再點「結束」"),
    RemoteText("重新录", "Record again", "重新錄", "重新錄"),
    RemoteText("设备体检报告", "Device check reports", "設備體檢報告", "裝置體檢報告"),
    RemoteText("「维护 → 性能诊断」里做过的体检", "Checks run under Maintenance → Performance diagnostics", "「維護 → 性能診斷」裡做過的體檢", "「維護 → 效能診斷」裡做過的體檢"),
    RemoteText("性能录制报告", "Performance recordings", "性能錄製報告", "效能錄製報告"),
    RemoteText("「维护 → 性能诊断」里录过的", "Recorded under Maintenance → Performance diagnostics", "「維護 → 性能診斷」裡錄過的", "「維護 → 效能診斷」裡錄過的"),
    RemoteText("照片", "Photos", "照片", "照片"),
    RemoteText("最多 {0} 张，每张压缩后不超过 {1}", "Up to {0}, each at most {1} after compression", "最多 {0} 張，每張壓縮後不超過 {1}", "最多 {0} 張，每張壓縮後不超過 {1}"),
    RemoteText("已附上", "Attached", "已附上", "已附上"),
    RemoteText("还没有这类报告：先在「设置 → 维护 → 性能诊断」里做一次。", "No such report yet. Run one under Settings → Maintenance → Performance diagnostics first.", "還沒有這類報告：先在「設置 → 維護 → 性能診斷」裡做一次。", "還沒有這類報告：先在「設定 → 維護 → 效能診斷」裡做一次。"),
    // 录制条
    RemoteText("结束", "Stop", "結束", "結束"),
    RemoteText("放弃", "Discard", "放棄", "放棄"),
    RemoteText("正在现场录制 {0} / {1}", "Recording {0} / {1}", "正在現場錄製 {0} / {1}", "正在現場錄製 {0} / {1}"),
    RemoteText("新反馈：在电视上把问题复现一遍，然后点「结束」", "New report: reproduce the problem on the TV, then tap “Stop”", "新反饋：在電視上把問題重現一遍，然後點「結束」", "新回饋：在電視上把問題重現一遍，然後點「結束」"),
    RemoteText("工单 #{0} 的补充：复现完点「结束」", "Note for ticket #{0}: tap “Stop” when done", "工單 #{0} 的補充：重現完點「結束」", "工單 #{0} 的補充：重現完點「結束」"),
    RemoteText("正在现场录制", "Recording", "正在現場錄製", "正在現場錄製"),
    // 照片
    RemoteText("一条最多附 {0} 张照片", "Up to {0} photos per message", "一條最多附 {0} 張照片", "一條最多附 {0} 張照片"),
    RemoteText("「{0}」不是图片", "“{0}” is not an image", "「{0}」不是圖片", "「{0}」不是圖片"),
    RemoteText("「{0}」打不开", "Can't open “{0}”", "「{0}」打不開", "「{0}」打不開"),
    RemoteText("「{0}」压缩后还有 {1}，超过 {2}，上传不了", "“{0}” is still {1} after compression, over {2}. Can't upload it", "「{0}」壓縮後還有 {1}，超過 {2}，上傳不了", "「{0}」壓縮後還有 {1}，超過 {2}，上傳不了"),
    RemoteText("照片没传上去，请确认手机与电视在同一网络后重试", "The photo wasn't uploaded. Make sure the phone and TV are on the same network and try again", "照片沒傳上去，請確認手機與電視在同一網絡後重試", "照片沒傳上去，請確認手機與電視在同一網路後重試"),
    // 我的反馈 / 详情
    RemoteText("我的反馈", "My feedback", "我的反饋", "我的回饋"),
    RemoteText("{0} 更新", "Updated {0}", "{0} 更新", "{0} 更新"),
    RemoteText("刷新中…", "Refreshing…", "刷新中…", "重新整理中…"),
    RemoteText(
        "还没有提交过反馈。在详情页「反馈」里改标题 logo、对应作品，也会记在这里。",
        "No feedback yet. Title logo and matched-title corrections sent from a details page's Feedback also show up here.",
        "還沒有提交過反饋。在詳情頁「反饋」裡改標題 logo、對應作品，也會記在這裡。",
        "還沒有提交過回饋。在詳情頁「回饋」裡改標題 logo、對應作品，也會記在這裡。",
    ),
    RemoteText("本机反馈编号：{0}", "This TV's feedback ID: {0}", "本機反饋編號：{0}", "本機回饋編號：{0}"),
    RemoteText("我", "Me", "我", "我"),
    RemoteText("维护者", "Maintainer", "維護者", "維護者"),
    RemoteText("状态：{0}", "Status: {0}", "狀態：{0}", "狀態：{0}"),
    RemoteText("把测试包装到电视：{0}", "Install test build on TV: {0}", "把測試包裝到電視：{0}", "把測試包裝到電視：{0}"),
    RemoteText("有 {0} 个附件没传上去。", "{0} attachments weren't uploaded.", "有 {0} 個附件沒傳上去。", "有 {0} 個附件沒傳上去。"),
    RemoteText("重新上传", "Upload again", "重新上傳", "重新上傳"),
    RemoteText("这张工单已经结束，不能再补充。", "This ticket is closed and can't take more notes.", "這張工單已經結束，不能再補充。", "這張工單已經結束，不能再補充。"),
    // 服务端回的提示
    RemoteText("故障反馈还没开放", "Bug reports aren't open yet", "故障反饋還沒開放", "故障回饋還沒開放"),
    RemoteText("另一条反馈正在现场录制，先结束那一段", "Another message is recording. Stop that one first", "另一條反饋正在現場錄製，先結束那一段", "另一條回饋正在現場錄製，先結束那一段"),
    RemoteText("这份报告已经不在了", "This report no longer exists", "這份報告已經不在了", "這份報告已經不在了"),
    RemoteText("只能删除已结束的反馈", "Only finished feedback can be deleted", "只能刪除已結束的反饋", "只能刪除已結束的回饋"),
    RemoteText("这张照片太大（{0}），上传不了，最多 {1}", "This photo is too large ({0}). The limit is {1}", "這張照片太大（{0}），上傳不了，最多 {1}", "這張照片太大（{0}），上傳不了，最多 {1}"),
    RemoteText("只能附 JPEG、PNG 或 WebP 图片", "Only JPEG, PNG or WebP images can be attached", "只能附 JPEG、PNG 或 WebP 圖片", "只能附 JPEG、PNG 或 WebP 圖片"),
    RemoteText("问题至少写 {0} 个字", "Write at least {0} characters for the problem", "問題至少寫 {0} 個字", "問題至少寫 {0} 個字"),
    RemoteText("问题最多 {0} 个字，细节写到描述里", "The problem can be at most {0} characters; put details in the description", "問題最多 {0} 個字，細節寫到描述裡", "問題最多 {0} 個字，細節寫到描述裡"),
    RemoteText(
        "描述至少写 {0} 个字：怎么操作会出问题、出问题时看到了什么",
        "Write at least {0} characters in the description: what you did and what you saw when it went wrong",
        "描述至少寫 {0} 個字：怎麼操作會出問題、出問題時看到了甚麼",
        "描述至少寫 {0} 個字：怎麼操作會出問題、出問題時看到了什麼",
    ),
    RemoteText("描述最多 {0} 个字", "The description can be at most {0} characters", "描述最多 {0} 個字", "描述最多 {0} 個字"),
    RemoteText("已提交，工单 #{0}。附件正在上传。", "Submitted as ticket #{0}. Uploading attachments.", "已提交，工單 #{0}。附件正在上傳。", "已提交，工單 #{0}。附件正在上傳。"),
    RemoteText("最多 {0} 个字", "At most {0} characters", "最多 {0} 個字", "最多 {0} 個字"),
    RemoteText("已补充", "Note added", "已補充", "已補充"),
    RemoteText("连不上工单服务，检查电视的网络后再试", "Can't reach the ticket service. Check the TV's network and try again", "連不上工單服務，檢查電視的網絡後再試", "連不上工單服務，檢查電視的網路後再試"),
    RemoteText("有的处理结果没查到，检查电视的网络后再试", "Some results couldn't be checked. Check the TV's network and try again", "有的處理結果沒查到，檢查電視的網絡後再試", "有的處理結果沒查到，檢查電視的網路後再試"),
    RemoteText(
        "新反馈要带一段现场录制：点「＋」选「现场录制」，在电视上把问题复现一遍再结束",
        "A new report needs a live recording: tap “+”, choose Live recording, reproduce the problem on the TV, then stop",
        "新反饋要帶一段現場錄製：點「＋」選「現場錄製」，在電視上把問題重現一遍再結束",
        "新回饋要帶一段現場錄製：點「＋」選「現場錄製」，在電視上把問題重現一遍再結束",
    ),
    RemoteText("还在现场录制，先点「结束」", "Still recording. Tap “Stop” first", "還在現場錄製，先點「結束」", "還在現場錄製，先點「結束」"),
    RemoteText("先写点什么，或者附上东西", "Write something or attach something first", "先寫點甚麼，或者附上東西", "先寫點什麼，或者附上東西"),
    RemoteText("这张工单已经结束，不能再补充", "This ticket is closed and can't take more notes", "這張工單已經結束，不能再補充", "這張工單已經結束，不能再補充"),
    RemoteText("提交太频繁，过一会儿再试", "Too many requests. Try again in a moment", "提交太頻繁，過一會兒再試", "提交太頻繁，過一會兒再試"),
    RemoteText("工单服务出错了，稍后再试", "The ticket service ran into an error. Try again later", "工單服務出錯了，稍後再試", "工單服務出錯了，稍後再試"),
    RemoteText("这台电视暂时不能提交故障反馈", "This TV can't send bug reports for now", "這部電視暫時不能提交故障反饋", "這台電視暫時不能提交故障回饋"),
    RemoteText("还有 {0} 张工单在处理中，处理完再提交新的", "{0} tickets are still open. Submit a new one after they're handled", "還有 {0} 張工單在處理中，處理完再提交新的", "還有 {0} 張工單在處理中，處理完再提交新的"),
    RemoteText("附件太大或太多，去掉一些再试", "Attachments are too large or too many. Remove some and try again", "附件太大或太多，去掉一些再試", "附件太大或太多，去掉一些再試"),
    RemoteText("工单服务不收这次的内容（{0}）", "The ticket service rejected this report ({0})", "工單服務不收這次的內容（{0}）", "工單服務不收這次的內容（{0}）"),
    RemoteText("正在重新上传附件", "Uploading attachments again", "正在重新上傳附件", "正在重新上傳附件"),
    RemoteText("这张工单没有要上传的附件", "This ticket has no attachments waiting to upload", "這張工單沒有要上傳的附件", "這張工單沒有要上傳的附件"),
    RemoteText("这个测试包已经不在工单的回复里了", "This test build is no longer in the ticket's replies", "這個測試包已經不在工單的回覆裡了", "這個測試包已經不在工單的回覆裡了"),
    RemoteText(
        "测试包已交给「应用更新」，在那里下载并安装",
        "The test build was handed to App update. Download and install it there",
        "測試包已交給「應用更新」，在那裡下載並安裝",
        "測試包已交給「應用更新」，在那裡下載並安裝",
    ),
    // 「应用更新」卡片里的测试包 (见 RemoteAppUpdate.offer)
    RemoteText("测试包（工单 #{0}）：{1}", "Test build (ticket #{0}): {1}", "測試包（工單 #{0}）：{1}", "測試包（工單 #{0}）：{1}"),
    RemoteText("下载并安装测试包", "Download and install the test build", "下載並安裝測試包", "下載並安裝測試包"),
    RemoteText(
        "测试包已经不在了，回到「反馈」页再点一次「装到电视」",
        "The test build is gone. Go back to Feedback and tap “Install on TV” again",
        "測試包已經不在了，回到「反饋」頁再點一次「裝到電視」",
        "測試包已經不在了，回到「回饋」頁再點一次「裝到電視」",
    ),
)
