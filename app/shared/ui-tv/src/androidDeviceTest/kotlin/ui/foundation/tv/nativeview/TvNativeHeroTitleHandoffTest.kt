/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * hero 文字块的标题交权 ([TvNativeHeroTextView.setTitleHandoff]): 缩回转场期间标题归转场层画 (隐藏), 交还时重新显示 ——
 * 文字正在分行进场时也一样. 进过播放器再缩回探索页时, 列表页在缩回层下重建、文字刚进场, 交还正落在进场途中.
 */
class TvNativeHeroTitleHandoffTest {
    private val host = TvNativeTestHost()
    private lateinit var heroText: TvNativeHeroTextView
    private lateinit var title: View
    private lateinit var summary: View

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            heroText = TvNativeHeroTextView(host.activity, testHeroTextStyle().copy(animated = true, stagger = true)).apply {
                titleWidthPx = 800
                summaryWidthPx = 800
            }
            host.root.addView(heroText, FrameLayout.LayoutParams(1200, FrameLayout.LayoutParams.WRAP_CONTENT))
            // 行的顺序: 标题, 信息行, 下一集行, 简介
            title = heroText.getChildAt(0)
            summary = heroText.getChildAt(3)
        }
        host.waitUntil("文字块排出来") { heroText.isLaidOut }
    }

    @AfterTest
    fun tearDown() = host.close()

    /** 从空进场 (简介比标题晚两档滑入), 进场时标题已交给转场层. 在主线程上调. */
    private fun enterWithTitleHandedOff() {
        heroText.setText(null, TvNativeTextTransition.Reset)
        heroText.setText(TEXT, TvNativeTextTransition.Key)
        heroText.setTitleHandoff(hidden = true, offsetX = 0f, offsetY = 0f, settling = true)
    }

    private fun waitUntilSettled() = host.waitUntil("各行进场完") { summary.translationX == 0f && summary.alpha == 1f }

    @Test
    fun `the title shows again when handed back after its own slide-in but before the later lines finish`() {
        // 这段只有两档错开那么长 (约 80ms), 模拟器卡一下就整段错过: 错过了从头再进场一次
        repeat(ATTEMPTS) {
            var released = false
            host.onMain {
                enterWithTitleHandedOff()
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (title.translationX == 0f && summary.translationX > 0f) {
                            // 标题滑完 (位移归零) 而简介还在滑
                            heroText.setTitleHandoff(hidden = false, offsetX = 0f, offsetY = 0f, settling = false)
                            released = true
                        } else if (summary.translationX > 0f) {
                            choreographer.postFrameCallback(this)
                        }
                    }
                })
            }
            waitUntilSettled()
            if (host.onMain { released }) {
                assertEquals(1f, host.onMain { title.alpha })
                return
            }
        }
        fail("试了 $ATTEMPTS 次, 交还都没落在标题滑完、简介还在滑的那几帧里")
    }

    @Test
    fun `a title still handed off stays hidden after the slide-in`() {
        host.onMain { enterWithTitleHandedOff() }
        waitUntilSettled()
        assertEquals(0f, host.onMain { title.alpha })
        host.onMain { heroText.setTitleHandoff(hidden = false, offsetX = 0f, offsetY = 0f, settling = false) }
        assertEquals(1f, host.onMain { title.alpha })
    }

    private companion object {
        const val ATTEMPTS = 5

        val TEXT = TvNativeHeroText(
            subjectId = 1,
            title = "城下町的蒲公英",
            infoReady = true,
            rating = "6.7",
            summary = "鲇濑花莲是樱田茜的青梅竹马和好朋友。",
        )
    }
}
