/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.player.PlaybackFailureLog
import me.him188.ani.app.domain.player.PlaybackFailureLog.Reason
import me.him188.ani.app.domain.player.PlaybackFailureLog.Stage
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.datasources.api.source.MediaSourceKind

/**
 * 播放卡上的「失败报告」: 这一集 (或换集前那一集) 播放失败过, 卡上才有入口 ([stateEntries] 给 `api/player` 加上次数), 点开读 [PATH].
 * 记录见 [PlaybackFailureLog]; 网页见 [FAIL_SCRIPT].
 */
internal object RemotePlaybackFailures {
    const val PATH = "api/player/failures"

    private fun reportOf(handle: RemotePlayerHandle): PlaybackFailureLog.Report? =
        PlaybackFailureLog.report.value?.takeIf { it.subjectId == handle.vm.subjectId && it.entries.isNotEmpty() }

    /** 换集前那一集的记录 (同一部番). */
    private fun previousReportOf(handle: RemotePlayerHandle): PlaybackFailureLog.Report? =
        PlaybackFailureLog.previousReport.value?.takeIf { it.subjectId == handle.vm.subjectId && it.entries.isNotEmpty() }

    /**
     * 并进播放卡状态的字段 (参与版本号, 次数变了网页就重画): 这一集失败过时 `failures` = 次数,
     * 换集前那一集失败过时 `prevFailures` = 它的次数; 都没有不加.
     */
    fun stateEntries(handle: RemotePlayerHandle): Map<String, JsonElement> = buildMap {
        reportOf(handle)?.let { put("failures", JsonPrimitive(it.entries.size)) }
        previousReportOf(handle)?.let { put("prevFailures", JsonPrimitive(it.entries.size)) }
    }

    /** 处理 [PATH]; 路径或方法不认识返回 null. */
    fun handle(handle: RemotePlayerHandle?, request: LanHttpRequest): LanHttpResponse? {
        if (request.path != PATH || (request.method != "GET" && request.method != "HEAD")) return null
        val sources = handle?.page?.mediaSourceResultListPresentation?.list.orEmpty()
        fun JsonObjectBuilder.putEntries(report: PlaybackFailureLog.Report?) = putJsonArray("entries") {
            // 新的在前
            report?.entries.orEmpty().asReversed().forEach { entry ->
                addJsonObject {
                    put("time", entry.timeMillis)
                    put("stage", stageLabel(entry.stage))
                    put("reason", reasonLabel(entry))
                    put("network", entry.reason == Reason.NETWORK)
                    put(
                        "source",
                        if (entry.mediaKind == MediaSourceKind.LocalCache) tr("本地缓存")
                        else sources.firstOrNull { it.mediaSourceId == entry.mediaSourceId }?.info?.displayName ?: entry.mediaSourceId,
                    )
                    put("title", entry.mediaTitle)
                    entry.host?.let { put("host", it) }
                    putJsonArray("causes") { entry.causes.forEach { add(it) } }
                    entry.stackTrace?.let { put("stack", it) }
                }
            }
        }
        val body = buildJsonObject {
            putEntries(handle?.let(::reportOf))
            handle?.let(::previousReportOf)?.let { previous ->
                putJsonObject("previous") {
                    put("episode", episodeLabel(handle, previous.episodeId))
                    putEntries(previous)
                }
            }
        }
        return json(body)
    }

    /** 选集列表里那一集的「第 N 话  标题」; 找不到就只写「上一集」. */
    private fun episodeLabel(handle: RemotePlayerHandle, episodeId: Int): String =
        handle.vm.episodeSelectorState.items.firstOrNull { it.episodeId == episodeId }
            ?.let { ep -> listOf(tr("第 {0} 话", ep.sort), ep.title).filter { it.isNotBlank() }.joinToString("  ") }
            ?: tr("上一集")

    private fun stageLabel(stage: Stage): String = when (stage) {
        Stage.RESOLVE -> tr("解析资源")
        Stage.OPEN -> tr("播放器打开")
        Stage.PLAYBACK -> tr("播放中")
    }

    private fun reasonLabel(entry: PlaybackFailureLog.Entry): String = when (entry.reason) {
        Reason.RESOLUTION_TIMED_OUT -> tr("解析超时")
        Reason.NETWORK -> tr("网络错误")
        Reason.HTTP_ERROR -> {
            val status = entry.httpStatus
            if (status != null && status >= 500) tr("服务器出错（HTTP {0}）", status) else tr("服务器拒绝了请求（HTTP {0}）", status)
        }
        Reason.NO_MATCHING_FILE -> tr("未找到可播放的文件")
        Reason.UNSUPPORTED -> tr("不支持该文件类型")
        Reason.PLAYER_ERROR -> tr("播放器出错")
        Reason.TOO_SHORT -> tr("视频只有 {0} 秒，不是正片（多半是站点的公告或广告）", ((entry.mediaDurationMillis ?: 0L) + 999) / 1000)
        Reason.UNKNOWN -> tr("未知错误")
    }

    private fun json(obj: JsonObject): LanHttpResponse =
        LanHttpResponse.bytes(obj.toString().toByteArray(), "application/json; charset=utf-8")
}
