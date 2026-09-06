/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.person.CharacterSubjectInfo
import me.him188.ani.app.data.models.person.PersonCastInfo
import me.him188.ani.app.data.models.person.PersonWorkInfo
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.nameCn
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.rememberTvImageZoomState
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.person_details_casts
import me.him188.ani.app.ui.lang.person_details_character_subjects
import me.him188.ani.app.ui.lang.person_details_works
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_details_voice_actor_label
import me.him188.ani.app.ui.subject.person.PeopleDetailsNavigation
import me.him188.ani.app.ui.subject.person.PeoplePreviewTarget
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem
import me.him188.ani.app.ui.subject.person.rememberPeopleClickHandler
import org.jetbrains.compose.resources.stringResource

/*
 * 「查看全部」在电视与平板上的弹窗 (居中弹窗形态; 手机仍是底部抽屉, 见 ViewAllSheet): 人物是圆头像画廊, 作品是海报画廊
 * (网格见 ViewAllGalleries). 点人物先关本弹窗再弹预览 (不叠第二层弹窗, 遥控器上两层弹窗的焦点归属没有好解法), 点作品进条目详情;
 * 遥控器上长按人物放大看照片 (大图画在本窗口里), 触屏上没有 (放大层只认返回键).
 */

/**
 * 条目角色的「查看全部」: 名字下面写「配音 某某」, 主角压一枚「主角」标签.
 *
 * @param subtitle 标题下面那行说明 (所属条目名); null = 不写.
 * @param onBeforeOpenPreview 点人物时在弹预览**之前**调用, 默认就是关掉本弹窗; 电视播放器额外记一笔「预览是从这儿开的」(关掉预览后焦点
 *   该还给胶囊而不是面板条目).
 */
@Composable
fun CharactersGalleryDialog(
    allCharacters: LazyPagingItems<RelatedCharacterInfo>,
    totalCount: Int?,
    onDismissRequest: () -> Unit,
    subtitle: String? = null,
    onBeforeOpenPreview: () -> Unit = onDismissRequest,
) {
    val onClickCharacter = rememberPeopleClickHandler()
    val voiceActor = stringResource(Lang.subject_details_voice_actor_label)
    val snapshot = allCharacters.itemSnapshotList
    val items = remember(snapshot, voiceActor) {
        snapshot.map { info ->
            info?.let {
                PeopleRowItem(
                    imageUrl = it.character.imageMedium,
                    name = it.character.displayName,
                    subtitle = it.character.actors.firstOrNull()?.let { actor -> "$voiceActor ${actor.displayName}" }.orEmpty(),
                    badge = it.role.nameCn.takeIf { _ -> it.isMainCharacter() },
                )
            }
        }
    }
    PeopleGalleryDialog(
        title = stringResource(Lang.subject_details_characters),
        count = totalCount,
        subtitle = subtitle,
        items = items,
        onDismissRequest = onDismissRequest,
        onClick = { index ->
            allCharacters.peek(index)?.let {
                onBeforeOpenPreview()
                onClickCharacter(PeoplePreviewTarget.Character(it.character.id))
            }
        },
        imageAt = { index -> allCharacters.peek(index)?.character?.imageLarge },
        onBind = { index -> if (index < allCharacters.itemCount) allCharacters[index] },
    )
}

/** 条目制作人员的「查看全部」: 名字下面写职位. 参数同 [CharactersGalleryDialog]. */
@Composable
fun StaffGalleryDialog(
    allStaff: LazyPagingItems<RelatedPersonInfo>,
    totalCount: Int?,
    onDismissRequest: () -> Unit,
    subtitle: String? = null,
    onBeforeOpenPreview: () -> Unit = onDismissRequest,
) {
    val onClickPerson = rememberPeopleClickHandler()
    val snapshot = allStaff.itemSnapshotList
    val items = remember(snapshot) {
        snapshot.map { info ->
            info?.let { PeopleRowItem(it.personInfo.imageMedium, it.personInfo.displayName, it.position.nameCn.orEmpty()) }
        }
    }
    PeopleGalleryDialog(
        title = stringResource(Lang.subject_details_staff),
        count = totalCount,
        subtitle = subtitle,
        items = items,
        onDismissRequest = onDismissRequest,
        onClick = { index ->
            allStaff.peek(index)?.let {
                onBeforeOpenPreview()
                onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id))
            }
        },
        imageAt = { index -> allStaff.peek(index)?.personInfo?.imageLarge },
        onBind = { index -> if (index < allStaff.itemCount) allStaff[index] },
    )
}

