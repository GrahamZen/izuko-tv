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
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.canAccessBangumiApiNow
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.profile.ProfileImportSession
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台上处理本地用户改成 Bangumi 用户时留下的本地收藏 (见 [BangumiProfileConversion]), 同电视上的 TvPendingCollectionsHost:
 * 登录了 Bangumi 就在网页上问合并到账号还是删除 —— 用手机登录的, 在手机上选. 电视上同时也会问, 哪边先选算哪边.
 *
 * `api/profiles` 里带上等着处理的条数 ([putPendingState]), 网页据此弹出; 预览、合并、删除走 `api/profiles/pending` 下的几个请求.
 * 合并在电视上后台跑, 与电视共用 [ProfileImportSession], 进度同导入见 `api/profiles/import/state`.
 */
internal object RemotePendingCollections {
    private val logger = logger<RemotePendingCollections>()

    private val conversion: BangumiProfileConversion?
        get() = runCatching { KoinPlatform.getKoin().get<BangumiProfileConversion>() }.getOrNull()
    private val sessionStateProvider: SessionStateProvider?
        get() = runCatching { KoinPlatform.getKoin().get<SessionStateProvider>() }.getOrNull()

    /** 最近一次预览与它的时刻: 确认合并时照它合并 (网页上给人看过的就是这一份), 太旧了让人重新打开. */
    @Volatile
    private var lastPreview: Pair<Long, LocalProfileImporter.Preview>? = null

    /** 处理 `api/profiles/pending` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/profiles/pending" && get -> preview()
                !post -> null
                request.path == "api/profiles/pending/merge" -> merge()
                request.path == "api/profiles/pending/discard" -> discard()
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote pending collections request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    /** 给 `api/profiles` 加上: 等着处理的本地收藏条数 `pendingLocal` (没有时不写), 现在能不能合并 `canMerge` (登录着 Bangumi). */
    fun JsonObjectBuilder.putPendingState() {
        val count = pendingCount()
        if (count == 0) return
        put("pendingLocal", count)
        put("canMerge", canMerge())
    }

    private fun pendingCount(): Int {
        if (UserProfiles.current.isLocal) return 0
        val conversion = conversion ?: return 0
        return runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { conversion.pendingCollections.first().size } } ?: 0
    }

    private fun canMerge(): Boolean {
        if (UserProfiles.current.isLocal) return false
        val session = sessionStateProvider ?: return false
        return runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { session.canAccessBangumiApiNow() } } == true
    }

    /** 预览: 留下的收藏里哪些会加进当前账号, 哪些账号里已经有了. 要把账号的收藏翻一遍, 慢一点. 字段同导入的预览. */
    private fun preview(): JsonObject {
        val conversion = conversion ?: return nothingPending()
        if (pendingCount() == 0) return nothingPending()
        if (!canMerge()) return result(false, tr("要先登录 Bangumi，才能合并到账号"))
        val preview = runBlocking { withTimeoutOrNull(PREVIEW_TIMEOUT) { conversion.previewMerge() } }
            ?: return result(false, tr("读取 Bangumi 上的收藏超时，请重试"))
        lastPreview = System.currentTimeMillis() to preview
        return buildJsonObject {
            put("ok", true)
            put("total", preview.toAdd.size + preview.alreadyCollected.size)
            putJsonArray("add") {
                for (entry in preview.toAdd) {
                    addJsonObject {
                        put("id", entry.subjectId)
                        put("name", entry.name)
                        put("type", entry.type.name)
                        put("score", entry.rating.score)
                        // 想看的不标集 (见 LocalProfileImporter.import)
                        put("episodes", if (entry.type == UnifiedCollectionType.WISH) 0 else entry.watchedEpisodeIds.size)
                    }
                }
            }
            put("skipped", preview.alreadyCollected.size)
        }
    }

    /** 照最近那份预览合并, 在后台跑; 进度见 `api/profiles/import/state`. */
    private fun merge(): JsonObject {
        val conversion = conversion ?: return nothingPending()
        if (ProfileImportSession.isRunning) return result(false, tr("正在导入，等这一次导完"))
        if (pendingCount() == 0) return nothingPending()
        if (!canMerge()) return result(false, tr("要先登录 Bangumi，才能合并到账号"))
        val preview = lastPreview
            ?.takeIf { (at, p) -> p.source.id == UserProfiles.currentId && System.currentTimeMillis() - at < PREVIEW_FRESH.inWholeMilliseconds }
            ?.second
            ?: return result(false, tr("预览已经过期，请重新打开"))
        val name = UserProfiles.current.name.ifBlank { tr("用户 {0}", UserProfiles.currentId) }
        val started = ProfileImportSession.start(preview, name) { onProgress -> conversion.merge(preview, onProgress) }
        if (!started) return result(false, tr("正在导入，等这一次导完"))
        logger.info { "Remote control merging pending local collections into Bangumi (${preview.toAdd.size} to add)" }
        return result(true, tr("开始合并"))
    }

    private fun discard(): JsonObject {
        val conversion = conversion ?: return nothingPending()
        if (ProfileImportSession.isRunning) return result(false, tr("正在导入，等这一次导完"))
        runBlocking { conversion.discard() }
        logger.info { "Remote control discarded pending local collections" }
        return result(true, tr("已删除之前的本地收藏"))
    }

    private fun nothingPending(): JsonObject = result(false, tr("没有等着处理的本地收藏了"))

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private val STATE_TIMEOUT = 3.seconds

    /** 预览要把 Bangumi 上的收藏翻到底 (一页 100 条), 收藏上千的也够. */
    private val PREVIEW_TIMEOUT = 60.seconds

    /** 预览多久之内确认合并还算数. */
    private val PREVIEW_FRESH = 10.minutes
}

