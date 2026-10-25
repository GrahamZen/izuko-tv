/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.togglePlayWhenReady
import kotlin.time.Duration.Companion.seconds

/**
 * 调试接口的「播放器」那几个: 读的字段与 Web 控制台的 `api/player` 同名 (控制台脚本的 state / candidates / select 两边通用).
 * 当前播放器取自保留会话 ([DebugConsole.playback]); 保留会话关着时拿不到.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal object DebugConsolePlayer {
    val vm: EpisodeViewModel? get() = DebugConsole.playback?.currentViewModel

    /** 页面状态与选源器只在有人订阅时更新: 没人看着 (后台、小窗) 时订阅一下等它出来. */
    private suspend fun page(vm: EpisodeViewModel): EpisodePageState? =
        vm.pageState.value ?: withTimeoutOrNull(WAIT) { vm.pageState.filterNotNull().first() }

    private suspend fun presentation(page: EpisodePageState): MediaSelectorState.Presentation {
        val flow = page.mediaSelectorState.presentationFlow
        return flow.value.takeUnless { it.isPlaceholder }
            ?: withTimeoutOrNull(WAIT) { flow.first { !it.isPlaceholder } }
            ?: flow.value
    }

    suspend fun state(): JsonObject {
        val vm = vm ?: return buildJsonObject {
            put("available", false)
            put("reason", "no playback session (needs the retained playback session setting)")
        }
        val page = page(vm)
        val pres = page?.let { presentation(it) }
        val selected = pres?.selected ?: vm.loadedMedia.value
        val sources = page?.mediaSourceResultListPresentation?.list.orEmpty()
        return buildJsonObject {
            put("available", true)
            put("background", DebugConsole.navigator?.backStack?.lastOrNull() !is NavRoutes.EpisodeDetail)
            put("subjectId", vm.subjectId)
            if (page != null) {
                val ep = page.episodePresentation
                put("title", page.subjectPresentation.title)
                put("episodeId", ep.episodeId)
                put("episode", listOf(ep.sort, ep.title).filter { it.isNotBlank() }.joinToString("  "))
                put("loading", page.isLoading)
                page.loadError?.let { put("loadError", it.toString()) }
            }
            put("selectedId", selected?.mediaId)
            put("selectedTitle", selected?.originalTitle)
            if (selected != null) {
                put("selectedSourceId", selected.mediaSourceId)
                put("selectedSource", sourceName(selected, sources))
            }
            put("playback", playback(vm))
            val currentEpisodeId = page?.episodePresentation?.episodeId
            putJsonArray("episodes") {
                for (ep in vm.episodeSelectorState.items) addJsonObject {
                    put("id", ep.episodeId)
                    put("label", listOf(ep.sort, ep.title).filter { it.isNotBlank() }.joinToString("  "))
                    put("current", ep.episodeId == currentEpisodeId)
                }
            }
            putJsonArray("sources") {
                for (source in sources) {
                    if (source.kind == MediaSourceKind.LocalCache || source.isDisabled) continue
                    addJsonObject {
                        put("id", source.mediaSourceId)
                        put("name", source.info.displayName)
                        put("state", source.stateLabel())
                        put("count", source.totalCount)
                    }
                }
            }
            val all = pres?.filteredCandidates.orEmpty()
            put("excludedCount", all.count { it is MaybeExcludedMedia.Excluded })
            // 按数据源分组 (缓存在前, 其余按数据源列表的顺序), 每组里放行的在前、被排除的附原因; 不截断
            val byId = all.sortedBy { if (it is MaybeExcludedMedia.Included) 0 else 1 }.groupBy { it.original.mediaSourceId }
            val order = byId.keys.sortedBy { id ->
                if (byId.getValue(id).first().original.kind == MediaSourceKind.LocalCache) -1
                else sources.indexOfFirst { it.mediaSourceId == id }.takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
            putJsonArray("groups") {
                for (id in order) {
                    val list = byId.getValue(id)
                    addJsonObject {
                        put("id", id)
                        put("name", sourceName(list.first().original, sources))
                        put("total", list.size)
                        put("more", 0)
                        putJsonArray("items") {
                            for (entry in list) addJsonObject {
                                val media = entry.original
                                val props = media.properties
                                put("id", media.mediaId)
                                put("title", media.originalTitle)
                                put("resolution", props.resolution.takeIf { it.isNotBlank() })
                                put("subtitles", props.subtitleLanguageIds.distinct().joinToString("/").takeIf { it.isNotBlank() })
                                put("alliance", props.alliance.takeIf { it.isNotBlank() })
                                put("size", props.size.takeIf { it.inBytes > 0 }?.toString())
                                put("selected", media == selected)
                                entry.exclusionReason?.let { reason ->
                                    put("excluded", true)
                                    put("reason", reason.toString())
                                    put("blocked", reason.blocksSelection)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun playback(vm: EpisodeViewModel): JsonObject = buildJsonObject {
        val player = vm.player
        put("state", player.state.value.toString())
        put("playing", player.state.value.isPlaying)
        put("position", player.currentPositionMillis.value)
        put("duration", player.mediaProperties.value?.durationMillis ?: 0L)
        player.features[PlaybackSpeed]?.let { put("speed", it.value) }
    }

    /** 选中候选 (含被排除的, 只有硬性不可用的不行); 出错时返回原因. */
    suspend fun select(mediaId: String): String? {
        val vm = vm ?: return "no playback session"
        val page = page(vm) ?: return "player page state not ready"
        val entry = presentation(page).filteredCandidates.firstOrNull { it.original.mediaId == mediaId }
            ?: return "no candidate with this id (list changed?)"
        if (entry.exclusionReason?.blocksSelection == true) return "this candidate cannot be selected: ${entry.exclusionReason}"
        page.mediaSelectorState.select(entry.original)
        return null
    }

    /** 播放控制: play / pause / toggle / back / forward (`ms` 步长, 默认 10 秒) / seek (`ms`) / speed (`v`). */
    fun control(request: DebugConsoleRequest): String? {
        val vm = vm ?: return "no playback session"
        val player = vm.player
        val ms = request.params["ms"]?.trim()?.toLongOrNull()
        when (request.params["action"]) {
            "toggle" -> player.togglePlayWhenReady()
            "play" -> player.play()
            "pause" -> player.pause()
            "back" -> player.skip(-(ms ?: SEEK_STEP_MILLIS))
            "forward" -> player.skip(ms ?: SEEK_STEP_MILLIS)
            "seek" -> {
                val target = ms ?: return "seek needs ms"
                val duration = player.mediaProperties.value?.durationMillis?.takeIf { it > 0 }
                player.seekTo(target.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it })
            }

            "speed" -> {
                val speed = request.params["v"]?.trim()?.toFloatOrNull() ?: return "speed needs v"
                if (player.features[PlaybackSpeed] == null) return "player has no speed control"
                vm.setPlaybackSpeed(speed)
            }

            else -> return "unknown action ${request.params["action"]}"
        }
        return null
    }

    /** 就地换集 (同播放器里的选集). */
    fun switchEpisode(episodeId: Int): String? {
        val vm = vm ?: return "no playback session"
        return if (vm.episodeSelectorState.selectEpisodeId(episodeId)) null else "episode $episodeId is not in the list"
    }

    private fun sourceName(media: Media, sources: List<MediaSourceResultPresentation>): String =
        if (media.kind == MediaSourceKind.LocalCache) "LocalCache"
        else sources.firstOrNull { it.mediaSourceId == media.mediaSourceId }?.info?.displayName ?: media.mediaSourceId

    private fun MediaSourceResultPresentation.stateLabel(): String = when {
        isWorking -> "loading"
        isCaptchaRequired -> "captcha"
        isRateLimited -> "limited"
        isFailedOrAbandoned -> "failed"
        isPaused -> "paused"
        else -> "done"
    }

    private val WAIT = 3.seconds
    private const val SEEK_STEP_MILLIS = 10_000L
}
