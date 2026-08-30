/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 缓存下载专用的调度器: **独立线程 + 低优先级**.
 *
 * 为什么不能用 `Dispatchers.IO_`: kotlinx 里 IO 与 Default **共用同一个 worker 池**
 * (线程名都是 `DefaultDispatcher-worker-N`), 于是缓存下载的读写会与驱动界面的那些数据流抢同一批
 * 线程. 真机实测 (2026-08-29, Shield, release 包): 缓存下载期间本进程吃到 220~306% CPU
 * (设备共 400%), 按线程统计 `DefaultDispatch` 累计值是第二名的 1.6 倍, 主线程随即出现 300~700ms
 * 的调度延迟 —— 表现为"一边缓存一边操作界面就很卡", 下载一结束立刻恢复流畅.
 *
 * 低优先级的用意是"让路而不是限速": 空闲时下载照样跑满, 一旦与界面抢 CPU, 系统调度器优先给界面.
 */
expect fun createCacheDownloadDispatcher(): CoroutineDispatcher