/** 网页上的文案 (见 [RemoteI18nTable]). */
internal fun pendingCollectionsTexts() = listOf(
    RemoteText("要先登录 Bangumi，才能合并到账号", "Sign in to Bangumi first to merge into the account", "要先登錄 Bangumi，才能合併到帳號", "要先登入 Bangumi，才能合併到帳號"),
    RemoteText("预览已经过期，请重新打开", "The preview has expired. Please open it again.", "預覽已經過期，請重新打開", "預覽已經過期，請重新開啟"),
    RemoteText("开始合并", "Merging", "開始合併", "開始合併"),
    RemoteText("已删除之前的本地收藏", "Earlier local collections deleted", "已刪除之前的本地收藏", "已刪除先前的本機收藏"),
    RemoteText("没有等着处理的本地收藏了", "No earlier local collections left to handle", "沒有等著處理的本地收藏了", "沒有等待處理的本機收藏了"),
    RemoteText("合并之前的本地收藏", "Merge earlier local collections", "合併之前的本地收藏", "合併先前的本機收藏"),
    RemoteText(
        "这个用户改成 Bangumi 用户之前在这台电视上的收藏，可以合并到刚登录的账号，也可以删除。",
        "Collections saved on this TV before this user switched to Bangumi can be merged into the account you just signed in to, or deleted.",
        "這個用戶改成 Bangumi 用戶之前在這台電視上的收藏，可以合併到剛登錄的帳號，也可以刪除。",
        "這位使用者改成 Bangumi 使用者之前在這台電視上的收藏，可以合併到剛登入的帳號，也可以刪除。",
    ),
    RemoteText("合并 {0} 部", "Merge {0}", "合併 {0} 部", "合併 {0} 部"),
    RemoteText("以后再说", "Later", "以後再說", "之後再說"),
    RemoteText("删除这 {0} 部本地收藏？删除后找不回来。", "Delete these {0} local collections? This can’t be undone.", "刪除這 {0} 部本地收藏？刪除後找不回來。", "刪除這 {0} 部本機收藏？刪除後無法復原。"),
    RemoteText(
        "之前的 {0} 部本地收藏还留着，登录 Bangumi 后可以合并到账号，也可以删除。",
        "{0} earlier local collections are kept. After signing in to Bangumi you can merge them into the account or delete them.",
        "之前的 {0} 部本地收藏還留著，登錄 Bangumi 後可以合併到帳號，也可以刪除。",
        "先前的 {0} 部本機收藏還保留著，登入 Bangumi 後可以合併到帳號，也可以刪除。",
    ),
    RemoteText("处理之前的本地收藏（{0} 部）", "Handle earlier local collections ({0})", "處理之前的本地收藏（{0} 部）", "處理先前的本機收藏（{0} 部）"),
    RemoteText("正在合并 {0}/{1}…", "Merging {0}/{1}…", "正在合併 {0}/{1}…", "正在合併 {0}/{1}…"),
    RemoteText("合并完成：新加 {0} 部收藏，标了 {1} 集看过。", "Merge complete: added {0} collections and marked {1} episodes as watched.", "合併完成：新加 {0} 部收藏，標了 {1} 集看過。", "合併完成：新增 {0} 部收藏，標了 {1} 集看過。"),
    RemoteText("没合并上的 {0} 部还留着，下次再问。", "The {0} not merged are kept; you’ll be asked again next time.", "沒合併上的 {0} 部還留著，下次再問。", "沒合併成功的 {0} 部還保留著，下次再問。"),
)
