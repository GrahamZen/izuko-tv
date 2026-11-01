/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import me.him188.ani.app.platform.Context

/**
 * 有触摸屏的设备用平板模式还是电视模式 (设置「界面」里选, 切换时重启应用). 默认平板模式: 触屏适配、播放时藏系统栏、触屏版设置页
 * ([LocalTvTouchInputEnabled] 为 true); 电视模式同没有触摸屏的电视, 给接遥控器用、系统却声明了触摸屏的盒子 / 投影仪.
 *
 * 存本机 SharedPreferences 而不是设置仓库: 启动时同步读, 第一帧就按它组合. 没有触摸屏的设备不读它.
 */
expect object TvInterfaceModeGate {
    fun isTvMode(context: Context): Boolean

    /** 紧接着要重启进程, 所以同步写盘. */
    fun setTvMode(context: Context, tvMode: Boolean)
}
