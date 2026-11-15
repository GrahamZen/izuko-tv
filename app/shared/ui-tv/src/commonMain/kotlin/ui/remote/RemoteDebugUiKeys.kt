/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** 调试接口 `api/debug/ui/key` 的一步: 按一个键 (可以按住), 或者停一会儿. */
internal sealed interface DebugUiKeyStep {
    /** @param holdMillis 0 = 按一下; 大于 0 = 按住这么久, 期间照系统的节奏连发 */
    data class Press(val keyCode: Int, val holdMillis: Long = 0) : DebugUiKeyStep

    data class Pause(val millis: Long) : DebugUiKeyStep
}

internal class DebugUiKeyFormatException(message: String) : IllegalArgumentException(message)

/**
 * 解析一串按键: 逗号或空白隔开, 每一项是 `键名`、`键名*次数`、`键名@按住的毫秒数` (两个后缀能一起写, 不分先后), 或 `300ms` (停这么久).
 * 例: `DOWN,DOWN,RIGHT*3,OK`、`OK@800`、`LEFT@1500,200ms,BACK`.
 *
 * 键名不分大小写: UP DOWN LEFT RIGHT、OK (同 CENTER / SELECT, 遥控器的确定键)、ENTER、BACK、MENU、PLAY (播放暂停)、FF、REW、NEXT、PREV、STOP;
 * 别的名字照 `KEYCODE_<名字>` 查 ([resolveOther]), 纯数字是键值.
 *
 * @throws DebugUiKeyFormatException 写得不对、不认识的键名, 或超过上限 (见 [DEBUG_UI_KEYS_MAX_PRESSES] 等)
 */
internal fun parseDebugUiKeys(spec: String, resolveOther: (String) -> Int? = ::keyCodeByName): List<DebugUiKeyStep> {
    val steps = ArrayList<DebugUiKeyStep>()
    var total = 0L
    for (token in spec.split(',', ' ', '\t', '\n').filter { it.isNotEmpty() }) {
        val pause = PAUSE.matchEntire(token)
        if (pause != null) {
            val millis = pause.groupValues[1].toLongOrNull() ?: throw DebugUiKeyFormatException("停顿太长: $token")
            if (millis > DEBUG_UI_KEYS_MAX_HOLD_MILLIS) throw DebugUiKeyFormatException("停顿太长: $token (最长 $DEBUG_UI_KEYS_MAX_HOLD_MILLIS 毫秒)")
            steps += DebugUiKeyStep.Pause(millis)
            total += millis
        } else {
            val match = PRESS.matchEntire(token) ?: throw DebugUiKeyFormatException("看不懂这一项: $token")
            val name = match.groupValues[1].uppercase()
            val keyCode = KEY_NAMES[name] ?: name.toIntOrNull() ?: resolveOther(name) ?: throw DebugUiKeyFormatException("不认识的键: $name")
            var count = 1
            var hold = 0L
            for (suffix in SUFFIX.findAll(match.groupValues[2])) {
                val number = suffix.groupValues[2].toLongOrNull() ?: throw DebugUiKeyFormatException("数字太大: $token")
                if (suffix.groupValues[1] == "*") count = number.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else hold = number
            }
            if (count < 1) throw DebugUiKeyFormatException("次数要大于 0: $token")
            if (hold > DEBUG_UI_KEYS_MAX_HOLD_MILLIS) throw DebugUiKeyFormatException("按住太久: $token (最长 $DEBUG_UI_KEYS_MAX_HOLD_MILLIS 毫秒)")
            if (steps.size + count > DEBUG_UI_KEYS_MAX_PRESSES) throw DebugUiKeyFormatException("一次最多 $DEBUG_UI_KEYS_MAX_PRESSES 步")
            repeat(count) { steps += DebugUiKeyStep.Press(keyCode, hold) }
            total += hold * count
        }
        if (steps.size > DEBUG_UI_KEYS_MAX_PRESSES) throw DebugUiKeyFormatException("一次最多 $DEBUG_UI_KEYS_MAX_PRESSES 步")
        if (total > DEBUG_UI_KEYS_MAX_TOTAL_MILLIS) throw DebugUiKeyFormatException("按住与停顿加起来最长 $DEBUG_UI_KEYS_MAX_TOTAL_MILLIS 毫秒")
    }
    if (steps.isEmpty()) throw DebugUiKeyFormatException("没有按键")
    return steps
}

internal const val DEBUG_UI_KEYS_MAX_PRESSES = 200
internal const val DEBUG_UI_KEYS_MAX_HOLD_MILLIS = 10_000L
internal const val DEBUG_UI_KEYS_MAX_TOTAL_MILLIS = 60_000L

private val PAUSE = Regex("""(\d+)ms""", RegexOption.IGNORE_CASE)
private val PRESS = Regex("""([A-Za-z0-9_]+)((?:[*@]\d+)*)""")
private val SUFFIX = Regex("""([*@])(\d+)""")

