/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

/**
 * 最近弹过的提示. Android 上提示是系统 Toast, 画在应用的窗口之外, 从界面树里读不到: 弹的地方记一笔 ([record]),
 * Web 控制台的调试接口读 ([recent], 见 ui-tv 的 RemoteDebugUi). 只在内存里留最近 [CAPACITY] 条.
 */
object ToastHistory {
    class Entry(val atMillis: Long, val text: String)

    private const val CAPACITY = 20
    private val entries = ArrayDeque<Entry>()

    fun record(text: String) {
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), text))
            while (entries.size > CAPACITY) entries.removeFirst()
        }
    }

    /** 从旧到新. */
    fun recent(): List<Entry> = synchronized(entries) { entries.toList() }
}
