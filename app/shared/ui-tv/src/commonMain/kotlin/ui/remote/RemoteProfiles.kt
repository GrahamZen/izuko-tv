/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfileManager
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
import java.net.URLDecoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「设置」标签顶上的**用户** (见 [UserProfileManager]): 电视上有哪几个人、现在是谁; 添加、切换、改名、删除.
 *
 * 添加只建不切 (手机上常一口气给家里几个人都建好), 要用时再切. 切换 = 重启电视上的应用, 网页服务跟着重启:
 * 先回应网页再动手, 网页挡住页面等电视回来, 确认换成了那个人就整页重载 —— 各标签里的数据都是按人的.
 * 重启要起新界面, Ani 不在前台时系统不许, 所以先叫回前台 ([TvRemoteControl.bringToFrontForRestart]), 叫不回来就不换:
 * 记下了新用户却没重启, 电视和网页就对不上了.
 *
 * 当前用户登录了 Bangumi 时, 还能把某个本地用户的收藏导入这个账号 (见 [LocalProfileImporter]): 先预览要加哪些,
 * 确认了照那份预览导, 在后台跑, 网页轮询进度. 本地用户导出成文件、从文件导入见 [RemoteProfileArchive].
 */
internal object RemoteProfiles {
    private val logger = logger<RemoteProfiles>()

    /** 平台不支持多用户时 (拿不到或 [UserProfileManager.isSupported] 为 false) 网页上不出现用户卡片. */
    private val manager: UserProfileManager?
        get() = runCatching { KoinPlatform.getKoin().get<UserProfileManager>() }.getOrNull()?.takeIf { it.isSupported }

    private val importer: LocalProfileImporter get() = KoinPlatform.getKoin().get()
    private val sessionStateProvider: SessionStateProvider?
        get() = runCatching { KoinPlatform.getKoin().get<SessionStateProvider>() }.getOrNull()

    /** 处理 `api/profiles` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest, scope: CoroutineScope): JsonObject? {
        if (request.path == "api/profiles/export" || request.path.startsWith("api/profiles/restore/")) {
            return RemoteProfileArchive.handle(request)
        }
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/profiles" && get -> state()
                request.path == "api/profiles/import" && get -> importPreview(request)
                request.path == "api/profiles/import/state" && get -> importState()
                !post -> null
                request.path == "api/profiles/add" -> add(request)
                request.path == "api/profiles/switch" -> switch(request, scope)
                request.path == "api/profiles/rename" -> rename(request)
                request.path == "api/profiles/delete" -> delete(request)
                request.path == "api/profiles/import" -> startImport(request)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote profile request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun state(): JsonObject {
        val manager = manager ?: return buildJsonObject {
            put("ok", true)
            put("supported", false)
        }
        val save = manager.state.value
        // 本进程的用户, 也就是网页上各标签的数据是谁的 (切换途中列表里记的可能已经是下一个人)
        val current = manager.currentId
        return buildJsonObject {
            put("ok", true)
            put("supported", true)
            put("currentId", current)
            putJsonArray("users") {
                for (profile in save.profiles) {
                    addJsonObject {
                        put("id", profile.id)
                        put("name", displayName(profile))
                        // 自己起的名字, 改名时预填; 没起过是空的, 输入框里拿默认名当占位
                        put("raw", profile.name)
                        put("current", profile.id == current)
                        put("primary", profile.isPrimary)
                        // 本地档: 不登录 Bangumi, 收藏与看过只存在电视上
                        put("local", profile.isLocal)
                        // 头像候选: 经电视转发 → 手机直连 (同账号卡片)
                        profile.avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            putJsonArray("avatar") {
                                add(RemoteImageProxy.proxied(url))
                                add(url)
                            }
                        }
                    }
                }
            }
            // 添加时不填名字就叫这个 (同电视)
            put("nextName", defaultName(save.nextId))
            // 能把本地用户的收藏导进当前用户 (登录了 Bangumi) 吗; 有导入在跑时网页接着显示进度
            put("canImport", save.profiles.any { it.isLocal } && canImportIntoCurrent())
            put("importing", ProfileImportSession.isRunning)
        }
    }

    /** 表单 `name` (空 = 默认名) + `kind` (`local` = 本地档, 其余 = 登录 Bangumi 的). */
    private fun add(request: LanHttpRequest): JsonObject {
        val manager = manager ?: return unsupported()
        val fields = request.formFields()
        // 没填就把默认名存下来 (同电视): 名字还要显示在侧边栏等处, 那里没有编号可拼
        val name = fields["name"].orEmpty().trim().ifEmpty { defaultName(manager.state.value.nextId) }
        val kind = if (fields["kind"] == "local") UserProfileKind.LOCAL else UserProfileKind.BANGUMI
        val profile = runBlocking { withTimeoutOrNull(OP_TIMEOUT) { manager.add(name, kind) } }
            ?: return result(false, tr("添加超时，请重试"))
        logger.info { "Remote control added user profile ${profile.id} (${profile.kind})" }
        return buildJsonObject {
            put("ok", true)
            put("id", profile.id)
            put("message", tr("已添加「{0}」", displayName(profile)))
        }
    }

