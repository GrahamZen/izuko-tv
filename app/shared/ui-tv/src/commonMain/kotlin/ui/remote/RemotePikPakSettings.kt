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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.torrent.engines.PikPakEngine
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.torrent.pikpak.PikPakNotConfiguredException
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台「设置 → 资源与弹幕」里的 PikPak 账号 (同设置页 PikPak 那一组的用户名与密码; 「启用 PikPak」开关留在电视上).
 *
 * 密码**只写不读**: 网页上不回显, 留空 = 保持原来的. 换账号时一并清掉密码与登录会话 (refreshToken), 换密码时清掉会话,
 * 下次用到 PikPak 时引擎按新的账号密码重新登录 —— 与设置页一致 (见 [applyCredentials]).
 */
internal object RemotePikPakSettings {
    private val logger = logger<RemotePikPakSettings>()

    /** 给网页的状态: 账号 (用户名明文)、有没有存密码、是否有登录会话、电视上开没开. */
    fun describe(config: PikPakConfig): JsonObject = buildJsonObject {
        put("enabled", config.enabled)
        put("username", config.username)
        put("hasPassword", config.password.isNotEmpty())
        put("signedIn", config.refreshToken.isNotEmpty())
    }

    /**
     * 网页提交的用户名 (去掉首尾空白) 与密码换成新的设置; 什么都没变时返回 null.
     *
     * - 换了用户名: 存新用户名与这次填的密码 (没填就是空), 清掉会话; 「旧缓存清理」的回答属于旧账号, 一并清掉.
     *   用户名清空 = 去掉账号, 密码也不留.
     * - 用户名没变、填了密码: 换密码并清掉会话.
     * - 用户名没变、密码留空: 不动 (网页上密码框总是空的, 留空不能当成清除).
     */
    fun applyCredentials(old: PikPakConfig, username: String, password: String): PikPakConfig? {
        val name = username.trim()
        return when {
            name != old.username -> old.copy(
                username = name,
                password = if (name.isEmpty()) "" else password,
                refreshToken = "",
                legacyNoticeAnswered = false,
            )

            password.isNotEmpty() -> old.copy(password = password, refreshToken = "")
            else -> null
        }
    }

    /** `POST api/settings/pikpak`: 表单 `username` / `password`. 回应带新的状态 (`pikpak`). */
    fun save(repository: SettingsRepository, request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val username = fields["username"].orEmpty()
        val password = fields["password"].orEmpty()
        val (changed, config) = runBlocking {
            val old = repository.pikpakConfig.flow.first()
            val new = applyCredentials(old, username, password)
            if (new != null) repository.pikpakConfig.set(new)
            (new != null) to (new ?: old)
        }
        logger.info { "Remote control saved PikPak account: changed=$changed, hasUsername=${config.username.isNotEmpty()}" }
        val message = when {
            !changed -> tr("没有改动")
            config.username.isEmpty() -> tr("已清除 PikPak 账号")
            config.password.isEmpty() -> tr("已换账号，请再填上这个账号的密码")
            else -> tr("已保存，下次用到 PikPak 时登录")
        }
        return buildJsonObject {
            put("ok", true)
            put("message", message)
            put("pikpak", describe(config))
        }
    }

    /**
     * `POST api/settings/pikpak/test`: 用当前账号读一次网盘用量 (同设置页「网盘状态」), 顺带确认账号密码能登录.
     * 要电视上开着 PikPak、账号填全才测得了.
     */
    fun test(): JsonObject {
        val engine = runCatching { KoinPlatform.getKoin().get<PikPakEngine>() }.getOrNull()
            ?: return result(false, tr("这台设备不支持 PikPak"))
        return try {
            val usage = runBlocking { withTimeoutOrNull(TEST_TIMEOUT) { Result.success(engine.driveUsage()) } }
                ?: return result(false, tr("连接超时，请重试"))
            val u = usage.getOrNull() ?: return result(false, tr("先在电视上打开「启用 PikPak」，并填好账号和密码"))
            val free = (u.accountLimitBytes - u.accountUsedBytes).coerceAtLeast(0L).bytes
            result(true, tr("登录成功，网盘剩余 {0}", free))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PikPakNotConfiguredException) {
            result(false, tr("先在电视上打开「启用 PikPak」，并填好账号和密码"))
        } catch (e: Exception) {
            logger.warn(e) { "Remote PikPak test failed" }
            result(false, tr("连接失败：{0}", e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName))
        }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    /** 读用量前可能要先登录 (登录接口限流, 慢的时候十几秒) */
    private val TEST_TIMEOUT = 30.seconds
}
