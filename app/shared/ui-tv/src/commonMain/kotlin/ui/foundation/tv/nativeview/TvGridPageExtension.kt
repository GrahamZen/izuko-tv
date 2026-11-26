/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

/**
 * 页面挂在网格页海报墙上的一块 (见 [TvNativeGridPageHost] 的 extensions). 网格页宿主的签名只认这个公共类型, 不带平台的视图类型;
 * 安卓上的实现是原生视图那一套 [TvNativeWallExtension], 宿主只挂这一种, 别的实现照不挂.
 */
interface TvGridPageExtension
