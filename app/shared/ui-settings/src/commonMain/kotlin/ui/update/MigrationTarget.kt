/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import me.him188.ani.app.platform.ContextMP

/**
 * 跳板包要迁去的新应用 (新包名的正式包) 在本机上装了没有. 跳板包只有 Android 版, 其它平台恒为 `false`.
 */
internal expect fun ContextMP.isMigrationTargetInstalled(): Boolean

/**
 * 打开跳板包要迁去的新应用. 新应用第一次打开时自己会把设置接过去.
 *
 * @return 打开了
 */
internal expect fun ContextMP.launchMigrationTarget(): Boolean
