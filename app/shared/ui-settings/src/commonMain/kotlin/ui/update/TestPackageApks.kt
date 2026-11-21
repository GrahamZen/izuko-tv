/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform

/**
 * 工单回复里给的测试包 (一组 APK 链接, 文件名同 release 的包) 里挑这台设备装得上的那个: 规则同检查到的新版本
 * ([pickInstallableApks]): 本机架构的专包优先、universal 兜底, 低于正式包 minSdk 的只挑兼容包; 都装不上为 null.
 * 不是 Android 时取第一个.
 */
fun pickTestPackageUrl(urls: List<String>): String? {
    val platform = currentPlatform()
    if (platform == Platform.Ios) return null // 测试包是 APK, iPad 上装不了
    val android = platform as? Platform.Android ?: return urls.firstOrNull()
    return pickTestPackageUrl(urls, android.supportedAbis, legacy = android.sdkInt?.let { it < NORMAL_APK_MIN_SDK } ?: false)
}

internal fun pickTestPackageUrl(urls: List<String>, abis: List<String>, legacy: Boolean): String? {
    val assets = urls.map { GitHubAsset(name = it.substringBefore('?').substringAfterLast('/'), browserDownloadUrl = it) }
    return assets.pickInstallableApks(abis, legacy).firstOrNull()?.browserDownloadUrl
}