    private fun switch(request: LanHttpRequest, scope: CoroutineScope): JsonObject {
        val manager = manager ?: return unsupported()
        val target = find(manager, request) ?: return notFound()
        val name = displayName(target)
        if (target.id == manager.currentId) return result(false, tr("电视现在就是「{0}」", name))
        // 切换会重启应用, 在跑的导入跟着断掉
        if (ProfileImportSession.isRunning) return result(false, tr("正在导入收藏，导完再切换用户"))
        if (!TvRemoteControl.bringToFrontForRestart()) {
            return result(false, tr("电视上没有显示 Izuko，切换不了。先在电视上打开 Izuko 再试"))
        }
        logger.info { "Remote control switching user profile ${manager.currentId} -> ${target.id}" }
        scope.launch {
            // 先让这次回应发出去: 切换会重启应用, 网页服务跟着停
            delay(RESPONSE_GRACE)
            manager.switchTo(target.id)
        }
        return buildJsonObject {
            put("ok", true)
            put("id", target.id)
            put("name", name)
        }
    }

    private fun rename(request: LanHttpRequest): JsonObject {
        val manager = manager ?: return unsupported()
        val target = find(manager, request) ?: return notFound()
        // 清空了同样存默认名 (同添加)
        val name = request.formFields()["name"].orEmpty().trim().ifEmpty { defaultName(target.id) }
        runBlocking { withTimeoutOrNull(OP_TIMEOUT) { manager.rename(target.id, name) } }
            ?: return result(false, tr("保存超时，请重试"))
        return result(true, tr("已保存"))
    }

    private fun delete(request: LanHttpRequest): JsonObject {
        val manager = manager ?: return unsupported()
        val target = find(manager, request) ?: return notFound()
        // 同电视长按菜单里写的理由 (见 UserProfileManager.delete)
        if (target.isPrimary) return result(false, tr("第一个用户只能改名，不能删除"))
        if (target.id == manager.currentId) return result(false, tr("要删除正在用的用户，先切换到别人"))
        val deleted = runBlocking { withTimeoutOrNull(OP_TIMEOUT) { manager.delete(target.id) } }
            ?: return result(false, tr("删除超时，请重试"))
        if (!deleted) return notFound()
        logger.info { "Remote control deleted user profile ${target.id}" }
        return result(true, tr("已删除「{0}」", displayName(target)))
    }

    /** 最近一次预览与它的时刻: 确认导入时照它导 (网页上给人看过的就是这一份), 太旧了让人重新打开. */
    @Volatile
    private var lastPreview: Pair<Long, LocalProfileImporter.Preview>? = null

    /** 当前用户能不能接收导入: 登录了 Bangumi 的用户 (本地档不能登录). */
    private fun canImportIntoCurrent(): Boolean {
        if (UserProfiles.current.isLocal) return false
        val session = sessionStateProvider ?: return false
        return runBlocking { withTimeoutOrNull(STATE_TIMEOUT) { session.canAccessBangumiApiNow() } } == true
    }

    /** 查询参数 `id` 的本地用户, 以及不能导入时的理由. */
    private fun importSource(manager: UserProfileManager, id: Int?): Pair<UserProfile?, JsonObject?> {
        val source = id?.let { manager.state.value.find(it) } ?: return null to notFound()
        if (!source.isLocal) return null to result(false, tr("只能导入本地用户的收藏"))
        if (!canImportIntoCurrent()) return null to result(false, tr("要先在电视上登录 Bangumi，才能把收藏导入这个账号"))
        return source to null
    }

