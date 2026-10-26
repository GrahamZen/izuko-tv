/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import me.him188.ani.app.domain.settings.SettingAdjustment
import me.him188.ani.app.domain.settings.SettingAdjustmentChoice
import me.him188.ani.app.domain.settings.SettingAdjustmentQuestion
import me.him188.ani.app.domain.settings.SettingAdjustments
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.player_video_enhancement_auto_off
import me.him188.ani.app.ui.lang.settings_adjust_skip_origin_confirm
import me.him188.ani.app.ui.lang.settings_adjust_skip_origin_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_auto_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_later
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_ech
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_mirrors
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_origin
import me.him188.ani.app.ui.lang.tv_source_search_paused_for_stutter
import me.him188.ani.app.ui.settings.SettingChoiceDialog
import me.him188.ani.app.ui.settings.SettingChoiceDialogAction
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * 应用自己改用户设置时的告知与询问 (见 [SettingAdjustments]): 告知弹一条提示, 询问弹 [SettingChoiceDialog].
 * 改动可能发生在任何一页 (或后台), 所以挂在 TV 根组合上. 每种情形的文案都在这里.
 *
 * 询问等应用在前台时才弹, 弹之前再判一次还要不要问 ([SettingAdjustmentQuestion.stillNeeded]): 后台起的进程可能被系统断网,
 * 那时判出来的「连不上」等用户回来时多半已经不成立.
 */
@Composable
fun TvSettingAdjustmentHost() {
    val adjustments = remember { GlobalKoin.get<SettingAdjustments>() }
    val toaster = LocalToaster.current
    LaunchedEffect(adjustments, toaster) {
        adjustments.notices.collect { adjustment ->
            noticeText(adjustment)?.let { toaster.toast(getString(it)) }
        }
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val inFront = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val pending by adjustments.question.collectAsState()
    var shown by remember { mutableStateOf<SettingAdjustmentQuestion?>(null) }
    LaunchedEffect(pending, inFront) {
        val question = pending
        if (question == null) {
            shown = null
            return@LaunchedEffect
        }
        if (!inFront || shown === question) return@LaunchedEffect
        if (noticeText(question.adjustment) == null && question.stillNeeded()) shown = question else adjustments.drop(question)
    }
    shown?.let { question ->
        SettingAdjustmentDialog(question) { choice ->
            shown = null
            adjustments.answer(question, choice)
        }
    }
}

/** 告知用的文案; 只问不告知的情形为 `null` (告知的情形不弹询问). */
private fun noticeText(adjustment: SettingAdjustment): StringResource? = when (adjustment) {
    SettingAdjustment.VideoEnhancementTooSlow -> Lang.player_video_enhancement_auto_off
    SettingAdjustment.FullSearchStutter -> Lang.tv_source_search_paused_for_stutter
    is SettingAdjustment.SkipBangumiOrigin -> null
}

/** 询问的弹窗; [onAnswer] 的参数 `null` = 「暂不」. */
@Composable
private fun SettingAdjustmentDialog(
    question: SettingAdjustmentQuestion,
    onAnswer: (SettingAdjustmentChoice?) -> Unit,
) {
    when (val adjustment = question.adjustment) {
        is SettingAdjustment.SkipBangumiOrigin -> {
            val confirm = stringResource(Lang.settings_adjust_skip_origin_confirm)
            SettingChoiceDialog(
                title = stringResource(Lang.settings_network_bangumi_mirror_auto_title),
                message = stringResource(Lang.settings_adjust_skip_origin_message, stringResource(routeName(adjustment.workingRoute))),
                actions = question.choices.map { choice -> SettingChoiceDialogAction(confirm) { onAnswer(choice) } },
                cancel = stringResource(Lang.settings_network_bangumi_mirror_switch_later),
                onDismissRequest = { onAnswer(null) },
            )
        }

        // 只告知的情形不弹询问 (宿主里直接放掉)
        SettingAdjustment.VideoEnhancementTooSlow, SettingAdjustment.FullSearchStutter -> Unit
    }
}

private fun routeName(kind: BangumiRouteKind): StringResource = when (kind) {
    BangumiRouteKind.ORIGIN -> Lang.settings_network_bangumi_route_origin
    BangumiRouteKind.CUSTOM -> Lang.settings_network_bangumi_custom
    BangumiRouteKind.ECH -> Lang.settings_network_bangumi_route_ech
    BangumiRouteKind.MIRRORS -> Lang.settings_network_bangumi_route_mirrors
}
