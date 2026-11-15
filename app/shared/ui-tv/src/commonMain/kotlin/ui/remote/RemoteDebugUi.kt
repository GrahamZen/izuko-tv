/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.widgets.ToastHistory
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.net.URLDecoder
import kotlin.time.Duration.Companion.seconds

/**
 * 控制台调试操作里通用的界面接口 (`api/debug/ui...`, 由 [RemoteDebugActions] 转来): 测试脚本不看截图就知道屏上有什么、焦点在哪,
 * 不经 adb 就能按遥控器的键. 哪一页都能用, 不用页面登记自己; 页面专用的接口 (如 [RemoteDebugSettingsPage]) 照旧.
 *
 * - `GET api/debug/ui` [`brief=1`]: 屏上每个窗口 (最上面的在前) 里的元素 —— 文字、描述、角色、能不能聚焦、选中 / 勾选 / 停用、位置,
 *   `focus` 是焦点所在的那个 (连同它同一排的 `row`), `toasts` 是最近弹过的提示. `brief=1` 时每个窗口只给打头的几段字.
 *   怎么读的、读不到什么见 [RemoteDebugUiReader].
 * - `POST api/debug/ui/key` `keys=DOWN,RIGHT*3,OK@800` [`expect=<文字>`] [`gap=<毫秒>`] [`path=system|app`] [`timeout=<毫秒>`] [`full=1`]: 照顺序按键
 *   (写法见 [parseDebugUiKeys]). 给了 `expect` 时先核对: 此刻焦点上的字里有这段才按, 没有就一下都不按、回失败 (晚弹出来的提示会把焦点抢走). 按键走系统的输入通道, 系统不许时 (安卓 13 起) 在应用里直接交给收按键的窗口, `path` 能指定 (两条路见
 *   [RemoteDebugUiKeys]). 每按一下等界面停稳再按下一下, 给了 `gap` 就改成固定隔这么久; 按完等界面停稳, 回答同 `GET api/debug/ui?brief=1`
 *   (`full=1` 时是整份), 另有 `sent` (按了几下)、`keyPath` (走的哪条路) 与 `settled` (`timeout` 内停没停稳, 默认 2 秒).
 * - `POST api/debug/ui/click` `text=<文字>` [`exact=1`] [`nth=<第几个, 从 0 起>`] [`press=0`] [`path`] [`timeout`] [`full=1`]: 按文字找一个能聚焦的
 *   元素 (整段相同的先于只是包含的), 把焦点送上去再按确定; `press=0` 只送焦点. 焦点没落到它上面时不按 (确定键会按在别的东西上), 回失败.
 *   焦点是直接送上去的, 不是一步步走过去的: 只用来摆状态, 测焦点怎么走要用 `key`. 回答同 `key`, 另有 `matches` (找到几个) 与 `via`
 *   (focus = 送焦点后按确定, click = 元素不能聚焦、直接点的).
 * - `GET / POST api/debug/ui/wait` `text=<文字>` 或 `gone=<文字>` [`timeout=<毫秒>`] [`full=1`]: 等屏上 (或刚弹的提示里) 出现这段文字, 或等它消失;
 *   回答同 `GET api/debug/ui?brief=1`, 另有 `found` 与 `waitedMs`. 默认等 5 秒.
 *
 * 屏上带令牌的控制台地址在回答里一律遮掉 (见 [maskConsoleToken]).
 */
