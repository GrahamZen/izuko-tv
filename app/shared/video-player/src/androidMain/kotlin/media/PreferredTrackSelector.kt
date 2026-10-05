/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.player.tracks.PlayerTrackChooser
import me.him188.ani.app.domain.player.tracks.TrackCandidate
import me.him188.ani.app.domain.player.tracks.TrackLanguage
import me.him188.ani.app.domain.player.tracks.TrackPick
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup
import androidx.media3.common.TrackGroup as ExoTrackGroup

private val logger = logger("PreferredTrackSelector")

/**
 * 每打开一个媒体按 [chooser] 选字幕与音轨 (见 PreferredTracksExtension), 用户手动换了告诉它; 并把音轨的选择下发给 ExoPlayer.
 *
 * mediamp 0.5.0 的 ExoPlayer 后端只下发字幕: 它按 `subtitleTracks.selected` 选字幕轨, 轨道一出来先选第一条;
 * 音轨的 `selected` 只是个状态, 没人交给 ExoPlayer. 这里按它给 ExoPlayer 设音轨 override, 选「自动」(null) 时清掉.
 *
 * 新媒体 = 轨道组换了实例 (各集文件轨道结构相同时 id 也相同, 只能按实例分). 自动选完记下结果, 之后 `selected` 变成别的就是用户换的
 * (界面、控制台、挂上外挂字幕后选那一条都算). 监听 `selected` 用 [Dispatchers.Main] (不是 immediate): 换媒体时 mediamp 先把字幕选成
 * 第一条、这里再改成要的那条, 都在同一个回调里; 收集排到回调之后才跑, 只看得到最后那个值, 不会把中间那一下当成用户换的.
 *
 * 只在主线程用.
 */
