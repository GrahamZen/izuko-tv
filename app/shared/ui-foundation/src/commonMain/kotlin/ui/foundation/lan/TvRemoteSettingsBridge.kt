/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.lan

// commonMain 里 @Volatile 必须显式 import: JVM/Android 编译时 kotlin.jvm.* 自动导入, 本地怎么编都过,
// 只有 CI 上 macOS 按 iOS 编 commonMain 元数据时才报 Unresolved reference 'Volatile'
import kotlin.concurrent.Volatile

/**
 * TV「Web 控制台」给设置页用的动作. 设置页在 ui-settings, 够不到 ui-tv 里的 `TvRemoteControl`, 由它在启动时登记到这里;
 * 没登记 (手机 / 桌面包) 时设置页不显示对应项.
 */
object TvRemoteSettingsBridge {
    /** 重置地址: 换一个新 token, 已扫过的手机与书签全部作废 (见 `TvRemoteControl.resetAddress`). */
    @Volatile
    var resetAddress: (() -> Unit)? = null
}
