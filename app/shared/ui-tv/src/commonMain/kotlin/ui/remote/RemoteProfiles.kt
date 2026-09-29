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
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「设置」标签顶上的**用户** (见 [UserProfileManager]): 电视上有哪几个人、现在是谁; 添加、切换、改名、删除.
 *
 * 添加只建不切 (手机上常一口气给家里几个人都建好), 要用时再切. 切换 = 重启电视上的应用, 网页服务跟着重启:
 * 先回应网页再动手, 网页挡住页面等电视回来, 确认换成了那个人就整页重载 —— 各标签里的数据都是按人的.
 * 重启要起新界面, Ani 不在前台时系统不许, 所以先叫回前台 ([TvRemoteControl.bringToFrontForRestart]), 叫不回来就不换:
 * 记下了新用户却没重启, 电视和网页就对不上了.
 */
internal object RemoteProfiles {
    private val logger = logger<RemoteProfiles>()

    /** 平台不支持多用户时 (拿不到或 [UserProfileManager.isSupported] 为 false) 网页上不出现用户卡片. */
    private val manager: UserProfileManager?
        get() = runCatching { KoinPlatform.getKoin().get<UserProfileManager>() }.getOrNull()?.takeIf { it.isSupported }

    /** 处理 `api/profiles` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest, scope: CoroutineScope): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/profiles" && get -> state()
                !post -> null
                request.path == "api/profiles/add" -> add(request)
                request.path == "api/profiles/switch" -> switch(request, scope)
                request.path == "api/profiles/rename" -> rename(request)
                request.path == "api/profiles/delete" -> delete(request)
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

    /** 回应切换请求后等这么久再重启, 让回应先写出去. */
    private val RESPONSE_GRACE = 500.milliseconds
}
