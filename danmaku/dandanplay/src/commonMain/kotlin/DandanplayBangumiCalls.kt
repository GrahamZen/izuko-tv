/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.danmaku.dandanplay.data.DandanplayGetBangumiResponse
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * 弹弹 play 作品详情接口 (`/api/v2/bangumi/{animeId}` 与按 Bangumi 条目映射的 `/api/v2/bangumi/bgmtv/{subjectId}`) 的调用记录.
 *
 * - 拿到的结果记 [keep]: 同一部番看下一集、手动换弹幕时不再取.
 * - 弹弹 play 的额度按应用 (AppId) 计、所有用户共用. 这类接口回「已达到接口调用配额上限」(HTTP 200, 响应体 errorCode = 429) 后
 *   [pause] 内不再调, 直接当作被拒; 别的接口 (搜索、取弹幕) 照常.
 */
internal class DandanplayBangumiCalls(
    private val keep: Duration = 12.hours,
    private val pause: Duration = 5.minutes,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private class Entry(val response: DandanplayGetBangumiResponse, val atMillis: Long)

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    @Volatile
    private var pausedUntilMillis = 0L

    /** 被限额暂停中. */
    val paused: Boolean get() = clock() < pausedUntilMillis

    suspend fun anime(animeId: Int, fetch: suspend () -> DandanplayGetBangumiResponse): DandanplayGetBangumiResponse =
        call(animeKey(animeId), fetch)

    suspend fun bgmtv(subjectId: Int, fetch: suspend () -> DandanplayGetBangumiResponse): DandanplayGetBangumiResponse =
        call("bgmtv:$subjectId", fetch)

    /** 记着的 [animeId] 作品详情, 不发请求; 没有或过期为 null. */
    suspend fun cachedAnime(animeId: Int): DandanplayGetBangumiResponse? = lock.withLock { getLocked(animeKey(animeId)) }

    /** 丢掉记着的 [animeId] 作品详情 (分集列表里没有要的那集, 可能是新播出的). */
    suspend fun forgetAnime(animeId: Int) {
        lock.withLock { entries.remove(animeKey(animeId)) }
    }

    private suspend fun call(key: String, fetch: suspend () -> DandanplayGetBangumiResponse): DandanplayGetBangumiResponse {
        lock.withLock { getLocked(key) }?.let { return it }
        if (paused) return QUOTA_EXCEEDED_RESPONSE
        val response = fetch()
        if (response.errorCode == QUOTA_EXCEEDED) {
            pausedUntilMillis = clock() + pause.inWholeMilliseconds
            return response
        }
        lock.withLock {
            entries.remove(key)
            entries[key] = Entry(response, clock())
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
        return response
    }

    private fun getLocked(key: String): DandanplayGetBangumiResponse? {
        val entry = entries[key] ?: return null
        if (clock() - entry.atMillis > keep.inWholeMilliseconds) {
            entries.remove(key)
            return null
        }
        return entry.response
    }

    private fun animeKey(animeId: Int) = "anime:$animeId"

    companion object {
        /** 弹弹 play 响应体里「已达到接口调用配额上限」的 errorCode. */
        const val QUOTA_EXCEEDED = 429

        private val QUOTA_EXCEEDED_RESPONSE = DandanplayGetBangumiResponse(
            bangumi = null,
            errorCode = QUOTA_EXCEEDED,
            success = false,
            errorMessage = "已达到接口调用配额上限 (暂停调用中)",
        )
    }
}
