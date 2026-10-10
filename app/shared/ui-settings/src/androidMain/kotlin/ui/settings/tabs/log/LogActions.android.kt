/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.log

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.ui.foundation.lan.findLanAddress
import me.him188.ani.app.ui.foundation.widgets.LocalToaster

/** 今天的日志文件在不在 (还没写过日志时没有). */
fun Context.hasCurrentLogFile(): Boolean = getCurrentLogFile().exists()

/**
 * 把今天的日志导出到用户在系统选择器里选的位置 (同设置 → 日志里「导出」那一行): 出现在组合里就打开选择器, 导完 (或取消后落到应用目录) 调 [onDone].
 * 选择器打开期间要一直留在组合里, 结果才收得到.
 */
@Composable
fun LogExportLauncher(onDone: () -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE)) { uri ->
        scope.launch {
            val logFile = context.getCurrentLogFile()
            if (uri == null) exportLogFileToAppDir(context, logFile, toaster) else exportLogFileTo(context, logFile, uri, toaster)
            onDone()
        }
    }
    LaunchedEffect(Unit) {
        runCatching { launcher.launch(EXPORT_FILE_NAME) }.onFailure {
            // 设备连 DocumentsUI 都没装, 选择器拉不起来: 落到应用目录
            exportLogFileToAppDir(context, context.getCurrentLogFile(), toaster)
            onDone()
        }
    }
}

/** 扫码把日志传到手机的服务此刻的样子 (见 [runLogLanShare]). */
sealed interface LogLanShareState {
    data object Starting : LogLanShareState
    data object NoNetwork : LogLanShareState
    data object Failed : LogLanShareState
    data class Ready(val url: String) : LogLanShareState
}

/**
 * 起「扫码传到手机」的局域网服务 (同设置 → 日志里那一行的弹窗), 把状态报给 [onState]; 一直挂着服务, 协程取消时关掉 (链接随之失效).
 * 给电视设置页用: 焦点停在那一行时跑, 离开就取消.
 */
suspend fun runLogLanShare(context: Context, onState: (LogLanShareState) -> Unit) {
    onState(LogLanShareState.Starting)
    // 建 socket 与枚举网卡都在 IO 线程: 前者在主线程会触发 NetworkOnMainThread 检查, 后者要走一遍 netlink
    val server = withContext(Dispatchers.IO) { runCatching { LogLanShareServer(context.getLogsDir()) } }.getOrElse {
        onState(LogLanShareState.Failed)
        return
    }
    try {
        val host = withContext(Dispatchers.IO) { findLanAddress() }
        if (host == null) {
            onState(LogLanShareState.NoNetwork)
            awaitCancellation()
        }
        onState(LogLanShareState.Ready(server.pageUrl(host)))
        server.serve()
    } finally {
        server.close()
    }
}
