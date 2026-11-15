/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Checkable
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState

/**
 * 读应用此刻屏上的界面 (给 [RemoteDebugUi]): 本进程的每个窗口 (主页面、Compose 的 Dialog / Popup) 各读一遍, 原生视图走视图树,
 * Compose 的内容走语义树 (不必打开无障碍), 两种混着放的 (Compose 里嵌原生视图) 都读得到. 全部在主线程上调.
 *
 * 读得到: 文字、无障碍描述、能不能聚焦、焦点在谁身上、选中 / 勾选 / 停用、位置. 读不到: 直接画在画布上的东西 (角标数字、进度条、
 * 高光与压暗). 看不见的不一定排除得掉: 透明的原生视图、清掉了语义的 Compose 内容 (见 [WindowWalk.shownChildren]) 不列, 别的办法藏起来的
 * (只设了透明度的 Compose 内容、同一个窗口里被别的东西盖住的、正在淡出的上一页) 照样列出.
 */
internal object RemoteDebugUiReader {
    fun read(): DebugUiSnapshot {
        val roots = windowRoots().filter { it.isAttachedToWindow && it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        // 系统按加进来的先后给, 后加的在上面
        return DebugUiSnapshot(roots.asReversed().map(::readWindow))
    }

    /** 本进程每个窗口的根视图, 照加进来的先后 (后加的在上面). */
    fun windowRoots(): List<View> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return WindowInspector.getGlobalWindowViews()
        // 安卓 10 以前没有公开的入口, 从窗口管理器里取 (同 Espresso 的做法); 取不到就当没有窗口
        return runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance)
            (views as List<*>).filterIsInstance<View>()
        }.getOrDefault(emptyList())
    }

    private fun readWindow(root: View): DebugUiWindow {
        val params = root.layoutParams as? WindowManager.LayoutParams
        val origin = IntArray(2).also(root::getLocationOnScreen)
        val walk = WindowWalk(origin[0], origin[1], root.findFocus())
        walk.visit(root)
        walk.markComposeFocusByRect()
        return DebugUiWindow(
            kind = windowKind(params?.type),
            title = params?.title?.toString()?.takeIf { it.isNotBlank() },
            focused = root.hasWindowFocus(),
            bounds = DebugUiBounds(origin[0], origin[1], origin[0] + root.width, origin[1] + root.height),
            elements = walk.elements.sortedWith(DEBUG_UI_READING_ORDER),
        )
    }

    private fun windowKind(type: Int?): String = when (type) {
        null -> "window"
        WindowManager.LayoutParams.TYPE_BASE_APPLICATION -> "activity"
        WindowManager.LayoutParams.TYPE_APPLICATION -> "dialog"
        in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW -> "popup"
        else -> "window:$type"
    }
}

/**
 * 读一个窗口. 能聚焦或能点、里面没有别的这类东西的, 连同里面的字算一个元素; 不在这类东西里面的字各算一个; 自己拿着焦点的容器也算一个.
 *
 * @param originX 窗口在屏幕上的位置
 * @param focusedView 这个窗口里拿着焦点的视图 (Compose 的内容拿着焦点时是那个 Compose 根视图)
 */
private class WindowWalk(private val originX: Int, private val originY: Int, private val focusedView: View?) {
    val elements = ArrayList<DebugUiElement>()
    private val rect = Rect()
    private val interactiveInside = HashMap<Any, Boolean>()

    /** 拿着焦点、而语义树上没有哪个节点标着焦点的 Compose 根视图 (见 [markComposeFocusByRect]). */
    private var composeFocusUnmarked: View? = null

