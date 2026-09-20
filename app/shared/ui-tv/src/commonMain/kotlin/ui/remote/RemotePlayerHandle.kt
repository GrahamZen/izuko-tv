/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.ui.foundation.SLIDER_VALUE_STEP
import me.him188.ani.app.ui.foundation.quantizeSliderValue
import me.him188.ani.app.ui.mediafetch.MediaSelectorState
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.mediafetch.request.toEditingMediaFetchRequest
import me.him188.ani.app.ui.mediafetch.request.toMediaFetchRequestOrNull
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodePresentation
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.tv.TV_PLAYBACK_SPEED_RANGE
import me.him188.ani.app.videoplayer.ui.PlayerStatsSnapshot
import me.him188.ani.app.videoplayer.ui.rememberPlayerStatsState
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.app.ui.remote.RemotePlayerExtras.putDanmakuState
import me.him188.ani.app.ui.remote.RemotePlayerExtras.putTrackState
import me.him188.ani.app.ui.remote.RemoteCandidates.putCandidates
import org.koin.mp.KoinPlatform
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.audioTracks
import org.openani.mediamp.features.subtitleTracks
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.togglePlayWhenReady
import kotlin.math.roundToInt
import android.media.AudioManager as SystemAudioManager

/**
 * 播放页登记给 [TvRemoteControl] 的把手: 手机网页「播放器」标签的读写都经它.
 *
 * - **读** ([stateJson]) 在 HTTP 线程上跑, 只读这里缓存的最新快照 ([page] / [presentation]) 与播放器的
 *   StateFlow, 不碰组合.
 * - **写** 一律投到 [uiScope] (播放页组合的 scope, 主线程) 执行: 播放器必须在主线程上调 (跨线程调 ExoPlayer
 *   就是「显示 1.25 倍实际原速」那个 bug 的真因), 选源也与电视上点选走同一条路.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal class RemotePlayerHandle(
    val vm: EpisodeViewModel,
    private val uiScope: CoroutineScope,
    /** true = 保留会话在后台 (播放页不在组合里): 可换源 / 改查询请求, 不给播放控制 (见 [TvRemoteControl]). */
    val background: Boolean,
) {
    @Volatile
    var page: EpisodePageState? = null

    /**
     * 手机上「播放信息」展开着 (最近几秒内有带 `stats=1` 的轮询). 组合据此决定要不要采集 —— 采集是每秒读一次播放器
     * (同电视上的播放信息浮层), 没人看就不做. 见 [RegisterTvRemotePlayer].
     */
    val statsWanted = MutableStateFlow(false)

    @Volatile
    private var lastStatsRequest = 0L

    /** 最近一次采到的播放信息; 没在采时为 null. */
    @Volatile
    var stats: PlayerStatsSnapshot? = null

    fun requestStats() {
        lastStatsRequest = System.currentTimeMillis()
        statsWanted.value = true
    }

    fun statsIdle(): Boolean = System.currentTimeMillis() - lastStatsRequest > STATS_IDLE_MILLIS

    /** 字幕轨 / 音轨的最新候选与选中 (组合里收集, 见 [RegisterTvRemotePlayer]); 播放器不支持时为 null. */
    @Volatile
    var subtitleState: RemoteTrackState<SubtitleTrack>? = null

    @Volatile
    var audioState: RemoteTrackState<AudioTrack>? = null

    /** 在播放页的主线程 scope 上执行: 写播放器与 VM 状态一律走这里 (见本类文档). */
    fun runOnUi(block: suspend () -> Unit) {
        uiScope.launch { block() }
    }

    /** 选字幕轨; 空 id = 关闭. 候选里找不到 (轨道已变) 返回 false. */
    fun selectSubtitle(id: String): Boolean {
        val group = vm.player.subtitleTracks ?: return false
        val track = if (id.isEmpty()) null else subtitleState?.candidates?.firstOrNull { it.id == id } ?: return false
        uiScope.launch { group.select(track) }
        return true
    }

    /** 选音轨; 空 id = 自动. */
    fun selectAudio(id: String): Boolean {
        val group = vm.player.audioTracks ?: return false
        val track = if (id.isEmpty()) null else audioState?.candidates?.firstOrNull { it.id == id } ?: return false
        uiScope.launch { group.select(track) }
        return true
    }

    /** 数据源选择器的最新呈现 (候选 / 当前选中). 由 [RegisterTvRemotePlayer] 持续收集. */
    @Volatile
    var presentation: MediaSelectorState.Presentation? = null

    fun stateJson(filter: RemoteMediaFilter = RemoteMediaFilter.None): JsonObject {
        val page = page
        val pres = presentation
        val selected = pres?.selected
        return buildJsonObject {
            put("available", true)
            put("background", background)
            // 手机上点卡片的剧名 = 电视打开这部的详情页
            put("subjectId", vm.subjectId)
            if (page != null) {
                put("title", page.subjectPresentation.title)
                val ep = page.episodePresentation
                put("episode", listOf(tr("第 {0} 话", ep.sort), ep.title).filter { it.isNotBlank() }.joinToString("  "))
                // 卡片底图: 这一集的剧照 (见 RemoteEpisodeArt)
                putEpisodeArt(vm.subjectId, ep.episodeId)
            }
            put("selectedId", selected?.mediaId)
            put("selectedTitle", selected?.originalTitle)

            // 选集: 与电视上选集侧边栏同一份列表 (EpisodeSelectorState.items), 当前集由页面状态给出
            val currentEpisodeId = page?.episodePresentation?.episodeId
            putJsonArray("episodes") {
                for (ep in vm.episodeSelectorState.items) addJsonObject {
                    put("id", ep.episodeId)
                    put("label", episodeLabel(ep))
                    put("current", ep.episodeId == currentEpisodeId)
                }
            }

            // 弹幕与音轨 / 字幕轨 (手机上两个可收起的区), 见 RemotePlayerExtras
            if (page != null) putDanmakuState(page)
            putTrackState(this@RemotePlayerHandle)

            // 当前查询条件 (「编辑查询请求」那几项), 手机上的表单用它预填
            val fetchRequest = page?.fetchRequest
            if (fetchRequest != null) {
                val editing = fetchRequest.toEditingMediaFetchRequest()
                putJsonObject("request") {
                    put("primary", editing.primaryName)
                    putJsonArray("others") { editing.complementaryNames.forEach { add(it) } }
                    put("sort", editing.episodeSort)
                    put("ep", editing.episodeEp)
                }
                put("requestIsDefault", fetchRequest.subjectNames == page.defaultFetchRequest?.subjectNames)
            }

            val sources = page?.mediaSourceResultListPresentation?.list.orEmpty()
            // 当前选中的来自哪个数据源 + 规格: 手机页放在播放键上方 (候选列表里的角标要往下翻很远才看得到)
            if (selected != null) {
                val props = selected.properties
                // 胶囊行里标出正在播的那个源 (与「按源筛选」选中的样式区分开)
                put("selectedSourceId", selected.mediaSourceId)
                put("selectedSource", sourceName(selected.mediaSourceId, selected, sources))
                put(
                    "selectedMeta",
                    listOf(
                        props.resolution,
                        props.subtitleLanguageIds.map { RemoteCandidates.subtitleLabel(it) }.distinct().joinToString("/"),
                        props.alliance,
                    ).filter { it.isNotBlank() }.distinct().joinToString(" · "), // 在线源的「字幕组」常就是字幕语言 (简中 · 简中)
                )
            }
            put("loading", sources.any { it.isWorking })
            putJsonArray("sources") {
                for (source in sources) {
                    // 本地缓存源是内部的, 用户无感 (与电视上的数据源列表一致)
                    if (source.kind == MediaSourceKind.LocalCache || source.isDisabled) continue
                    addJsonObject {
                        put("id", source.mediaSourceId)
                        put("name", source.info.displayName)
                        put("state", source.stateLabel())
                        put("count", source.totalCount)
                    }
                }
            }

            // 候选列表 (分组 / 下拉筛选 / 被排除的): 与缓存页同一份实现, 见 RemoteCandidates
            putCandidates(
                all = pres?.filteredCandidates.orEmpty(),
                sourceOrder = sources.map { it.mediaSourceId },
                sourceName = { id, sample -> sourceName(id, sample, sources) },
                filter = filter,
                selected = selected,
            )
        }
    }

    /**
     * 选中 [mediaId] 对应的候选 (含被排除的). 只在当前候选里找 (手机上的列表可能已过时).
     * 与电视上在数据源弹窗里点选同一条路 ([MediaSelectorState.select]): 换源、记住分辨率 / 字幕组等偏好.
     * 被排除的也能选 (电视上同样可以), 只有硬性不可用的那档 ([blocksSelection], 缓存还没下完) 不行.
     * @return 出错时的提示文案; 成功为 null.
     */
    fun select(mediaId: String): String? {
        val page = page ?: return tr("电视当前不在播放页")
        val entry = presentation?.filteredCandidates.orEmpty().firstOrNull { it.original.mediaId == mediaId }
            ?: return tr("这个数据源已不在列表里，请刷新")
        if (entry.exclusionReason?.blocksSelection == true) return tr("这个资源现在不能播放（缓存还没下完）")
        uiScope.launch { page.mediaSelectorState.select(entry.original) }
        return null
    }

    /**
     * 换集: 与电视上选集条 / 选集侧边栏同一条路 ([EpisodeSelectorState.selectEpisodeId], 就地换集不导航).
     * 后台会话也能换 (照样加载, 只是被按住暂停); 保留会话记的「当前集」取自页面状态, 会跟着变.
     * @return 出错时的提示文案; 成功为 null.
     */
    fun switchEpisode(episodeId: Int): String? {
        val state = vm.episodeSelectorState
        if (page?.episodePresentation?.episodeId == episodeId) return null
        if (state.items.none { it.episodeId == episodeId }) return tr("这一集不在列表里，请刷新")
        uiScope.launch { state.selectEpisodeId(episodeId) }
        return null
    }

    /**
     * 改查询条件 (同电视上「编辑查询请求」的「保存并刷新」): 重启所有数据源的搜索, 条目名按条目记住
     * (见 [EpisodeViewModel.updateFetchRequest]). 校验与电视上的编辑框一致: 主搜索名不能空, 两种集数至少填一个.
     * @return 出错时的提示文案; 成功为 null.
     */
    fun updateRequest(primary: String, others: List<String>, sort: String, ep: String): String? {
        val page = page ?: return tr("电视当前不在播放页")
        val current = page.fetchRequest ?: return tr("数据源还在加载，请稍后再试")
        if (primary.isBlank()) return tr("主搜索名不能为空")
        if (sort.isBlank() && ep.isBlank()) return tr("两种集数至少要填一个")
        val request = current.toEditingMediaFetchRequest().copy(
            primaryName = primary.trim(),
            complementaryNames = others.map { it.trim() }.filter { it.isNotEmpty() },
            episodeSort = sort.trim(),
            episodeEp = ep.trim(),
        ).toMediaFetchRequestOrNull() ?: return tr("请求无效，请检查")
        uiScope.launch { vm.updateFetchRequest(request) }
        return null
    }

    /** 恢复默认查询条件 (Bangumi 名称与本集原本的集数); 同时清掉为本条目记住的搜索名. */
    fun resetRequest(): String? {
        val page = page ?: return tr("电视当前不在播放页")
        val default = page.defaultFetchRequest ?: return tr("数据源还在加载，请稍后再试")
        uiScope.launch { vm.updateFetchRequest(default) }
        return null
    }

    /**
     * 播放状态 (是否在播 / 位置 / 总长). 与 [stateJson] 分开: 位置每秒都变, 放进候选那份里的话版本号每秒都换,
     * 几百条候选就得每秒整份重发. 服务端每次轮询都附上这一小份, 不参与版本号.
     */
    /** @param includeStats 附上「播放信息」各行 (手机上展开着时); 还没采到时是空数组 */
    fun playbackJson(includeStats: Boolean = false): JsonObject = buildJsonObject {
        val player = vm.player
        put("playing", player.state.value.isPlaying)
        put("position", player.currentPositionMillis.value)
        put("duration", player.mediaProperties.value?.durationMillis ?: 0L)
        // 音量 (0~1) 与静音 (见 setVolume); 系统音量与播放器音量都取不到就不给, 手机上不显示音量条
        val sys = systemAudio
        if (sys != null) {
            val max = sys.getStreamMaxVolume(SystemAudioManager.STREAM_MUSIC).coerceAtLeast(1)
            put("volume", sys.getStreamVolume(SystemAudioManager.STREAM_MUSIC).toFloat() / max)
            put("muted", sys.isStreamMute(SystemAudioManager.STREAM_MUSIC))
            // 手机上按加减调音量的步进 = 系统的一档 (Shield 是 15 档). 让网页自己定 (比如固定 5%) 会与档位
            // 对不齐 —— setVolume 按档取整, 点一下可能原地不动或跳两档; 同倍速那边的教训: 别在网页重抄一份常量
            put("volumeStep", 1f / max)
        } else {
            player.features[AudioLevelController]?.let {
                put("volume", (it.volume.value / it.maxVolume).coerceIn(0f, 1f))
                put("muted", it.isMute.value)
                // 播放器音量是连续的, 给个手感合适的步进
                put("volumeStep", 0.05f)
            }
        }
        // 倍速 (见 setSpeed): 取不到倍速能力的播放器不给, 手机上就不显示这一行.
        // 范围用 [TV_PLAYBACK_SPEED_RANGE] —— 与电视上播放器内的倍速条同一个常量, 不是配置里的 min/max
        // (那条设置在遥控器形态下被隐藏, 永远是出厂的 0.5x–2.5x).
        vm.player.features[PlaybackSpeed]?.let {
            put("speed", it.value)
            put("speedMin", TV_PLAYBACK_SPEED_RANGE.start)
            put("speedMax", TV_PLAYBACK_SPEED_RANGE.endInclusive)
            // 手机那边按这个步进画档位; 与 SteppedSlider (电视倍速条) 同一个值
            put("speedStep", SLIDER_VALUE_STEP)
        }
        if (includeStats) {
            val s = stats
            putJsonArray("stats") {
                if (s != null) for ((k, v) in statsRows(s)) addJsonObject {
                    put("k", k)
                    put("v", v)
                }
            }
        }
    }

    /** 与电视上的播放信息浮层 (PlayerStatsOverlay) 同样的几行、同样的格式. */
    private fun statsRows(s: PlayerStatsSnapshot): List<Pair<String, String>> = buildList {
        add(tr("播放器") to s.backend)
        add(tr("状态") to s.playbackState)
        s.title?.takeIf { it.isNotBlank() }?.let { add(tr("媒体") to it) }
        add(tr("进度") to "${statsDuration(s.positionMillis)} / ${statsDuration(s.durationMillis)}")
        s.resolution?.let { add(tr("分辨率") to it) }
        s.frameRate?.let { add(tr("帧率") to "${statsDecimal(it)} fps") }
        s.videoCodec?.let { add(tr("视频编码") to it) }
        statsBitrate(s.videoBitrate)?.let { add(tr("视频码率") to it) }
        s.audioCodec?.let { add(tr("音频编码") to it) }
        statsBitrate(s.audioBitrate)?.let { add(tr("音频码率") to it) }
        listOfNotNull(
            s.audioSampleRate?.takeIf { it > 0 }?.let { "$it Hz" },
            s.audioChannels?.takeIf { it > 0 }?.let { "$it ch" },
        ).joinToString(" / ").takeIf { it.isNotBlank() }?.let { add(tr("音频格式") to it) }
        s.playbackSpeed?.let { add(tr("播放速度") to "${statsDecimal(it)}x") }
        statsBitrate(s.realtimeInputBitrate)?.let { add(tr("实时输入") to it) }
        statsBitrate(s.realtimeDemuxBitrate)?.let { add(tr("实时解复用") to it) }
        listOfNotNull(
            s.decodedVideoFrames?.let { "V $it" },
            s.decodedAudioFrames?.let { "A $it" },
            s.droppedVideoFrames?.takeIf { it > 0 }?.let { tr("丢帧 {0}", it) },
            s.droppedAudioBuffers?.takeIf { it > 0 }?.let { tr("丢音频 {0}", it) },
        ).joinToString(" / ").takeIf { it.isNotBlank() }?.let { add(tr("解码") to it) }
    }

    private fun statsDuration(millis: Long?): String {
        if (millis == null || millis < 0) return "--:--"
        val t = millis / 1000
        val h = t / 3600
        val m = t % 3600 / 60
        val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun statsBitrate(bps: Long?): String? = when {
        bps == null || bps <= 0 -> null
        bps >= 1_000_000 -> "${statsDecimal(bps / 1_000_000f)} Mbps"
        else -> "${(bps / 1000f).roundToInt()} kbps"
    }

    private fun statsDecimal(value: Float): String {
        val text = ((value * 100).roundToInt() / 100f).toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }

    /** 播放控制; 在播放页的主线程 scope 上执行. 未知动作返回 false. */
    fun control(action: String): Boolean {
        val run: (() -> Unit) = when (action) {
            // 与电视上的播放暂停键同一个动作 (按播放意图切, 缓冲中按一下是"暂停")
            "toggle" -> { { vm.player.togglePlayWhenReady() } }
            // 网页的播放 / 暂停键按手机上看到的状态发明确的动作: 休眠中按下时 Ani 被叫回前台会自己续播, 再 toggle 就又停了
            "play" -> { { vm.player.play() } }
            "pause" -> { { vm.player.pause() } }
            "back" -> { { vm.player.skip(-SEEK_STEP_MILLIS) } }
            "forward" -> { { vm.player.skip(SEEK_STEP_MILLIS) } }
            // 手机音量条左边的喇叭 (见 setVolume)
            "mute" -> {
                {
                    systemAudio?.adjustStreamVolume(SystemAudioManager.STREAM_MUSIC, SystemAudioManager.ADJUST_TOGGLE_MUTE, 0)
                        ?: vm.player.features[AudioLevelController]?.let { it.setMute(!it.isMute.value) }
                }
            }
            else -> return false
        }
        uiScope.launch { run() }
        return true
    }

    /**
     * 系统媒体音量 (STREAM_MUSIC): 与 App 在 Android 上划屏调音量同一个 (`AndroidAudioManager`, 优先于播放器音量).
     * Android 上的播放器后端不提供播放器音量 (`AudioLevelController` 为空, 真机实测), 所以这是电视上唯一调得动的那个.
     * 输出设备音量固定 (`isVolumeFixed`, 如部分 HDMI 直通) 时调了没用, 当作没有, 退回播放器音量.
     */
    private val systemAudio: SystemAudioManager? by lazy {
        runCatching { KoinPlatform.getKoin().get<Context>().getSystemService(Context.AUDIO_SERVICE) as SystemAudioManager }
            .getOrNull()?.takeUnless { it.isVolumeFixed }
    }

    /**
     * 手机上拖音量条 (0~1): 系统媒体音量 (见 [systemAudio]), 按系统的档位取整 (Shield 是 15 档); 拖到非零顺带取消静音.
     * 电视遥控器的音量键走 HDMI-CEC 调的是电视机本身, 系统音量平时停在最大, 这里是在电视机音量之下再调小.
     * 系统音量是系统自己的设置, 会一直保持 (同在电视系统设置里调). 取不到系统音量时退回播放器音量 (不存盘).
     * @return 两种音量都取不到时 false
     */
    fun setVolume(fraction: Float): Boolean {
        val level = fraction.coerceIn(0f, 1f)
        systemAudio?.let { am ->
            val stream = SystemAudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            val index = (level * max).roundToInt().coerceIn(0, max)
            am.setStreamVolume(stream, index, 0)
            if (index > 0 && am.isStreamMute(stream)) am.adjustStreamVolume(stream, SystemAudioManager.ADJUST_UNMUTE, 0)
            return true
        }
        val controller = vm.player.features[AudioLevelController] ?: return false
        uiScope.launch {
            controller.setVolume(level * controller.maxVolume)
            if (level > 0f && controller.isMute.value) controller.setMute(false)
        }
        return true
    }

    /**
     * 手机上拖倍速条. 走 [EpisodeViewModel.setPlaybackSpeed] —— 与电视上播放器内调倍速同一条路, 由它落到
     * 播放器并按「记住播放倍速」决定要不要写回设置; 这边不直接碰播放器 (那是主线程的事, 见 PlaybackSpeedExtension).
     * @return 播放器不支持倍速时 false
     */
    fun setSpeed(speed: Float): Boolean {
        if (vm.player.features[PlaybackSpeed] == null) return false
        // 量化到与电视倍速条同一套档位: 手机的滑条已经按 speedStep 画了, 这里再兜一道 —— 别的客户端
        // (或者旧版网页) 发个 1.15x 过来, 电视自己的倍速条永远产生不了这个值, 显示会对不上.
        vm.setPlaybackSpeed(quantizeSliderValue(speed, TV_PLAYBACK_SPEED_RANGE))
        return true
    }

    /** 跳到 [positionMillis] (手机上拖进度条 / 输入时间点), 夹在 0 到片长之间; 同 [control] 在主线程执行. */
    fun seekTo(positionMillis: Long) {
        val duration = vm.player.mediaProperties.value?.durationMillis?.takeIf { it > 0 }
        val target = positionMillis.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        uiScope.launch { vm.player.seekTo(target) }
    }

    /** 下拉框里的一集: 集号 + 标题 (同电视选集侧边栏); 看过的前面打勾, 确定还没播出的标出来. */
    private fun episodeLabel(ep: EpisodePresentation): String = buildString {
        if (ep.collectionType == UnifiedCollectionType.DONE) append("✓ ")
        append(ep.sort)
        if (ep.title.isNotBlank()) append("  ").append(ep.title)
        if (ep.isKnownNotYetAired) append(tr("（未播出）"))
    }

    private fun sourceName(sourceId: String, sample: Media, sources: List<MediaSourceResultPresentation>): String =
        when {
            sample.kind == MediaSourceKind.LocalCache -> tr("本地缓存")
            else -> sources.firstOrNull { it.mediaSourceId == sourceId }?.info?.displayName ?: sourceId
        }

    private fun MediaSourceResultPresentation.stateLabel(): String = when {
        isWorking -> "loading"
        isCaptchaRequired -> "captcha"
        isRateLimited -> "limited"
        isFailedOrAbandoned -> "failed"
        else -> "done"
    }

    private companion object {
        /** 手机上「后退 / 前进」一次跳多少. */
        const val SEEK_STEP_MILLIS = 10_000L
    }
}

/**
 * 播放页在组合里时把自己登记为 [TvRemoteControl] 的当前播放器, 离开组合即注销.
 *
 * 播放页登记的是**前台**把手; 播放页不在时, 保留会话的后台把手 ([RegisterTvRemoteBackgroundPlayer]) 顶上.
 * 两者都在时前台优先 —— 前台才有播放控制.
 */
@Composable
fun RegisterTvRemotePlayer(vm: EpisodeViewModel, page: EpisodePageState, background: Boolean = false) {
    val scope = rememberCoroutineScope()
    val handle = remember(vm, background) { RemotePlayerHandle(vm, scope, background) }
    SideEffect { handle.page = page }
    val selectorState = page.mediaSelectorState
    LaunchedEffect(handle, selectorState) {
        // presentationFlow 是 WhileSubscribed 的: 电视上数据源弹窗没开时没人订阅, 这里订着让它保持最新
        selectorState.presentationFlow.collect { handle.presentation = it }
    }
    // 音轨 / 字幕轨的候选与选中缓存到把手上, HTTP 线程只读缓存 (candidates 是 Flow, 不在请求里阻塞地取)
    LaunchedEffect(handle) {
        val group = vm.player.subtitleTracks ?: return@LaunchedEffect
        combine(group.candidates, group.selected) { c, s -> RemoteTrackState(c, s) }
            .collect { handle.subtitleState = it }
    }
    LaunchedEffect(handle) {
        val group = vm.player.audioTracks ?: return@LaunchedEffect
        combine(group.candidates, group.selected) { c, s -> RemoteTrackState(c, s) }
            .collect { handle.audioState = it }
    }
    DisposableEffect(handle) {
        TvRemoteControl.registerPlayer(handle)
        onDispose { TvRemoteControl.unregisterPlayer(handle) }
    }
    // 手机上展开「播放信息」时才采集 (同电视浮层那份, 每秒读一次播放器); 快照经 snapshotFlow 交给把手, 不让播放页
    // 陪着重组. 手机收起 (几秒没有带 stats=1 的轮询) 就停
    val statsWanted by handle.statsWanted.collectAsState()
    if (statsWanted) {
        val statsState = rememberPlayerStatsState(vm.player)
        LaunchedEffect(handle, statsState) {
            snapshotFlow { statsState.value }.collect { handle.stats = it }
        }
        LaunchedEffect(handle) {
            while (!handle.statsIdle()) delay(STATS_CHECK_MILLIS)
            handle.statsWanted.value = false
            handle.stats = null
        }
    }
}

/** 手机多久没要播放信息就停止采集. */
private const val STATS_IDLE_MILLIS = 6_000L
private const val STATS_CHECK_MILLIS = 2_000L

/**
 * 保留播放会话在后台时 (播放页不在组合里) 由 TV 根组合调用: 把后台会话登记为 [TvRemoteControl] 的后台播放器,
 * 手机上照样能看候选、换源、改查询请求. 后台会话照常搜源解析 (Web 解析器由保留会话挂在应用根部), 只是被按住
 * 暂停, 所以新数据源静音加载, 回到播放器接着播. 播放页在前台时它自己的前台登记优先.
 */
@Composable
fun RegisterTvRemoteBackgroundPlayer(vm: EpisodeViewModel) {
    val page by vm.pageState.collectAsStateWithLifecycle()
    page?.let { RegisterTvRemotePlayer(vm, it, background = true) }
}

/**
 * 手机网页上的下拉筛选 (分辨率 / 字幕 / 字幕组) 与「显示被排除的」.
 *
 * **只筛手机上的列表, 不动电视上的偏好**: 电视选择器那排筛选是「偏好」, 会被记住并影响以后的自动选源;
 * 而且点选任何一个源都会把「数据源」偏好设成它 —— 手机列表若跟着偏好走, 点一次就只剩那一个源了.
 * 真正点选某个资源时仍然走 [MediaSelectorState.select], 偏好照电视上的规矩记住.
 */
internal class RemoteMediaFilter(
    val resolution: String?,
    val subtitle: String?,
    val alliance: String?,
    val showExcluded: Boolean,
    /** 网页上勾了「显示全部」的那个数据源 (只能是当时胶囊选中的那一个): 它的候选不按每源上限截断. */
    val fullSource: String? = null,
) {
    fun accepts(media: Media): Boolean {
        val p = media.properties
        return (resolution == null || p.resolution == resolution) &&
                (subtitle == null || subtitle in p.subtitleLanguageIds) &&
                (alliance == null || p.alliance == alliance)
    }

    companion object {
        val None = RemoteMediaFilter(null, null, null, showExcluded = false)
    }
}
