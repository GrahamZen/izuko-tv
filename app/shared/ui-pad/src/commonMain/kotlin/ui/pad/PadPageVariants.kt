/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import me.him188.ani.app.ui.exploration.ExplorationPageVariant
import me.him188.ani.app.ui.exploration.LocalExplorationPageVariant
import me.him188.ani.app.ui.exploration.schedule.LocalSchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.SchedulePageVariant
import me.him188.ani.app.ui.exploration.search.LocalSearchPageVariant
import me.him188.ani.app.ui.exploration.search.SearchPageVariant
import me.him188.ani.app.ui.pad.collection.PadCollectionPage
import me.him188.ani.app.ui.pad.exploration.PadExplorationPage
import me.him188.ani.app.ui.pad.schedule.PadSchedulePage
import me.him188.ani.app.ui.pad.search.PadSearchPage
import me.him188.ani.app.ui.settings.LocalSettingsScreenVariant
import me.him188.ani.app.ui.settings.SettingsScreenVariant
import me.him188.ani.app.ui.settings.tv.TouchSettingsPage
import me.him188.ani.app.ui.settings.tv.TvSettingsPlatform
import me.him188.ani.app.ui.subject.collection.CollectionPageVariant
import me.him188.ani.app.ui.subject.collection.LocalCollectionPageVariant

/**
 * 把平板触屏页面 (探索 / 追番 / 搜索 / 新番时间表 / 设置) 装进各共享页的变体插槽. iOS 与 Android phone 变体的入口各调一次,
 * 两边是同一套页面; 别的页面照旧用共享的默认布局.
 */
@Composable
fun InstallPadPageVariants(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalExplorationPageVariant provides PadExplorationPageVariant,
        LocalCollectionPageVariant provides PadCollectionPageVariant,
        LocalSearchPageVariant provides PadSearchPageVariant,
        LocalSchedulePageVariant provides PadSchedulePageVariant,
        LocalSettingsScreenVariant provides PadSettingsScreenVariant,
        content = content,
    )
}

private val PadExplorationPageVariant = ExplorationPageVariant { state, modifier ->
    PadExplorationPage(state, modifier)
}

private val PadCollectionPageVariant = CollectionPageVariant { state, modifier ->
    PadCollectionPage(state, modifier)
}

private val PadSearchPageVariant = SearchPageVariant { state, onIntent, suggestionsPager, modifier ->
    PadSearchPage(state, onIntent, suggestionsPager, modifier)
}

private val PadSchedulePageVariant = SchedulePageVariant { presentation, onRetry, modifier ->
    PadSchedulePage(presentation, onRetry, modifier)
}

/** 设置页: 触屏版 (分类与设置项同电视版); 电视独有的项 (控制台、首次引导等) 平板上没有. */
private val PadSettingsScreenVariant = SettingsScreenVariant { vm, initialTab, legacy, licenses, modifier ->
    TouchSettingsPage(vm, initialTab, legacy, licenses, TvSettingsPlatform.None, modifier)
}
