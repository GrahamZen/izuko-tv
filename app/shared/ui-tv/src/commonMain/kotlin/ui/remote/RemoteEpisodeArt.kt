/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.matchToEpisodes
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.datasources.api.toLocalDateOrNull
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * 手机「播放器」标签那张卡 (正在播 / 接下来播放) 的底图: 这一集的 TMDB 剧照, 没有就整部的横屏图, 都没有就不画.
 *
 * 状态每 1.5 秒轮询一次、按内容算版本号, 所以这里**不能阻塞, 结果也不能每次都变**: 第一次问起时在后台查, 结果记下来,
 * 下一次轮询带上. 只查缓存 (`peekCached*`, 不触发 TMDB 查询) —— 播放页 / 详情页自己早把这部的剧照与横屏图查进缓存了;
 * 刚进播放页时那边可能还在路上, 所以查空的过 [RETRY_MILLIS] 再查一次.
 *
 * 卡片几乎整屏宽 (3 倍屏约 1100 像素), 取 w1280 (实测剧照 80~140KB); 经电视转发 ([RemoteImageProxy]), 手机连不上 TMDB 也有图.
 */
internal object RemoteEpisodeArt {
    private val logger = logger<RemoteEpisodeArt>()
    private val collections: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val tmdb: TmdbImageService get() = KoinPlatform.getKoin().get()
    private val settings: SettingsRepository get() = KoinPlatform.getKoin().get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Found(val art: List<String>, val atMillis: Long)

    private val found = ConcurrentHashMap<Long, Found>()
    private val pending: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** 这一集卡片底图的候选 (手机依次试); 还没查过 / 正在查返回上次的结果或 null, 不等. */
    fun peek(subjectId: Int, episodeId: Int): List<String>? {
        val key = (subjectId.toLong() shl 32) or (episodeId.toLong() and 0xffffffffL)
        val hit = found[key]
        if (hit != null && (hit.art.isNotEmpty() || System.currentTimeMillis() - hit.atMillis < RETRY_MILLIS)) return hit.art
        if (pending.add(key)) {
            scope.launch {
                val art = try {
                    withTimeoutOrNull(LOOKUP_TIMEOUT) { lookup(subjectId, episodeId) }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to look up player card art for subject $subjectId episode $episodeId" }
                    emptyList()
                } finally {
                    pending.remove(key)
                }
                if (found.size >= MAX_ENTRIES) found.clear()
                found[key] = Found(art, System.currentTimeMillis())
            }
        }
        return hit?.art
    }

    private suspend fun lookup(subjectId: Int, episodeId: Int): List<String> {
        // 同 TV 各页取剧照的语言: 剧照缓存按抓取语言记
        val language = (settings.uiSettings.flow.first().appLanguage ?: Locale.current).toTmdbLanguage()
        val still = tmdb.peekCachedEpisodeStills(subjectId, language)?.let { stills ->
            val info = collections.subjectCollectionFlow(subjectId).first()
            stills.matchToEpisodes(info.episodes, info.subjectInfo.airDate.toLocalDateOrNull()?.toString())?.get(episodeId)?.stillUrl
        }
        val backdrop = tmdb.peekCachedBackdropUrl(subjectId)
        logger.info { "Player card art for subject $subjectId episode $episodeId: still=${still != null}, backdrop=${backdrop != null}" }
        return listOfNotNull(still, backdrop).distinct().map { RemoteImageProxy.proxied(it.replace(TMDB_SIZE_SEGMENT, "/t/p/w1280/")) }
    }

    private const val RETRY_MILLIS = 10_000L
    private const val MAX_ENTRIES = 200
    private val LOOKUP_TIMEOUT = 5.seconds
    private val TMDB_SIZE_SEGMENT = Regex("""/t/p/[^/]+/""")
}

/** 状态 JSON 里放 `art` (见 [RemoteEpisodeArt]); 还没查到 / 没有图就不放. */
internal fun JsonObjectBuilder.putEpisodeArt(subjectId: Int?, episodeId: Int?) {
    if (subjectId == null || episodeId == null) return
    val art = RemoteEpisodeArt.peek(subjectId, episodeId)
    if (!art.isNullOrEmpty()) putJsonArray("art") { art.forEach { add(it) } }
}
