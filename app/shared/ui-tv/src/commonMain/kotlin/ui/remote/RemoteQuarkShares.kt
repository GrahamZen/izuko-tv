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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.quark.QuarkAddedShareMediaSource
import me.him188.ani.app.domain.mediasource.quark.QuarkAddedShareService
import me.him188.ani.app.domain.mediasource.quark.QuarkDriveService
import me.him188.ani.app.domain.mediasource.quark.QuarkShareFileInfo
import me.him188.ani.app.domain.mediasource.quark.QuarkShareInspection
import me.him188.ani.app.domain.mediasource.quark.QuarkShareLink
import me.him188.ani.app.domain.mediasource.quark.QuarkShareLinks
import me.him188.ani.app.domain.mediasource.quark.QuarkShareReadStatus
import me.him188.ani.app.domain.mediasource.quark.QuarkShareUnavailableException
import me.him188.ani.app.domain.mediasource.quark.ensureAddedShareMediaSource
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台播放器页的「添加夸克分享」: 用户把自己找到的夸克分享链接粘贴进来, 电视打开分享、按当前这部番对出剧集,
 * 对上了就记到这部番名下 (「我添加的分享」数据源, 见 [QuarkAddedShareService]), 之后每一集的选源列表里都有它.
 *
 * 一集都没认出时不记下, 把分享里的视频列给手机, 点一个就临时播成当前这一集.
 */
internal object RemoteQuarkShares {
    private val logger = logger<RemoteQuarkShares>()

    private val service: QuarkAddedShareService get() = KoinPlatform.getKoin().get()
    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()
    private val drive: QuarkDriveService get() = KoinPlatform.getKoin().get()

    /** 一次粘贴里最多认几个链接. */
    private const val MAX_LINKS = 5

