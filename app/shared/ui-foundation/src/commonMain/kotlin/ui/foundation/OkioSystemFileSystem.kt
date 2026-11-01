/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import okio.FileSystem

/** 本机文件系统: okio 只在 JVM 与 native 上有 [FileSystem.SYSTEM], 不在公共 API 里, 经这里拿. */
internal expect val okioSystemFileSystem: FileSystem
