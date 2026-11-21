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
import me.him188.ani.app.data.repository.subject.RewatchStartResult
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台收藏菜单里的「重温」(见 `SubjectRewatch`): 读收藏状态的接口多回 `rewatching`, 设收藏状态的接口认 `type=REWATCH`
 * 开始重温. 其余收藏类型照旧走改收藏状态的用例, 重温中选了就结束重温 (同电视详情页的收藏胶囊).
 */
internal object RemoteRewatch {
    /** 收藏菜单里代表「重温」的类型名, 不是 Bangumi 的收藏类型. */
    const val TYPE = "REWATCH"

    private val repository: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val logger = logger<RemoteRewatch>()
    private val READ_TIMEOUT = 3.seconds
    private val START_TIMEOUT = 10.seconds

    /** 这一部是不是正在重温; 读不出来 (超时) 当没在重温. */
    fun isRewatching(subjectId: Int): Boolean = runCatching {
        runBlocking { withTimeoutOrNull(READ_TIMEOUT) { repository.rewatchingFlow(subjectId).first() } }
    }.getOrNull() == true

    fun start(subjectId: Int): JsonObject {
        val outcome = try {
            runBlocking { withTimeoutOrNull(START_TIMEOUT) { repository.startRewatch(subjectId) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Remote start rewatch failed: subject $subjectId" }
            return result(false, tr("开始重温失败，请重试"))
        }
        logger.info { "Remote start rewatch: subject $subjectId -> $outcome" }
        return when (outcome) {
            RewatchStartResult.STARTED -> result(true, tr("已开始重温，这一轮的进度只记在电视上"))
            RewatchStartResult.NOT_DONE -> result(false, tr("只有看过的番能重温"))
            RewatchStartResult.PENDING_SYNC -> result(false, tr("这部还有看过记录没同步到 Bangumi，同步完再开始重温"))
            null -> result(false, tr("操作超时，请重试"))
        }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
