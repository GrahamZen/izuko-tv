/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.video.player.R
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import androidx.media3.ui.R as Media3UiR

@OptIn(UnstableApi::class)
class SlimPlayerViewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inflate(): PlayerView = LayoutInflater.from(context).inflate(R.layout.izuko_player_view, null) as PlayerView

    @Test
    fun `slim layout keeps the video surface and the subtitle view`() = runBlocking<Unit>(Dispatchers.Main) {
        val view = inflate()
        assertIs<SurfaceView>(view.videoSurfaceView)
        assertNotNull(view.subtitleView)
        assertNotNull(view.findViewById<View>(Media3UiR.id.exo_content_frame))
    }

    @Test
    fun `slim layout builds no controller and no buffering spinner`() = runBlocking<Unit>(Dispatchers.Main) {
        val view = inflate()
        assertFalse(view.useController)
        assertNull(view.findViewById<View>(Media3UiR.id.exo_controller))
        assertNull(view.findViewById<View>(Media3UiR.id.exo_buffering))
        // 没有控制栏时这些是空操作, 不能崩
        view.hideController()
        view.controllerAutoShow = false
    }
}
