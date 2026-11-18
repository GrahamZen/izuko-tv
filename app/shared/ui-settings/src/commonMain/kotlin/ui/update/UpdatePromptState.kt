/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 右下角那张卡 ([UpdateNotifier]) 与外面的约定 (TV 上新版本是一条通知, 见 docs/contributing/code/notices.md):
 * - [newVersion]: 检查到、还没开始下载的新版本 (TV 的通知种类据此列一条); 没有为 null. 卡只在首页上, 离开首页时照旧留着 (通知中心在设置里也能打开);
 * - [requestDownload]: 通知里按了「自动更新」, 由 [UpdateNotifier] 开始下载 (进度、安装、重试在右下角的下载卡上); 不在首页时记下, 回到首页再下 ([notifierActive]);
 * - [cardShown]: 下载卡在不在 (下载中、下好待安装、失败). 它会抢焦点, 在场时 TV 的通知气泡等它走了再出;
 * - [bubble]: 右下角要显示的提示气泡 (TV 的通知气泡), 下载卡不在时画.
 */
object UpdatePromptState {
    private val _cardShown = MutableStateFlow(false)
    val cardShown: StateFlow<Boolean> = _cardShown.asStateFlow()

    private val _newVersion = MutableStateFlow<NewVersion?>(null)
    val newVersion: StateFlow<NewVersion?> = _newVersion.asStateFlow()

    private val _pendingDownload = MutableStateFlow<String?>(null)

    /** 要开始下载的版本名 (见 [requestDownload]); [UpdateNotifier] 取走 ([takeDownload]) 前一直在 */
    internal val pendingDownload: StateFlow<String?> = _pendingDownload.asStateFlow()

    private val _notifierActive = MutableStateFlow(false)

    /** 右下角那张卡此刻在不在组合里 (只在首页上): 不在时按了「自动更新」要回到首页才开始下载 */
    val notifierActive: StateFlow<Boolean> = _notifierActive.asStateFlow()

    private val _bubble = MutableStateFlow<CornerBubble?>(null)
    val bubble: StateFlow<CornerBubble?> = _bubble.asStateFlow()

    internal fun setCardShown(shown: Boolean) {
        _cardShown.value = shown
    }

    internal fun setNewVersion(version: NewVersion?) {
        _newVersion.value = version
    }

    internal fun setNotifierActive(active: Boolean) {
        _notifierActive.value = active
    }

    /** 开始下载新版本 [version] (版本名; 不是此刻检查到的那一版时不下). 右下角那张卡不在 (不在首页) 时记下, 回到首页再下. */
    fun requestDownload(version: String) {
        _pendingDownload.value = version
    }

    /** 取走要下载的 [version] (还是它时清掉并返回 true). */
    internal fun takeDownload(version: String): Boolean = _pendingDownload.compareAndSet(version, null)

    /** 在右下角显示 [bubble] (替换正在显示的). */
    fun showBubble(bubble: CornerBubble) {
        _bubble.value = bubble
    }

    /** 撤掉 [bubble] (正在显示的已经换成别的就不动). */
    fun hideBubble(bubble: CornerBubble) {
        _bubble.compareAndSet(bubble, null)
    }
}

/**
 * 右下角的提示气泡: 标题 [title]、几行字 [lines]、一颗主按钮 [actionLabel] 与关闭按钮.
 * 按主按钮 [onAction]; 按关闭、返回键、20 秒没动作 [onDismiss]. 两种都由显示的一方撤掉气泡.
 */
@Immutable
class CornerBubble(
    val title: String,
    val lines: List<String>,
    val actionLabel: String,
    val onAction: () -> Unit,
    val onDismiss: () -> Unit,
)
