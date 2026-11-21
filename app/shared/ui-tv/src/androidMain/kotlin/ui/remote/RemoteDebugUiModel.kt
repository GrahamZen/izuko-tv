/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * 调试接口 `api/debug/ui` 读到的界面 (见 [RemoteDebugUi]): 屏上每个窗口一份 [DebugUiWindow], 窗口里看得见的东西各一个 [DebugUiElement].
 * 这个文件只有数据与在数据上算的东西 (同一排、按文字找、界面停没停稳、写成 JSON), 读界面在 [RemoteDebugUiReader].
 */

/** 屏幕上的位置 (像素), 已经裁到看得见的那一块. */
internal data class DebugUiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/**
 * 屏上的一个东西. 能聚焦或能点的 ([interactive]) 一个算一个, [text] 是它里面所有的字; 不在这类东西里面的字各算一个.
 *
 * @param text 文字, 几段时用 [DEBUG_UI_TEXT_SEPARATOR] 连起来; 没有字的 (图标) 是空串
 * @param desc 无障碍描述, 与 [text] 一样时不给
 * @param role Compose 的角色 (Button / Checkbox / Switch / Tab …, 输入框是 edit) 或原生视图的类名
 * @param checked 勾选类的状态: on / off / mixed
 * @param props Compose 上其余的语义属性 (名字 → 值), 页面自己加的状态从这里看
 * @param source compose 或 view (原生视图)
 * @param handle 点它时用的语义节点或视图
 * @param group 它在哪个容器里 (同一个容器里的东西这一项相等): 认同一排时用
 */
internal class DebugUiElement(
    val text: String,
    val bounds: DebugUiBounds,
    val source: String,
    val desc: String? = null,
    val role: String? = null,
    val interactive: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checked: String? = null,
    val disabled: Boolean = false,
    val tag: String? = null,
    val props: Map<String, String> = emptyMap(),
    val handle: Any? = null,
    val group: Any? = null,
) {
    /** 按文字找、等文字出现时比的字: 有字用字, 没字用描述. */
    val label: String get() = text.ifEmpty { desc.orEmpty() }
}

/**
 * 一个窗口: 主页面 (activity)、Compose 的 Dialog (dialog)、Popup (popup) 等.
 *
 * @param focused 按键此刻送到这个窗口
 * @param elements 从上到下、从左到右
 */
internal class DebugUiWindow(
    val kind: String,
    val title: String?,
    val focused: Boolean,
    val bounds: DebugUiBounds,
    val elements: List<DebugUiElement>,
)

/** @param windows 最上面的窗口在前 */
internal class DebugUiSnapshot(val windows: List<DebugUiWindow>) {
    /** 按键此刻送到的窗口, 应用不在前台时没有. */
    val focusedWindowIndex: Int get() = windows.indexOfFirst { it.focused }

    /** 焦点所在的元素与它在第几个窗口. */
    fun focus(): Pair<Int, DebugUiElement>? {
        val index = focusedWindowIndex
        if (index < 0) return null
        val element = windows[index].elements.firstOrNull { it.focused } ?: return null
        return index to element
    }

    /** 按文字找、等文字出现时看窗口的顺序: 收按键的窗口先看, 其余照从上到下. */
    fun windowsFocusedFirst(): List<IndexedValue<DebugUiWindow>> {
        val focused = focusedWindowIndex
        return windows.withIndex().sortedBy { if (it.index == focused) -1 else it.index }
    }
}

internal class DebugUiMatch(val window: Int, val element: DebugUiElement)

internal class DebugUiToast(val text: String, val agoMillis: Long)

internal const val DEBUG_UI_TEXT_SEPARATOR = " · "

/** 排序: 从上到下, 同一高度从左到右. */
internal val DEBUG_UI_READING_ORDER: Comparator<DebugUiElement> = compareBy({ it.bounds.top }, { it.bounds.left })

/**
 * 与 [focus] 同一排的可聚焦元素 (含它自己), 从左到右: 竖直方向与它重叠过半 (按两者里矮的那个算) 的算同一排.
 * 一排胶囊、一行按钮、海报墙的一行都这样看. 它所在的容器里还有别的可聚焦元素时只在这个容器里找 (同一个窗口里别处碰巧一样高的不算:
 * 侧边栏的图标、压在页面上的浮层后面的卡); 容器里只有它一个时 (每颗各包了一层) 在整个窗口里找同一种界面 (Compose 或原生) 的.
 * 没有字也没有描述的不算 (说不出是什么).
 */
internal fun debugUiRow(elements: List<DebugUiElement>, focus: DebugUiElement): List<DebugUiElement> {
    val group = focus.group
    val grouped = group != null && elements.any { it !== focus && it.interactive && it.group == group }
    return elements
        .filter {
            it === focus || (
                it.interactive && it.label.isNotEmpty() && sameBand(it.bounds, focus.bounds) &&
                    (if (grouped) it.group == group else it.source == focus.source)
                )
        }
        .sortedBy { it.bounds.left }
}

