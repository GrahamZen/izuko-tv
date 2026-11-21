/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.domain.feedback.FeedbackNotices
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.ui.notice.TvFeedbackTab
import me.him188.ani.app.ui.notice.TvNotices
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 触屏端 (平板 / iPad) 应用内的反馈: 弹窗开着哪一屏 ([screen], null = 关着). 通知中心「我的反馈」(写反馈、补充说明, 见 TvFeedbackTabTouch)
 * 与现场录制条的「结束」从这里打开,
 * 画在界面根部的 [TouchFeedbackHost] 里 —— 现场录制时弹窗关掉, 去别的页面复现, 录制条要跨页面一直在.
 * 电视上写反馈用 Web 控制台, 不装这些.
 */
internal object TouchFeedback {
    sealed interface Screen {
        /** 写新反馈 (草稿 [FeedbackTickets.NEW]). */
        data object New : Screen

        /** 一条反馈的会话 (故障工单可以接着补充). */
        data class Conversation(val kind: FeedbackKind, val issue: Int) : Screen
    }

    private val current = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = current.asStateFlow()

    /** 弹窗外面的协程 (存写到一半的文字、查处理结果): 弹窗关掉也要做完. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun open(screen: Screen) {
        if (current.value != screen) touchFeedbackLogger.info { "[TouchFeedback] 打开 $screen" }
        current.value = screen
    }

    /**
     * 弹窗的「←」: 关掉弹窗回到通知中心「我的反馈」(反馈列表与来往记录在那里, 同电视, 见 TvFeedbackTab); [record] 给了时焦点停在那张单上.
     */
    fun back(record: FeedbackRecord? = null) {
        close()
        TvNotices.open(focusKey = record?.let(FeedbackNotices::keyOf), tab = TvFeedbackTab.ID)
    }

    fun close() {
        if (current.value != null) touchFeedbackLogger.info { "[TouchFeedback] 关闭弹窗" }
        current.value = null
    }

    /** 现场录制录给的那一条: 新反馈回「写反馈」, 补充回那张单的会话. */
    fun screenOf(target: Int): Screen =
        if (target == FeedbackTickets.NEW) Screen.New else Screen.Conversation(FeedbackKind.TICKET, target)

    fun key(r: FeedbackRecord): String = "${r.kind.name}-${r.issue}"
}

/** 触屏反馈弹窗的日志, 行首 `[TouchFeedback]` (网络那层的结果另由 FeedbackTickets 记). */
internal val touchFeedbackLogger = logger("TouchFeedback")

/** 界面根部 (触屏): 反馈弹窗与现场录制条. 装在页面之上, 切页面时录制条一直在. */
@Composable
fun TouchFeedbackHost() {
    val screen by TouchFeedback.screen.collectAsState()
    screen?.let { TouchFeedbackDialog(it) }
    TouchFeedbackRecordingBar()
}
