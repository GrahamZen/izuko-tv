/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.tracks

import me.him188.ani.app.data.models.preference.SubjectTrackChoice
import kotlin.concurrent.Volatile

/**
 * 播放器打开一个媒体、轨道出来时问它选哪条字幕与音轨; 之后用户手动换了也告诉它. 播放器在主线程上同步调用.
 */
interface PlayerTrackChooser {
    fun chooseSubtitle(candidates: List<TrackCandidate>): TrackPick
    fun chooseAudio(candidates: List<TrackCandidate>): TrackPick

    /** 打开媒体之前问: 音轨先按这些语言选, 多数时候第一次就选对, 不用开播后再切 (切音轨要重新缓冲) */
    fun preferredAudioLanguages(): List<String>

    /** 用户手动换了字幕; [candidate] 为 null = 关掉字幕 */
    fun onManualSubtitle(candidate: TrackCandidate?)

    /** 用户手动换了音轨; [candidate] 为 null = 交回播放器自动选 */
    fun onManualAudio(candidate: TrackCandidate?)
}

/** 能按 [PlayerTrackChooser] 选轨的播放器 (Android 的 ExoPlayer 后端). */
interface TrackChooserHost {
    /** 只在主线程读写; null = 不管, 维持播放器自己的选择. */
    var trackChooser: PlayerTrackChooser?
}

/**
 * 一部番的选轨: 按界面语言与这部番记下的选择挑 (见 [TrackChoicePolicy]); 用户手动换了就记下来, 经 [save] 存起来.
 *
 * @param uiLanguage 界面语言 (BCP 47, 如 `zh-CN`)
 */
class SubjectTrackChooser(
    private val uiLanguage: String,
    private val save: (SubjectTrackChoice) -> Unit,
) : PlayerTrackChooser {
    /** 这部番记下的选择; 读出存着的那份后由调用方设置 */
    @Volatile
    var choice: SubjectTrackChoice = SubjectTrackChoice()

    override fun chooseSubtitle(candidates: List<TrackCandidate>): TrackPick =
        TrackChoicePolicy.pickSubtitle(candidates, uiLanguage, choice.subtitle)

    override fun chooseAudio(candidates: List<TrackCandidate>): TrackPick =
        TrackChoicePolicy.pickAudio(candidates, choice.audio)

    override fun preferredAudioLanguages(): List<String> = TrackChoicePolicy.preferredAudioLanguages(choice.audio)

    override fun onManualSubtitle(candidate: TrackCandidate?) {
        // 认不出语言、也没起名的轨道记不住: 清掉旧的, 免得下一集按旧选择选到别的
        update(choice.copy(subtitle = TrackChoicePolicy.rememberValue(candidate)))
    }

    override fun onManualAudio(candidate: TrackCandidate?) {
        update(choice.copy(audio = candidate?.let { TrackChoicePolicy.rememberValue(it) }))
    }

    private fun update(newChoice: SubjectTrackChoice) {
        if (newChoice == choice) return
        choice = newChoice
        save(newChoice)
    }
}
