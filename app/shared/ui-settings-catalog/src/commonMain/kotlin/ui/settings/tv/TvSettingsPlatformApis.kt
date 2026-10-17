/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.runtime.Composable
import me.him188.ani.app.platform.Context
import me.him188.ani.app.ui.settings.tabs.log.LogLanShareState

// 设置清单用到的系统功能: 安卓上照电视版原来的做法, 别的平台没有的给「不支持」(用到它的项不显示).

/** 应用语言的 BCP 47 标签 (多个时取第一个; 空 = 跟随系统); null = 这个平台不能在应用里换语言. */
internal expect fun appLanguageTag(context: Context): String?

/** 把应用语言换成 [tag] (空 = 跟随系统); 跟现在一样时不动. */
internal expect fun setAppLanguage(context: Context, tag: String)

/** 设备有没有触摸屏 (平板 / 手机为 true, 电视为 false). */
internal expect fun hasTouchscreen(context: Context): Boolean

/** 屏幕支持的显示模式 (取不到时为空). */
internal expect fun displayModes(context: Context): List<TvDisplayMode>

/** 系统能不能按壁纸取主题色. */
internal expect val dynamicColorSupported: Boolean

/** 用系统浏览器打开 [url]; 打不开 (没有浏览器) 返回 false. */
internal expect fun openUrlInBrowser(context: Context, url: String): Boolean

/** 在主线程的下一轮抛出 [error] (不被调用方的出错处理接住): 调试里测崩溃上报用. */
internal expect fun crashOnMainThread(error: Throwable)

/** 能不能「扫码传到手机」(起局域网服务把日志给手机下载). */
internal expect val logShareSupported: Boolean

/** 起「扫码传到手机」的服务, 状态报给 [onState]; 一直挂着, 协程取消时关掉. */
internal expect suspend fun runLogShare(context: Context, onState: (LogLanShareState) -> Unit)

/** 能不能把日志导出到用户选的位置. */
internal expect val logExportSupported: Boolean

/** 今天的日志文件在不在. */
internal expect fun currentLogFileExists(context: Context): Boolean

/** 导出今天的日志: 出现在组合里就打开系统选择器, 导完调 [onDone]. */
@Composable
internal expect fun LogExport(onDone: () -> Unit)
