/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.datasources.api.Media

/** 画面的动态范围 (HDR 一类). 排在前面的更好: 同时写着几种时取最前面的. */
enum class MediaDynamicRange {
    DolbyVision,
    Hdr10Plus,
    Hdr10,
    Hlg,

    /** 只写了「HDR」, 不知道是哪一种. */
    Hdr,
}

/** 值得标出来的音轨格式 (多声道 / 无损). 排在前面的更好. */
enum class MediaSurroundAudio {
    DolbyAtmos,
    DtsX,
    TrueHd,
    DtsHd,
}

/**
 * 一条资源画面与声音上值得标出来的特性 (选源时给人看的标签). 两项都可能不知道 (null).
 */
data class MediaAvFeatures(
    val dynamicRange: MediaDynamicRange? = null,
    val audio: MediaSurroundAudio? = null,
) {
    val isEmpty: Boolean get() = dynamicRange == null && audio == null

    /** 两份信息合起来: 每项取更好的那个. */
    operator fun plus(other: MediaAvFeatures): MediaAvFeatures = MediaAvFeatures(
        dynamicRange = listOfNotNull(dynamicRange, other.dynamicRange).minOrNull(),
        audio = listOfNotNull(audio, other.audio).minOrNull(),
    )

    companion object {
        val NONE = MediaAvFeatures()

        /**
         * 从标题 (种子标题、文件名) 里认: 压制组一般把 `HDR10` `DV` `DoVi` `Dolby Vision` `TrueHD` `Atmos` `DTS-HD` 这类写在标题里.
         * 只认独立的词 (前后不是字母数字), `HDRip` (片源类型) 与 `DVD` 不算.
         */
        fun fromTitle(title: String): MediaAvFeatures {
            if (title.isEmpty()) return NONE
            val dynamicRange = DYNAMIC_RANGE_PATTERNS.firstOrNull { (_, regex) -> regex.containsMatchIn(title) }?.first
            val audio = AUDIO_PATTERNS.firstOrNull { (_, regex) -> regex.containsMatchIn(title) }?.first
            return if (dynamicRange == null && audio == null) NONE else MediaAvFeatures(dynamicRange, audio)
        }

        /** [media] 的特性: 标题里认出的, 加上读文件头读到的 (见 [ProbedMediaAvFeatures]). */
        fun of(media: Media): MediaAvFeatures = fromTitle(media.originalTitle) + (ProbedMediaAvFeatures.of(media.mediaId) ?: NONE)

        private fun word(pattern: String) = Regex("""(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

        // 按好坏排, 取第一个认出的
        private val DYNAMIC_RANGE_PATTERNS = listOf(
            MediaDynamicRange.DolbyVision to word("""DV|DoVi|Dolby[ ._-]?Vision|DVHDR|杜比视界|杜比視界"""),
            MediaDynamicRange.Hdr10Plus to Regex("""(?<![A-Za-z0-9])HDR10(?:\+|Plus|P(?![A-Za-z0-9]))""", RegexOption.IGNORE_CASE),
            MediaDynamicRange.Hdr10 to word("""HDR10"""),
            MediaDynamicRange.Hlg to word("""HLG"""),
            MediaDynamicRange.Hdr to word("""HDR"""),
        )

        private val AUDIO_PATTERNS = listOf(
            MediaSurroundAudio.DolbyAtmos to Regex("""(?<![A-Za-z0-9])Atmos(?![A-Za-z0-9])|全景声|全景聲""", RegexOption.IGNORE_CASE),
            MediaSurroundAudio.DtsX to word("""DTS[:._ -]?X"""),
            MediaSurroundAudio.TrueHd to word("""True[ ._-]?HD"""),
            MediaSurroundAudio.DtsHd to Regex("""(?<![A-Za-z0-9])DTS[ ._-]?HD""", RegexOption.IGNORE_CASE),
        )
    }
}

/**
 * 读文件头读到的特性 (网盘读视频头时一并认出的, 见 DriveVideoInfoProbe), 按资源 id 记下, 给选源界面拿. 同 DriveVideoBitrates.
 */
object ProbedMediaAvFeatures {
    private val lock = SynchronizedObject()
    private val features = LinkedHashMap<String, MediaAvFeatures>()

    /** 记下资源 [mediaId] 读到的 [value] (空的不记). */
    fun record(mediaId: String, value: MediaAvFeatures) {
        if (value.isEmpty) return
        synchronized(lock) {
            features.remove(mediaId)
            features[mediaId] = value
            while (features.size > MAX_ENTRIES) features.remove(features.keys.first())
        }
    }

    fun of(mediaId: String): MediaAvFeatures? = synchronized(lock) { features[mediaId] }

    /** 最多记多少条, 旧的先丢. */
    private const val MAX_ENTRIES = 5000
}
