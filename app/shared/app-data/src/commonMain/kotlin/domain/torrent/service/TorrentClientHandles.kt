/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.service

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * BT 服务进程里打开着的文件句柄, 记着各是哪个文件、哪个应用进程 (pid) 建的.
 *
 * 服务在单独的进程里, 应用进程重启 (切换用户、在后台被系统结束、崩溃) 时服务还在, 上一个应用进程开的句柄没人关:
 * 它们照旧请求着文件优先级, 新进程暂停自己的句柄盖不过它们 (文件照下), 删除时种子也一直算在用 (分块留着).
 * 新的应用进程给一个文件建句柄时 ([register]), 把这个文件上已经不在了的进程 ([isAlive] 为 false) 留下的句柄交给调用方关掉.
 * 只交同一个文件的: 这时新句柄已经开着, 关掉旧的不会连带关掉种子会话 (会话没有句柄时就关, 别的文件的恢复可能正要用它).
 * 应用进程不在的这段时间服务里的下载照旧接着下, 到下一个进程恢复这个缓存、建起自己的句柄时才交接.
 *
 * @param H 句柄, [F] 文件 (都按对象本身认)
 */
class TorrentClientHandles<H : Any, F : Any>(
    private val isAlive: (pid: Int) -> Boolean,
) {
    private class Owner<F>(val file: F, val pid: Int)

    private val lock = SynchronizedObject()
    private val owners = HashMap<H, Owner<F>>()

    /**
     * 进程 [pid] 刚给 [file] 建了 [handle]. 返回 [file] 上已经不在了的进程留下的句柄 (已从记录里去掉, 由调用方关掉); 没有时为空.
     * 每个别的进程只查一次活没活.
     */
    fun register(handle: H, file: F, pid: Int): List<H> = synchronized(lock) {
        owners[handle] = Owner(file, pid)
        val others = owners.filter { (_, owner) -> owner.file === file && owner.pid != pid }
        if (others.isEmpty()) return emptyList()
        val dead = others.values.mapTo(HashSet()) { it.pid }.filterTo(HashSet()) { !isAlive(it) }
        val stale = others.filterValues { it.pid in dead }.keys.toList()
        stale.forEach { owners.remove(it) }
        stale
    }

    /** [handle] 关掉了 */
    fun unregister(handle: H) {
        synchronized(lock) { owners.remove(handle) }
    }

    /** 记着的句柄数 */
    val size: Int get() = synchronized(lock) { owners.size }
}
