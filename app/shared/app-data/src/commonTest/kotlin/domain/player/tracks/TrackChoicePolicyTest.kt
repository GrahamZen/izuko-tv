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
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackChoicePolicyTest {
    private fun track(id: String, label: String?, code: String? = null, default: Boolean = false, forced: Boolean = false) =
        TrackCandidate(id, label, TrackLanguage.of(label, code), isDefault = default, isForced = forced)

    private val en = track("en", "English")
    private val sc = track("sc", "简体中文")
    private val tc = track("tc", "繁體中文")
    private val scJa = track("scja", "简日双语")

    private fun subtitle(vararg tracks: TrackCandidate, ui: String = "zh-CN", remembered: String? = null) =
        TrackChoicePolicy.pickSubtitle(tracks.toList(), ui, remembered)

    @Test
    fun `subtitles follow the interface language`() {
        assertEquals(TrackPick.Select("sc"), subtitle(en, tc, sc))
        assertEquals(TrackPick.Select("tc"), subtitle(en, sc, tc, ui = "zh-TW"))
        assertEquals(TrackPick.Select("tc"), subtitle(en, sc, tc, ui = "zh-HK"))
        assertEquals(TrackPick.Select("en"), subtitle(sc, en, ui = "en"))
        // 简体界面: 简体 > 简日双语 > 繁体
        assertEquals(TrackPick.Select("scja"), subtitle(en, tc, scJa))
        assertEquals(TrackPick.Select("sc"), subtitle(scJa, sc))
        assertEquals(TrackPick.Select("tc"), subtitle(en, tc))
        // 只标了语言码的
        assertEquals(TrackPick.Select("b"), subtitle(track("a", null, "eng"), track("b", null, "chi")))
    }

    @Test
    fun `forced subtitles come after full ones`() {
        val signs = track("signs", "简体中文 (特效)", forced = true)
        assertEquals(TrackPick.Select("tc"), subtitle(signs, tc))
        assertEquals(TrackPick.Select("signs"), subtitle(signs, en))
    }

    @Test
    fun `without the interface language the container default wins - otherwise keep`() {
        assertEquals(TrackPick.Select("b"), subtitle(track("a", "English"), track("b", "Español", "spa", default = true)))
        assertEquals(TrackPick.Keep, subtitle(track("a", null), track("b", null)))
        assertEquals(TrackPick.Keep, subtitle())
    }

    @Test
    fun `an external ass beats an embedded mp4 text track`() {
        // 工单 #43: mp4 里内嵌的中文字幕是网盘转码时从 ASS 转来的, 正文与注释同时出现时只剩注释; 外挂的文件名没写语言
        val mp4Zh = TrackCandidate("0:2-0", null, TrackLanguage.of(null, "zh"), subtitleKind = SubtitleKind.MP4_TEXT)
        val mp4Und = TrackCandidate("0:2-0", null, null, subtitleKind = SubtitleKind.MP4_TEXT)
        val ass = TrackCandidate("1:0-0", "外挂字幕", null, subtitleKind = SubtitleKind.EXTERNAL_ASS)
        assertEquals(TrackPick.Select("1:0-0"), subtitle(mp4Zh, ass))
        // 两条都认不出语言时播放器原本放第一条 (内嵌的)
        assertEquals(TrackPick.Select("1:0-0"), subtitle(mp4Und, ass))
        // 外挂的是同一语言或更合适的也换
        val assSc = TrackCandidate("1:0-0", "简体中文", TrackLanguage.of("简体中文", null), subtitleKind = SubtitleKind.EXTERNAL_ASS)
        assertEquals(TrackPick.Select("1:0-0"), subtitle(mp4Zh, assSc))
    }

    @Test
    fun `an external ass of another language does not replace the embedded mp4 text track`() {
        val mp4Sc = TrackCandidate("0:2-0", "简体中文", TrackLanguage.of("简体中文", null), subtitleKind = SubtitleKind.MP4_TEXT)
        val assJa = TrackCandidate("1:0-0", "日本語", TrackLanguage.of("日本語", "jpn"), subtitleKind = SubtitleKind.EXTERNAL_ASS)
        val assTc = TrackCandidate("2:0-0", "繁體中文", TrackLanguage.of("繁體中文", null), subtitleKind = SubtitleKind.EXTERNAL_ASS)
        assertEquals(TrackPick.Select("0:2-0"), subtitle(mp4Sc, assJa))
        assertEquals(TrackPick.Select("0:2-0"), subtitle(mp4Sc, assTc))
        // 别的内嵌字幕 (mkv 里的 ASS / SRT) 照语言规则, 不受影响
        assertEquals(TrackPick.Select("sc"), subtitle(sc, assJa))
        // 用户这部番手动选过内嵌那条的照旧
        assertEquals(TrackPick.Select("0:2-0"), subtitle(mp4Sc, assTc, remembered = "zh-Hans#简体中文"))
    }

    @Test
    fun `remembered choice goes by language not position`() {
        assertEquals(TrackPick.Select("tc"), subtitle(sc, tc, remembered = "zh-Hant"))
        assertEquals(TrackPick.Off, subtitle(sc, tc, remembered = TrackChoicePolicy.OFF))
        // 记的是简日双语, 这一集只有简体: 退到同一语言同一字体
        assertEquals(TrackPick.Select("sc"), subtitle(tc, sc, remembered = "zh-Hans+ja", ui = "zh-TW"))
        // 记的语言这一集没有: 按界面语言
        assertEquals(TrackPick.Select("sc"), subtitle(en, sc, remembered = "ja"))
        assertEquals(TrackPick.Select("b"), subtitle(track("a", "Signs"), track("b", "Dialogue"), remembered = "label:Dialogue"))
    }

    @Test
    fun `among several tracks of the remembered language the same named one wins`() {
        // 合集里两个字幕组的简体, 上一集手动挑了第二组
        val groupA = track("a", "简体中文 字幕组A", "chi")
        val groupB = track("b", "简体中文 字幕组B", "chi")
        val remembered = TrackChoicePolicy.rememberValue(groupB)
        assertEquals("zh-Hans#简体中文 字幕组B", remembered)
        assertEquals(TrackPick.Select("b"), subtitle(groupA, groupB, remembered = remembered))
        // 这一集没有同名的: 按语言, 同档里靠前的
        assertEquals(TrackPick.Select("a"), subtitle(groupA, track("c", "简体中文 字幕组C", "chi"), remembered = remembered))
        // 只记了语言的 (以前记下的)
        assertEquals(TrackPick.Select("a"), subtitle(groupA, groupB, remembered = "zh-Hans"))
        // 挑的是强制字幕也照选
        val signs = track("signs", "简体中文 特效", "chi", forced = true)
        assertEquals(TrackPick.Select("signs"), subtitle(groupA, signs, remembered = TrackChoicePolicy.rememberValue(signs)))
        // 音轨开流前的语言偏好只看语言
        assertEquals(listOf("ja"), TrackChoicePolicy.preferredAudioLanguages("ja#日语 FLAC"))
    }

    @Test
    fun `audio prefers japanese then the container default`() {
        val zhDefault = track("zh", "国语", default = true)
        val ja = track("ja", null, "jpn")
        assertEquals(TrackPick.Select("ja"), TrackChoicePolicy.pickAudio(listOf(zhDefault, ja), null))
        assertEquals(TrackPick.Select("zh"), TrackChoicePolicy.pickAudio(listOf(zhDefault, ja), "zh"))
        assertEquals(TrackPick.Select("b"), TrackChoicePolicy.pickAudio(listOf(track("a", null), track("b", null, default = true)), null))
        assertEquals(TrackPick.Keep, TrackChoicePolicy.pickAudio(listOf(track("a", null), track("b", null)), null))
        assertEquals(TrackPick.Keep, TrackChoicePolicy.pickAudio(listOf(zhDefault), null))
        assertEquals(listOf("ja"), TrackChoicePolicy.preferredAudioLanguages(null))
        assertEquals(listOf("zh"), TrackChoicePolicy.preferredAudioLanguages("zh"))
        assertEquals(listOf("ja"), TrackChoicePolicy.preferredAudioLanguages("label:Commentary"))
    }

    @Test
    fun `audio the device cannot play is skipped`() {
        // 蓝光原盘: 日语 TrueHD (标了默认, 盒子没有解码器) + 日语 AC3 + 英语
        val trueHd = TrackCandidate("jaHd", "日语", TrackLanguage.of("日语", "jpn"), isDefault = true, isPlayable = false)
        val ac3 = track("ja", "日语", "jpn")
        val enHd = TrackCandidate("en", "英语", TrackLanguage.of("英语", "eng"), isPlayable = false)
        assertEquals(TrackPick.Select("ja"), TrackChoicePolicy.pickAudio(listOf(trueHd, ac3, enHd), null))
        assertEquals(TrackPick.Select("ja"), TrackChoicePolicy.pickAudio(listOf(trueHd, ac3, enHd), "en"))
        // 全都放不了: 照常选
        assertEquals(TrackPick.Select("jaHd"), TrackChoicePolicy.pickAudio(listOf(trueHd, enHd), null))
    }

    @Test
    fun `chooser remembers manual switches and saves them`() {
        val saved = mutableListOf<SubjectTrackChoice>()
        val chooser = SubjectTrackChooser("zh-CN") { saved += it }
        assertEquals(TrackPick.Select("sc"), chooser.chooseSubtitle(listOf(tc, sc)))

        chooser.onManualSubtitle(tc)
        assertEquals(TrackPick.Select("tc"), chooser.chooseSubtitle(listOf(sc, tc)))
        chooser.onManualSubtitle(null)
        assertEquals(TrackPick.Off, chooser.chooseSubtitle(listOf(sc, tc)))
        // 认不出也没起名的记不住: 清掉旧的
        chooser.onManualSubtitle(track("x", null))
        assertEquals(TrackPick.Select("sc"), chooser.chooseSubtitle(listOf(tc, sc)))

        chooser.onManualAudio(track("zh", "国语"))
        chooser.onManualAudio(null)
        assertEquals(
            listOf(
                // 语言后面接着轨道名 (同一语言有好几条时认它)
                SubjectTrackChoice(subtitle = "zh-Hant#繁體中文"),
                SubjectTrackChoice(subtitle = "off"),
                SubjectTrackChoice(subtitle = null),
                SubjectTrackChoice(audio = "zh#国语"),
                SubjectTrackChoice(),
            ),
            saved,
        )
    }
}
