/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.repository.user.SettingsBackupCodec
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform

/**
 * Web 控制台「设置 → 维护 → 设置备份」: 应用设置导出成文件存在手机上, 重装或换电视后再从文件导入 (电视上用不了剪贴板).
 *
 * 内容就是 [SettingsBackupCodec] 编出的那段 JSON, 与设置页复制到剪贴板的一样: 原样存成文件, 剪贴板里的备份存成文本文件也能导入.
 * 导入时整个文件放在请求体里 (纯文本), 只给这一个接口放宽请求体上限 ([maxBodyBytes]); 网页在发之前先确认会覆盖全部设置.
 */
internal object RemoteSettingsBackup {
    private val logger = logger<RemoteSettingsBackup>()

    const val EXPORT_PATH = "api/settings/backup/export"
    const val IMPORT_PATH = "api/settings/backup/import"

    /**
     * 导入接口的请求体上限. 没改过的设置项不写进备份, 全是默认设置时只有 0.6 KiB, 加上改过的设置、登录凭据与弹幕屏蔽词
     * 一般几 KiB; Peer 黑名单或屏蔽词贴了大段列表时会长到几百 KiB, 超过默认的 64 KiB, 这里留足.
     */
    private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024

    /** 给 LanHttpServer 的按路径请求体上限: 导入接口放宽, 其余不管 (null). */
    fun maxBodyBytes(path: String): Long? = if (path == IMPORT_PATH) MAX_IMPORT_BYTES else null

    private val codec: SettingsBackupCodec
        get() = KoinPlatform.getKoin().let { SettingsBackupCodec(it.get(), it.get(), it.get()) }

    /** 处理 `api/settings/backup/` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return when {
            request.path == EXPORT_PATH && get -> export()
            request.path == IMPORT_PATH && post -> import(request.body.toString(Charsets.UTF_8))
            else -> null
        }
    }

    /** 当前设置的备份 (`content`, 网页原样存成文件) */
    private fun export(): JsonObject {
        val content = runBlocking { codec.export() }
        logger.info { "Remote control exported settings backup (${content.length} chars)" }
        return buildJsonObject {
            put("ok", true)
            put("content", content)
        }
    }

    private fun import(text: String): JsonObject {
        // 记事本存的文件可能带 BOM
        val content = text.removePrefix("\uFEFF").trim()
        if (content.isEmpty()) return result(false, tr("文件是空的"))
        try {
            runBlocking { codec.restore(content) }
        } catch (e: IllegalArgumentException) {
            // 含 SerializationException: 不是 JSON, 或缺了备份该有的字段
            // 异常信息里会带一段文件原文 (可能有登录凭据), 不进日志
            logger.warn { "Remote control settings import rejected: ${e::class.simpleName}" }
            return result(false, tr("这个文件不是设置备份，或者已经损坏"))
        }
        logger.info { "Remote control imported settings backup (${content.length} chars)" }
        return result(true, tr("已导入设置"))
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
