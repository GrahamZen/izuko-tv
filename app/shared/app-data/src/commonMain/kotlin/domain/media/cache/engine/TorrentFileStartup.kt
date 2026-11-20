/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 一份 BT 缓存打开它那个文件的过程 ([TorrentMediaCacheEngine.FileHandle] 的状态).
 *
 * 要先拿到种子信息 (文件列表) 才知道下哪个文件. 建缓存、恢复缓存时最多等 [TORRENT_FILES_TIMEOUT_MILLIS] 就返回 (不然缓存存储的锁
 * 被占着); 没等到的不算失败: 停在 [Phase.AwaitingMetadata], 后台接着等 —— 磁力链接在节点少的网络里常要几分钟.
 * 等到以后照最后一次 [resume] / [pause] 办 (存储建好缓存当场就调了 resume, 那时还没有文件可下); 等的时候 [close] 了
 * (删除、刷新替换), 等到也不再打开文件.
 */
internal class TorrentFileStartup(initial: Phase = Phase.AwaitingMetadata) {
    sealed interface Phase {
        /** 种子信息还没拿到, 后台在等 */
        data object AwaitingMetadata : Phase

        /** 拿到了; [TorrentMediaCacheEngine.FileHandle.State.handle] 为 null = 种子里没有这一集的文件 */
        class Started(val state: TorrentMediaCacheEngine.FileHandle.State) : Phase

        /** 打不开 (启动出错) */
        class Failed(val cause: Throwable?) : Phase
    }

    private val _phase = MutableStateFlow(initial)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 此刻打开着的; 还在等、打不开时为 null */
    val started: TorrentMediaCacheEngine.FileHandle.State? get() = (_phase.value as? Phase.Started)?.state

    private val lock = Mutex()

    /** 最后一次要它下 (true) 还是停 (false); 还没说过为 null */
    private var wantsRunning: Boolean? = null
    private var closed = false

    /** 后台在等种子信息的那一段; [close] 时取消 */
    var waiting: Job? = null

    suspend fun resume() = lock.withLock {
        wantsRunning = true
        started?.handle?.resume(FilePriority.NORMAL)
    }

    suspend fun pause() = lock.withLock {
        wantsRunning = false
        started?.handle?.pause()
    }

    suspend fun close() {
        val state = lock.withLock {
            closed = true
            waiting?.cancel()
            started
        } ?: return
        state.sidecars?.close()
        state.handle?.close()
    }

    /**
     * 等到了结果: 已经关掉的不留 (打开的文件关上), 否则记下, 照最后一次要的下或停. 在后台那条线上调, 出错只记日志
     * (它挂在缓存存储的协程下面, 不能把存储带着取消).
     */
    suspend fun settle(result: Phase) {
        try {
            val discard = lock.withLock {
                if (closed) return@withLock (result as? Phase.Started)?.state
                _phase.value = result
                val handle = (result as? Phase.Started)?.state?.handle
                when (wantsRunning) {
                    true -> handle?.resume(FilePriority.NORMAL)
                    false -> handle?.pause()
                    null -> Unit
                }
                null
            } ?: return
            discard.sidecars?.close()
            discard.handle?.close() ?: discard.session.closeIfNotInUse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to apply the late torrent info" }
        }
    }

    private companion object {
        private val logger = logger<TorrentFileStartup>()
    }
}

/** 建缓存、恢复缓存时等种子的文件列表 (要先拿到元数据) 最多等多久再返回; 没等到的后台接着等, 见 [TorrentFileStartup] */
internal const val TORRENT_FILES_TIMEOUT_MILLIS = 30_000L
