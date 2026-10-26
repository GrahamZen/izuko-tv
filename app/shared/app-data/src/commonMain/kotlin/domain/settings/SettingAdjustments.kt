/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 应用自己调整用户设置的各种情形. 界面按它取文案 (电视: ui-tv 的 `TvSettingAdjustmentHost`), [key] 用于日志与「这次运行不再问」.
 */
sealed interface SettingAdjustment {
    val key: String

    /** 告知: 画质增强开着而设备算不过来 (画面每秒只出一两帧), 已改回关闭. */
    data object VideoEnhancementTooSlow : SettingAdjustment {
        override val key: String get() = "video-enhancement-too-slow"
    }

    /** 告知: 播放时搜索把画面拖卡了, 「完整搜索」已改成只手动, 这一集也停了搜索. */
    data object FullSearchStutter : SettingAdjustment {
        override val key: String get() = "full-search-stutter"
    }

    /** 询问: 连不上 Bangumi 官方, 经 [workingRoute] 连上了; 要不要以后跳过官方. */
    data class SkipBangumiOrigin(val workingRoute: BangumiRouteKind) : SettingAdjustment {
        override val key: String get() = "skip-bangumi-origin"
    }
}

/**
 * 询问里的一种改法 (「暂不」不算在内, 总是有). 界面按 ([SettingAdjustment], [id]) 取按钮文案; 用户选了才执行 [apply].
 */
class SettingAdjustmentChoice(
    val id: String,
    val apply: suspend () -> Unit,
)

/**
 * 一个等用户回答的询问 (见 [SettingAdjustments.ask]).
 *
 * @property stillNeeded 弹出来之前再判一次还要不要问 (界面在场时才判, 例如网络这时已经恢复, 就不必问了)
 */
class SettingAdjustmentQuestion internal constructor(
    val adjustment: SettingAdjustment,
    val choices: List<SettingAdjustmentChoice>,
    val stillNeeded: suspend () -> Boolean,
)

/**
 * 应用自己改用户设置的统一出口. 检测到某个设置在这台设备 / 这个网络上不合适时经这里, 不悄悄改:
 *
 * - [notify]: 已经改了 —— 当场不改就坏 (例如播放中画面卡住), 改完告知一声 (电视上是一条提示).
 * - [ask]: 要改, 先问 —— 不影响眼前、又关系到账号或网络的; 用户选了才改. 选了「暂不」的这次运行不再问,
 *   同一件事在等回答时再报也不重复问.
 *
 * 每次都写一行日志 (`[SettingAdjust]`): 查「设置为什么变了」看这里. 检测与当场要做的事 (清掉效果、暂停搜索) 留在各自原处.
 */
class SettingAdjustments(private val scope: CoroutineScope) {
    private val logger = logger<SettingAdjustments>()

    private val _notices = MutableSharedFlow<SettingAdjustment>(extraBufferCapacity = 8)

    /** 已经改了、要告知的. 界面收到弹一次提示. */
    val notices: SharedFlow<SettingAdjustment> = _notices.asSharedFlow()

    private val queue = MutableStateFlow<List<SettingAdjustmentQuestion>>(emptyList())
    private val _question = MutableStateFlow<SettingAdjustmentQuestion?>(null)

    /** 现在该问的那一个 (一次问一件, 其余排队). */
    val question: StateFlow<SettingAdjustmentQuestion?> = _question.asStateFlow()

    /** 这次运行里选过「暂不」的 [SettingAdjustment.key]. */
    private val declined = MutableStateFlow(emptySet<String>())

    /** 调用方已经改好了设置, 告知用户. */
    fun notify(adjustment: SettingAdjustment) {
        logger.info { "[SettingAdjust] ${adjustment.key}: changed, notifying" }
        _notices.tryEmit(adjustment)
    }

    /** 想改, 先问用户. 见本类说明. */
    fun ask(
        adjustment: SettingAdjustment,
        choices: List<SettingAdjustmentChoice>,
        stillNeeded: suspend () -> Boolean = { true },
    ) {
        if (adjustment.key in declined.value) return
        var added = false
        queue.update { pending ->
            if (pending.any { it.adjustment.key == adjustment.key }) pending
            else (pending + SettingAdjustmentQuestion(adjustment, choices, stillNeeded)).also { added = true }
        }
        if (!added) return
        logger.info { "[SettingAdjust] ${adjustment.key}: asking ($adjustment)" }
        showNext()
    }

    /**
     * 界面问完了: [choice] = 用户选的改法, `null` = 「暂不」(这次运行不再问这件事).
     */
    fun answer(question: SettingAdjustmentQuestion, choice: SettingAdjustmentChoice?) {
        val key = question.adjustment.key
        if (choice == null) {
            declined.update { it + key }
            logger.info { "[SettingAdjust] $key: declined" }
        } else {
            logger.info { "[SettingAdjust] $key: user chose ${choice.id}" }
            scope.launch { choice.apply() }
        }
        remove(question)
    }

    /** 界面弹之前判出不必问了 ([SettingAdjustmentQuestion.stillNeeded]): 不算「暂不」, 以后再报还会问. */
    fun drop(question: SettingAdjustmentQuestion) {
        logger.info { "[SettingAdjust] ${question.adjustment.key}: no longer needed, not asking" }
        remove(question)
    }

    private fun remove(question: SettingAdjustmentQuestion) {
        queue.update { pending -> pending.filterNot { it === question } }
        showNext()
    }

    private fun showNext() {
        _question.value = queue.value.firstOrNull()
    }
}
