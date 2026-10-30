/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import android.graphics.Color as AndroidColor

/**
 * [TvDanmakuAroundVideo]: 画不了单独弹幕层的设备上, 弹幕退回画在界面里, 要在视频面之后组合 —— 视频面在窗口上挖洞,
 * 画在它之前的界面内容被清成透明, 弹幕整层看不见.
 *
 * 视频面用一块真的 [SurfaceView] (PlayerView 的视频面就是它), 弹幕用一块整屏纯色; 截本窗口看纯色还在不在.
 * 第二条是对照: 摆在视频面之前的界面内容确实被清掉, 说明第一条不是白过.
 */
class TvDanmakuAroundVideoTest {
    private val host = TvNativeTestHost()

    @BeforeTest
    fun setUp() = host.launch()

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `danmaku drawn in the window stays visible above the video surface`() {
        show(danmakuOnOwnSurface = false)
        assertTrue(isMark(host.windowShot().center()), "界面里画的弹幕在视频面之后组合, 窗口上看得见")
    }

    @Test
    fun `window content composed before the video surface is cleared`() {
        show(danmakuOnOwnSurface = true)
        assertFalse(isMark(host.windowShot().center()), "摆在视频面之前的界面内容被视频面挖的洞清掉")
    }

    private fun show(danmakuOnOwnSurface: Boolean) {
        var videoDrawn = false
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    TvDanmakuAroundVideo(
                        danmaku = { Box(Modifier.matchParentSize().background(MARK)) },
                        video = {
                            AndroidView(
                                factory = { context -> SurfaceView(context).apply { holder.addCallback(FillOnCreate { videoDrawn = true }) } },
                                modifier = Modifier.matchParentSize(),
                            )
                        },
                        danmakuOnOwnSurface = danmakuOnOwnSurface,
                    )
                }
            }
        }
        host.waitUntil("视频面画了第一帧") { videoDrawn }
        // 视频面画过之后的下一帧窗口才挖洞
        SystemClock.sleep(500)
        host.instrumentation.waitForIdleSync()
    }

    /** 表面一建好就涂一帧蓝色: 视频面画过才在窗口上挖洞. */
    private class FillOnCreate(private val onDrawn: () -> Unit) : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val canvas = holder.lockCanvas() ?: return
            canvas.drawColor(AndroidColor.BLUE)
            holder.unlockCanvasAndPost(canvas)
            onDrawn()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {}
    }

    private fun Bitmap.center(): Int = getPixel(width / 2, height / 2)

    private fun isMark(pixel: Int): Boolean =
        AndroidColor.alpha(pixel) > 200 && AndroidColor.red(pixel) > 200 &&
            AndroidColor.green(pixel) < 60 && AndroidColor.blue(pixel) < 60

    private companion object {
        val MARK = Color.Red
    }
}
