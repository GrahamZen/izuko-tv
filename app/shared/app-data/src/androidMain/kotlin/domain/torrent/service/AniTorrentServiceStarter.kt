/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.torrent.IRemoteAniTorrentEngine
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * @param onServiceDisconnected optional callback when service disconnected.
 */
class AniTorrentServiceStarter(
    private val context: Context,
    private val startServiceImpl: () -> ComponentName?,
    private val onServiceDisconnected: () -> Unit = { },
) : TorrentServiceStarter<IRemoteAniTorrentEngine> {
    private val logger = logger<AniTorrentServiceStarter>()

    private val startupIntentFilter = IntentFilter(AniTorrentService.INTENT_STARTUP)
    private val binderDeferred = MutableStateFlow<CompletableDeferred<IRemoteAniTorrentEngine>?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                binderDeferred.value?.completeExceptionally(ServiceStartException.NullBinder())
            }
            val result = IRemoteAniTorrentEngine.Stub.asInterface(service)
            binderDeferred.value?.complete(result)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binderDeferred.value?.completeExceptionally(ServiceStartException.DisconnectedUnexpectedly())
            onServiceDisconnected()
        }
    }

    override suspend fun start(): IRemoteAniTorrentEngine {
        // 与连接循环那两行一起用: 这行到 [1/4] 之间只有注册广播接收器与 startService 调用,
        // 如果这一段耗了几十秒, 那就是系统侧 (主线程 / binder) 卡住, 而不是我们没去启动
        logger.debug { "[0/4] Starting service: registering receiver." }
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    logger.debug { "[2/4] Received service startup result broadcast: $intent" }
                    context.unregisterReceiver(this)

                    val result = intent?.getBooleanExtra(AniTorrentService.INTENT_STARTUP_EXTRA, false) == true

                    if (!result) {
                        cont.resumeWithException(ServiceStartException.StartRespondFailure())
                    } else {
                        cont.resume(Unit)
                    }
                }
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                startupIntentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            cont.invokeOnCancellation {
                context.unregisterReceiver(receiver)
            }

            val startResult = try {
                startServiceImpl()
            } catch (e: Exception) {
                context.unregisterReceiver(receiver)

                cont.resumeWithException(ServiceStartException.StartFailed(e))
                return@suspendCancellableCoroutine
            }

            if (startResult == null) {
                context.unregisterReceiver(receiver)
                cont.resumeWithException(ServiceStartException.StartFailed())
            } else {
                logger.debug { "[1/4] Started service, result: $startResult" }
            }
        }
    
        // 上一轮的绑定还在的话先解绑. 服务进程死掉时绑定不会自动断开: 系统会在服务重启后用**旧的**绑定回调
        // onServiceConnected, 那时 binderDeferred 还是上一轮的 (完成了也没人等); 而对已经绑着的 conn 再次
        // bindService 不会再回调一次, 于是 [3/4] 之后永远等不到 [4/4]. 解绑后重新绑定才能拿到新的回调.
        unbind()

        val newDeferred = CompletableDeferred<IRemoteAniTorrentEngine>()
        binderDeferred.value = newDeferred

        val bindResult = context.bindService(
            Intent(context, AniTorrentService.actualServiceClass),
            conn,
            Context.BIND_ABOVE_CLIENT,
        )
        if (!bindResult) throw ServiceStartException.BindServiceFailed()
        bound = true
        logger.debug { "[3/4] Bound service successfully." }

        // 限时等待: 等不到就解绑重来, 而不是永远挂着 —— 挂着的话所有 torrent 调用都会一直阻塞 (它们等的就是这个 binder)
        val result = withTimeoutOrNull(BIND_TIMEOUT) { newDeferred.await() } ?: run {
            logger.warn { "Timed out waiting for service binder after $BIND_TIMEOUT, will unbind and retry." }
            unbind()
            throw ServiceStartException.BinderTimeout()
        }
        logger.debug { "[4/4] Got service binder: $result" }
        return result
    }

    /** 已经 [bindService][Context.bindService] 过、还没解绑. 只在 [start] 里读写, 而 [start] 由连接循环单线程调用. */
    private var bound = false

    private fun unbind() {
        if (!bound) return
        bound = false
        try {
            context.unbindService(conn)
        } catch (e: IllegalArgumentException) {
            // 没绑过或已经解绑, 忽略
            logger.warn(e) { "Failed to unbind service." }
        }
    }

    private companion object {
        /** 绑定成功到拿到通信对象的限时. 服务进程这时已经起来了 (广播已收到), 正常是毫秒级. */
        private val BIND_TIMEOUT = 15.seconds
    }
}