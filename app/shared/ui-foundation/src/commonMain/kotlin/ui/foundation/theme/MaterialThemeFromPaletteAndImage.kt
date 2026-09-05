/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.kmpalette.color
import com.kmpalette.generatePalette
import com.kmpalette.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.ui.foundation.resize
import me.him188.ani.app.ui.foundation.themeColor

/**
 * Generate a [MaterialTheme] from a [Palette].
 *
 * @receiver The [Palette] to generate from.
 * @return Generated [MaterialTheme]
 */
@Composable
fun MaterialThemeFromPaletteAndImage(
    palette: Palette?,
    image: ImageBitmap? = null,
    /**
     * 主色的缓存键 (如条目 id); null = 不缓存.
     *
     * 取色是异步的 (调色板 + 位图缩放都在后台线程), 取到之前这里退回应用主题色 —— 于是每次
     * **重新进入**同一个页面都要"先主题色再跳成动态色"闪一下 (从设置页/播放页返回时最明显,
     * 那时页面被返回栈重建). 同一个键取过的主色记在进程里 ([SubjectSeedColorCache]),
     * 再进来第一帧就是对的.
     */
    cacheKey: Any? = null,
    content: @Composable () -> Unit
) {
    val themeSettings = LocalThemeSettings.current
    val isDark = when (themeSettings.darkMode) {
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        DarkMode.AUTO -> isSystemInDarkTheme()
    }
    val useBlackBackground = themeSettings.useBlackBackground

    // **缓存的是取出来的主色, 不是算好的配色**: 主色与深浅色/纯黑背景无关, 键只要条目 id;
    // 而 dynamicColorScheme 是纯函数, 有了主色就能在组合里同步算出来 —— 于是命中缓存时第一帧
    // 就是对的, 连异步那一步都绕过去了 (原来存配色的话, 键还得带上那两档, 切档就得重算)
    var seedColor by remember(cacheKey) {
        mutableStateOf(cacheKey?.let { SubjectSeedColorCache[it] })
    }

    LaunchedEffect(palette, image, cacheKey) {
        val primaryColor = palette?.vibrantSwatch?.color
            ?: image?.subjectSeedColor()
            ?: return@LaunchedEffect

        seedColor = primaryColor
        // 命中缓存时这一步照跑: 封面换了 (或上次取色时图还没解码完) 就在这里自愈
        cacheKey?.let { SubjectSeedColorCache[it] = primaryColor }
    }

    // 纯函数, 但**必须包 remember**: 不包的话每次重组都要重算一整套配色
    val colorScheme = seedColor?.let { primary ->
        remember(primary, isDark, useBlackBackground) {
            dynamicColorScheme(
                primary = primary,
                isDark = isDark,
                isAmoled = useBlackBackground,
                style = PaletteStyle.TonalSpot,
                modifyColorScheme = { colorScheme ->
                    modifyColorSchemeForBlackBackground(
                        colorScheme,
                        isDark,
                        useBlackBackground,
                    )
                },
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme ?: MaterialTheme.colorScheme,
        content = content,
    )
}

/**
 * 从一张图里取出"这个条目的主色" —— **提前取色与详情页自己算必须共用这一个函数**.
 *
 * 优先级与 [MaterialThemeFromPaletteAndImage] 里那条一致: 调色板的 vibrant swatch, 取不到
 * (灰度图/低饱和图常常没有) 才退回 [themeColor]. 两处算法不一致的话, 提前算好的色会在页面
 * 自己算完之后被换掉 —— 观感就是"进去先一个色, 过一下变成另一个色" (2026-09-05 真机踩到).
 *
 * 全程在 [Dispatchers.Default] 上 (kmpalette 自己也切到那里).
 */
suspend fun ImageBitmap.subjectSeedColor(): Color =
    runCatching { generatePalette().vibrantSwatch?.color }.getOrNull()
        ?: withContext(Dispatchers.Default) { resize(64, 64).themeColor() }

/**
 * 条目主色的进程内缓存.
 *
 * 只存**取出来的那一个主色**: 它与深浅色/纯黑背景无关, 所以键只要条目 id; 配色由 [dynamicColorScheme]
 * 同步算出来. 位图与调色板一概不存 —— 那两个大得多, 而重算它们的目的就是为了得到这一个色.
 *
 * 两个写入点:
 * - 详情页自己取到色之后 (见 [MaterialThemeFromPaletteAndImage] 的 cacheKey);
 * - **电视端卡片聚焦停稳时提前算** (见 TvPortraitCard 的 themeSeedSubjectId) —— 于是点进去
 *   第一帧就是对的, 不再"先主题色再跳动态色".
 *
 * 两处必须**用同一张图**(竖版封面), 否则会从"跳一次"变成"跳成另一个色".
 *
 * 32 条足够覆盖"来回翻几个条目"; 满了按插入顺序丢最旧的. **只活在进程里**: 重开应用后每个条目
 * 要重算一次 (那一次由卡片聚焦兜住, 通常也看不见).
 *
 * 只在组合与其效应里访问 (主线程), 不加锁.
 */
object SubjectSeedColorCache {
    private const val MAX_ENTRIES = 32
    private val colors = mutableMapOf<Any, Color>()
    private val order = ArrayDeque<Any>()

    operator fun get(key: Any): Color? = colors[key]

    operator fun set(key: Any, value: Color) {
        if (colors.put(key, value) == null) {
            order.addLast(key)
            while (order.size > MAX_ENTRIES) colors.remove(order.removeFirst())
        }
    }
}
