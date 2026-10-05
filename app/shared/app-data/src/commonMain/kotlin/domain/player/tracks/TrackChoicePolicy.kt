/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.tracks

/**
 * 播放器里的一条字幕轨或音轨, 带上选轨要看的信息.
 *
 * @param id 播放器给这条轨道的 id (同一个媒体内唯一)
 * @param label 轨道名; 没起名时为 null
 * @param language 认出的语言 (见 [TrackLanguage.of]); 认不出为 null
 * @param isDefault 容器里标了「默认」
 * @param isForced 容器里标了「强制」(多为只翻招牌、歌词的特效字幕)
 */
class TrackCandidate(
    val id: String,
    val label: String?,
    val language: TrackLanguage?,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
) {
    override fun toString(): String = "TrackCandidate($id, $label, ${language?.key}, default=$isDefault, forced=$isForced)"
}

/** 选哪条轨道. */
sealed interface TrackPick {
    /** 没意见: 维持播放器自己的选择 (字幕是第一条, 音轨按容器的默认标记). */
    data object Keep : TrackPick

    /** 不显示字幕. */
    data object Off : TrackPick

    data class Select(val id: String) : TrackPick
}

/**
 * 打开一个媒体时选哪条字幕与音轨:
 * - 这部番用户手动换过的, 按语言 (不按轨道序号) 沿用 (见 [rememberValue]);
 * - 字幕选界面语言的: 简体界面 简体 > 简日双语 > 没分简繁的中文 > 繁体, 繁体界面反过来, 英文界面选英文;
 * - 音轨选日语原声, 没有日语的选容器里标了默认的;
 * - 都认不出时不动 (播放器原来的选择).
 *
 * 同一档里不是强制字幕的、标了默认的、排在前面的优先. 强制字幕 (只翻招牌) 排在所有完整字幕后面.
 */
object TrackChoicePolicy {
    /** 记住「不显示字幕」. */
    const val OFF = "off"
    private const val LABEL_PREFIX = "label:"

    /**
     * 用户手动选了 [candidate] (null = 关掉字幕) 时记下的值: 认得出语言记语言 ([TrackLanguage.key]), 认不出记轨道名;
     * 两样都没有时返回 null (记不住).
     */
    fun rememberValue(candidate: TrackCandidate?): String? = when {
        candidate == null -> OFF
        candidate.language != null -> candidate.language.key
        !candidate.label.isNullOrBlank() -> LABEL_PREFIX + candidate.label
        else -> null
    }

    /**
     * @param uiLanguage 界面语言 (BCP 47, 如 `zh-CN`, `zh-TW`, `en`)
     * @param remembered 这部番记下的选择 (见 [rememberValue])
     */
    fun pickSubtitle(candidates: List<TrackCandidate>, uiLanguage: String, remembered: String?): TrackPick {
        if (candidates.isEmpty()) return TrackPick.Keep
        if (remembered == OFF) return TrackPick.Off
        remembered?.let { matchRemembered(candidates, it) }?.let { return TrackPick.Select(it.id) }
        val order = subtitleOrder(uiLanguage)
        best(candidates) { c -> c.language?.key?.let { order.indexOf(it) }?.takeIf { it >= 0 } }?.let { return TrackPick.Select(it.id) }
        return fallback(candidates)
    }

    /** @param remembered 这部番记下的选择 (见 [rememberValue]) */
    fun pickAudio(candidates: List<TrackCandidate>, remembered: String?): TrackPick {
        if (candidates.size < 2) return TrackPick.Keep
        remembered?.let { matchRemembered(candidates, it) }?.let { return TrackPick.Select(it.id) }
        best(candidates) { if (it.language?.base == "ja") 0 else null }?.let { return TrackPick.Select(it.id) }
        return fallback(candidates)
    }

    /**
     * 打开媒体前就交给播放器的音轨语言偏好 (多数时候让播放器第一次就选对, 不用开播后再切): 记下的语言, 否则日语.
     */
    fun preferredAudioLanguages(remembered: String?): List<String> {
        val language = remembered?.takeIf { it != OFF && !it.startsWith(LABEL_PREFIX) }?.let { TrackLanguage.parseKey(it) }
        return listOf(language?.base ?: "ja")
    }

    /** 字幕按界面语言的先后; 不在表里的语言不按界面语言选. */
    internal fun subtitleOrder(uiLanguage: String): List<String> {
        val ui = TrackLanguage.fromCode(uiLanguage)
        return when {
            ui == null -> emptyList()
            ui.base == "zh" && ui.script == TrackLanguage.HANT ->
                listOf("zh-Hant", "zh-Hant+ja", "zh", "zh+ja", "zh-Hans", "zh-Hans+ja")

            ui.base == "zh" -> listOf("zh-Hans", "zh-Hans+ja", "zh", "zh+ja", "zh-Hant", "zh-Hant+ja")
            else -> listOf(ui.key)
        }
    }

    private fun matchRemembered(candidates: List<TrackCandidate>, remembered: String): TrackCandidate? {
        if (remembered.startsWith(LABEL_PREFIX)) {
            val label = remembered.removePrefix(LABEL_PREFIX)
            return candidates.firstOrNull { it.label == label }
        }
        val want = TrackLanguage.parseKey(remembered) ?: return null
        // 一模一样的语言; 没有时退到同一语言同一字体 (记的是简日双语, 这一集只有简体)
        return best(candidates) { if (it.language == want) 0 else null }
            ?: best(candidates) { c -> c.language?.takeIf { it.base == want.base && it.script == want.script }?.let { 0 } }
    }

    /** 没有合适语言时: 容器里标了默认的完整字幕 / 音轨, 否则不动. */
    private fun fallback(candidates: List<TrackCandidate>): TrackPick =
        candidates.firstOrNull { it.isDefault && !it.isForced }?.let { TrackPick.Select(it.id) } ?: TrackPick.Keep

    /** [rank] 给出档次 (越小越好, null = 不要) 的候选里挑最好的一条. */
    private inline fun best(candidates: List<TrackCandidate>, rank: (TrackCandidate) -> Int?): TrackCandidate? {
        var best: TrackCandidate? = null
        var bestKey: Triple<Boolean, Int, Boolean>? = null
        for (candidate in candidates) {
            val r = rank(candidate) ?: continue
            // 完整字幕在强制字幕前; 同档里标了默认的在前; 再同就按原来的顺序
            val key = Triple(candidate.isForced, r, !candidate.isDefault)
            if (bestKey == null || compareKeys(key, bestKey) < 0) {
                best = candidate
                bestKey = key
            }
        }
        return best
    }

    private fun compareKeys(a: Triple<Boolean, Int, Boolean>, b: Triple<Boolean, Int, Boolean>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
}