    /** 最近打开过的分享 (分享 id → 结果), 手机上点「播放」时凭它做资源, 不用再打开一次. */
    private val recent = object : LinkedHashMap<String, QuarkShareInspection>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QuarkShareInspection>?) = size > 16
    }

    /** 处理 `api/player/shares` 下的请求; 路径或方法不认识返回 null. */
    fun handle(player: RemotePlayerHandle?, request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/player/shares" && get -> list(player)
                !post -> null
                request.path == "api/player/shares/add" -> add(player, request.field("text"))
                request.path == "api/player/shares/delete" -> delete(player, request.field("id"))
                request.path == "api/player/shares/play" -> play(player, request.field("share"), request.field("fid"))
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote quark shares request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun list(player: RemotePlayerHandle?): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val shares = runBlocking { service.sharesOf(handle.vm.subjectId) }
        return buildJsonObject {
            put("ok", true)
            put("message", "")
            put("loggedIn", loggedIn())
            putJsonArray("shares") {
                for (share in shares) addJsonObject {
                    put("id", share.shareId)
                    put("title", share.title.ifBlank { share.shareId })
                    put("addedAt", share.addedAtMillis)
                    service.readStatusOf(share.shareId)?.let { status ->
                        val note = noteOf(status) ?: return@let
                        put("note", note)
                        put("noteLevel", if (status.savedCopies > 0) "attention" else "error")
                    }
                }
            }
        }
    }

    /** 最近一次打开这个分享时出了问题的话, 说一句是什么问题、还能不能播. 打开了且有视频时为 null. */
    private fun noteOf(status: QuarkShareReadStatus): String? {
        if (status.videos > 0) return null
        val reason = status.error?.let { tr("打不开这个分享（{0}）", it) }
            ?: tr("分享里已经没有文件了，可能被分享者删除或被夸克屏蔽")
        val playable = if (status.savedCopies > 0) {
            tr("。已经转存到你网盘的 {0} 集照常能播", status.savedCopies)
        } else {
            tr("。可以删掉它，换一个分享链接")
        }
        return reason + playable
    }

    private fun add(player: RemotePlayerHandle?, text: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val request = handle.page?.fetchRequest ?: return result(false, tr("数据源还在加载，请稍后再试"))
        val links = QuarkShareLinks.parse(text)
        if (links.isEmpty()) {
            return result(false, if (OTHER_DRIVES.containsMatchIn(text)) tr("只支持夸克网盘的分享链接") else tr("没有找到夸克分享链接"))
        }
        val subjectId = handle.vm.subjectId
        val outcomes = runBlocking {
            links.take(MAX_LINKS).map { link ->
                async { inspect(request, link) }
            }.awaitAll()
        }
        val added = outcomes.mapNotNull { it.inspection?.takeIf { inspection -> inspection.episodes.isNotEmpty() } }
        var message = outcomes.singleOrNull()?.message ?: tr("已处理 {0} 个链接，看下面每个的结果", outcomes.size)
        if (added.isNotEmpty()) {
            runBlocking { added.forEach { service.add(subjectId, it) } }
            message += refreshAddedSource(handle)
        }
        if (links.size > MAX_LINKS) message += tr("（一次最多处理 {0} 个链接）", MAX_LINKS)
        return buildJsonObject {
            put("ok", added.isNotEmpty())
            put("message", message)
            put("loggedIn", loggedIn())
            putJsonArray("results") {
                for (outcome in outcomes) addJsonObject { putOutcome(outcome) }
            }
        }
    }

    private class Outcome(val link: QuarkShareLink, val message: String, val inspection: QuarkShareInspection? = null)

    private suspend fun inspect(request: MediaFetchRequest, link: QuarkShareLink): Outcome {
        val inspection = try {
            withTimeoutOrNull(INSPECT_TIMEOUT) { service.inspect(request, link) }
                ?: return Outcome(link, tr("打开分享超时，请稍后再试"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuarkShareUnavailableException) {
            logger.info { "Remote added Quark share ${link.shareId} unavailable: ${e.message}" }
            val needsPasscode = link.passcode.isEmpty() && e.message.orEmpty().let { it.contains("提取码") || it.contains("密码") }
            return Outcome(
                link,
                if (needsPasscode) tr("这个分享要提取码，请把「提取码：xxxx」一起粘贴进来")
                else tr("分享打不开：{0}", e.message.orEmpty()),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Remote failed to open Quark share ${link.shareId}" }
            return Outcome(link, tr("打开分享失败：{0}", e.message ?: e::class.simpleName))
        }
        synchronized(recent) { recent[link.shareId] = inspection }
        val message = when {
            inspection.files.isEmpty() -> tr("分享里没有视频文件，可能被分享者删除或被夸克屏蔽了")

            inspection.episodes.isEmpty() ->
                tr("没认出这部番的剧集（文件名认不出集号，或者季对不上）。在下面点一个文件当作这一集播放，会记下这个分享和这一集")

            inspection.currentFiles.isNotEmpty() -> tr("认出第 {0} 集", formatEpisodes(inspection.episodes))
            else -> tr("认出第 {0} 集，没有第 {1} 集", formatEpisodes(inspection.episodes), request.episodeSort)
        }
        return Outcome(link, message, inspection)
    }

    private fun JsonObjectBuilder.putOutcome(outcome: Outcome) {
        val inspection = outcome.inspection
        put("shareId", outcome.link.shareId)
        put("title", inspection?.title.orEmpty())
        put("ok", inspection != null && inspection.episodes.isNotEmpty())
        put("message", outcome.message)
        if (inspection == null) return
        putJsonArray("current") {
            for (file in inspection.currentFiles) addJsonObject { putFile(file) }
        }
        // 当前这一集没认出时给全部文件, 让用户手动挑 (认出了的看选源列表就行)
        if (inspection.currentFiles.isEmpty()) {
            putJsonArray("files") {
                for (file in inspection.files.take(MAX_LISTED_FILES)) addJsonObject { putFile(file) }
            }
            if (inspection.files.size > MAX_LISTED_FILES) put("moreFiles", inspection.files.size - MAX_LISTED_FILES)
        }
    }

    private fun JsonObjectBuilder.putFile(file: QuarkShareFileInfo) {
        put("fid", file.fid)
        put("name", file.fileName)
        put("meta", listOfNotNull(file.folders.lastOrNull(), file.size.takeIf { it > 0 }?.bytes?.toString()).joinToString(" · "))
    }

    private fun delete(player: RemotePlayerHandle?, shareId: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        if (shareId.isBlank()) return result(false, tr("这个分享已经不在了"))
        runBlocking { service.remove(handle.vm.subjectId, shareId) }
        addedSourceId()?.let { handle.vm.restartSource(it) }
        return result(true, tr("已删除"))
    }

    /**
     * 播放最近打开的分享里的文件 [fid] (当作当前这一集).
     *
     * 不是自动认出的这一集时 (用户手动挑的), 先记下这个分享与「这个文件是这一集」, 让「我添加的分享」重查.
     * 等选源列表里出现这一条再照电视上点选 (会记住这部番用这个源, 列表里也标着正在播放); 等不到 (还在重查) 就先临时播.
     */
    private fun play(player: RemotePlayerHandle?, shareId: String, fid: String): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val request = handle.page?.fetchRequest ?: return result(false, tr("数据源还在加载，请稍后再试"))
        val inspection = synchronized(recent) { recent[shareId] } ?: return result(false, tr("找不到这个文件，请重新粘贴链接"))
        if (inspection.files.none { it.fid == fid }) return result(false, tr("找不到这个文件，请重新粘贴链接"))
        val picked = inspection.currentFiles.none { it.fid == fid }
        if (picked) {
            runBlocking { service.pick(handle.vm.subjectId, inspection, fid, request.episodeSort) }
            refreshAddedSource(handle)
        }
        val sourceId = addedSourceId() ?: QuarkAddedShareMediaSource.FactoryId.value
        val media = service.mediaOf(inspection, fid, sourceId, request) ?: return result(false, tr("找不到这个文件，请重新粘贴链接"))
        if (awaitCandidate(handle, media.mediaId)) {
            handle.select(media.mediaId)?.let { return result(false, it) }
        } else {
            handle.vm.playTemporarily(media)
        }
        val hint = if (loggedIn()) "" else tr("。播放前要先登录夸克（「数据源」页的「夸克网盘」）")
        val message = if (picked) tr("已记下这个文件是第 {0} 集，正在电视上播放", request.episodeSort) else tr("正在电视上播放")
        return result(true, message + hint)
    }

    /**
     * 让这次搜索里的「我添加的分享」重查: 搜索会话取的数据源快照里没有它 (刚建的, 或者会话比它早) 时, 只能用当前的数据源列表
     * 重建一次会话 (见 MediaFetchSessionRefresh), 否则只重查这一个源, 不打断正在播的视频.
     *
     * @return 接在提示后面的一句
     */
    private fun refreshAddedSource(handle: RemotePlayerHandle): String {
        val (instanceId, changed) = runBlocking { ensureAddedShareMediaSource(manager) }
        return if (changed || !handle.hasSource(instanceId)) {
            handle.searchAllSources()
            KoinPlatform.getKoin().get<MediaFetchSessionRefresh>().request()
            tr("。正在重新搜索一次，让「我添加的分享」加入，电视上的视频会重新加载")
        } else {
            handle.vm.restartSource(instanceId)
            tr("。已加到选源列表的「我添加的分享」")
        }
    }

    /** 等选源列表里出现 [mediaId] (重查要打开分享, 一般几秒), 最多 [CANDIDATE_WAIT_MILLIS]. 在 HTTP 线程上调用. */
    private fun awaitCandidate(handle: RemotePlayerHandle, mediaId: String): Boolean {
        val deadline = System.currentTimeMillis() + CANDIDATE_WAIT_MILLIS
        while (handle.candidate(mediaId) == null) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(500)
        }
        return true
    }

    private fun addedSourceId(): String? = runBlocking { manager.allInstances.first() }
        .firstOrNull { it.factoryId == QuarkAddedShareMediaSource.FactoryId }?.instanceId

    private fun loggedIn(): Boolean = runBlocking { drive.config.first().isLoggedIn }

    /**
     * 认出的集号写成一行: 连着的整数集并成 `1~12`, 其余照写 (`1~12、14、16~20`).
     */
    internal fun formatEpisodes(episodes: List<EpisodeSort>): String {
        val parts = ArrayList<String>()
        var start: Int? = null
        var end: Int? = null
        fun flush() {
            val s = start ?: return
            parts += if (end == s) "$s" else "$s~$end"
            start = null
            end = null
        }
        for (episode in episodes) {
            val number = (episode as? EpisodeSort.Normal)?.number?.takeIf { it == floor(it) }?.toInt()
            if (number == null) {
                flush()
                parts += episode.toString()
                continue
            }
            if (start != null && number == end!! + 1) {
                end = number
            } else {
                flush()
                start = number
                end = number
            }
        }
        flush()
        return parts.joinToString(tr("、"))
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.field(name: String): String =
        formFieldList().lastOrNull { it.first == name }?.second.orEmpty()

    private val INSPECT_TIMEOUT = 30.seconds

    private const val CANDIDATE_WAIT_MILLIS = 20_000L

    /** 一集都没认出时最多列出几个文件给用户挑. */
    private const val MAX_LISTED_FILES = 60

    private val OTHER_DRIVES = Regex(
        """pan\.baidu\.com|aliyundrive\.com|alipan\.com|pan\.xunlei\.com|115\.com|115cdn\.com|123pan\.com|cloud\.189\.cn|caiyun\.139\.com|drive\.uc\.cn""",
    )
}
