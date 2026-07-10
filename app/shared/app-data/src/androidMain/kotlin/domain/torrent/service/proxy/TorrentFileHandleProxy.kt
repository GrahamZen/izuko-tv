/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.service.proxy

import kotlinx.coroutines.runBlocking
import me.him188.ani.app.domain.torrent.IRemoteTorrentFileEntry
import me.him188.ani.app.domain.torrent.IRemoteTorrentFileHandle
import me.him188.ani.app.domain.torrent.client.ConnectivityAware
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.utils.coroutines.childScope
import kotlin.coroutines.CoroutineContext

class TorrentFileHandleProxy(
    private val delegate: TorrentFileHandle,
    private val connectivityAware: ConnectivityAware,
    context: CoroutineContext
) : IRemoteTorrentFileHandle.Stub() {
    private val scope = context.childScope()

    override fun getTorrentFileEntry(): IRemoteTorrentFileEntry {
        return TorrentFileEntryProxy(delegate.entry, connectivityAware, scope.coroutineContext)
    }

    override fun resume(priorityEnum: Int) {
        delegate.resume(FilePriority.entries[priorityEnum])
    }

    override fun pause() {
        delegate.pause()
    }

    // close/closeAndDelete 都必须同步等到完成再返回, 不能 fire-and-forget:
    // - closeAndDelete: 调用方 (TorrentMediaCacheEngine.closeAndDeleteFiles) 靠这个调用的返回来
    //   释放目录锁/清理状态, 提前返回会让 App 侧过早放锁, 后续新会话正在使用的目录可能被这里
    //   延迟执行的删除误删.
    // - close: 删除分支在它返回后立即删本集文件, 若服务端此刻仍持有/写入该文件, 会删失败、
    //   被重建或留残缺文件; TorrentFileHandle.close 的契约本身要求最后一个 handle 关闭时等
    //   session 完全关闭.
    // AIDL 非 oneway, binder 调用本身是同步的; 客户端 (RemoteTorrentFileHandle) 在 Dispatchers.IO
    // 上调用, 阻塞数秒可接受. runBlocking 桥接在本服务代理里是既有模式 (TorrentSessionProxy.getName 等).

    override fun close() {
        runBlocking {
            delegate.close()
        }
    }

    override fun closeAndDelete() {
        runBlocking {
            delegate.closeAndDelete()
        }
    }
}