    fun visit(view: View) {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f) return
        if (view is ViewRootForTest) {
            visitCompose(view)
            // 嵌在 Compose 里的原生视图是它的子视图
            (view as? ViewGroup)?.forEachChild(::visit)
            return
        }
        if (!view.getGlobalVisibleRect(rect)) return
        val bounds = DebugUiBounds(originX + rect.left, originY + rect.top, originX + rect.right, originY + rect.bottom)
        val focused = view === focusedView
        val leaf = view.isInteractive() && !view.hasInteractiveDescendant()
        if (leaf || focused) {
            val parts = if (leaf) view.subtreeTexts() else emptyList()
            val desc = view.contentDescription?.toString()?.clean()
            elements += DebugUiElement(
                text = parts.joinToString(DEBUG_UI_TEXT_SEPARATOR),
                bounds = bounds,
                source = SOURCE_VIEW,
                // 描述多半就是里面的某一行字 (卡的标题), 那样不另给
                desc = desc?.takeIf { it.isNotEmpty() && it !in parts },
                role = view.javaClass.simpleName.ifEmpty { view.javaClass.name.substringAfterLast('.') },
                interactive = true,
                focused = focused,
                selected = view.isSelected,
                checked = (view as? Checkable)?.let { if (it.isChecked) CHECKED_ON else CHECKED_OFF },
                disabled = !view.isEnabled,
                handle = view,
                group = view.parent,
            )
            if (leaf) return
        } else {
            val text = (view as? TextView)?.text?.toString()?.clean().orEmpty()
            val desc = view.contentDescription?.toString()?.clean().orEmpty()
            // 没有字只有描述的, 只认叶子 (图标): 容器的描述多半是把里面的字又说了一遍
            if (text.isNotEmpty() || (desc.isNotEmpty() && view !is ViewGroup)) {
                elements += DebugUiElement(text = text, bounds = bounds, source = SOURCE_VIEW, desc = desc.takeIf { it.isNotEmpty() && it != text })
            }
        }
        (view as? ViewGroup)?.forEachChild(::visit)
    }

    private fun View.isInteractive(): Boolean = isFocusable || isClickable

    private fun View.hasInteractiveDescendant(): Boolean = interactiveInside.getOrPut(this) {
        var found = false
        (this as? ViewGroup)?.forEachChild { child ->
            if (!found && child.visibility == View.VISIBLE && (child.isInteractive() || child.hasInteractiveDescendant())) found = true
        }
        found
    }

    private fun View.subtreeTexts(): List<String> {
        val parts = ArrayList<String>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE) return
            (view as? TextView)?.text?.toString()?.clean()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            (view as? ViewGroup)?.forEachChild(::collect)
        }
        collect(this)
        return parts
    }

    // region Compose

    private fun visitCompose(root: ViewRootForTest) {
        val view = root.view
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        val inWindow = IntArray(2).also(view::getLocationInWindow)
        val before = elements.size
        visitNode(root.semanticsOwner.unmergedRootSemanticsNode, onScreen[0] - inWindow[0], onScreen[1] - inWindow[1])
        if (view === focusedView && (before until elements.size).none { elements[it].focused }) composeFocusUnmarked = view
    }

    private fun visitNode(node: SemanticsNode, dx: Int, dy: Int) {
        val config = node.config
        val focused = config.getOrNull(SemanticsProperties.Focused) == true
        val leaf = config.isInteractive() && !node.hasInteractiveDescendant()
        val box = node.boundsInWindow
        val visible = box.width > 0f && box.height > 0f
        val bounds = DebugUiBounds(dx + box.left.toInt(), dy + box.top.toInt(), dx + box.right.toInt(), dy + box.bottom.toInt())
        if (visible && (leaf || focused)) {
            val texts = ArrayList<String>()
            val descs = ArrayList<String>()
            if (leaf) node.collectTexts(texts, descs) else config.collectOwnTexts(texts, descs)
            val text = texts.joinToString(DEBUG_UI_TEXT_SEPARATOR)
            elements += DebugUiElement(
                text = text,
                bounds = bounds,
                source = SOURCE_COMPOSE,
                desc = descs.filter { it !in texts }.joinToString(DEBUG_UI_TEXT_SEPARATOR).takeIf { it.isNotEmpty() },
                role = config.role(),
                interactive = true,
                focused = focused,
                selected = config.getOrNull(SemanticsProperties.Selected) == true,
                checked = config.checked(),
                disabled = config.contains(SemanticsProperties.Disabled),
                tag = config.getOrNull(SemanticsProperties.TestTag),
                props = config.otherProps(),
                handle = node,
                group = node.parent?.let { COMPOSE_GROUP_PREFIX + it.id },
            )
        } else if (visible && !leaf) {
            val texts = ArrayList<String>()
            val descs = ArrayList<String>()
            config.collectOwnTexts(texts, descs)
            if (texts.isNotEmpty() || descs.isNotEmpty()) {
                val text = texts.joinToString(DEBUG_UI_TEXT_SEPARATOR)
                elements += DebugUiElement(
                    text = text,
                    bounds = bounds,
                    source = SOURCE_COMPOSE,
                    desc = descs.filter { it !in texts }.joinToString(DEBUG_UI_TEXT_SEPARATOR).takeIf { it.isNotEmpty() },
                    role = config.role(),
                    selected = config.getOrNull(SemanticsProperties.Selected) == true,
                    checked = config.checked(),
                    tag = config.getOrNull(SemanticsProperties.TestTag),
                    props = config.otherProps(),
                )
            }
        }
        if (!leaf) for (child in node.shownChildren()) visitNode(child, dx, dy)
    }

    private fun SemanticsConfiguration.isInteractive(): Boolean =
        contains(SemanticsProperties.Focused) || contains(SemanticsActions.OnClick)

    private fun SemanticsNode.hasInteractiveDescendant(): Boolean = interactiveInside.getOrPut(this.id) {
        shownChildren().any { it.config.isInteractive() || it.hasInteractiveDescendant() }
    }

    /**
     * 往下读的子节点. 清掉了子孙语义的节点 (`clearAndSetSemantics`) 不往下读: 界面用它标出不给人看的内容 —— 只为量尺寸摆着的透明副本、
     * 被一句描述替掉的装饰.
     */
    private fun SemanticsNode.shownChildren(): List<SemanticsNode> = if (config.isClearingSemantics) emptyList() else children

    private fun SemanticsNode.collectTexts(texts: MutableList<String>, descs: MutableList<String>) {
        config.collectOwnTexts(texts, descs)
        for (child in shownChildren()) child.collectTexts(texts, descs)
    }

    private fun SemanticsConfiguration.collectOwnTexts(texts: MutableList<String>, descs: MutableList<String>) {
        getOrNull(SemanticsProperties.EditableText)?.text?.clean()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        getOrNull(SemanticsProperties.Text)?.forEach { part -> part.text.clean().takeIf { it.isNotEmpty() }?.let(texts::add) }
        getOrNull(SemanticsProperties.ContentDescription)?.forEach { part -> part.clean().takeIf { it.isNotEmpty() }?.let(descs::add) }
    }

    private fun SemanticsConfiguration.role(): String? =
        getOrNull(SemanticsProperties.Role)?.toString() ?: if (contains(SemanticsProperties.EditableText)) ROLE_EDIT else null

    private fun SemanticsConfiguration.checked(): String? = when (getOrNull(SemanticsProperties.ToggleableState)) {
        ToggleableState.On -> CHECKED_ON
        ToggleableState.Off -> CHECKED_OFF
        ToggleableState.Indeterminate -> CHECKED_MIXED
        null -> null
    }

    /** 上面没单独给出的语义属性里值简单的那些 (页面自己加的状态多半在这里); 动作不算. */
    private fun SemanticsConfiguration.otherProps(): Map<String, String> {
        var props: MutableMap<String, String>? = null
        for ((key, value) in this) {
            if (key.name in HANDLED_PROPS || value is AccessibilityAction<*>) continue
            val text = when (value) {
                is Boolean, is Number, is String, is Enum<*> -> value.toString()
                is Unit -> "true"
                else -> continue
            }
            (props ?: LinkedHashMap<String, String>().also { props = it })[key.name] = text
        }
        return props ?: emptyMap()
    }

    /**
     * Compose 的内容拿着焦点、语义树上却没有节点标着焦点 (拿焦点的那个没写进语义): 照 Compose 报的焦点框认 —— 框里的元素里最小的那个
     * 算焦点所在; 框里一个元素都没有时补一个只有位置的.
     */
    fun markComposeFocusByRect() {
        val view = composeFocusUnmarked ?: return
        val focusRect = Rect()
        view.getFocusedRect(focusRect)
        if (focusRect.width() >= view.width && focusRect.height() >= view.height) return // 没有更细的焦点框
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        val bounds = DebugUiBounds(
            onScreen[0] + focusRect.left, onScreen[1] + focusRect.top, onScreen[0] + focusRect.right, onScreen[1] + focusRect.bottom,
        )
        val inside = elements.withIndex().filter { (_, e) ->
            e.source == SOURCE_COMPOSE && bounds.contains(e.bounds.centerX, e.bounds.centerY)
        }
        val holder = inside.filter { it.value.interactive }.minByOrNull { it.value.bounds.width * it.value.bounds.height }
        if (holder != null) {
            elements[holder.index] = holder.value.withFocus()
        } else {
            elements += DebugUiElement(
                text = inside.map { it.value.label }.filter { it.isNotEmpty() }.joinToString(DEBUG_UI_TEXT_SEPARATOR),
                bounds = bounds,
                source = SOURCE_COMPOSE,
                interactive = true,
                focused = true,
            )
        }
    }

    private fun DebugUiElement.withFocus() = DebugUiElement(
        text = text, bounds = bounds, source = source, desc = desc, role = role, interactive = true, focused = true,
        selected = selected, checked = checked, disabled = disabled, tag = tag, props = props, handle = handle, group = group,
    )

    // endregion

    private fun String.clean(): String = trim().replace(WHITESPACE, " ")

    private inline fun ViewGroup.forEachChild(action: (View) -> Unit) {
        for (i in 0 until childCount) action(getChildAt(i))
    }

    private companion object {
        const val SOURCE_VIEW = "view"
        const val SOURCE_COMPOSE = "compose"
        const val ROLE_EDIT = "edit"
        const val COMPOSE_GROUP_PREFIX = "compose:"
        const val CHECKED_ON = "on"
        const val CHECKED_OFF = "off"
        const val CHECKED_MIXED = "mixed"
        val WHITESPACE = Regex("""\s+""")

        /** 已经单独给出的, 与只对无障碍服务有意义的. */
        val HANDLED_PROPS = setOf(
            "Text", "EditableText", "InputText", "ContentDescription", "Focused", "Selected", "ToggleableState", "Role", "TestTag", "Disabled",
            "IsTraversalGroup", "TraversalIndex", "IsEditable", "IsShowingTextSubstitution", "MaxTextLength", "LinkTestMarker", "IsContainer",
            "IsSensitiveData",
        )
    }
}
