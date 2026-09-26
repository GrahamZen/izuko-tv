/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeSettingsTest {
    /**
     * 回归: [ThemeSettings.effectiveUiScale] 曾用 companion 里的 `val UI_SCALE_RANGE` 做 clamp,
     * 而 `Default` 在同一个 companion 里排在它前面 —— 类初始化时 range 还是 null, 构造 `Default`
     * 当场 NPE. `Default` 在 Koin 启动阶段就被触碰, 于是应用一启动就崩.
     */
    @Test
    fun `touching Default does not throw`() {
        assertEquals(1f, ThemeSettings.Default.effectiveUiScale)
    }

    @Test
    fun `ui scale is clamped into range`() {
        assertEquals(ThemeSettings.UI_SCALE_MAX, ThemeSettings(uiScale = 99f).effectiveUiScale)
        assertEquals(ThemeSettings.UI_SCALE_MIN, ThemeSettings(uiScale = 0f).effectiveUiScale)
        assertEquals(1.5f, ThemeSettings(uiScale = 1.5f).effectiveUiScale)
    }

    @Test
    fun `non-finite ui scale falls back to 1`() {
        assertEquals(1f, ThemeSettings(uiScale = Float.NaN).effectiveUiScale)
        assertEquals(1f, ThemeSettings(uiScale = Float.POSITIVE_INFINITY).effectiveUiScale)
    }

    /** 4K 面板误报 1080p 密度时需要的补偿正好是 2.0, 上界必须留有余量, 否则这类设备只能顶满档用. */
    @Test
    fun `range leaves headroom above the 2x correction`() {
        assertTrue(ThemeSettings.UI_SCALE_MAX > 2f)
        assertEquals(ThemeSettings.UI_SCALE_MIN..ThemeSettings.UI_SCALE_MAX, ThemeSettings.UI_SCALE_RANGE)
    }

    /**
     * 追番页那排分类标签的顺序: 页面里每一处都是 `tabOrder.indexOf(type)` / `tabOrder[i ± 1]`,
     * 全靠"解析结果永远是全集的一个排列"才不会越界. 这几条钉住这个前提.
     */
    @Test
    fun `tab order is always a permutation of the defaults`() {
        val defaults = COLLECTION_TABS
        // 用户排过的乱序
        assertEquals(defaults.size, resolveSavedOrder(defaults.reversed(), defaults).size)
        assertEquals(defaults.toSet(), resolveSavedOrder(defaults.reversed(), defaults).toSet())
        // 只存了一部分 (旧版本存的, 或者存坏了)
        val partial = resolveSavedOrder(listOf(UnifiedCollectionType.DONE), defaults)
        assertEquals(defaults.size, partial.size)
        assertEquals(defaults.toSet(), partial.toSet())
        // 重复项与不属于这一排的分类
        val dirty = resolveSavedOrder(
            listOf(
                UnifiedCollectionType.DONE, UnifiedCollectionType.DONE,
                UnifiedCollectionType.NOT_COLLECTED,
            ),
            defaults,
        )
        assertEquals(defaults.size, dirty.size)
        assertEquals(defaults.toSet(), dirty.toSet())
    }

    @Test
    fun `never sorted means the default order`() {
        assertEquals(emptyList(), ThemeSettings.Default.tvCollectionTabOrder)
        assertEquals(COLLECTION_TABS, resolveSavedOrder(ThemeSettings.Default.tvCollectionTabOrder, COLLECTION_TABS))
    }

    @Test
    fun `user order is kept as is`() {
        val mine = listOf(
            UnifiedCollectionType.DOING, UnifiedCollectionType.WISH,
            UnifiedCollectionType.ON_HOLD, UnifiedCollectionType.DONE, UnifiedCollectionType.DROPPED,
        )
        assertEquals(mine, resolveSavedOrder(mine, COLLECTION_TABS))
    }
}

/** 追番页标签行的默认顺序 (与 `TvCollectionPage` 的 `TV_COLLECTION_TABS` 一致; 那份在 ui-tv, 这里够不到). */
private val COLLECTION_TABS = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.DROPPED,
)