private fun sameBand(a: DebugUiBounds, b: DebugUiBounds): Boolean {
    val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return overlap * 2 > minOf(a.height, b.height)
}

/**
 * 按文字找元素. 整段相同的排前面, 只是包含的排后面 ([exact] 时不要); 同一档里收按键的窗口先、能聚焦的先.
 */
internal fun debugUiFind(snapshot: DebugUiSnapshot, query: String, exact: Boolean = false): List<DebugUiMatch> {
    if (query.isEmpty()) return emptyList()
    val equal = ArrayList<DebugUiMatch>()
    val containing = ArrayList<DebugUiMatch>()
    for ((index, window) in snapshot.windowsFocusedFirst()) {
        for (element in window.elements.sortedByDescending { it.interactive }) {
            when {
                element.text == query || element.desc == query -> equal += DebugUiMatch(index, element)
                query in element.text || query in element.desc.orEmpty() -> containing += DebugUiMatch(index, element)
            }
        }
    }
    return if (exact) equal else equal + containing
}

/** 界面此刻的样子压成一个串: 两次读出来相同 = 这段时间里没有东西出现、消失、挪动或换状态. */
internal fun debugUiSignature(snapshot: DebugUiSnapshot): String = buildString {
    for (window in snapshot.windows) {
        append(window.kind).append(window.focused).append(window.bounds).append('\n')
        for (e in window.elements) {
            append(e.text).append('|').append(e.desc).append('|').append(e.bounds)
                .append(e.focused).append(e.selected).append(e.checked).append(e.disabled).append(e.props).append('\n')
        }
    }
}

private val CONSOLE_ADDRESS = Regex("""(:\d{2,5}/)[A-Za-z0-9_-]{6,}""")

/** 屏上的控制台地址 (启动时的二维码弹窗、设置里的那一行) 带令牌, 给出去之前遮掉. */
internal fun maskConsoleToken(text: String): String = CONSOLE_ADDRESS.replace(text) { it.groupValues[1] + "<token>" }

/**
 * 写成接口的回答.
 *
 * @param full true 时每个窗口列出全部元素; false 时只给每个窗口打头的几段字 (够认出是哪一页、哪个弹窗)
 * @param toasts 从旧到新
 */
internal fun DebugUiSnapshot.toJson(full: Boolean, toasts: List<DebugUiToast>): JsonObject = buildJsonObject {
    put("ok", true)
    put("front", focusedWindowIndex >= 0)
    focus()?.let { (index, element) ->
        putJsonObject("focus") {
            put("window", index)
            putElement(element)
            val row = debugUiRow(windows[index].elements, element)
            if (row.size > 1) {
                put("rowIndex", row.indexOf(element))
                putJsonArray("row") {
                    for (other in row) addJsonObject {
                        put("text", maskConsoleToken(other.label))
                        if (other.focused) put("focused", true)
                        if (other.selected) put("selected", true)
                        other.checked?.let { put("checked", it) }
                        if (other.disabled) put("disabled", true)
                    }
                }
            }
        }
    }
    putJsonArray("windows") {
        for (window in windows) addJsonObject {
            put("kind", window.kind)
            window.title?.let { put("title", maskConsoleToken(it)) }
            if (window.focused) put("focused", true)
            putBounds(window.bounds)
            if (full) {
                putJsonArray("elements") { for (element in window.elements) addJsonObject { putElement(element) } }
            } else {
                putJsonArray("head") {
                    window.elements.asSequence().map { it.label }.filter { it.isNotEmpty() }.take(BRIEF_HEAD_COUNT)
                        .forEach { add(maskConsoleToken(if (it.length > BRIEF_HEAD_TEXT_MAX) it.take(BRIEF_HEAD_TEXT_MAX) + "…" else it)) }
                }
                put("elementCount", window.elements.size)
            }
        }
    }
    putJsonArray("toasts") {
        for (toast in toasts) addJsonObject {
            put("text", maskConsoleToken(toast.text))
            put("agoMs", toast.agoMillis)
        }
    }
}

private const val BRIEF_HEAD_COUNT = 8
private const val BRIEF_HEAD_TEXT_MAX = 60

private fun JsonObjectBuilder.putElement(element: DebugUiElement) {
    put("text", maskConsoleToken(element.text))
    element.desc?.let { put("desc", maskConsoleToken(it)) }
    element.role?.let { put("role", it) }
    if (element.interactive) put("interactive", true)
    if (element.focused) put("focused", true)
    if (element.selected) put("selected", true)
    element.checked?.let { put("checked", it) }
    if (element.disabled) put("disabled", true)
    element.tag?.let { put("tag", it) }
    if (element.props.isNotEmpty()) putJsonObject("props") { for ((key, value) in element.props) put(key, value) }
    put("source", element.source)
    putBounds(element.bounds)
}

private fun JsonObjectBuilder.putBounds(bounds: DebugUiBounds) {
    putJsonArray("bounds") {
        add(bounds.left)
        add(bounds.top)
        add(bounds.right)
        add(bounds.bottom)
    }
}
