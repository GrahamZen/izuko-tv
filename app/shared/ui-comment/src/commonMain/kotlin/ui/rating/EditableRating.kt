/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.rating

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.TestSelfRatingInfo
import me.him188.ani.app.data.models.subject.TestSubjectInfo
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.DismissDialogButton
import me.him188.ani.app.ui.foundation.widgets.aniDialogContainerColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.rating_requires_collection
import me.him188.ani.app.ui.lang.settings_mediasource_close
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.stringResource

/**
 * 可编辑评分的展示数据. 交互见 [EditableRatingActions], 逻辑见 [RatingEditController].
 */
@Immutable
data class EditableRatingUiState(
    val ratingInfo: RatingInfo,
    val selfRatingInfo: SelfRatingInfo,
    /** 是否允许点击进入编辑 (必须已收藏条目). */
    val enableEdit: Boolean,
    val showRatingDialog: Boolean = false,
    /** 未收藏时点击评分, 提示需要先收藏. */
    val showRatingRequiresCollectionDialog: Boolean = false,
    val isUpdating: Boolean = false,
    /**
     * 本次评分弹窗是**哪个入口**打开的 (调用方给的任意标记对象), 见 [isEditingFrom] 与 [EditableRatingActions.requestEditRating].
     *
     * 同一份评分状态会同时挂在好几个入口上 (详情页的评分组件、「查看全部」评论里的写评价…),
     * 它们都活着且都在观察 [showRatingDialog]. 不分辨来源的话, 弹窗关闭时每个入口都会去抢焦点.
     */
    val editRequestSource: Any? = null,
) {
    companion object {
        val Placeholder = EditableRatingUiState(
            ratingInfo = RatingInfo.Empty,
            selfRatingInfo = SelfRatingInfo.Empty,
            enableEdit = false,
        )
    }
}

/**
 * 评分弹窗当前是否由 [source] 这个入口打开的.
 *
 * 用于 `Modifier.restoreFocusAfter`: 只有打开它的那个入口才该在关闭后把焦点收回来.
 */
fun EditableRatingUiState.isEditingFrom(source: Any?): Boolean = showRatingDialog && editRequestSource === source

/**
 * 可编辑评分的交互. 通常由 ViewModel 或页面状态实现, 见 [RatingEditController].
 */
interface EditableRatingActions {
    fun requestEditRating()

    /**
     * 同 [requestEditRating], 并记下由哪个入口打开 ([source] 为调用方给的任意标记对象), 见 [EditableRatingUiState.editRequestSource].
     */
    fun requestEditRating(source: Any?) {
        requestEditRating()
    }
    fun cancelEditRating()
    fun submitRating(request: RateRequest)

    /**
     * 只修改分数, 保留已有的评价内容和可见性, 并等待完成. 供只能打分的界面 (例如 TV) 使用.
     *
     * @return 失败原因, 成功为 `null`.
     */
    suspend fun updateScore(score: Int): LoadError?
    fun dismissRatingRequiresCollectionDialog()

    companion object Noop : EditableRatingActions {
        override fun requestEditRating() {}
        override fun cancelEditRating() {}
        override fun submitRating(request: RateRequest) {}
        override suspend fun updateScore(score: Int): LoadError? = null
        override fun dismissRatingRequiresCollectionDialog() {}
    }
}

/**
 * 评分展示 + 点击进入编辑, 自带 [EditableRatingDialogsHost].
 */
@Composable
fun EditableRating(
    uiState: EditableRatingUiState,
    actions: EditableRatingActions,
    modifier: Modifier = Modifier,
) {
    EditableRatingDialogsHost(uiState, actions)
    Rating(
        rating = uiState.ratingInfo,
        selfRatingScore = uiState.selfRatingInfo.score,
        onClick = { actions.requestEditRating() },
        clickEnabled = uiState.enableEdit && !uiState.isUpdating,
        modifier = modifier,
    )
}

/**
 * 评分编辑对话框与 "需要先收藏" 提示. 页面里放一个即可.
 */
@Composable
fun EditableRatingDialogsHost(
    uiState: EditableRatingUiState,
    actions: EditableRatingActions,
) {
    if (uiState.showRatingRequiresCollectionDialog) {
        AniAlertDialog(
            { actions.dismissRatingRequiresCollectionDialog() },
            // 独立窗口: 遥控器全局键接回主窗口 (见 tvOverlayWindowKeys)
            modifier = Modifier.tvOverlayWindowKeys { actions.dismissRatingRequiresCollectionDialog() },
            text = { Text(stringResource(Lang.rating_requires_collection)) },
            // 纯提示, 唯一的按钮就是"关闭"
            confirmButton = {
                DismissDialogButton(stringResource(Lang.settings_mediasource_close)) {
                    actions.dismissRatingRequiresCollectionDialog()
                }
            },
            containerColor = aniDialogContainerColor(),
        )
    }

    if (uiState.showRatingDialog) {
        val selfRatingInfo = uiState.selfRatingInfo
        RatingEditorDialog(
            remember(selfRatingInfo) {
                RatingEditorState(
                    initialScore = selfRatingInfo.score,
                    initialComment = selfRatingInfo.comment ?: "",
                    initialIsPrivate = selfRatingInfo.isPrivate,
                )
            },
            onDismissRequest = { actions.cancelEditRating() },
            onRate = { actions.submitRating(it) },
            isLoading = uiState.isUpdating,
        )
    }
}

@TestOnly
val TestEditableRatingUiState
    get() = EditableRatingUiState(
        ratingInfo = TestSubjectInfo.ratingInfo,
        selfRatingInfo = TestSelfRatingInfo,
        enableEdit = true,
    )
