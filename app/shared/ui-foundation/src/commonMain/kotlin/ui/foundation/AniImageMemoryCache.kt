/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch

private const val MEBIBYTE = 1024L * 1024L

/**
 * 解码后位图的内存缓存上限 (字节); null = 用 Sketch 的默认大小.
 *
 * 内存缓存省的是「从磁盘缓存重新解码」: 原始字节一直在磁盘缓存里, 被挤出去的图再显示时只是重新解码, 不会重新下载.
 * 电视上最值得留住的是全屏背景图 (w1280 解出来一张约 3.5 MB): 首页轮播一整轮约 40 MB, 再加一屏封面与最近看过的
 * 十来张背景图, 几十 MB 就够覆盖遥控器的「来回走」. 代价却随机器差很多: 1.5~2 GB 内存的电视上, 多压一百来 MB
 * 位图就可能让前台的应用被系统杀掉. 所以按设备总内存定上限, 不按堆上限 (Sketch 的默认值是大堆上限的 30%,
 * 512 MB 大堆的电视约 154 MB).
 */
internal expect fun aniImageMemoryCacheSize(context: PlatformContext): Long?

/** 总内存的 1/32, 夹在 32~128 MiB 之间: 1 GB → 32 MiB, 2 GB → 64 MiB, 3 GB → 96 MiB. */
internal fun imageMemoryCacheSizeForTotalMemory(totalMemoryBytes: Long): Long =
    (totalMemoryBytes / 32).coerceIn(32 * MEBIBYTE, 128 * MEBIBYTE)

/**
 * 系统报告内存严重不足 (再不释放, 前台的应用也要被杀) 时清空图片内存缓存. Sketch 自己在内存偏低时只减半,
 * 进后台才清空.
 *
 * @return 停止监听
 */
internal expect fun Sketch.clearMemoryCacheOnCriticalMemory(context: PlatformContext): () -> Unit
