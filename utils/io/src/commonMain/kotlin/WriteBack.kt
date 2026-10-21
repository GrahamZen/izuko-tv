/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.io

/**
 * 大块写盘时边写边落盘 (fsync), 不让脏页积起来. 下载、缓存、局域网传文件这类一口气写几百 MB 的地方都要用.
 *
 * 电视的 `/data` 是 ext4 `data=ordered` (缓存目录 `/storage/emulated/0` 就是 `/data/media`, 同一个文件系统). 这种模式下
 * **任何进程**的一次小 fsync 都得先等文件系统里**全部**脏页落盘. 以十几 MB/s 往里灌、只写进页缓存不落盘, 脏页积到几百 MB,
 * 蓝牙栈与 system_server 的一次 fsync 就要等好几秒 —— 遥控器的按键卡在蓝牙进程里出不来, 主线程与数据库跟着卡,
 * 画面停住, 只有缓冲在内存里的声音照常 (冻结时 USB 鼠标全程正常).
 *
 * 自己每写 [syncEveryBytes] 就 fsync 一次, 别人的 fsync 最多等这一小段; 代价由写盘的线程承担 (它本来就是后台任务).
 *
 * - 自己写的文件: 每写一段调一次 [wrote], 返回 true 就 fsync 写它的句柄.
 * - 别人写的文件 (libtorrent、ffmpeg、播放器的磁盘缓存): fsync 按文件生效, 用 [syncFileContents] 另开句柄替它落盘.
 *
 * 不是线程安全的: 由写这个文件的线程 (或同一把锁下) 使用.
 */
class WriteBackCounter(private val syncEveryBytes: Long = SYNC_EVERY_BYTES) {
    private var unsyncedBytes = 0L

    /** 又写了 [bytes] 字节. 攒够 [syncEveryBytes] 时返回 true 并重新计数, 调用方随即 fsync. */
    fun wrote(bytes: Long): Boolean {
        unsyncedBytes += bytes
        if (unsyncedBytes < syncEveryBytes) return false
        unsyncedBytes = 0
        return true
    }

    /** 文件刚另外落过一次盘 (例如定时保存进度时), 重新计数. */
    fun synced() {
        unsyncedBytes = 0
    }

    companion object {
        /**
         * 默认间隔. 别人的一次 fsync 最多等 4 MB 落盘 (约 100~200 ms), 是轻微迟滞而不是冻结;
         * 纯顺序写盘时比每 1 MB 一次快得多 (413 MB 合并实测 27.9 秒对 38 秒).
         */
        const val SYNC_EVERY_BYTES: Long = 4L * 1024 * 1024

        /**
         * 边从网络收边写盘 (在线源、BT 下载) 时的间隔. 网络本来就有空隙, 等落盘的时间藏在等数据里, 不怎么拖吞吐
         * (在线源真机实测仍有 11 MB/s); 每次落的量小, 别的进程 (蓝牙栈) 的 fsync 也等得短 ——
         * BT 八个文件同时下、每 4 MB 落一次时, 26 分钟里仍有 7 次蓝牙命令超时.
         */
        const val SYNC_EVERY_BYTES_DOWNLOAD: Long = 1L * 1024 * 1024
    }
}

/**
 * 把 [path] 已写的内容落盘, 不必是写它的那个句柄: 给别人正在写的文件用 (见 [WriteBackCounter]).
 * 不截断、不写入, 文件不存在时也不会把它建出来. 返回这次是否做成了; 文件不存在、平台做不到时返回 false.
 */
expect fun syncFileContents(path: SystemPath): Boolean
