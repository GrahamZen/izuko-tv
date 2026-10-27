/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.utils.io.SystemPath

/**
 * 片库能不能用、放在哪, 由平台提供. 片库在应用私有目录之外 (卸载、清除数据都不删), 安卓上要「所有文件访问」:
 * 关掉这个权限后片库里的文件 (连自己写的) 都读不了, 重新打开即恢复, 覆盖升级与清除数据都保留它.
 * 没有实现的平台不支持片库.
 */
interface MediaLibraryAccess {
    /** 平台有片库要的权限 (安卓 11 起); 没有时设置里那一项打不开 */
    val isSupported: Boolean

    /** 片库要的权限此刻开着 (用户在系统设置里开的, 或用 adb 开的, 都算) */
    fun isGranted(): Boolean

    /** [location] 位置上片库的根目录 (那块盘的根目录下 [LIBRARY_DIR_NAME]); 推不出来时为 `null` */
    fun libraryRootOf(location: MediaCacheLocation): SystemPath?

    /** 打开系统里给这个权限的页面, 返回打开的是哪一种; 一个入口都没有时为 `null` (用 [adbGrantCommand]) */
    fun openGrantPage(): GrantPage? = null

    /** 没有入口时用电脑给权限的 adb 命令 */
    val adbGrantCommand: String? get() = null

    enum class GrantPage {
        /** 只管这个应用的授权页 */
        APP,

        /** 所有应用的列表, 要用户自己找到这个应用 */
        LIST,

        /** 应用信息页, 要用户自己进「权限 → 文件和媒体」 */
        APP_DETAILS,
    }

    companion object {
        const val LIBRARY_DIR_NAME = "Izuko"
    }
}
