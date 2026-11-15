/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 调试接口 `api/debug/ui/key` 的按键串怎么解 (见 [parseDebugUiKeys]). */
class RemoteDebugUiKeysTest {
    private fun parse(spec: String) = parseDebugUiKeys(spec) { name -> OTHER_KEYS[name] }

    private fun press(keyCode: Int, hold: Long = 0) = DebugUiKeyStep.Press(keyCode, hold)

    @Test
    fun `keys are pressed in the order written and a count repeats the key`() {
        assertEquals(
            listOf(
                press(KeyEvent.KEYCODE_DPAD_DOWN),
                press(KeyEvent.KEYCODE_DPAD_DOWN),
                press(KeyEvent.KEYCODE_DPAD_RIGHT),
                press(KeyEvent.KEYCODE_DPAD_RIGHT),
                press(KeyEvent.KEYCODE_DPAD_RIGHT),
                press(KeyEvent.KEYCODE_DPAD_CENTER),
            ),
            parse("DOWN,DOWN, right*3 ok"),
        )
    }

    @Test
    fun `a hold and a count combine in either order`() {
        val twice = listOf(press(KeyEvent.KEYCODE_DPAD_CENTER, 800), press(KeyEvent.KEYCODE_DPAD_CENTER, 800))
        assertEquals(twice, parse("OK@800*2"))
        assertEquals(twice, parse("OK*2@800"))
    }

    @Test
    fun `a pause is written in milliseconds`() {
        assertEquals(
            listOf(press(KeyEvent.KEYCODE_DPAD_LEFT, 1500), DebugUiKeyStep.Pause(200), press(KeyEvent.KEYCODE_BACK)),
            parse("LEFT@1500,200ms,BACK"),
        )
    }

    @Test
    fun `the confirm key has three names and enter is its own key`() {
        assertEquals(parse("OK"), parse("CENTER"))
        assertEquals(parse("OK"), parse("select"))
        assertEquals(listOf(press(KeyEvent.KEYCODE_ENTER)), parse("ENTER"))
    }

    @Test
    fun `other names are looked up and a number is a key code`() {
        assertEquals(listOf(press(KeyEvent.KEYCODE_CAPTIONS)), parse("captions"))
        assertEquals(listOf(press(KeyEvent.KEYCODE_CAPTIONS)), parse("KEYCODE_CAPTIONS"))
        assertEquals(listOf(press(175)), parse("175"))
    }

    @Test
    fun `input that cannot be pressed is rejected`() {
        for (bad in listOf("", " , ", "FOO", "UP*0", "UP**2", "UP@", "UP@10001", "20000ms", "UP*201", "OK@10000*7")) {
            assertFailsWith<DebugUiKeyFormatException>("应该拒收: \"$bad\"") { parse(bad) }
        }
    }

    private companion object {
        val OTHER_KEYS = mapOf("CAPTIONS" to KeyEvent.KEYCODE_CAPTIONS, "KEYCODE_CAPTIONS" to KeyEvent.KEYCODE_CAPTIONS)
    }
}
