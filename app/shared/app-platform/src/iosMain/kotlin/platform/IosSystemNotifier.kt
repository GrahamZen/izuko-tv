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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS 的系统通知 (UNUserNotificationCenter): 本地通知, 不经服务器, 侧载签名也能用. 要在应用启动完之前 [install]
 * (点通知冷启动时, 系统把点了哪条交给那时已经设好的代理). 应用在前台时来的也照样弹横幅 (平常只在后台发, 前台的是调试接口发的).
 */
object IosSystemNotifier : SystemNotifier {
    private const val PAYLOAD_KEY = "izuko.payload"

    private val logger = logger<IosSystemNotifier>()
    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    // 冷启动时点开的那条在界面接上之前就到了: 留一条给后来的收集者
    private val _opened = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    override val opened: Flow<String> = _opened.asSharedFlow()

    // 在后台被叫醒起来的进程一开始就不在前台; 真正的状态启动完后在主线程上读 (见 install)
    private val _appActive = MutableStateFlow(false)
    override val appActive: StateFlow<Boolean> = _appActive.asStateFlow()

    private val delegate = Delegate()
    private var installed = false

    /** 设好代理、开始看前后台. 主线程上调, 一个进程一次. */
    fun install() {
        if (installed) return
        installed = true
        center.delegate = delegate
        // startIosApp 在 SwiftUI 的 App 初始化里调, 那时应用对象可能还没起来: 等主线程跑起来再读
        NSOperationQueue.mainQueue.addOperationWithBlock {
            _appActive.value = UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground
        }
        val notifications = NSNotificationCenter.defaultCenter
        notifications.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            _appActive.value = true
        }
        notifications.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            _appActive.value = false
        }
    }

    override suspend fun authorization(): SystemNotificationAuthorization = suspendCancellableCoroutine { cont ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            val result = when (status) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> SystemNotificationAuthorization.Granted
                UNAuthorizationStatusDenied -> SystemNotificationAuthorization.Denied
                else -> SystemNotificationAuthorization.NotDetermined
            }
            if (cont.isActive) cont.resume(result)
        }
    }

    override suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { cont ->
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { granted, error ->
            if (error != null) logger.info { "Notification authorization error: ${error.localizedDescription}" }
            if (cont.isActive) cont.resume(granted)
        }
    }

    override fun post(notification: SystemNotification) {
        val content = UNMutableNotificationContent()
        content.setTitle(notification.title)
        notification.body?.let { content.setBody(it) }
        notification.group?.let { content.setThreadIdentifier(it) }
        content.setUserInfo(mapOf<Any?, Any?>(PAYLOAD_KEY to notification.payload))
        val request = UNNotificationRequest.requestWithIdentifier(notification.id, content, null)
        center.addNotificationRequest(request) { error ->
            if (error != null) logger.info { "Failed to post system notification ${notification.id}: ${error.localizedDescription}" }
        }
    }

    override fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        center.removeDeliveredNotificationsWithIdentifiers(ids.toList())
        center.removePendingNotificationRequestsWithIdentifiers(ids.toList())
    }

    private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        /** 应用在前台时来的: 也弹横幅、进通知中心 */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList)
        }

        /** 用户点开了一条 */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            val payload = didReceiveNotificationResponse.notification.request.content.userInfo[PAYLOAD_KEY] as? String
            if (payload != null) _opened.tryEmit(payload)
            withCompletionHandler()
        }
    }
}