@OptIn(UnstableApi::class)
internal class PreferredTrackSelector(
    private val exoPlayer: ExoPlayer,
    private val subtitleTracks: TrackGroup<SubtitleTrack>?,
    private val audioTracks: TrackGroup<AudioTrack>?,
    private val chooser: () -> PlayerTrackChooser?,
) : Player.Listener {
    /** mediamp 的一条轨道与它在 ExoPlayer 里的轨道组 id ([internalId]) */
    private class Entry<T>(val track: T, val id: String, val internalId: String)

    private var currentGroups: List<ExoTrackGroup> = emptyList()
    private var expectedSubtitle: SubtitleTrack? = null
    private var expectedAudio: AudioTrack? = null

    fun start(scope: CoroutineScope) {
        exoPlayer.addListener(this)
        subtitleTracks?.let { group ->
            scope.launch(Dispatchers.Main) {
                group.selected.collect { track ->
                    if (track == expectedSubtitle) return@collect
                    expectedSubtitle = track
                    if (!hasMedia()) return@collect
                    val candidate = track?.let { candidateOfSelected(it, subtitleEntries(), C.TRACK_TYPE_TEXT) }
                    logger.info { "Subtitle switched by user: $candidate" }
                    chooser()?.onManualSubtitle(candidate)
                }
            }
        }
        audioTracks?.let { group ->
            scope.launch(Dispatchers.Main) {
                group.selected.collect { track ->
                    if (track == expectedAudio) return@collect
                    expectedAudio = track
                    if (!hasMedia()) return@collect
                    applyAudio(track)
                    val candidate = track?.let { candidateOfSelected(it, audioEntries(), C.TRACK_TYPE_AUDIO) }
                    logger.info { "Audio switched by user: $candidate" }
                    chooser()?.onManualAudio(candidate)
                }
            }
        }
    }

    /**
     * 准备新媒体之前 (主线程): 清掉指向上一个媒体的音轨 override, 音轨先按偏好的语言选 —— 多数时候第一次就选对,
     * 不用开播后再切 (切音轨要重新缓冲).
     */
    fun onNewMedia() {
        syncExpected()
        val languages = chooser()?.preferredAudioLanguages().orEmpty()
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguages(*languages.toTypedArray())
            .build()
    }

    override fun onTracksChanged(tracks: Tracks) {
        logPlaying(tracks)
        val groups = tracks.groups.map { it.mediaTrackGroup }
        if (groups.size == currentGroups.size && groups.indices.all { groups[it] === currentGroups[it] }) return
        currentGroups = groups
        if (groups.isEmpty()) {
            // 媒体关了 / 正在换: mediamp 把候选清空、选中改成 null, 不是用户选的
            syncExpected()
            return
        }
        autoSelect()
    }

    /** 当前的选中不是用户选的 (换媒体时 mediamp 自己改的): 记成已知, 收集到时不当作用户换的. */
    private fun syncExpected() {
        expectedSubtitle = subtitleTracks?.selected?.value
        expectedAudio = audioTracks?.selected?.value
    }

    /** 有媒体、轨道列表也出来了; 没有时选中的变化都是播放器自己的 (关媒体、换媒体). */
    private fun hasMedia(): Boolean = currentGroups.isNotEmpty() &&
        (subtitleEntries().isNotEmpty() || audioEntries().isNotEmpty())

    private var playingTracks: String? = null

    /** ExoPlayer 实际选中的音轨与字幕轨变了时记一行, 对照界面上的选择 (界面上的选中只是 mediamp 的状态). */
    private fun logPlaying(tracks: Tracks) {
        fun describe(type: Int): String? = tracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { group ->
            val format = group.getTrackFormat((0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0)
            "${group.mediaTrackGroup.id} ${format.label ?: "-"} ${format.language ?: "-"}"
        }
        val playing = "audio=${describe(C.TRACK_TYPE_AUDIO)}, subtitle=${describe(C.TRACK_TYPE_TEXT)}"
        if (playing == playingTracks) return
        playingTracks = playing
        logger.info { "Playing tracks: $playing" }
    }

    private fun autoSelect() {
        val chooser = chooser()
        val subtitles = candidates(subtitleEntries(), C.TRACK_TYPE_TEXT)
        val audios = candidates(audioEntries(), C.TRACK_TYPE_AUDIO)
        val subtitlePick = chooser?.chooseSubtitle(subtitles.map { it.second }) ?: TrackPick.Keep
        val audioPick = chooser?.chooseAudio(audios.map { it.second }) ?: TrackPick.Keep

        // 先记下要选的再选: 选的同时 selected 就变了
        expectedSubtitle = when (subtitlePick) {
            TrackPick.Keep -> subtitleTracks?.selected?.value
            TrackPick.Off -> null
            is TrackPick.Select -> subtitles.firstOrNull { it.first.id == subtitlePick.id }?.first?.track
                ?: subtitleTracks?.selected?.value
        }
        subtitleTracks?.select(expectedSubtitle)

        // 音轨没意见时交回 ExoPlayer 自动选 (按打开前给的语言偏好与容器的默认标记), 别沿用上一集的序号
        expectedAudio = (audioPick as? TrackPick.Select)?.let { pick -> audios.firstOrNull { it.first.id == pick.id }?.first?.track }
        audioTracks?.select(expectedAudio)
        applyAudio(expectedAudio)

        if (chooser != null && (subtitles.isNotEmpty() || audios.size > 1)) {
            logger.info {
                "Tracks of new media: subtitles=${subtitles.map { it.second }} -> $subtitlePick, " +
                    "audio=${audios.map { it.second }} -> $audioPick"
            }
        }
    }

    /** 给 ExoPlayer 设音轨 override; null = 清掉, 由它自动选. */
    private fun applyAudio(track: AudioTrack?) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (track != null) {
            val all = audioEntries()
            all.firstOrNull { it.track == track }?.let { formatOf(it, all, C.TRACK_TYPE_AUDIO) }?.let { (group, index) ->
                builder.setOverrideForType(TrackSelectionOverride(group, index))
            }
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    // mediamp 的候选是 MutableStateFlow (接口上只给 Flow); 它在自己的 onTracksChanged 里先于本类更新
    @Suppress("UNCHECKED_CAST")
    private fun subtitleEntries(): List<Entry<SubtitleTrack>> =
        (subtitleTracks?.candidates as? StateFlow<List<SubtitleTrack>>)?.value.orEmpty()
            .map { Entry(it, it.id, it.internalId) }

    @Suppress("UNCHECKED_CAST")
    private fun audioEntries(): List<Entry<AudioTrack>> =
        (audioTracks?.candidates as? StateFlow<List<AudioTrack>>)?.value.orEmpty()
            .map { Entry(it, it.id, it.internalId) }

    private fun <T> candidates(entries: List<Entry<T>>, type: Int): List<Pair<Entry<T>, TrackCandidate>> =
        entries.mapNotNull { entry -> candidateOf(entry, entries, type)?.let { entry to it } }

    private fun <T> candidateOfSelected(track: T, all: List<Entry<T>>, type: Int): TrackCandidate? =
        all.firstOrNull { it.track == track }?.let { candidateOf(it, all, type) }

    private fun <T> candidateOf(entry: Entry<T>, all: List<Entry<T>>, type: Int): TrackCandidate? {
        val (group, index) = formatOf(entry, all, type) ?: return null
        val format = group.getFormat(index)
        return TrackCandidate(
            id = entry.id,
            label = format.label?.takeIf { it.isNotBlank() },
            language = TrackLanguage.of(format.label, format.language),
            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
        )
    }

    /** mediamp 的一条轨道在 ExoPlayer 里对应的轨道组与组内序号: 同一组有几条时按出现的先后对. */
    private fun <T> formatOf(entry: Entry<T>, all: List<Entry<T>>, type: Int): Pair<ExoTrackGroup, Int>? {
        val group = exoPlayer.currentTracks.groups.firstOrNull { it.type == type && it.mediaTrackGroup.id == entry.internalId }
            ?: return null
        val index = all.filter { it.internalId == entry.internalId }.indexOf(entry)
        if (index !in 0 until group.length) return null
        return group.mediaTrackGroup to index
    }
}