private val KEY_NAMES = mapOf(
    "UP" to KeyEvent.KEYCODE_DPAD_UP,
    "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
    "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
    "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
    "CENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
    "SELECT" to KeyEvent.KEYCODE_DPAD_CENTER,
    "ENTER" to KeyEvent.KEYCODE_ENTER,
    "BACK" to KeyEvent.KEYCODE_BACK,
    "MENU" to KeyEvent.KEYCODE_MENU,
    "PLAY" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    "PLAYPAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    "FF" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    "REW" to KeyEvent.KEYCODE_MEDIA_REWIND,
    "NEXT" to KeyEvent.KEYCODE_MEDIA_NEXT,
    "PREV" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    "STOP" to KeyEvent.KEYCODE_MEDIA_STOP,
)

private fun keyCodeByName(name: String): Int? =
    KeyEvent.keyCodeFromString(if (name.startsWith("KEYCODE_")) name else "KEYCODE_$name").takeIf { it != KeyEvent.KEYCODE_UNKNOWN }

/** 按键送不出去: 此刻收按键的窗口不是本应用的 (应用不在前台). */
internal class DebugUiNotInFrontException : IllegalStateException("the app has no window taking keys")

/** 按键从哪条路送进界面, 见 [RemoteDebugUiKeys]. */
internal enum class DebugUiKeyPath(val id: String) {
    /** 系统的输入通道. */
    SYSTEM("system"),

    /** 应用里直接交给收按键的窗口. */
    APP("app"),
}

/**
 * 应用给自己送遥控器的按键, 两条路:
 *
 * - [DebugUiKeyPath.SYSTEM] 系统的输入通道 (同遥控器: 输入分发 → 收按键的那个窗口 → 焦点所在的视图链). 安卓 12 及以前系统许应用往自己的
 *   窗口送; 安卓 13 起要系统权限, 应用拿不到.
 * - [DebugUiKeyPath.APP] 在应用里照系统窗口分发按键的步骤做一遍 ([dispatchInApp]): 交给收按键的那个窗口的根视图 (主页面、弹窗都一样),
 *   没人要的方向键照系统的办法找下一个焦点. 不经系统的输入分发与输入法: 输入法开着时真遥控器的键先给输入法, 这条路直接给界面.
 *
 * 不指定时系统通道能用就用它 ([defaultPath]). 都不能在主线程上调 (要等按键处理完).
 */
internal object RemoteDebugUiKeys {
    private val instrumentation = Instrumentation()

    /** 遥控器的方向键报的来源. */
    private const val SOURCE = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD

