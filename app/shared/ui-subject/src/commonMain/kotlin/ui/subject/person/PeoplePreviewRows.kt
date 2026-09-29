/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.person

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 人物 / 角色预览弹窗与整页 (中栏) 里横滑行的另一套实现: 电视应用入口提供原生版 (ui-tv 的 TvPeoplePreviewRows), 没提供时
 * (手机 / 桌面) 照旧用 Compose 的行.
 *
 * 两种行: 圆头像 ([PeopleRow], 声优、出演角色) 与海报 ([PosterRow], 参与作品、出演作品). 行只画卡片, 标题行与「查看全部」由调用方画.
 * 上下键行不接 (交给弹窗), 左右键两头吞掉.
 */
@Stable
interface PeoplePreviewRows {
    /**
     * @param items 一格一个人; 列表里的 null 是分页还没到的那一格.
     * @param onBind 第几格被绑定 (分页的访问提示).
     * @param contentPadding 行所在内容列的水平留白: 行往两侧出血到弹窗边上, 卡片从留白线排起、滑过的从弹窗边出屏.
     */
    @Composable
    fun PeopleRow(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        contentPadding: Dp,
        modifier: Modifier,
    )

    /** 海报行, 参数同 [PeopleRow]. */
    @Composable
    fun PosterRow(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        contentPadding: Dp,
        modifier: Modifier,
    )
}

/** 圆头像行的一格: 头像 (没有就写首字), 名字, 下面一行 (空串照样占一行). */
@Immutable
data class PeopleRowItem(
    val imageUrl: String?,
    val name: String,
    val subtitle: String,
)

/** 海报行的一张: 海报, 名字, 下面一行小字 (null = 名字占两行). */
@Immutable
data class PosterRowItem(
    val imageUrl: String?,
    val title: String,
    val subtitle: String?,
)

/** 见 [PeoplePreviewRows]; null = 用 Compose 的行. */
val LocalPeoplePreviewRows = staticCompositionLocalOf<PeoplePreviewRows?> { null }
