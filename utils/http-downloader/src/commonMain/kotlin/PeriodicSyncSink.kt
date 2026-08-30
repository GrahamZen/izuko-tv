/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.io.RawSink

/**
 * 打开一个每写满 [syncEveryBytes] 字节就 fsync 一次的文件 sink; 平台不支持则返回 null (调用方回落
 * 到普通 sink).
 *
 * 这是"缓存时遥控器失灵"的真正修法 (2026-08-30 定案). Shield 的 /data 是 `ext4 data=ordered`,
 * 而缓存目录 /storage/emulated/0 就是 /data/media —— 同一个文件系统. data=ordered 下**任何进程**的
 * 一次小 fsync 都得先等文件系统里**全部**脏页落盘. 我们以十几 MB/s 往里灌, 脏页积到几百 MB, 蓝牙栈
 * 与 system_server 的一次 fsync 就要等好几秒 —— 遥控器的按键卡在蓝牙进程里出不来, 表现为"整机没反应"
 * (冻结时 USB 鼠标全程正常; 连自家 Room 都报过 30 秒拿不到连接).
 *
 * 自己周期 fsync 就是不让脏页积起来: 别人的 fsync 最多等我们这一小段. 代价全由我们自己的写线程
 * 承担 (它本来就是后台任务).
 *
 * 曾经试过换 OkHttp 直接落盘 (堆分配 7.6x -> 1.0x), 实测**照样冻** —— GC 从来不是主因; 而自建 client
 * 会绕开 Ktor 的代理/Cookie/UA/重定向, 对代理用户是静默故障. 所以只换写文件这一步, 请求仍走 Ktor.
 */
expect fun openPeriodicSyncSink(absolutePath: String, syncEveryBytes: Long): RawSink?

/**
 * 下载分段时的 fsync 间隔.
 *
 * 取 1MB: 网络本来就有空隙, 同步等待藏在等包的时间里, 不拖吞吐 (真机实测仍有 11MB/s).
 */
const val SYNC_EVERY_BYTES_DOWNLOAD: Long = 1L * 1024 * 1024

/**
 * 合并 (拼接分段) 时的 fsync 间隔, 比下载放宽.
 *
 * 拼接是纯磁盘顺序读写, 每 1MB 就同步等一次会让写盘完全串行 (413MB 实测 38 秒, 不 sync 是 20 秒).
 * 放到 4MB 让内核有余地流水 (实测 27.9 秒); 别人的 fsync 最多等 4MB 落盘 (~100-200ms), 是可感的
 * 轻微迟滞而不是冻结 —— 拼接期间没有别的写在抢, 这个上限就是最坏情况.
 */
const val SYNC_EVERY_BYTES_MERGE: Long = 4L * 1024 * 1024

/**
 * 对**别人正在写**的文件周期 fsync: 返回 true 表示这一次同步做成了, 平台不支持返回 false.
 *
 * 存在的理由: m3u8 合并那一步的输出文件是 ffmpeg 自己写的, 我们插不进 [openPeriodicSyncSink].
 * 原先只能用 ffmpeg 的 `-readrate` 把吞吐压下来 —— 副作用是 24 分钟一集要合并两分多钟, 而界面
 * 那段时间一直写着"下载中 100%", 看起来像卡死.
 *
 * 但 fsync 是**按 inode 生效**的, 不必是写它的那个 fd: 另开一个 fd 指向同一个文件同样能把它的
 * 脏页刷下去. 于是合并可以全速跑, 由旁边一个协程按 [SYNC_EVERY_BYTES_MERGE] 的节奏替它同步,
 * 效果与下载路径那条一致.
 *
 * 只读打开是不够的 (fsync 需要写权限的 fd), 但打开时**绝不能截断**, 也不写入任何字节 ——
 * JVM 实现用追加模式打开, 只调 fsync.
 */
expect fun syncFileContents(absolutePath: String): Boolean