    val defaultPath: DebugUiKeyPath
        get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) DebugUiKeyPath.SYSTEM else DebugUiKeyPath.APP

    /**
     * 按一下 [keyCode]; [holdMillis] 大于 0 时按住这么久再抬起, 期间照系统的节奏连发 (先等 [ViewConfiguration.getKeyRepeatTimeout],
     * 之后每 [ViewConfiguration.getKeyRepeatDelay] 一发, 第一发带长按标记), 与真按住遥控器时应用收到的一样.
     *
     * @param path 指定走哪条路; null 时走 [defaultPath], 系统通道被拒就改走应用里那条
     * @return 实际走的那条路
     * @throws DebugUiNotInFrontException 应用不在前台
     */
    fun press(keyCode: Int, holdMillis: Long = 0, path: DebugUiKeyPath? = null): DebugUiKeyPath {
        var used = path ?: defaultPath
        val downTime = SystemClock.uptimeMillis()
        if (used == DebugUiKeyPath.SYSTEM) {
            try {
                send(used, downTime, KeyEvent.ACTION_DOWN, keyCode, repeat = 0)
            } catch (e: SecurityException) {
                if (path != null) throw DebugUiNotInFrontException()
                used = DebugUiKeyPath.APP
            }
        }
        if (used == DebugUiKeyPath.APP) {
            leaveTouchMode()
            send(used, downTime, KeyEvent.ACTION_DOWN, keyCode, repeat = 0)
        }
        if (holdMillis > 0) {
            val end = downTime + holdMillis
            var next = downTime + ViewConfiguration.getKeyRepeatTimeout()
            val delay = ViewConfiguration.getKeyRepeatDelay().coerceAtLeast(1)
            var repeat = 0
            val taking = onMain { keyWindowRoot() }
            while (next <= end) {
                sleepUntil(next)
                // 按住的中途拿按键的窗口换了 (长按打开了弹窗): 同系统, 连发到此为止, 新窗口只收到抬起
                if (onMain { keyWindowRoot() } !== taking) break
                repeat++
                if (!send(used, downTime, KeyEvent.ACTION_DOWN, keyCode, repeat)) return used
                // 应用处理得慢时不补发落下的那几发 (系统也是处理完上一发才发下一发)
                next = maxOf(next + delay, SystemClock.uptimeMillis())
            }
            sleepUntil(end)
        }
        send(used, downTime, KeyEvent.ACTION_UP, keyCode, repeat = 0)
        return used
    }

    /** @return 这一下按键还接着发: 应用内通道上按下时拿着按键的窗口中途不再拿按键时为 false (见 [gestureRootFor]) */
    private fun send(path: DebugUiKeyPath, downTime: Long, action: Int, keyCode: Int, repeat: Int): Boolean {
        val longPress = if (action == KeyEvent.ACTION_DOWN && repeat == 1) KeyEvent.FLAG_LONG_PRESS else 0
        val now = SystemClock.uptimeMillis()
        return when (path) {
            DebugUiKeyPath.SYSTEM -> try {
                instrumentation.sendKeySync(KeyEvent(downTime, now, action, keyCode, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, longPress, SOURCE))
                true
            } catch (e: SecurityException) {
                // 抬起送不进去 (按住的中途应用被切到后台) 时界面那边由系统补取消, 这里照不在前台报
                if (action == KeyEvent.ACTION_DOWN && repeat == 0) throw e
                throw DebugUiNotInFrontException()
            }

            DebugUiKeyPath.APP -> {
                val event = KeyEvent(
                    downTime, now, action, keyCode, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, longPress or KeyEvent.FLAG_FROM_SYSTEM, SOURCE,
                )
                onMain { dispatchInApp(event) }
            }
        }
    }

    /**
     * 系统窗口 (ViewRootImpl) 分发一个按键的步骤, 在主线程上照做: 先给输入法之前的那一层, 再交给窗口的根视图 (主页面与弹窗的根视图转给
     * 页面 / 弹窗自己, 再到焦点所在的视图链); 没人要的方向键按下时照系统的办法挪焦点 —— 从拿着焦点的视图朝那个方向找下一个, 没有东西拿着
     * 焦点时落到默认的那个.
     */
    private fun dispatchInApp(event: KeyEvent): Boolean {
        val root = gestureRootFor(event) ?: return false
        if (root.dispatchKeyEventPreIme(event)) return true
        if (root.dispatchKeyEvent(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return true
        }
        val focused = root.findFocus()
        if (focused == null) {
            root.restoreDefaultFocus()
            return true
        }
        val next = focused.focusSearch(direction)
        if (next != null && next !== focused) {
            // 上一个焦点的位置换到新焦点的坐标里, 新焦点据此决定落在自己里面的哪一处
            val rect = Rect()
            focused.getFocusedRect(rect)
            if (root is ViewGroup) {
                root.offsetDescendantRectToMyCoords(focused, rect)
                root.offsetRectIntoDescendantCoords(next, rect)
            }
            if (next.requestFocus(direction, rect)) return true
        }
        root.dispatchUnhandledMove(focused, direction)
        return true
    }

    /** 应用内通道上正按着的这一下落在哪个窗口 (按下时拿着按键的那个) */
    private var gestureRoot: View? = null

    /**
     * 这个按键事件给哪个窗口: 按下给此刻拿着按键的窗口, 这一下的连发与抬起都给同一个. 按住的中途它不再拿按键 (长按打开了弹窗、窗口关了)
     * 时同系统: 给它补一个取消的抬起, 连发到此为止 (回 null); 抬起交给现在拿着按键的窗口 —— 它没见过这一下的按下, 理不理由它自己定
     * (真遥控器上也是这样, 应用里靠吞掉残余按键的那一层挡着).
     *
     * @throws DebugUiNotInFrontException 按下时没有窗口拿着按键
     */
    private fun gestureRootFor(event: KeyEvent): View? {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val root = keyWindowRoot() ?: throw DebugUiNotInFrontException()
            gestureRoot = root
            return root
        }
        val up = event.action == KeyEvent.ACTION_UP
        val root = gestureRoot ?: return if (up) keyWindowRoot() else null
        if (up) gestureRoot = null
        if (root.isAttachedToWindow && root.hasWindowFocus()) return root
        gestureRoot = null
        if (root.isAttachedToWindow) {
            root.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), event.flags or KeyEvent.FLAG_CANCELED))
        }
        return if (up) keyWindowRoot() else null
    }

    /** 此刻拿着按键的窗口的根视图 (在主线程上读); 没有为 null */
    private fun keyWindowRoot(): View? = RemoteDebugUiReader.windowRoots().lastOrNull { it.isAttachedToWindow && it.hasWindowFocus() }

    private fun <T> onMain(block: () -> T): T = runBlocking { withTimeout(MAIN_THREAD_TIMEOUT) { withContext(Dispatchers.Main) { block() } } }

    /** 真按键会让窗口退出触摸模式 (触摸模式下多数视图拿不了焦点); 应用里那条路不经系统, 自己退. 系统只许拿着焦点窗口的应用这么做. */
    private fun leaveTouchMode() {
        val inTouchMode = runBlocking {
            withTimeout(MAIN_THREAD_TIMEOUT) {
                withContext(Dispatchers.Main) { RemoteDebugUiReader.windowRoots().any { it.hasWindowFocus() && it.isInTouchMode } }
            }
        }
        if (inTouchMode) runCatching { instrumentation.setInTouchMode(false) }
    }

    private fun sleepUntil(uptimeMillis: Long) {
        val wait = uptimeMillis - SystemClock.uptimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
    }

    private val MAIN_THREAD_TIMEOUT = 5.seconds
}
