/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.nameCn
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.subject.details.sections.CharactersGalleryDialog
import me.him188.ani.app.ui.subject.details.sections.SectionHeader
import me.him188.ani.app.ui.subject.details.sections.StaffGalleryDialog
import me.him188.ani.app.ui.subject.details.sections.monogramInitials
import me.him188.ani.app.ui.subject.person.PeoplePreviewTarget
import me.him188.ani.app.ui.subject.person.rememberPeopleClickHandler
import org.jetbrains.compose.resources.stringResource

/**
 * 详情页人物页里一排演职人员的数据状态: 有人 / 确认没有 (总数为 0) / 还在路上. 两排 (角色 / 制作人员) 各算各的, 数据到了就原地
 * 填进那一排 (见 [TvDetailsCharactersRow]).
 */
internal enum class TvPeopleRowState { LOADING, CONTENT, EMPTY }

/** [itemCount] = 露出来的那几个已经到了多少, [totalCount] = 总数 (null = 还不知道). */
internal fun tvPeopleRowState(itemCount: Int, totalCount: Int?): TvPeopleRowState = when {
    itemCount > 0 -> TvPeopleRowState.CONTENT
    totalCount == 0 -> TvPeopleRowState.EMPTY
    else -> TvPeopleRowState.LOADING
}

/**
 * 详情页的角色行: 标题 + 原生圆头像横滑行 ([TvNativeMonogramStrip]). 数据没到时是一排占位格, 照样接得住焦点, 到了原地换 ——
 * 焦点所在的节点不拆, 不会被系统改派到页面别处. 还有没露出来的人时行末补一格「查看全部」(开全量弹窗).
 * 短按人物预览, 长按放大看图 ([imageZoom], 大图层与按键拦截挂在页面根上). 上下键交给页面 (行不接).
 *
 * @param horizontalPadding 标题两侧与行两端的留白 (行本身全宽出血, 滑过行首的格从屏幕左缘出屏).
 * @param restoreFocus 返回本页时进页落点回这一行 (见 [TvNativeMonogramStrip] 的同名参数).
 */
@Composable
internal fun TvDetailsCharactersRow(
    exposed: LazyPagingItems<RelatedCharacterInfo>,
    all: LazyPagingItems<RelatedCharacterInfo>,
    totalCount: Int?,
    imageZoom: TvImageZoomState,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    restoreFocus: Boolean = false,
    /** 「查看全部」弹窗标题下面那行 (所属条目名). */
    subjectName: String? = null,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickCharacter = rememberPeopleClickHandler()
    val count = exposed.itemCount
    val snapshot = exposed.itemSnapshotList
    val items = remember(snapshot, totalCount) {
        if (count == 0) {
            null
        } else {
            snapshot.map { info ->
                info?.let {
                    val name = it.character.displayName
                    TvNativeMonogram.Person(
                        imageUrl = it.character.imageMedium,
                        name = name,
                        subtitle = it.character.actors.firstOrNull()?.displayName ?: it.role.nameCn,
                        initials = monogramInitials(name),
                    )
                }
            } + tvPeopleViewAllCell(count, totalCount)
        }
    }
    TvDetailsPeopleRow(
        title = stringResource(Lang.subject_details_characters),
        items = items,
        onClick = { index ->
            if (index >= count) showAll = true
            else exposed.peek(index)?.let { onClickCharacter(PeoplePreviewTarget.Character(it.character.id)) }
        },
        onLongPress = { index -> exposed.peek(index)?.let { imageZoom.open(it.character.imageLarge) } },
        onBind = { index -> if (index < exposed.itemCount) exposed[index] },
        horizontalPadding = horizontalPadding,
        modifier = modifier,
        restoreFocus = restoreFocus,
    )
    if (showAll) {
        CharactersGalleryDialog(all, totalCount, onDismissRequest = { showAll = false }, subtitle = subjectName)
    }
}

/** 详情页的制作人员行: 同 [TvDetailsCharactersRow], 副标题是职位. */
@Composable
internal fun TvDetailsStaffRow(
    exposed: LazyPagingItems<RelatedPersonInfo>,
    all: LazyPagingItems<RelatedPersonInfo>,
    totalCount: Int?,
    imageZoom: TvImageZoomState,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    restoreFocus: Boolean = false,
    subjectName: String? = null,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickPerson = rememberPeopleClickHandler()
    val count = exposed.itemCount
    val snapshot = exposed.itemSnapshotList
    val items = remember(snapshot, totalCount) {
        if (count == 0) {
            null
        } else {
            snapshot.map { info ->
                info?.let {
                    val name = it.personInfo.displayName
                    TvNativeMonogram.Person(
                        imageUrl = it.personInfo.imageMedium,
                        name = name,
                        subtitle = it.position.nameCn ?: "",
                        initials = monogramInitials(name),
                    )
                }
            } + tvPeopleViewAllCell(count, totalCount)
        }
    }
    TvDetailsPeopleRow(
        title = stringResource(Lang.subject_details_staff),
        items = items,
        onClick = { index ->
            if (index >= count) showAll = true
            else exposed.peek(index)?.let { onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id)) }
        },
        onLongPress = { index -> exposed.peek(index)?.let { imageZoom.open(it.personInfo.imageLarge) } },
        onBind = { index -> if (index < exposed.itemCount) exposed[index] },
        horizontalPadding = horizontalPadding,
        modifier = modifier,
        restoreFocus = restoreFocus,
    )
    if (showAll) {
        StaffGalleryDialog(all, totalCount, onDismissRequest = { showAll = false }, subtitle = subjectName)
    }
}

/** 还有没露出来的人时行末那格「查看全部」(副标题写剩几个); 都露出来了就没有. */
private fun tvPeopleViewAllCell(count: Int, totalCount: Int?): List<TvNativeMonogram> =
    if ((totalCount ?: 0) > count) listOf(TvNativeMonogram.ViewAll(totalCount?.minus(count))) else emptyList()

/** 一排演职人员: 标题 (两侧留白) + 圆头像横滑行, 间距同 Compose 版的 CharactersSection. */
@Composable
private fun TvDetailsPeopleRow(
    title: String,
    items: List<TvNativeMonogram?>?,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    restoreFocus: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title, modifier = Modifier.padding(horizontal = horizontalPadding))
        TvDetailsPeopleStrip(
            items = items,
            onClick = onClick,
            onLongPress = onLongPress,
            onBind = onBind,
            horizontalPadding = horizontalPadding,
            restoreFocus = restoreFocus,
        )
    }
}
