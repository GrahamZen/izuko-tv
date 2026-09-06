/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.LocalPlatformFontFamily
import me.him188.ani.app.ui.foundation.PlatformFontFamily
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 引导最后一步「外观与操作」: 一进来焦点在当前选的那张「海报上按确定」卡上, 按确定当场写设置;
 * 选了直接播放 / 直接进详情页时模糊背景那一组隐去且够不着, 底下一排从左走到右只碰得到颜色、视觉效果与「开始使用」.
 * 每条测试顺带在测试包的 cache 目录里存截图 (onboarding-theme-*.png), 看排版用.
 */
class TvOnboardingThemeStepTest {
    private val host = TvNativeTestHost()
    private var settings by mutableStateOf(ThemeSettings.Default)
    private val writes = mutableListOf<Set<String>>()
    private var finished = false

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalThemeSettings provides settings,
                    LocalPlatformFontFamily provides PlatformFontFamily(null),
                ) {
                    AniTheme {
                        val focus = rememberTvFocusScope()
                        OnboardingSurface(focus, Modifier) {
                            ThemeStep(
                                focus,
                                onUpdate = { transform ->
                                    writes += writtenFields(transform)
                                    settings = settings.transform()
                                },
                                onFinished = { finished = true },
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    @AfterTest
    fun tearDown() {
        host.close()
    }

    @Test
    fun `confirm on a card writes the setting`() {
        saveShot("onboarding-theme-hero-dark.png")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        assertEquals(TvPosterConfirmAction.Play, settings.tvPosterConfirm)
        saveShot("onboarding-theme-play-dark.png")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        assertEquals(TvPosterConfirmAction.Details, settings.tvPosterConfirm)
    }

    @Test
    fun `bottom row reaches the blur group only while showing the summary first`() {
        val hero = walkBottomRow()
        assertTrue("blur" in hero, "先看简介时够得着模糊背景: $hero")
        saveShot("onboarding-theme-hero-light.png")

        // 回到第一行选直接播放, 再走一遍
        host.onMain {
            settings = settings.copy(darkMode = DarkMode.DARK, tvPosterConfirm = TvPosterConfirmAction.Play)
            finished = false
        }
        writes.clear()
        repeat(2) { host.press(KeyEvent.KEYCODE_DPAD_UP) }
        settle()
        val play = walkBottomRow()
        assertFalse("blur" in play, "直接播放时模糊背景够不着: $play")
        assertTrue("dark" in play && "effects" in play, "颜色与视觉效果照样够得着: $play")
    }

    /** 下到底下一排, 最左起一格一格往右按确定, 直到按到「开始使用」. 返回碰到的设置种类. */
    private fun walkBottomRow(): Set<String> {
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(10) { host.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(12) {
            if (finished) return writes.flatten().toSet()
            host.press(KeyEvent.KEYCODE_DPAD_CENTER)
            settle()
            host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        assertTrue(finished, "走到底没按到「开始使用」: $writes")
        return writes.flatten().toSet()
    }

    private fun settle() {
        host.instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun saveShot(name: String) {
        val shot = host.windowShot()
        File(host.activity.cacheDir, name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        /** 这次写动了哪几项: 拿两份取值相反的设置各套一遍, 写同值的也认得出来. */
        fun writtenFields(transform: ThemeSettings.() -> ThemeSettings): Set<String> = buildSet {
            for (probe in listOf(
                ThemeSettings.Default.copy(darkMode = DarkMode.AUTO, tvHeroBlurBackdrop = true),
                ThemeSettings.Default.copy(darkMode = DarkMode.AUTO, tvHeroBlurBackdrop = false),
            )) {
                val written = probe.transform()
                if (written.darkMode != probe.darkMode) add("dark")
                if (written.tvHeroBlurBackdrop != probe.tvHeroBlurBackdrop) add("blur")
                if (written.tvVisualEffects != probe.tvVisualEffects) add("effects")
            }
        }
    }
}