/** 人物 (声优) 出演角色的「查看全部」: 角色头像, 名字下面写作品名; 点角色走 [navigation] (预览里换成那个角色). */
@Composable
internal fun PersonCastsGalleryDialog(
    casts: LazyPagingItems<PersonCastInfo>,
    navigation: PeopleDetailsNavigation,
    onDismissRequest: () -> Unit,
) {
    val snapshot = casts.itemSnapshotList
    val items = remember(snapshot) {
        snapshot.map { cast -> cast?.let { PeopleRowItem(it.character.imageMedium, it.character.displayName, it.subject.displayName) } }
    }
    PeopleGalleryDialog(
        title = stringResource(Lang.person_details_casts),
        items = items,
        onDismissRequest = onDismissRequest,
        onClick = { index ->
            casts.peek(index)?.let {
                onDismissRequest()
                navigation.onClickCharacter(it.character.id)
            }
        },
        imageAt = { index -> casts.peek(index)?.character?.imageLarge },
        onBind = { index -> if (index < casts.itemCount) casts[index] },
    )
}

/** 人物参与作品的「查看全部」: 海报, 番名下面写职位. */
@Composable
internal fun PersonWorksGalleryDialog(
    works: LazyPagingItems<PersonWorkInfo>,
    navigation: PeopleDetailsNavigation,
    onDismissRequest: () -> Unit,
) {
    val snapshot = works.itemSnapshotList
    val items = remember(snapshot) {
        snapshot.map { work ->
            work?.let {
                PosterRowItem(
                    it.subject.imageLarge,
                    it.subject.displayName,
                    it.positions.mapNotNull { p -> p.nameCn }.distinct().joinToString("、"),
                    subjectId = it.subject.subjectId,
                )
            }
        }
    }
    PosterGalleryDialog(
        title = stringResource(Lang.person_details_works),
        items = items,
        onDismissRequest = onDismissRequest,
        onClick = { index ->
            works.peek(index)?.let {
                onDismissRequest()
                navigation.onClickSubject(it.subject)
            }
        },
        onBind = { index -> if (index < works.itemCount) works[index] },
    )
}

/** 角色出演作品的「查看全部」: 海报, 番名下面写主角 / 配角. */
@Composable
internal fun CharacterSubjectsGalleryDialog(
    subjects: LazyPagingItems<CharacterSubjectInfo>,
    navigation: PeopleDetailsNavigation,
    onDismissRequest: () -> Unit,
) {
    val snapshot = subjects.itemSnapshotList
    val items = remember(snapshot) {
        snapshot.map { item ->
            item?.let { PosterRowItem(it.subject.imageLarge, it.subject.displayName, it.role.nameCn, subjectId = it.subject.subjectId) }
        }
    }
    PosterGalleryDialog(
        title = stringResource(Lang.person_details_character_subjects),
        items = items,
        onDismissRequest = onDismissRequest,
        onClick = { index ->
            subjects.peek(index)?.let {
                onDismissRequest()
                navigation.onClickSubject(it.subject)
            }
        },
        onBind = { index -> if (index < subjects.itemCount) subjects[index] },
    )
}

/**
 * 人物画廊弹窗: [ViewAllPanelDialog] 里一张 [ViewAllGalleries.PeopleGallery]. [imageAt] 给第几格的大图 (遥控器长按放大; null = 那一格没有).
 */
@Composable
private fun PeopleGalleryDialog(
    title: String,
    items: List<PeopleRowItem?>,
    onDismissRequest: () -> Unit,
    onClick: (index: Int) -> Unit,
    imageAt: (index: Int) -> String?,
    onBind: (index: Int) -> Unit,
    count: Int? = null,
    subtitle: String? = null,
) {
    val imageZoom = rememberTvImageZoomState()
    val zoom = imageZoom.takeUnless { LocalTvTouchInputEnabled.current }
    ViewAllPanelDialog(title, onDismissRequest, count = count, subtitle = subtitle, imageZoom = zoom) { initialFocus ->
        LocalViewAllGalleries.current.PeopleGallery(
            items = items,
            onClick = onClick,
            onLongPress = zoom?.let { z -> { index -> imageAt(index)?.let { z.open(it) } } },
            onBind = onBind,
            initialFocus = initialFocus,
            modifier = galleryModifier(),
        )
    }
}

/** 作品画廊弹窗: [ViewAllPanelDialog] 里一张 [ViewAllGalleries.PosterGallery]. */
@Composable
private fun PosterGalleryDialog(
    title: String,
    items: List<PosterRowItem?>,
    onDismissRequest: () -> Unit,
    onClick: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
) {
    ViewAllPanelDialog(title, onDismissRequest) { initialFocus ->
        LocalViewAllGalleries.current.PosterGallery(
            items = items,
            onClick = onClick,
            onBind = onBind,
            initialFocus = initialFocus,
            modifier = galleryModifier(),
        )
    }
}

/** 网格在面板里: 内容多时占满余下的高、在网格里滚, 少时随内容. */
private fun ColumnScope.galleryModifier(): Modifier = Modifier.weight(1f, fill = false)
