/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_update_popup_auto_update
import me.him188.ani.app.ui.lang.settings_update_popup_feedback_group_hint
import me.him188.ani.app.ui.lang.settings_update_popup_new_version
import me.him188.ani.app.ui.lang.settings_update_popup_see_details
import me.him188.ani.app.ui.lang.tv_notice_update_queued
import me.him188.ani.app.ui.lang.tv_notice_update_started
import me.him188.ani.app.ui.update.NewVersion
import me.him188.ani.app.ui.update.NewVersionDetails
import me.him188.ani.app.ui.update.UpdatePromptState
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Instant

/**
 * 有新版本 (右下角那张卡检查到、还没开始下载的那一版, [UpdatePromptState.newVersion]): 一条, 时间是这一版的发布时间.
 * 键带版本号与这次启动的时刻: 每次进应用都算新的 (弹一次气泡、头像亮红点), 看过后这次运行里不再弹, 没更新就下次进应用再提醒 (同系统设置上的更新角标: 装上之前一直提醒).
 * 开始下载后不列 (进度、安装、重试在右下角的下载卡上), 下载失败又列. 标题「新版本」接版本号, 列表里摘要写第一项改动; 右栏写主要改动
 * (一项一个小标题与一句说明)、要加群的提示; 两个操作「自动更新」(开始下载, 弹一句提示; 排在前面, 进右栏焦点先落它) 与「查看详情」(更新内容弹窗).
 * 控制台上去「设置 → 维护」里的应用更新.
 */
internal object TvUpdateNotice : TvNoticeKind {
    override val id: String = "app.update"
    override val category: TvNoticeCategory = TvNoticeCategory.Update

    override fun source(): Flow<List<Notice>> = UpdatePromptState.newVersion.map { version ->
        listOfNotNull(
            version?.let {
                Notice(kind = id, key = "$id:${it.name}:$launchedAt", atMillis = publishedMillis(it), args = mapOf(ARG_VERSION to it.name))
            },
        )
    }

    override suspend fun title(notice: Notice): String? =
        notice.args[ARG_VERSION]?.let { getStringOffMain(Lang.settings_update_popup_new_version) + " " + it }

    /** 第一项主要改动的名字 */
    override suspend fun summary(notice: Notice): String? = versionOf(notice)?.majorChanges?.firstOrNull()?.let { parseNoticeText(it).title }

    /** 说明取不到时 (这一版已经不是此刻检查到的) 的去处: 更新内容 */
    override fun destination(notice: Notice): TvNoticeDestination? = versionOf(notice)?.let(::details)

    override suspend fun detail(notice: Notice): TvNoticeDetail? {
        val version = versionOf(notice) ?: return null
        return TvNoticeDetail(
            body = version.majorChanges.map(::changeParagraph) + listOfNotNull(
                if (version.hasFeedbackGroup) TvNoticeParagraph(getStringOffMain(Lang.settings_update_popup_feedback_group_hint)) else null,
            ),
            actions = listOf(
                TvNoticeAction(
                    getStringOffMain(Lang.settings_update_popup_auto_update),
                    TvNoticeDestination.Run { toaster ->
                        val now = UpdatePromptState.notifierActive.value
                        UpdatePromptState.requestDownload(version.name)
                        toaster.toast(getStringOffMain(if (now) Lang.tv_notice_update_started else Lang.tv_notice_update_queued))
                    },
                ),
                TvNoticeAction(getStringOffMain(Lang.settings_update_popup_see_details), details(version)),
            ),
        )
    }

    /** 一项改动: 「名字：说明」写成小标题与一句说明, 没有冒号的整句写; 一行写不下的截断, 全文在「查看详情」里 */
    private fun changeParagraph(change: String): TvNoticeParagraph {
        val parsed = parseNoticeText(change)
        return TvNoticeParagraph(
            text = parsed.plainDetail ?: parsed.title,
            title = parsed.title.takeIf { parsed.plainDetail != null },
            bullet = true,
            maxLines = CHANGE_MAX_LINES,
        )
    }

    override fun consoleHref(notice: Notice): String = "#settings/maintain"

    private fun details(version: NewVersion): TvNoticeDestination =
        TvNoticeDestination.Dialog { onDismiss -> NewVersionDetails(version, onDismiss) }

    /** 这一条说的那一版, 还是此刻检查到的才有 */
    private fun versionOf(notice: Notice): NewVersion? =
        UpdatePromptState.newVersion.value?.takeIf { it.name == notice.args[ARG_VERSION] }

    private fun publishedMillis(version: NewVersion): Long =
        runCatching { Instant.parse(version.publishedAt).toEpochMilliseconds() }.getOrDefault(Notices.UNKNOWN_TIME)

    private const val ARG_VERSION = "version"

    /** 这次启动的时刻 (这个进程里第一次用到这一种通知时) */
    private val launchedAt = currentTimeMillis()

    /** 一项改动的说明至多几行 */
    private const val CHANGE_MAX_LINES = 1
}
