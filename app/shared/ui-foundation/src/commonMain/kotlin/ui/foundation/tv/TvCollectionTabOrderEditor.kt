/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 「自定义追番页标签顺序」页的插槽. 实现在 `ui-tv` (`TvCollectionTabOrderPage`), 由 TV 应用入口装上.
 *
 * 与 [TvPlayerChromeEditorVariant] 同一个套路: 那一页排的是遥控器形态追番页顶部那排分类标签,
 * 共享代码里连那排标签都不存在, 所以只开一个插槽 —— **为 null 就等于没有这个功能**, 设置页据此
 * 决定要不要摆出入口, 导航目的地据此决定画什么.
 */
fun interface TvCollectionTabOrderEditorVariant {
    @Composable
    fun Page(onNavigateBack: () -> Unit, modifier: Modifier)
}

val LocalTvCollectionTabOrderEditorVariant = staticCompositionLocalOf<TvCollectionTabOrderEditorVariant?> { null }
