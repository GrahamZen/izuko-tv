/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.errorOrNull

/*
 * iPad 测试包的播放诊断 (只在 CI 测试分支): 很多数据源 iOS 上放不了 (AVPlayer 不收 mkv / 本地流, BT 被滤掉, 规则源不走直解等),
 * 播不了时要看是哪一步: 各数据源查询结果 → 选中哪一条 → 解析 → 交给播放器 → 播放器报错. 日志里搜 `iPadDiag`.
 * 地址只记协议、主机与扩展名, 不记查询参数与请求头.
 */

private val diagLogger = logger("iPadDiag")

fun ipadDiag(message: String) {
    diagLogger.info { "[iPadDiag] $message" }
}

/** 地址的诊断描述: 协议://主机 …扩展名 (不带路径中段与查询参数). */
fun ipadDiagUrl(url: String): String {
    val scheme = url.substringBefore("://", "")
    val rest = url.substringAfter("://")
    val host = rest.substringBefore('/').substringBefore('?')
    val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('/').substringAfterLast('.', "").take(8)
    return "$scheme://$host${if (ext.isNotEmpty()) " …$ext" else ""}"
}

/** 一条资源的诊断描述. */
fun Media.ipadDiagText(): String =
    "source=$mediaSourceId kind=$kind download=${download::class.simpleName}(${ipadDiagUrl(download.uri)}) " +
            "res=${properties.resolution} size=${properties.size} title=$originalTitle"

/** 跟着一个播放页会话记: 各数据源的查询状态、选中哪一条、加载状态、播放状态与播放器错误. 在播放页 ViewModel 的后台协程里跑. */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun observeIpadPlayerDiag(state: EpisodeFetchSelectPlayState, player: MediampPlayer): Unit = coroutineScope {
    launch {
        state.episodeSessionFlow.flatMapLatest { it.fetchSelectFlow }.filterNotNull().collectLatest { bundle ->
            coroutineScope {
                ipadDiag("fetch session: ${bundle.mediaFetchSession.mediaSourceResults.size} sources")
                for (result in bundle.mediaFetchSession.mediaSourceResults) {
                    launch {
                        result.state.collect { s ->
                            val detail = when (s) {
                                is MediaSourceFetchState.Failed -> " ${s.cause::class.simpleName}: ${s.cause.message?.take(160)}"
                                is MediaSourceFetchState.Abandoned -> " ${s.cause::class.simpleName}: ${s.cause.message?.take(160)}"
                                else -> ""
                            }
                            if (s !is MediaSourceFetchState.Idle && s !is MediaSourceFetchState.Working) {
                                ipadDiag("source ${result.sourceInfo.displayName} (${result.mediaSourceId}, ${result.kind}) -> ${s::class.simpleName}$detail")
                            }
                        }
                    }
                }
                launch {
                    // 总共多少条、被筛掉的各是什么原因 (按原因计数)
                    bundle.mediaSelector.filteredCandidates.map { list ->
                        val excluded = list.mapNotNull { it.exclusionReason?.let { r -> r::class.simpleName ?: r.toString() } }
                        "candidates: total=${list.size} included=${list.size - excluded.size} excluded=${excluded.groupingBy { it }.eachCount()}"
                    }.distinctUntilChanged().collect { ipadDiag(it) }
                }
                bundle.mediaSelector.selected.collect { ipadDiag("selected: ${it?.ipadDiagText()}") }
            }
        }
    }
    launch {
        state.playerSession.videoLoadingState.collect { ipadDiag("videoLoading: $it") }
    }
    launch {
        player.playbackState.collect { ipadDiag("playback: $it at ${player.currentPositionMillis.value}ms") }
    }
    launch {
        player.mediaProperties.filterNotNull().collect { ipadDiag("mediaProperties: duration=${it.durationMillis}ms title=${it.title}") }
    }
    launch {
        player.state.map { it.errorOrNull }.distinctUntilChanged().filterNotNull().collect { e ->
            ipadDiag("player error: ${e::class.simpleName}: ${e.message?.take(300)} cause=${e.cause?.let { c -> "${c::class.simpleName}: ${c.message?.take(300)}" }}")
        }
    }
}
