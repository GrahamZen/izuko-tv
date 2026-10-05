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
                SubjectTrackChoice(subtitle = "zh-Hant"),
                SubjectTrackChoice(subtitle = "off"),
                SubjectTrackChoice(subtitle = null),
                SubjectTrackChoice(audio = "zh"),
                SubjectTrackChoice(),
            ),
            saved,
        )
    }
}
