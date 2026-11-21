/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统通知 (锁屏与系统通知中心里的那种). 只在有实现的平台上放进 Koin (现在只有 iOS); 取不到就是这个平台不发.
 * 应用内的通知怎么转成系统通知见 ui-tv 的 TvSystemNotices.
 */
interface SystemNotifier {
    /** 系统允许没允许这个应用发通知. */
    suspend fun authorization(): SystemNotificationAuthorization

    /** 向系统要发通知的权限 (还没问过时系统弹窗问用户); 允许了为 true. */
    suspend fun requestAuthorization(): Boolean

    /** 发一条; 同一个 [SystemNotification.id] 的替换旧的. */
    fun post(notification: SystemNotification)

    /** 撤掉这几条 (已经显示的从系统通知中心拿掉). */
    fun remove(ids: Collection<String>)

    /** 用户点开了哪一条: 那条的 [SystemNotification.payload]. 冷启动时点开的那条也会发出来. */
    val opened: Flow<String>

    /** 应用在前台 (用户正看着) 时为 true; 在后台跑 (画中画、后台刷新) 或被挂起时为 false. */
    val appActive: StateFlow<Boolean>
}

enum class SystemNotificationAuthorization {
    /** 还没问过 */
    NotDetermined,
    Granted,

    /** 用户拒绝过, 只能去系统设置里打开 */
    Denied,
}

/**
 * 一条系统通知. [id] 同一件事不变 (再发替换旧的); [group] 系统按它把几条收在一起; [payload] 点开时交回来 (见 [SystemNotifier.opened]).
 */
data class SystemNotification(
    val id: String,
    val title: String,
    val body: String?,
    val group: String?,
    val payload: String,
)
