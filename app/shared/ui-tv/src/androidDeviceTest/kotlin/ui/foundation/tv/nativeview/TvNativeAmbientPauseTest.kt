/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 原生页面的跑马灯 (hero 标题、下一集集名) 在页面不在前台 (被放大进来的详情页盖着, 视图仍附着) 时由 [TvNativeHost] 暂停, 回前台恢复.
 * 跑马灯按选中态跑、不看焦点: 不停的话盖着的文字每帧失效, 整个窗口跟着逐帧重画.
 */
class TvNativeAmbientPauseTest {
    private val host = TvNativeTestHost()
    private val foreground = mutableStateOf(true)
    private lateinit var heroText: TvNativeHeroTextView

    /** 装一块 hero 文字的原生页面, 同探索页 / 网格页的做法把暂停转给文字块. */
    private class Page(context: Context, val heroText: TvNativeHeroTextView) : FrameLayout(context), TvNativeAmbientAnimations {
        init {
            addView(heroText, LayoutParams(1200, LayoutParams.WRAP_CONTENT))
        }

        override fun setAmbientAnimationsPaused(paused: Boolean) {
            heroText.marqueePaused = paused
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(LocalPageIsForeground provides foreground) {
                    TvNativeHost(
                        factory = { context ->
                            heroText = TvNativeHeroTextView(context, testHeroTextStyle().copy(marqueeRepeat = -1)).apply {
                                titleWidthPx = 500
                                summaryWidthPx = 500
                                setText(
                                    TvNativeHeroText(
                                        subjectId = 1,
                                        title = LONG_TEXT,
                                        infoReady = true,
                                        status = TvNativeHeroStatus("第 3 话", LONG_TEXT, null, Color.WHITE),
                                    ),
                                    TvNativeTextTransition.Reset,
                                )
                                setTitleMarquee(true)
                            }
                            Page(context, heroText)
                        },
                        update = {},
                    )
                }
            }
        }
        host.waitUntil("hero 文字排出来") { heroText.isLaidOut }
    }

    @AfterTest
    fun tearDown() = host.close()

    /** 在跑马灯的文字数 (选中态); waitUntil 的条件本来就在主线程上调. */
    private fun selectedTexts(): Int = countSelected(heroText)

    private fun countSelected(view: View): Int = when (view) {
        is TextView -> if (view.isSelected) 1 else 0
        is ViewGroup -> (0 until view.childCount).sumOf { countSelected(view.getChildAt(it)) }
        else -> 0
    }

    @Test
    fun `marquees run while the page is foreground`() {
        assertEquals(2, host.onMain { selectedTexts() }, "标题与下一集集名都应在跑马灯")
    }

    @Test
    fun `marquees pause while the page is covered and resume when it is foreground again`() {
        host.onMain { foreground.value = false }
        host.waitUntil("盖住后跑马灯全停") { selectedTexts() == 0 }
        host.onMain { foreground.value = true }
        host.waitUntil("回到前台后跑马灯恢复") { selectedTexts() == 2 }
    }

    private companion object {
        const val LONG_TEXT = "一个很长很长的番剧标题, 定宽一行放不下, 于是要靠跑马灯把后半截滚出来给人看"
    }
}
