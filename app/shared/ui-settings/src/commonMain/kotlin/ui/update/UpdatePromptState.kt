/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面最外层管着的那一份更新状态 (电视): 查新版本、下载、安装都在它上面, 走到哪一页都不停. 提供了时首页不再挂 [UpdateNotifier],
 * 设置页与动作面板顶上的下载那一格读它, 看到的是同一份进度.
 */
val LocalAppUpdateViewModel = staticCompositionLocalOf<AppUpdateViewModel?> { null }

/**
 * 应用更新 ([UpdateNotifier]) 与外面的约定 (TV 上新版本是一条通知, 见 docs/contributing/code/notices.md):
 * - [newVersion]: 检查到、还没装上的新版本 (TV 的通知种类据此列一条); 没有为 null;
 * - [requestDownload]: 按了「自动更新」(通知里, 或电视设置的「检查更新」), 由 [UpdateNotifier] 接着做
 *   (见 [updateRequestStep]; 进度、安装、重试在右下角的下载卡上, 电视上在动作面板顶上); [UpdateNotifier] 不在组合里时记下, 它出来再下;
 * - [dismissedDownload]: 用户在动作面板里按 ✕ 收起的那一版 (同下载卡上按「取消」: 那一格不再显示). 再按「自动更新」清掉.
 */
object UpdatePromptState {
    private val _newVersion = MutableStateFlow<NewVersion?>(null)
    val newVersion: StateFlow<NewVersion?> = _newVersion.asStateFlow()

    private val _pendingDownload = MutableStateFlow<String?>(null)

    /** 要开始下载的版本名 (见 [requestDownload]); [UpdateNotifier] 取走 ([takeDownload]) 前一直在 */
    internal val pendingDownload: StateFlow<String?> = _pendingDownload.asStateFlow()

    private val _dismissedDownload = MutableStateFlow<String?>(null)
    val dismissedDownload: StateFlow<String?> = _dismissedDownload.asStateFlow()

    /** 收起 [version] (版本名) 的下载. */
    fun dismissDownload(version: String) {
        _dismissedDownload.value = version
    }

    internal fun setNewVersion(version: NewVersion?) {
        _newVersion.value = version
    }

    /** 更新到新版本 [version] (版本名; 不是此刻检查到的那一版时不做). [UpdateNotifier] 不在组合里时记下, 它出来再下. */
    fun requestDownload(version: String) {
        _dismissedDownload.value = null
        _pendingDownload.value = version
    }

    /** 取走要下载的 [version] (还是它时清掉并返回 true). */
    internal fun takeDownload(version: String): Boolean = _pendingDownload.compareAndSet(version, null)
}

/** 按「自动更新」(通知里, 或电视设置的「检查更新」第二下) 做什么, 见 [updateRequestStep]. */
internal enum class UpdateRequestStep { Download, ShowProgress, Install }

/** 按「自动更新」做什么: 正在下载、安装时只带去看进度 (不重新下), 下好了就安装, 别的时候开始下载. */
internal fun updateRequestStep(state: AppUpdateState): UpdateRequestStep = when (state) {
    is AppUpdateState.Downloading, is AppUpdateState.Installing -> UpdateRequestStep.ShowProgress
    is AppUpdateState.Downloaded -> UpdateRequestStep.Install
    else -> UpdateRequestStep.Download
}
