/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.torrent

import androidx.datastore.core.DataStore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.hours

/**
 * tracker 列表的订阅 ([AnitorrentConfig.trackerSubscriptionUrls]): 从订阅地址拉 tracker 列表, 拉到的与内置、额外的 tracker 一起
 * 加到 BT 下载里 (见 [withSubscribedTrackers]). 每个地址各留一份最近拉到的 ([TrackerSubscriptionEntry]), 拉不到时继续用那一份.
 *
 * @param urls 现在填着的订阅地址 (见 [trackerSubscriptionUrlList])
 */
class TrackerSubscriptionRepository(
    private val urls: Flow<List<String>>,
    private val dataStore: DataStore<TrackerSubscriptionSaveData>,
    private val httpClient: ScopedHttpClient,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val logger = logger<TrackerSubscriptionRepository>()
    private val refreshLock = Mutex()

    /** 现在填着的各个地址的情况, 按填的顺序; 还没拉过的是一份空的. */
    val entries: Flow<List<TrackerSubscriptionEntry>> = combine(urls, dataStore.data) { urls, data ->
        urls.map { url -> data.entries.firstOrNull { it.url == url } ?: TrackerSubscriptionEntry(url) }
    }

    /** 现在填着的地址拉到的全部 tracker, 去重. */
    val trackers: Flow<List<String>> = entries.map { list -> list.flatMap { it.trackers }.distinct() }.distinctUntilChanged()

    /**
     * 跟着订阅地址走: 地址一变就拉, 之后每 [CHECK_INTERVAL] 看一次, 拉到超过 [REFRESH_AFTER] 的重拉.
     * 一直挂着 (电视上进程常常活好几天), 由调用方的作用域取消.
     */
    suspend fun keepUpdated() {
        urls.distinctUntilChanged().collectLatest {
            while (true) {
                refresh(reuseRecent = true)
                delay(CHECK_INTERVAL)
            }
        }
    }

    /**
     * 拉一遍现在填着的地址, 返回各地址的情况. [reuseRecent] 时 [REFRESH_AFTER] 之内拉成功过的不联网 (启动与定时用),
     * 控制台「立即更新」传 `false`. 拉不到的地址留着上一份并记下原因; 不再填着的地址删掉.
     */
    suspend fun refresh(reuseRecent: Boolean = false): List<TrackerSubscriptionEntry> = refreshLock.withLock {
        val current = urls.first()
        val saved = dataStore.data.first().entries.associateBy { it.url }
        val updated = current.map { url ->
            val old = saved[url] ?: TrackerSubscriptionEntry(url)
            if (reuseRecent && old.isFresh()) old else fetch(old)
        }
        dataStore.updateData { TrackerSubscriptionSaveData(updated) }
        updated
    }

    private suspend fun fetch(old: TrackerSubscriptionEntry): TrackerSubscriptionEntry {
        val result = try {
            httpClient.use {
                val response = get(old.url)
                if (response.status.isSuccess()) Result.success(response.bodyAsText())
                else Result.failure(IllegalStateException("HTTP ${response.status.value}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val text = result.getOrElse { e ->
            logger.warn { "Failed to fetch tracker subscription ${old.url}, keeping ${old.trackers.size} trackers from last time: $e" }
            return old.copy(error = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "error")
        }
        val trackers = parseTrackerList(text)
        if (trackers.isEmpty()) {
            logger.warn { "Tracker subscription ${old.url} lists no tracker, keeping ${old.trackers.size} from last time" }
            return old.copy(error = TrackerSubscriptionEntry.EMPTY_LIST)
        }
        logger.info { "Tracker subscription ${old.url}: ${trackers.size} trackers" }
        return TrackerSubscriptionEntry(old.url, trackers, fetchedAtMillis = clock())
    }

    private fun TrackerSubscriptionEntry.isFresh(): Boolean =
        trackers.isNotEmpty() && error == null && clock() - fetchedAtMillis < REFRESH_AFTER.inWholeMilliseconds

    private companion object {
        /** 列表一般每天更新一次 */
        val REFRESH_AFTER = 24.hours
        val CHECK_INTERVAL = 6.hours
    }
}

@Serializable
data class TrackerSubscriptionSaveData(
    val entries: List<TrackerSubscriptionEntry> = emptyList(),
) {
    companion object {
        val Default = TrackerSubscriptionSaveData()
    }
}

/** 一个订阅地址最近拉到的. */
@Serializable
data class TrackerSubscriptionEntry(
    val url: String,
    /** 最近一次拉成功拿到的 tracker */
    val trackers: List<String> = emptyList(),
    /** 最近一次拉成功的时间, 没成功过是 0 */
    val fetchedAtMillis: Long = 0,
    /** 最近一次没拉成的原因 ([EMPTY_LIST] = 拉到了但里面没有 tracker), 拉成功后清掉 */
    val error: String? = null,
) {
    companion object {
        const val EMPTY_LIST = "empty"
    }
}

/** 填着的订阅地址: 每行一个, 去掉空行与重复. */
fun AnitorrentConfig.trackerSubscriptionUrlList(): List<String> =
    trackerSubscriptionUrls.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

/** 交给 BT 引擎的配置: [AnitorrentConfig.extraTrackers] 后面接上订阅拉到的 [subscribed] (与内置的重复的由下载器去掉). */
fun AnitorrentConfig.withSubscribedTrackers(subscribed: List<String>): AnitorrentConfig =
    if (subscribed.isEmpty()) this
    else copy(extraTrackers = (extraTrackers.lines().filter { it.isNotBlank() } + subscribed).joinToString("\n"))

/**
 * 从订阅拉到的文本里取 tracker: 一行一个或用逗号隔开 (两种列表格式都有), `#` 开头的行是注释.
 * 只认 tracker 的写法 (http / https 的要有 announce, 免得把错误页、登录页里的链接当成 tracker), 最多 [MAX_TRACKERS] 个.
 */
internal fun parseTrackerList(text: String): List<String> = text.lineSequence()
    .map { it.trim() }
    .filterNot { it.startsWith('#') }
    .flatMap { line -> line.split(',', ' ', '\t').asSequence() }
    .map { it.trim() }
    .filter { it.looksLikeTracker() }
    .distinct()
    .take(MAX_TRACKERS)
    .toList()

private fun String.looksLikeTracker(): Boolean {
    if (any { it == '<' || it == '>' || it == '"' || it == '\'' }) return false
    val scheme = substringBefore("://", missingDelimiterValue = "").lowercase()
    val rest = substringAfter("://", missingDelimiterValue = "")
    if (rest.isEmpty() || rest.startsWith('/')) return false
    return when (scheme) {
        "udp", "ws", "wss" -> true
        "http", "https" -> rest.contains("/announce")
        else -> false
    }
}

private const val MAX_TRACKERS = 500
