/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.subject.nameCnOrName
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.comment.CommentContext
import me.him188.ani.app.domain.comment.CommentSendResult
import me.him188.ani.app.domain.comment.PostCommentUseCase
import me.him188.ani.app.domain.danmaku.DanmakuLoadingState
import me.him188.ani.app.domain.danmaku.DanmakuRepository
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.ui.DanmakuPresentation
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.videoplayer.ui.progress.audioName
import me.him188.ani.app.videoplayer.ui.progress.subtitleLanguage
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuEpisode
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.DanmakuSubject
import me.him188.ani.danmaku.api.provider.MatchingDanmakuProvider
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/** 字幕轨 / 音轨的候选与当前选中 (播放页组合里收集, 见 RegisterTvRemotePlayer). */
internal class RemoteTrackState<T>(val candidates: List<T>, val selected: T?)

/**
 * 手机网页「播放器」标签里的**弹幕**与**音轨 / 字幕轨** —— 播放器里遥控器最难用的几处:
 *
 * - 弹幕匹配错了要换: 电视上是层层弹窗 + 打字搜条目. 手机上直接驱动弹弹play 的交互匹配 (搜条目 → 选集 → 取弹幕),
 *   拿到结果交给 `vm.onMatchingDanmakuComplete`, 与电视上手动匹配完成那一步同一个入口. **不调
 *   `vm.startMatchingDanmaku`**: 那会让电视上弹出匹配对话框. 只有弹弹play 支持手动匹配 (Animeko 源按 id 匹配);
 *   覆盖只在内存里、只对当前这一集有效, 换集即作废 (同电视上).
 * - 各弹幕源的开关与时间偏移: 同电视上弹幕面板, 也只对这次播放有效; 总开关会记住.
 * - 音轨: Android 上播放器界面**根本没有**音轨切换 (只有桌面端有), 多音轨的片子在电视上换不了.
 *   字幕轨: 电视上有, 但若用户在电视上明确选过, 播放器那边会把手机的选择改回去 (SubtitleTrackState 记着电视的选择).
 */
internal object RemotePlayerExtras {
    private val logger = logger<RemotePlayerExtras>()

    private val danmakuRepository: DanmakuRepository get() = KoinPlatform.getKoin().get()