    /** 预览: 这个本地用户的收藏里, 哪些会加进当前的 Bangumi 账号, 哪些已经有了. 要把 Bangumi 上的收藏翻一遍, 慢一点. */
    private fun importPreview(request: LanHttpRequest): JsonObject {
        val manager = manager ?: return unsupported()
        val (source, refusal) = importSource(manager, request.queryParam("id")?.toIntOrNull())
        if (source == null) return refusal!!
        val preview = runBlocking { withTimeoutOrNull(PREVIEW_TIMEOUT) { importer.preview(source) } }
            ?: return result(false, tr("读取 Bangumi 上的收藏超时，请重试"))
        lastPreview = System.currentTimeMillis() to preview
        return buildJsonObject {
            put("ok", true)
            put("source", displayName(source))
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

    /** 照最近那份预览导入 (表单 `id` 要对得上), 在后台跑 (与电视共用 [ProfileImportSession]); 进度见 [importState]. */
    private fun startImport(request: LanHttpRequest): JsonObject {
        val manager = manager ?: return unsupported()
        if (ProfileImportSession.isRunning) return result(false, tr("正在导入，等这一次导完"))
        val (source, refusal) = importSource(manager, request.formFields()["id"]?.toIntOrNull())
        if (source == null) return refusal!!
        val preview = lastPreview
            ?.takeIf { (at, p) -> p.source.id == source.id && System.currentTimeMillis() - at < PREVIEW_FRESH.inWholeMilliseconds }
            ?.second
            ?: return result(false, tr("预览已经过期，请重新打开导入"))
        if (!ProfileImportSession.start(importer, preview, displayName(source))) return result(false, tr("正在导入，等这一次导完"))
        logger.info { "Remote control importing user profile ${source.id} into Bangumi (${preview.toAdd.size} to add)" }
        return result(true, tr("开始导入"))
    }

    private fun importState(): JsonObject = when (val state = ProfileImportSession.state.value) {
        ProfileImportSession.State.Idle -> buildJsonObject {
            put("ok", true)
            put("running", false)
        }

        is ProfileImportSession.State.Running -> buildJsonObject {
            put("ok", true)
            put("running", true)
            put("source", state.sourceName)
            put("done", state.done)
            put("total", state.total)
        }

        is ProfileImportSession.State.Failed -> buildJsonObject {
            put("ok", true)
            put("running", false)
            put("source", state.sourceName)
            put("error", state.message)
        }

        is ProfileImportSession.State.Finished -> buildJsonObject {
            put("ok", true)
            put("running", false)
            put("source", state.sourceName)
            state.result.let { r ->
                putJsonObject("result") {
                    put("added", r.added)
                    put("skipped", r.skipped)
                    put("episodes", r.episodesMarked)
                    putJsonArray("failures") {
                        for (failure in r.failures) {
                            addJsonObject {
                                put("name", failure.entry.name)
                                // 收藏加上了, 只是看过的集没标上
                                put("added", failure.collectionAdded)
                                put("message", failure.error.message ?: failure.error::class.simpleName)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun find(manager: UserProfileManager, request: LanHttpRequest): UserProfile? {
        val id = request.formFields()["id"]?.toIntOrNull() ?: return null
        return manager.state.value.find(id)
    }

    /** 列表里的名字: 没起过名字的显示「用户 N」(同电视). */
    private fun displayName(profile: UserProfile): String = profile.name.ifBlank { defaultName(profile.id) }

    private fun defaultName(id: Int): String = tr("用户 {0}", id)

    private fun unsupported(): JsonObject = result(false, tr("这台设备不支持多用户"))

    private fun notFound(): JsonObject = result(false, tr("没有这个用户了，刷新页面再看看"))

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private val OP_TIMEOUT = 10.seconds

    private val STATE_TIMEOUT = 3.seconds

    /** 预览要把 Bangumi 上的收藏翻到底 (一页 100 条), 收藏上千的也够. */
    private val PREVIEW_TIMEOUT = 60.seconds

    /** 预览多久之内确认导入还算数. */
    private val PREVIEW_FRESH = 10.minutes

    /** 回应切换请求后等这么久再重启, 让回应先写出去. */
    private val RESPONSE_GRACE = 500.milliseconds
}