internal object RemoteDebugUi {
    private val logger = logger<RemoteDebugUi>()

    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        val params = if (post) request.formFields() else request.queryParams()
        return when {
            request.path == "api/debug/ui" && get -> describe(read(), full = params["brief"] != "1")
            request.path == "api/debug/ui/key" && post -> key(params)
            request.path == "api/debug/ui/click" && post -> click(params)
            request.path == "api/debug/ui/wait" && (get || post) -> wait(params)
            else -> null
        }
    }

    private fun key(params: Map<String, String>): JsonObject {
        val steps = try {
            parseDebugUiKeys(params["keys"].orEmpty())
        } catch (e: DebugUiKeyFormatException) {
            return failure(e.message.orEmpty())
        }
        val gap = params["gap"]?.toLongOrNull()?.coerceIn(0, DEBUG_UI_KEYS_MAX_HOLD_MILLIS)
        val path = params.keyPath() ?: return failure("path 只能是 system 或 app")
        val before = readInFront() ?: return failure(NOT_IN_FRONT)
        val expect = params["expect"].orEmpty()
        if (expect.isNotEmpty()) {
            // 按之前核对焦点: 晚弹出来的提示、弹窗会把焦点抢走, 照原来想的按下去就按在别的东西上了
            val focused = before.focus()?.second
            if (focused == null || (expect !in focused.text && expect !in focused.desc.orEmpty())) {
                return failure("焦点不在「$expect」上, 没有按") {
                    put("sent", 0)
                    focused?.let { put("focus", maskConsoleToken(it.label)) }
                }
            }
        }
        logger.info { "Remote debug: keys ${params["keys"]}" }
        var sent = 0
        var used = path.value ?: RemoteDebugUiKeys.defaultPath
        for ((index, step) in steps.withIndex()) {
            when (step) {
                is DebugUiKeyStep.Pause -> SystemClock.sleep(step.millis)
                is DebugUiKeyStep.Press -> {
                    try {
                        used = RemoteDebugUiKeys.press(step.keyCode, step.holdMillis, path.value)
                    } catch (e: DebugUiNotInFrontException) {
                        return failure(NOT_IN_FRONT) { put("sent", sent) }
                    }
                    sent++
                    if (index < steps.lastIndex) {
                        if (gap != null) SystemClock.sleep(gap) else settle(BETWEEN_KEYS_TIMEOUT_MILLIS, BETWEEN_KEYS_QUIET_MILLIS)
                    }
                }
            }
        }
        val (snapshot, settled) = settle(params.timeout(DEFAULT_SETTLE_TIMEOUT_MILLIS))
        return describe(snapshot, full = params["full"] == "1") {
            put("sent", sent)
            put("keyPath", used.id)
            put("settled", settled)
        }
    }

    /** `path` 参数: 没给是 null (自动挑), 写错了整个是 null. */
    private class KeyPathParam(val value: DebugUiKeyPath?)

    private fun Map<String, String>.keyPath(): KeyPathParam? {
        val name = this["path"].orEmpty()
        if (name.isEmpty()) return KeyPathParam(null)
        return DebugUiKeyPath.entries.firstOrNull { it.id == name }?.let(::KeyPathParam)
    }

    private fun click(params: Map<String, String>): JsonObject {
        val query = params["text"].orEmpty()
        if (query.isEmpty()) return failure("没有给 text")
        val found = debugUiFind(read(), query, exact = params["exact"] == "1")
        val matches = found.filter { it.element.interactive && it.element.handle != null }
        val target = matches.getOrNull(params["nth"]?.toIntOrNull() ?: 0)
            ?: return failure(if (found.isEmpty()) "屏上没有这段文字: $query" else "这段文字不在能聚焦的元素里: $query") { put("matches", matches.size) }
        val press = params["press"] != "0"
        val path = params.keyPath() ?: return failure("path 只能是 system 或 app")
        logger.info { "Remote debug: click \"$query\"" }
        val via = onMain { activate(target.element, press) } ?: return failure("送不上焦点, 也点不了: $query")
        if (via == VIA_FOCUS) {
            // 焦点落稳了再按: 送焦点会带起滚动、放大. 没落到要点的那个上就不按 (确定键会按在别的东西上)
            val (landed, _) = settle(BETWEEN_KEYS_TIMEOUT_MILLIS, BETWEEN_KEYS_QUIET_MILLIS)
            val focused = landed.focus()?.second
            if (focused == null || !sameElement(focused, target.element)) {
                return failure("焦点没落到「${maskConsoleToken(target.element.label)}」上" + if (press) ", 没有按确定" else "") {
                    focused?.let { put("focus", maskConsoleToken(it.label)) }
                }
            }
            if (press) {
                try {
                    RemoteDebugUiKeys.press(KeyEvent.KEYCODE_DPAD_CENTER, path = path.value)
                } catch (e: DebugUiNotInFrontException) {
                    return failure(NOT_IN_FRONT)
                }
            }
        }
        val (snapshot, settled) = settle(params.timeout(DEFAULT_SETTLE_TIMEOUT_MILLIS))
        return describe(snapshot, full = params["full"] == "1") {
            put("matches", matches.size)
            put("via", via)
            put("settled", settled)
        }
    }

    /** 两次读到的是不是同一个东西: 同一个视图 / 同一个语义节点, 或字一样. */
    private fun sameElement(a: DebugUiElement, b: DebugUiElement): Boolean {
        val first = a.handle
        val second = b.handle
        if (first is View && second is View) return first === second
        if (first is SemanticsNode && second is SemanticsNode) return first.id == second.id
        return a.source == b.source && a.label == b.label
    }

    /** 把焦点送到 [element] 上 (回 [VIA_FOCUS]); 它不能聚焦而要按时直接点 (回 [VIA_CLICK]); 都不行回 null. 在主线程上调. */
    private fun activate(element: DebugUiElement, press: Boolean): String? {
        if (element.focused) return VIA_FOCUS
        when (val handle = element.handle) {
            is SemanticsNode -> {
                if (handle.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true) return VIA_FOCUS
                if (press && handle.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) return VIA_CLICK
            }

            is View -> {
                if (handle.isFocusable && handle.requestFocus()) return VIA_FOCUS
                if (press && handle.performClick()) return VIA_CLICK
            }
        }
        return null
    }

    private fun wait(params: Map<String, String>): JsonObject {
        val appear = params["text"].orEmpty()
        val gone = params["gone"].orEmpty()
        if (appear.isEmpty() == gone.isEmpty()) return failure("text 与 gone 给一个")
        val started = System.currentTimeMillis()
        val startedUptime = SystemClock.uptimeMillis()
        val deadline = startedUptime + params.timeout(DEFAULT_WAIT_TIMEOUT_MILLIS)
        while (true) {
            val snapshot = read()
            val onScreen = debugUiFind(snapshot, appear.ifEmpty { gone }).isNotEmpty()
            // 提示画在应用窗口之外: 等出现时, 等的这段时间里弹的、与正在显示的 (系统长提示约 3.5 秒) 也算
            val inToast = appear.isNotEmpty() && ToastHistory.recent().any { it.atMillis >= started - TOAST_VISIBLE_MILLIS && appear in it.text }
            val found = if (appear.isNotEmpty()) onScreen || inToast else !onScreen
            if (found || SystemClock.uptimeMillis() >= deadline) {
                return describe(snapshot, full = params["full"] == "1") {
                    put("found", found)
                    put("waitedMs", SystemClock.uptimeMillis() - startedUptime)
                }
            }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
    }

    /**
     * 等界面停稳: 连续 [quietMillis] 读出来都一样 (没有东西出现、消失、挪动、换状态) 就算停稳; [timeoutMillis] 内没停稳照最后读到的回.
     * 一直在动的界面 (播放进度、倒计时) 停不稳, 属正常.
     */
    private fun settle(timeoutMillis: Long, quietMillis: Long = SETTLE_QUIET_MILLIS): Pair<DebugUiSnapshot, Boolean> {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var snapshot = read()
        var signature = debugUiSignature(snapshot)
        var stableSince = SystemClock.uptimeMillis()
        while (true) {
            SystemClock.sleep(SETTLE_POLL_MILLIS)
            snapshot = read()
            val now = SystemClock.uptimeMillis()
            val current = debugUiSignature(snapshot)
            if (current != signature) {
                signature = current
                stableSince = now
            }
            if (now - stableSince >= quietMillis) return snapshot to true
            if (now >= deadline) return snapshot to false
        }
    }

    private fun read(): DebugUiSnapshot = onMain { RemoteDebugUiReader.read() }

    /** 主线程卡住时不把处理线程一直占着. */
    private fun <T> onMain(block: () -> T): T = runBlocking { withTimeout(MAIN_THREAD_TIMEOUT) { withContext(Dispatchers.Main) { block() } } }

    private fun describe(snapshot: DebugUiSnapshot, full: Boolean, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val now = System.currentTimeMillis()
        val toasts = ToastHistory.recent()
            .map { DebugUiToast(it.text, now - it.atMillis) }
            .filter { full || it.agoMillis <= BRIEF_TOAST_WINDOW_MILLIS }
        return JsonObject(snapshot.toJson(full, toasts) + buildJsonObject(extra))
    }

    private fun failure(message: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", false)
        put("message", message)
        extra()
    }

    /**
     * 读界面, 有窗口拿着按键时的那一份. 弹窗刚出来、换内容的那几帧里没有窗口拿着按键: 等一会儿 ([FRONT_WAIT_MILLIS]), 还没有就是应用
     * 不在前台 (null).
     */
    private fun readInFront(): DebugUiSnapshot? {
        val deadline = SystemClock.uptimeMillis() + FRONT_WAIT_MILLIS
        while (true) {
            val snapshot = read()
            if (snapshot.focusedWindowIndex >= 0) return snapshot
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
    }

    private fun Map<String, String>.timeout(default: Long): Long =
        this["timeout"]?.toLongOrNull()?.coerceIn(0, MAX_TIMEOUT_MILLIS) ?: default

    private fun LanHttpRequest.queryParams(): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }

    private const val NOT_IN_FRONT = "应用不在前台: 按键只能送到本应用的窗口"
    private const val VIA_FOCUS = "focus"
    private const val VIA_CLICK = "click"

    private const val SETTLE_POLL_MILLIS = 40L
    /** 按完之后要这么久没动静才算停稳: 淡出的那几百毫秒里界面上的东西不变, 淡完才消失. */
    private const val SETTLE_QUIET_MILLIS = 300L
    private const val DEFAULT_SETTLE_TIMEOUT_MILLIS = 2_000L
    private const val BETWEEN_KEYS_QUIET_MILLIS = 120L
    private const val BETWEEN_KEYS_TIMEOUT_MILLIS = 1_500L
    private const val DEFAULT_WAIT_TIMEOUT_MILLIS = 5_000L
    private const val WAIT_POLL_MILLIS = 100L

    /** 送按键前没有窗口拿着按键时最多等多久 (弹窗刚出来、换内容的那几帧) */
    private const val FRONT_WAIT_MILLIS = 1_500L
    private const val MAX_TIMEOUT_MILLIS = 30_000L
    private const val TOAST_VISIBLE_MILLIS = 3_500L
    private const val BRIEF_TOAST_WINDOW_MILLIS = 10_000L
    private val MAIN_THREAD_TIMEOUT = 5.seconds
}
