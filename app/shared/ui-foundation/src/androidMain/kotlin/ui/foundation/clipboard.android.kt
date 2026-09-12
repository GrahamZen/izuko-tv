/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

actual fun textClipEntryOf(text: String): ClipEntry {
    // label 只是给系统看的名字, 不能放内容: 原来把整段文本也当 label, Binder 里就传两份,
    // 复制 256KB 日志要 1MB, 超过事务上限抛 TransactionTooLargeException
    return ClipEntry(ClipData("text", arrayOf("text/plain"), ClipData.Item(text)))
}

actual suspend fun Clipboard.getClipEntryText(): String? {
    return getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()
}
