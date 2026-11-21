/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import androidx.compose.runtime.Composable

/**
 * 在 Perfetto / systrace 里显示成一段 (安卓的 `android.os.Trace`, 应用标签), 名字统一 `Izuko:` 开头. 没在抓的时候 `beginSection` 只读一个开关, 几乎没有开销.
 * iOS 上什么都不记, 只组合 [content].
 *
 * release 包里 Compose 自带的组合追踪被它自己的混淆规则去掉了, 要知道一帧里是哪一块组合得慢只能靠这些. 抓法: Perfetto 配置的
 * `atrace_apps` 写本应用包名, 主线程上就能看到这些区段 (每次重组都会出现一次, 跳过的也有, 只是很短).
 */
@Composable
inline fun TraceSection(name: String, content: @Composable () -> Unit) {
    traceSectionBegin(name)
    content()
    traceSectionEnd()
}

/** [TraceSection] 的开头 (安卓 `Trace.beginSection`). */
expect fun traceSectionBegin(name: String)

/** [TraceSection] 的结尾 (安卓 `Trace.endSection`). */
expect fun traceSectionEnd()