    /** 处理 `api/player/danmaku/` 下与 `api/player/track` 的写请求. */
    fun handle(handle: RemotePlayerHandle, request: LanHttpRequest): JsonObject {
        val f = request.formFields()
        return runCatching {
            when (request.path) {
                "api/player/danmaku/enable" -> {
                    val on = f["on"] == "1"
                    handle.runOnUi { handle.vm.setDanmakuEnabled(on) }
                    result(true, if (on) tr("已打开弹幕") else tr("已关闭弹幕"))
                }

                "api/player/danmaku/source" -> {
                    val service = f["service"].orEmpty().ifEmpty { return result(false, tr("无效的弹幕源")) }
                    val on = f["on"] == "1"
                    handle.runOnUi { handle.vm.setDanmakuSourceEnabled(DanmakuServiceId(service), on) }
                    result(true, "")
                }

                "api/player/danmaku/shift" -> {
                    val service = f["service"].orEmpty().ifEmpty { return result(false, tr("无效的弹幕源")) }
                    val ms = f["ms"]?.toLongOrNull()?.coerceIn(-MAX_SHIFT_MILLIS, MAX_SHIFT_MILLIS)
                        ?: return result(false, tr("无效的偏移"))
                    handle.runOnUi { handle.vm.setDanmakuSourceShiftMillis(DanmakuServiceId(service), ms) }
                    result(true, "")
                }

                "api/player/danmaku/search" -> search(f["q"].orEmpty().trim())
                "api/player/danmaku/episodes" -> episodes(handle, DanmakuSubject(f["sid"].orEmpty(), f["sname"].orEmpty()))
                "api/player/danmaku/apply" -> apply(
                    handle,
                    DanmakuSubject(f["sid"].orEmpty(), f["sname"].orEmpty()),
                    DanmakuEpisode(f["eid"].orEmpty(), f["ename"].orEmpty()),
                )

                "api/player/danmaku/send" -> sendDanmaku(handle, f["text"].orEmpty())
                "api/player/review" -> reviewState(handle)
                "api/player/review/collect" -> setCollection(handle, f["type"].orEmpty())
                "api/player/review/rate" -> rate(handle, f)
                "api/player/comment" -> postComment(handle, f["text"].orEmpty())

                "api/player/track" -> {
                    val id = f["id"].orEmpty()
                    val ok = when (f["kind"]) {
                        "sub" -> handle.selectSubtitle(id)
                        "audio" -> handle.selectAudio(id)
                        else -> false
                    }
                    if (ok) result(true, "") else result(false, tr("这条轨道已经不在了"))
                }

                else -> result(false, tr("未知操作"))
            }
        }.getOrElse {
            logger.warn(it) { "Remote player extras request failed: ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun matcher(): MatchingDanmakuProvider? =
        danmakuRepository.getInteractiveDanmakuFetcherOrNull(DanmakuProviderId.Dandanplay)?.startInteractiveMatch()

    private fun search(q: String): JsonObject {
        if (q.isEmpty()) return result(false, tr("请输入番剧名"))
        val m = matcher() ?: return result(false, tr("弹弹play 不可用"))
        val list = runBlocking { withTimeoutOrNull(NETWORK_TIMEOUT) { m.fetchSubjectList(q) } }
            ?: return result(false, tr("搜索超时，请重试"))
        return buildJsonObject {
            put("ok", true)
            putJsonArray("items") {
                for (s in list) addJsonObject {
                    put("id", s.id)
                    put("name", s.name)
                }
            }
        }
    }

    private fun episodes(handle: RemotePlayerHandle, subject: DanmakuSubject): JsonObject {
        if (subject.id.isEmpty()) return result(false, tr("无效的条目"))
        val m = matcher() ?: return result(false, tr("弹弹play 不可用"))
        val list = runBlocking { withTimeoutOrNull(NETWORK_TIMEOUT) { m.fetchEpisodeList(subject) } }
            ?: return result(false, tr("加载剧集超时，请重试"))
        // 标出与当前这一集集号相同的那一条, 手机上自动滚过去 (弹弹play 的集名通常是「第5话 标题」)
        val sort = handle.page?.episodePresentation?.sort?.toFloatOrNull()
        val suggested = if (sort == null) -1 else list.indexOfFirst { ep ->
            NUMBER.findAll(ep.name).any { it.value.toFloatOrNull() == sort }
        }
        return buildJsonObject {
            put("ok", true)
            put("suggested", suggested)
            putJsonArray("items") {
                for (e in list) addJsonObject {
                    put("id", e.id)
                    put("name", e.name)
                }
            }
        }
    }

    private fun apply(handle: RemotePlayerHandle, subject: DanmakuSubject, episode: DanmakuEpisode): JsonObject {
        if (subject.id.isEmpty() || episode.id.isEmpty()) return result(false, tr("无效的剧集"))
        val m = matcher() ?: return result(false, tr("弹弹play 不可用"))
        val results = runBlocking { withTimeoutOrNull(NETWORK_TIMEOUT) { m.fetchDanmakuList(subject, episode) } }
            ?: return result(false, tr("加载弹幕超时，请重试"))
        val total = results.sumOf { it.list.size }
        handle.runOnUi { handle.vm.onMatchingDanmakuComplete(DanmakuProviderId.Dandanplay, results) }
        logger.info { "Remote control applied manual danmaku match: $total danmaku" }
        return result(true, tr("已换成「{0}」{1}，共 {2} 条弹幕", subject.name, episode.name, total))
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    // ============================ 发弹幕 / 评论 / 评分 ============================

    /**
     * 在电视当前进度上发一条弹幕 (同电视上的发送: 白色、普通滚动). 要登录 (服务端校验). 成功后同电视上一样把自己这条
     * 立刻放上屏幕 —— 后台会话没有画面, 不放 (放了会一直等画面就绪).
     */
    private fun sendDanmaku(handle: RemotePlayerHandle, raw: String): JsonObject {
        val text = raw.trim()
        if (text.isEmpty()) return result(false, tr("请输入弹幕内容"))
        if (text.length > MAX_DANMAKU_LENGTH) return result(false, tr("弹幕太长了（最多 {0} 个字）", MAX_DANMAKU_LENGTH))
        val vm = handle.vm
        val content = DanmakuContent(vm.player.currentPositionMillis.value, WHITE_ARGB, text, DanmakuLocation.NORMAL)
        val info = runCatching { runBlocking { withTimeoutOrNull(NETWORK_TIMEOUT) { vm.postDanmaku(content) } } }
            .getOrElse { return result(false, errorText(it, tr("发送失败"))) }
            ?: return result(false, tr("发送超时，请重试"))
        if (!handle.background) handle.runOnUi { vm.danmakuHostState.send(DanmakuPresentation(info, isSelf = true)) }
        return result(true, tr("已发送"))
    }

    /** 收藏状态 / 我的评分与短评 (预填手机上的表单), 以及登录与否. */
    private fun reviewState(handle: RemotePlayerHandle): JsonObject {
        val subjectId = handle.vm.subjectId
        val info = runCatching {
            runBlocking { withTimeoutOrNull(PREFILL_TIMEOUT) { collectionRepository.subjectCollectionFlow(subjectId).first() } }
        }.getOrNull() ?: return result(false, tr("读取收藏信息失败，请重试"))
        val loggedIn = runBlocking { withTimeoutOrNull(3.seconds) { sessionStateProvider.stateFlow.first() } } is SessionState.Valid
        val rating = info.selfRatingInfo
        return buildJsonObject {
            put("ok", true)
            put("loggedIn", loggedIn)
            put("title", info.subjectInfo.nameCnOrName)
            put("collection", info.collectionType.name)
            put("score", rating.score)
            put("comment", rating.comment.orEmpty())
            put("private", rating.isPrivate)
            handle.page?.episodePresentation?.let { put("episode", tr("第 {0} 话", it.sort)) }
        }
    }

    private fun setCollection(handle: RemotePlayerHandle, typeName: String): JsonObject {
        val type = UnifiedCollectionType.entries.firstOrNull { it.name == typeName } ?: return result(false, tr("无效的收藏状态"))
        val done = runCatching {
            runBlocking { withTimeoutOrNull(NETWORK_TIMEOUT) { setCollectionType(handle.vm.subjectId, type); true } }
        }.getOrElse { return result(false, errorText(it, tr("设置失败"))) }
        return if (done == true) result(true, tr("已设为「{0}」", tr(COLLECTION_LABELS[type].orEmpty()))) else result(false, tr("操作超时，请重试"))
    }

    /**
     * 不依赖播放页的收藏状态 (`api/subject/collection`, 手机搜索结果左滑「收藏」用): GET `?id=` 读当前状态, POST `id` + `type` 设.
     * 设法与播放页「评论与评分」里那一排一样 (同一个用例、同样的提示).
     */
    fun subjectCollection(request: LanHttpRequest): JsonObject {
        val post = request.method == "POST"
        val f = if (post) request.formFields() else request.query.split('&').mapNotNull { p ->
            p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val subjectId = f["id"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        if (!post) {
            val info = runCatching {
                runBlocking { withTimeoutOrNull(PREFILL_TIMEOUT) { collectionRepository.subjectCollectionFlow(subjectId).first() } }
            }.getOrNull() ?: return result(false, tr("读取收藏状态失败，请重试"))
            return buildJsonObject {
                put("ok", true)
                put("title", info.subjectInfo.nameCnOrName)
                put("collection", info.collectionType.name)
            }
        }
        val type = UnifiedCollectionType.entries.firstOrNull { it.name == f["type"] } ?: return result(false, tr("无效的收藏状态"))
        val done = runCatching {
            runBlocking { withTimeoutOrNull(NETWORK_TIMEOUT) { setCollectionType(subjectId, type); true } }
        }.getOrElse { return result(false, errorText(it, tr("设置失败"))) }
        logger.info { "Remote set collection: subject $subjectId -> $type" }
        return if (done == true) result(true, tr("已设为「{0}」", tr(COLLECTION_LABELS[type].orEmpty()))) else result(false, tr("操作超时，请重试"))
    }

    /**
     * 评分 (0 = 不评分) + 短评 + 仅自己可见. **四项每次都要带全**: 服务端把没带的当成清空 (标签也是), 所以标签照原样带回去.
     * 没收藏的条目评不了分 (同电视详情页的规则), 先提示去设收藏状态.
     */
    private fun rate(handle: RemotePlayerHandle, f: Map<String, String>): JsonObject {
        val score = f["score"]?.toIntOrNull()?.takeIf { it in 0..10 } ?: return result(false, tr("评分要在 0~10 之间"))
        val comment = f["comment"].orEmpty().trim()
        val isPrivate = f["private"] == "1"
        val subjectId = handle.vm.subjectId
        val outcome = runCatching {
            runBlocking {
                withTimeoutOrNull(NETWORK_TIMEOUT) {
                    val info = collectionRepository.subjectCollectionFlow(subjectId).first()
                    if (info.collectionType == UnifiedCollectionType.NOT_COLLECTED) return@withTimeoutOrNull false
                    collectionRepository.updateRating(
                        subjectId,
                        score = score,
                        comment = comment,
                        tags = info.selfRatingInfo.tags,
                        isPrivate = isPrivate,
                    )
                    true
                }
            }
        }.getOrElse { return result(false, errorText(it, tr("保存失败"))) }
        return when (outcome) {
            null -> result(false, tr("操作超时，请重试"))
            false -> result(false, tr("先设置收藏状态（想看 / 在看 / 看过…）再评分"))
            true -> result(true, if (score == 0) tr("已保存（不评分）") else tr("已保存：{0} 分", score))
        }
    }

    /** 发表本集评论 (同电视上的评论框, 不走人机验证). 失败时用例不区分「没登录」与「网络错误」, 提示里一并说. */
    private fun postComment(handle: RemotePlayerHandle, raw: String): JsonObject {
        val text = raw.trim()
        if (text.isEmpty()) return result(false, tr("请输入评论内容"))
        val episodeId = handle.page?.episodePresentation?.episodeId ?: return result(false, tr("还不知道当前是哪一集，请稍后再试"))
        val res = runBlocking {
            withTimeoutOrNull(NETWORK_TIMEOUT) { postCommentUseCase(CommentContext.Episode(handle.vm.subjectId, episodeId.toLong()), text) }
        } ?: return result(false, tr("发表超时，请重试"))
        return when (res) {
            CommentSendResult.Ok -> result(true, tr("已发表到本集评论"))
            CommentSendResult.NetworkError -> result(false, tr("发表失败：网络错误，或者电视还没登录"))
            is CommentSendResult.UnknownError -> result(false, tr("发表失败：{0}", res.message))
        }
    }

    private fun errorText(e: Throwable, prefix: String): String = when (e) {
        is RepositoryAuthorizationException -> tr("需要先登录（手机上「设置」→「账号」可以直接登录）")
        is RepositoryRateLimitedException -> tr("操作太频繁，稍后再试")
        is RepositoryNetworkException -> tr("网络错误，请重试")
        else -> "$prefix：${e.message ?: e::class.simpleName}"
    }

    /** 弹幕状态, 放进播放器状态 JSON (随版本号变化才重发). */
    fun JsonObjectBuilder.putDanmakuState(page: EpisodePageState) {
        val stats = page.danmakuStatistics
        putJsonObject("danmaku") {
            put("enabled", page.danmakuEnabled)
            put("loading", stats.danmakuLoadingState is DanmakuLoadingState.Loading)
            put("canMatch", danmakuRepository.getInteractiveDanmakuFetcherOrNull(DanmakuProviderId.Dandanplay) != null)
            putJsonArray("sources") {
                for (r in stats.fetchResults) addJsonObject {
                    put("service", r.serviceId.value)
                    put("name", SERVICE_NAMES[r.serviceId.value] ?: r.serviceId.value)
                    put("count", r.matchInfo.count)
                    put("on", r.config.enabled)
                    put("shift", r.config.shiftMillis)
                    when (val m = r.matchInfo.method) {
                        is DanmakuMatchMethod.Exact -> {
                            put("method", tr("精确匹配"))
                            put("matched", "${m.subjectTitle} ${m.episodeTitle}".trim())
                        }

                        is DanmakuMatchMethod.ExactSubjectFuzzyEpisode -> {
                            put("method", tr("半模糊匹配"))
                            put("matched", "${m.subjectTitle} ${m.episodeTitle}".trim())
                        }

                        is DanmakuMatchMethod.Fuzzy -> {
                            put("method", tr("模糊匹配"))
                            put("matched", "${m.subjectTitle} ${m.episodeTitle}".trim())
                        }

                        is DanmakuMatchMethod.ExactId -> put("method", tr("按条目匹配"))
                        else -> put("method", tr("没有匹配到"))
                    }
                }
            }
        }
    }

    /** 音轨 / 字幕轨, 放进播放器状态 JSON. `sel` 为 null = 自动 (音轨) / 关闭 (字幕). */
    fun JsonObjectBuilder.putTrackState(handle: RemotePlayerHandle) {
        putJsonObject("tracks") {
            handle.audioState?.let { st ->
                putJsonObject("audio") {
                    put("sel", st.selected?.id)
                    putJsonArray("items") {
                        for (t in st.candidates) addJsonObject {
                            put("id", t.id)
                            put("name", t.audioName)
                        }
                    }
                }
            }
            handle.subtitleState?.let { st ->
                putJsonObject("subs") {
                    put("sel", st.selected?.id)
                    putJsonArray("items") {
                        for (t in st.candidates) addJsonObject {
                            put("id", t.id)
                            put("name", t.subtitleLanguage)
                        }
                    }
                }
            }
        }
    }

    private val NETWORK_TIMEOUT = 20.seconds
    private val PREFILL_TIMEOUT = 10.seconds
    private const val MAX_DANMAKU_LENGTH = 100

    /** 同电视上发弹幕用的颜色 (白, ARGB). */
    private const val WHITE_ARGB = -1

    private val collectionRepository: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val setCollectionType: SetSubjectCollectionTypeOrDeleteUseCase get() = KoinPlatform.getKoin().get()
    private val postCommentUseCase: PostCommentUseCase get() = KoinPlatform.getKoin().get()
    private val sessionStateProvider: SessionStateProvider get() = KoinPlatform.getKoin().get()

    private val COLLECTION_LABELS = mapOf(
        UnifiedCollectionType.WISH to "想看",
        UnifiedCollectionType.DOING to "在看",
        UnifiedCollectionType.DONE to "看过",
        UnifiedCollectionType.ON_HOLD to "搁置",
        UnifiedCollectionType.DROPPED to "抛弃",
        UnifiedCollectionType.NOT_COLLECTED to "未收藏",
    )

    /** 偏移上限: 电视上的偏移对话框是 ±30 秒, 这里放宽一倍, 防手机上连点跑飞. */
    private const val MAX_SHIFT_MILLIS = 60_000L

    private val NUMBER = Regex("""\d+(\.\d+)?""")

    private val SERVICE_NAMES = mapOf(
        "Animeko" to "Animeko",
        "Dandanplay" to "弹弹play",
        "Bilibili" to "哔哩哔哩",
        "Acfun" to "AcFun",
        "Baha" to "巴哈姆特",
        "Tucao" to "吐槽",
    )
}
