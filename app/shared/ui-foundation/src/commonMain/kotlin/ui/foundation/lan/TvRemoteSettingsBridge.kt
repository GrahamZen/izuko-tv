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

    /**
     * 按 SharedPreferences 里存着的 token 重启服务 (服务没在跑就什么都不做). 换分发包名时新包接过旧包的 token 后调它,
     * 手机上已加的书签与扫过的二维码照旧能用 (见 `TvRemoteControl.ensureStarted`).
     */
    @Volatile
    var reloadToken: (() -> Unit)? = null

    /** 地址里的 token 存在哪: SharedPreferences 的文件名与键. 沿用「搜索输入」时代的名字, 升级后 token 不变. */
    const val TOKEN_PREFS_NAME = "tv_remote_search_input"
    const val TOKEN_KEY = "token"
}
