/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 封面右上角的角标 ([TvNativeWallStyle.badge], 新番时间表的「在追」): 卡片带 [TvNativeCard.badge] 的画, 不带的不画; 副标题颜色按卡片给的
 * ([TvNativeCard.subtitleColor]).
 */
class TvNativeCardBadgeTest {
    private val host = TvNativeTestHost()
    private lateinit var grid: TvNativeGridView
    private val badge = TvNativeCardBadgeStyle(
        icon = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) },
        sizePx = 40f,
        insetPx = 10f,
        backgroundColor = Color.RED,
    )

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val metrics = TvNativeGridMetrics(
                columns = 6,
                startPx = 128,
                endPx = 48,
                topBleedPx = 0,
                bottomBleedPx = 0,
                endMarginPx = 60,
                heroLinePx = 0,
                fadeDistancePx = 128f,
            )
            grid = TvNativeGridView(host.activity, testWallStyle().copy(badge = badge), host.sketch, metrics)
            grid.animatedScroll = false
            host.root.addView(grid, FrameLayout.LayoutParams(1920, 1000))
            grid.cards.submit(
                listOf(
                    TvNativeCard(imageUrl = null, title = "在追", subtitle = "12:30", subtitleColor = Color.GREEN, badge = true),
                    TvNativeCard(imageUrl = null, title = "没追", subtitle = "12:30"),
                ),
            ) { it.toLong() }
        }
        host.waitUntil("卡片排出来") { grid.childCount == 2 }
    }

    @AfterTest
    fun tearDown() = host.close()

    /** 第 [index] 张卡的封面画成位图, 取角标圆里、图标外的那一点. */
    private fun badgePixel(index: Int): Int = host.onMain {
        val card = grid.findViewHolderForAdapterPosition(index)!!.itemView as TvNativeCardView
        val cover = (0 until card.childCount).map { card.getChildAt(it) }.first { it is ImageView }
        val bitmap = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888)
        cover.draw(Canvas(bitmap))
        val cx = cover.width - badge.insetPx - badge.sizePx / 2f
        val cy = badge.insetPx + badge.sizePx / 2f
        bitmap.getPixel((cx + 12).toInt(), cy.toInt())
    }

    @Test
    fun `only the card that asks for a badge draws one`() {
        assertEquals(Color.RED, badgePixel(0), "在追的那张画了角标")
        assertEquals(testWallStyle().placeholderColor, badgePixel(1), "另一张那里还是封面 (没图时的底色)")
    }

    @Test
    fun `the subtitle takes the color the card gives`() {
        val colors = host.onMain {
            (0..1).map { index ->
                val card = grid.findViewHolderForAdapterPosition(index)!!.itemView as TvNativeCardView
                val texts = ArrayList<TvNativeTextView>()
                fun collect(v: View) {
                    if (v is TvNativeTextView) texts += v
                    if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
                }
                collect(card)
                texts.first { it.text.toString() == "12:30" }.currentTextColor
            }
        }
        assertEquals(listOf(Color.GREEN, testWallStyle().subtitleColor), colors)
    }
}
