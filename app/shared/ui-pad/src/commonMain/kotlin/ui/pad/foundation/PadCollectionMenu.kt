/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.subject.SetSubjectCollectionTypeOrDeleteUseCase
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

/**
 * 长按卡片弹出的收藏菜单 (复用详情页收藏按钮的 [EditCollectionTypeDropDown]). 当前收藏状态按收藏库里各类的条目 id 现查
 * (同电视版时间表), 改了收藏菜单下次打开就是新的.
 */
@Stable
class PadCollectionMenu internal constructor(
    private val types: State<Map<Int, UnifiedCollectionType>>,
    private val onSet: (subjectId: Int, type: UnifiedCollectionType) -> Unit,
) {
    @Composable
    fun Dropdown(subjectId: Int, expanded: Boolean, onDismissRequest: () -> Unit) {
        EditCollectionTypeDropDown(
            currentType = types.value[subjectId] ?: UnifiedCollectionType.NOT_COLLECTED,
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            onClick = { action -> onSet(subjectId, action.type) },
        )
    }
}

@Composable
fun rememberPadCollectionMenu(): PadCollectionMenu {
    val collectionRepo = remember { GlobalKoin.get<SubjectCollectionRepository>() }
    val setCollectionType = remember { GlobalKoin.get<SetSubjectCollectionTypeOrDeleteUseCase>() }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val types = remember(collectionRepo) { collectionTypesFlow(collectionRepo) }
        .collectAsStateWithLifecycle(emptyMap())
    return remember(types, setCollectionType, scope, toaster) {
        PadCollectionMenu(types) { subjectId, type ->
            scope.launch {
                try {
                    setCollectionType(subjectId, type)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toaster.showLoadError(LoadError.fromException(e))
                }
            }
        }
    }
}

/** 条目 id -> 收藏类型. 没收藏的不在表里. */
private fun collectionTypesFlow(collectionRepo: SubjectCollectionRepository): Flow<Map<Int, UnifiedCollectionType>> = flow {
    val flows = PAD_MENU_COLLECTION_TYPES.map { type ->
        collectionRepo.getSubjectIdsByCollectionType(listOf(type)).map { ids -> type to ids }
    }
    emitAll(
        combine(flows) { pairs ->
            buildMap {
                for ((type, ids) in pairs) for (id in ids) put(id, type)
            }
        },
    )
}

private val PAD_MENU_COLLECTION_TYPES = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DROPPED,
)
