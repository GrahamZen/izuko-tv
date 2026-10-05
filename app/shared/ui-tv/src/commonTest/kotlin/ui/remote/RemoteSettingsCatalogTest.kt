/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_alliance
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RemoteSettingsCatalogTest {
    /**
     * 清单里每一项的字段都要在数据类里找得到、输入方式与字段类型对得上: 设置的字段改名或换类型时在这里失败,
     * 而不是网页上那一项悄悄失效.
     */
    @Test
    fun `every catalog item resolves to a field it can edit`() {
        // 调试区登记的项与正式清单同样要对得上, key 也不能与正式清单重名
        val all = RemoteSettingsCatalog.items + RemoteSettingsCatalog.debugItems
        val keys = all.map { it.key }
        assertEquals(keys.distinct(), keys, "key 不能重复")
        for (spec in all) {
            spec.resolvedEditor
        }
    }

    @Test
    fun `writing one field keeps the others`() {
        @Suppress("UNCHECKED_CAST")
        val spec = RemoteSettingsCatalog.items.single { it.key == "excludedAlliances" } as RemoteSettingSpec<MediaPreference>
        val before = MediaPreference.PlatformDefault.copy(alliancePatterns = listOf("桜都"), showWithoutSubtitle = true)

        val after = spec.write(before, JsonArray(listOf(JsonPrimitive("喵萌"), JsonPrimitive("北宇治"))))

        assertEquals(before.copy(excludedAlliancePatterns = listOf("喵萌", "北宇治")), after)
        assertEquals(JsonArray(listOf(JsonPrimitive("喵萌"), JsonPrimitive("北宇治"))), spec.read(after))
    }

    @Test
    fun `trackers are stored one per line and checked`() {
        val spec = RemoteSettingsCatalog.items.single { it.key == "extraTrackers" }

        val ok = RemoteGenericSettings.parse(spec, " udp://a.example:1337/announce \n\nhttps://b.example/announce\n")
        assertIs<RemoteGenericSettings.Parsed.Value>(ok)
        assertEquals(JsonPrimitive("udp://a.example:1337/announce\nhttps://b.example/announce"), ok.element)

        val bad = RemoteGenericSettings.parse(spec, "udp://a.example:1337/announce\nnot-a-tracker")
        assertIs<RemoteGenericSettings.Parsed.Error>(bad)
        assertContains(bad.message, "not-a-tracker")
    }

    @Test
    fun `lines are split by line and optionally by commas`() {
        assertEquals(listOf("a", "b,c", "d"), RemoteGenericSettings.splitLines(" a \n\nb,c\n d \n a", splitCommas = false))
        assertEquals(listOf("a", "b", "c", "d"), RemoteGenericSettings.splitLines("a\nb，c, ,d", splitCommas = true))
        assertEquals(emptyList<String>(), RemoteGenericSettings.splitLines(" \n ,", splitCommas = true))
    }

    @Test
    fun `editor is inferred from the field type`() {
        assertIs<RemoteSettingEditor.Toggle>(spec(MediaSelectorSettings.serializer(), "hideSingleEpisodeForCompleted").resolvedEditor)
        assertIs<RemoteSettingEditor.Choice>(spec(MediaSelectorSettings.serializer(), "preferKind").resolvedEditor)
        assertIs<RemoteSettingEditor.Number>(spec(VideoScaffoldConfig.serializer(), "upNextTipLeadSeconds").resolvedEditor)
        assertIs<RemoteSettingEditor.Text>(spec(MediaPreference.serializer(), "alliance").resolvedEditor)
        assertIs<RemoteSettingEditor.Lines>(spec(MediaPreference.serializer(), "alliancePatterns").resolvedEditor)
    }

    @Test
    fun `mismatched editor or missing field fails`() {
        assertFailsWith<IllegalArgumentException> {
            spec(MediaPreference.serializer(), "noSuchField").resolvedEditor
        }
        assertFailsWith<IllegalArgumentException> {
            spec(MediaPreference.serializer(), "alliancePatterns", RemoteSettingEditor.Toggle).resolvedEditor
        }
    }

    private fun <T> spec(serializer: KSerializer<T>, field: String, editor: RemoteSettingEditor? = null) = RemoteSettingSpec(
        key = field,
        settings = { error("not used") },
        serializer = serializer,
        fieldName = field,
        title = Lang.settings_media_alliance,
        editor = editor,
    )
}
