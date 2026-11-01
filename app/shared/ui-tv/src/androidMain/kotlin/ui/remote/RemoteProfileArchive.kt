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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.repository.RepositorySubjectNotAccessibleException
import me.him188.ani.app.domain.profile.ProfileArchive
import me.him188.ani.app.domain.profile.ProfileArchiver
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台里本地用户的导出与导入 (见 [ProfileArchiver]): 导出成 JSON 文件存在手机上, 换电视、重装后再导进来.
 *
 * 导入只进当前用户, 而且要是本地用户. 网页逐条发: 条目信息库里没有时电视要去 Bangumi 取, 一条一两秒, 网页显示进度;
 * 一个请求只导一条, 电视上不用暂存整份文件 (请求体有 64 KiB 上限). 每个请求带着目标用户的编号, 电视上换了人就拒,
 * 回应里 `stop` 为 true, 网页停下不再发.
 */
internal object RemoteProfileArchive {
    private val logger = logger<RemoteProfileArchive>()

    private val manager: UserProfileManager?
        get() = runCatching { KoinPlatform.getKoin().get<UserProfileManager>() }.getOrNull()?.takeIf { it.isSupported }

    private val archiver: ProfileArchiver?
        get() = runCatching { KoinPlatform.getKoin().get<ProfileArchiver>() }.getOrNull()

    /** 处理导出与导入的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/profiles/export" && get -> export(request)
                !post -> null
                request.path == "api/profiles/restore/check" -> check(request)
                request.path == "api/profiles/restore/collection" -> restoreCollection(request)
                request.path == "api/profiles/restore/playback" -> restorePlayback(request)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote profile archive request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    /** 查询参数 `id` 的本地用户导出成的文件 (`archive`), 连同条数. 网页存成文件. */
    private fun export(request: LanHttpRequest): JsonObject {
        val manager = manager ?: return unsupported()
        val profile = request.queryParam("id")?.toIntOrNull()?.let { manager.state.value.find(it) }
            ?: return result(false, tr("没有这个用户了，刷新页面再看看"))
        if (!profile.isLocal) return result(false, tr("只有本地用户可以导出"))
        val archiver = archiver ?: return unsupported()
        val archive = runBlocking { withTimeoutOrNull(EXPORT_TIMEOUT) { archiver.export(profile) } }
            ?: return result(false, tr("导出超时，请重试"))
        logger.info { "Remote control exported user profile ${profile.id}: ${archive.collections.size} collections, ${archive.playback.size} playback" }
        return buildJsonObject {
            put("ok", true)
            put("collections", archive.collections.size)
            put("playback", archive.playback.size)
            put("archive", ProfileArchive.Json.encodeToJsonElement(ProfileArchive.serializer(), archive))
        }
    }

    /** 表单 `subjects` (逗号分隔的条目编号) 里当前用户已经收藏了的: 网页据此算出会新加几部. */
    private fun check(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        refusal(fields)?.let { return it }
        val archiver = archiver ?: return unsupported()
        val subjects = fields["subjects"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        val collected = runBlocking { withTimeoutOrNull(OP_TIMEOUT) { archiver.collectedAmong(subjects) } }
            ?: return result(false, tr("读取超时，请重试"))
        return buildJsonObject {
            put("ok", true)
            putJsonArray("collected") { collected.forEach { add(it) } }
        }
    }

    /** 表单 `entry`: 一条收藏 ([ProfileArchive.Collection] 的 JSON). 回应 `added` (false = 已经收藏了, 没动) 与 `episodes`. */
    private fun restoreCollection(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        refusal(fields)?.let { return it }
        val archiver = archiver ?: return unsupported()
        val item = runCatching {
            ProfileArchive.Json.decodeFromString(ProfileArchive.Collection.serializer(), fields["entry"].orEmpty())
        }.getOrNull() ?: return result(false, tr("这一条读不了"))
        val outcome = try {
            runBlocking { withTimeoutOrNull(ENTRY_TIMEOUT) { archiver.restoreCollection(item) } }
                ?: return result(false, tr("从 Bangumi 取条目信息超时"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepositorySubjectNotAccessibleException) {
            return result(false, tr("Bangumi 上打不开这个条目"))
        } catch (e: IllegalArgumentException) {
            return result(false, tr("这一条读不了"))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore subject ${item.subjectId}" }
            return result(false, tr("取条目信息失败：{0}", e.message ?: e::class.simpleName))
        }
        return buildJsonObject {
            put("ok", true)
            put("added", outcome is ProfileArchiver.CollectionOutcome.Added)
            put("episodes", (outcome as? ProfileArchiver.CollectionOutcome.Added)?.episodesMarked ?: 0)
        }
    }

    /** 表单 `records`: 一批播放进度 ([ProfileArchive.Playback] 的 JSON 数组). 回应导进来的条数 `restored`. */
    private fun restorePlayback(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        refusal(fields)?.let { return it }
        val archiver = archiver ?: return unsupported()
        val items = runCatching {
            ProfileArchive.Json.decodeFromString(ListSerializer(ProfileArchive.Playback.serializer()), fields["records"].orEmpty())
        }.getOrNull() ?: return result(false, tr("播放进度读不了"))
        val restored = runBlocking { withTimeoutOrNull(OP_TIMEOUT) { archiver.restorePlayback(items) } }
            ?: return result(false, tr("写入超时，请重试"))
        return buildJsonObject {
            put("ok", true)
            put("restored", restored)
        }
    }

    /** 导进哪个用户 (表单 `id`) 对不上电视上的当前用户, 或当前用户不是本地用户: 整个导入停下. */
    private fun refusal(fields: Map<String, String>): JsonObject? {
        val manager = manager ?: return unsupported()
        if (fields["id"]?.toIntOrNull() != manager.currentId) return stop(tr("电视上的用户换了，刷新页面再导入"))
        if (!UserProfiles.current.isLocal) return stop(tr("只能导入到本地用户"))
        return null
    }

    private fun stop(message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("stop", true)
        put("message", message)
    }

    private fun unsupported(): JsonObject = result(false, tr("这台设备不支持多用户"))

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private val OP_TIMEOUT = 10.seconds

    private val EXPORT_TIMEOUT = 30.seconds

    /** 一条收藏: 库里没有这个条目时要取条目信息与分集 (经镜像时慢一些). */
    private val ENTRY_TIMEOUT = 30.seconds
}
