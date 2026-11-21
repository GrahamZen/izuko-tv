/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/**
 * 手机网页的配色跟电视的主题色走: 电视设置里选的主题色 (或「动态取色」) 生成与 App 同一套 M3 配色 (materialkolor TonalSpot,
 * 同 `appColorScheme`), 映射成网页的 CSS 变量, 打开页面时写进 `<style>`, 盖住页面里写死的默认值 (默认值是 App 默认紫那套).
 *
 * 只同步颜色, 深浅仍由手机决定 (网页「外观」: 自动 / 浅色 / 深色), 所以浅色、深色两套都给. 电视的「高对比度黑」不跟:
 * 那是给电视屏幕的, 手机上纯黑底会把卡片的层次糊掉. 电视上改了主题色, 手机刷新页面就换过来.
 *
 * 浅色的页面底色用 surfaceContainerLow (带一点主题色的极浅色, 同 App 浅色主题的页面底), 卡片用 surfaceContainerLowest (白).
 * 状态色 (成功 / 警告 / 出错) 不跟主题色, 仍用页面里的固定值.
 */
internal object RemoteTheme {
    private val logger = logger<RemoteTheme>()

    /** 覆盖页面默认配色的 CSS; 读不到设置或生成失败返回空串 (页面照用默认的). */
    fun css(): String = runCatching {
        val settings = runBlocking {
            withTimeoutOrNull(READ_TIMEOUT) { KoinPlatform.getKoin().get<SettingsRepository>().themeSettings.flow.first() }
        } ?: ThemeSettings.Default
        val (light, dark) = schemes(settings)
        ":root {\n" + vars(light, isDark = false) + "}\n:root[data-theme=\"dark\"] {\n" + vars(dark, isDark = true) + "}\n"
    }.onFailure { logger.warn(it) { "Failed to build remote page theme, using defaults" } }.getOrDefault("")

    /** 同 App 的 appColorScheme (Android): 动态取色 (系统 12+) 或按主题色生成; 这里不套高对比度黑. */
    private fun schemes(settings: ThemeSettings): Pair<ColorScheme, ColorScheme> {
        if (settings.useDynamicTheme && Build.VERSION.SDK_INT >= 31) {
            val context = KoinPlatform.getKoin().get<Context>()
            return dynamicLightColorScheme(context) to dynamicDarkColorScheme(context)
        }
        fun of(dark: Boolean) = dynamicColorScheme(
            primary = settings.seedColor,
            isDark = dark,
            isAmoled = false,
            style = PaletteStyle.TonalSpot,
        )
        return of(false) to of(true)
    }

    /** M3 角色 → 网页变量 (变量含义见页面样式开头那两组默认值). */
    private fun vars(s: ColorScheme, isDark: Boolean): String = buildString {
        fun v(name: String, c: Color, alpha: Float? = null) {
            append("  --").append(name).append(": ").append(if (alpha == null) hex(c) else rgba(c, alpha)).append(";\n")
        }
        v("p", s.primary)
        v("on-p", s.onPrimary)
        v("p-soft", s.secondaryContainer)
        v("seg-on", if (isDark) s.secondaryContainer else s.surfaceContainerLowest)
        if (isDark) {
            v("bg", s.surface)
            v("card", s.surfaceContainer)
            v("raised", s.surfaceContainerHigh)
            v("field", s.surfaceContainerLow)
            v("soft", s.surfaceContainerHigh)
            v("soft2", s.surfaceContainerLow)
        } else {
            v("bg", s.surfaceContainerLow)
            v("card", s.surfaceContainerLowest)
            v("raised", s.surfaceContainerLowest)
            v("field", s.surfaceContainerLowest)
            v("soft", s.surfaceContainer)
            v("soft2", s.surfaceContainerLow)
        }
        v("disabled", s.surfaceContainerHigh)
        v("fg", s.onSurface)
        v("sub", s.onSurfaceVariant)
        v("mute", s.outline)
        v("chip", s.surfaceContainerHighest)
        v("on-chip", s.onSurface)
        v("outline", s.outlineVariant)
        v("line", s.surfaceContainerHigh)
        v("line2", s.surfaceContainerHighest)
        v("toast-bg", s.inverseSurface, .94f)
        v("toast-fg", s.inverseOnSurface)
        v("fade", if (isDark) s.surface else s.surfaceContainerLow, 0f)
        // 播放卡 (见页面样式里「播放卡的配色」)
        v("now-fg", s.onSurface)
        v("now-fg2", s.onSurface, .77f)
        v("now-fg3", s.onSurface, .63f)
        v("now-track", s.onSurface, if (isDark) .22f else .16f)
        v("now-accent-pill", if (isDark) s.onPrimary else s.secondaryContainer, if (isDark) .55f else .84f)
    }

    private fun hex(c: Color): String = "#%06x".format(c.toArgb() and 0xffffff)

    private fun rgba(c: Color, alpha: Float): String {
        val argb = c.toArgb()
        return "rgba(${argb shr 16 and 0xff},${argb shr 8 and 0xff},${argb and 0xff},$alpha)"
    }

    private val READ_TIMEOUT = 2.seconds
}
