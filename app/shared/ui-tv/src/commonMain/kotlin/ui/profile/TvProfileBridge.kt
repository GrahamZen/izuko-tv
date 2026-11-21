/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 选人页 (一台设备上有两个以上用户时打开应用先选人; 侧边栏头像的「切换用户」也打开它). 页面只用开关这两样;
 * 没有多用户入口的端 (iOS) 给空的, 永远不出现.
 */
expect object TvUserProfilePicker {
    val visible: MutableStateFlow<Boolean>

    /** @param held 先藏着, 等放出来 (见安卓的实现) */
    fun show(held: Boolean = false)
}

/** 换人重启进来时盖在首屏上的那一帧 (见安卓的 ProfileSwitchFrame): 非 null 期间首屏让着它. */
internal expect val tvProfileSwitchLanding: StateFlow<Any?>
