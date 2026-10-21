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
 * 这个路径所在分区还能写多少字节; 取不到时为 null. 路径还不存在时 (比如还没缓存过的缓存目录) 按它最近的、已存在的上级目录算.
 */
expect fun SystemPath.usableSpace(): Long?
