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
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import me.him188.ani.app.ui.foundation.TvKeyLongPressHost
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuLook
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuPresenter
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuRequest
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuSession

/**
 * 原生菜单的宿主 ([TvNativeMenuPresenter] 的 TV 实现): 一次只开一个菜单, 画在 [TvNativeMenuLayer] 挂上的那一层里.
 *
 * 按键: 菜单开着时根部的 [tvNativeMenuKeys] 把键全截下来给菜单 (上下挪高亮、确定选、返回收起). 系统焦点与 Compose 焦点都留在锚点上不动
 * (Compose 视图一失去系统焦点就清掉自己的焦点, 关菜单后还得找回来), 关掉菜单后什么都不用恢复.
 * - 菜单开着时按下的键, 剩下的连发与抬起在菜单收起后照样吞掉, 不漏给下面的页面 (返回键在抬起时才收菜单, 页面那边也是抬起时处理返回).
 * - 确定 / 返回只认菜单开着时见过按下的: 长按卡片开的菜单, 那一下的抬起不算选.
 * - 播放暂停与音量放行: 画面在菜单后面照放, 播放暂停键照样管用.
 * - 长按返回照旧交给根部的长按宿主; 它认领了 (开了动作面板) 菜单就收起.
 */
@Stable
class TvNativeMenuHost internal constructor(private val composeView: View) : TvNativeMenuPresenter {
    private var layer: TvNativeMenuLayerView? = null
    private var active: Menu? = null
    private val pendingUps = HashSet<Int>()
    private var styleFor: TvNativeMenuLook? = null
    private var style: TvNativeMenuStyle? = null

    internal var check: Bitmap? = null
        set(value) {
            field = value
            layer?.check = value
        }

    /** 菜单开着 (收起过渡不算). */
    val isOpen: Boolean get() = active != null

    /** 只看窗口, 不看那一层挂没挂上: 同一个菜单不中途换画法; 那一层还没挂上时开的菜单, 挂上时补画 (见 [attach]). */
    @Composable
    override fun isInHostWindow(): Boolean = LocalView.current.rootView === composeView.rootView

    override fun open(request: TvNativeMenuRequest): TvNativeMenuSession {
        active?.let { previous ->
            previous.closed = true
            active = null
            previous.request.onDismissRequest()
        }
        val menu = Menu(request)
        active = menu
        layer?.show(request, styleOf(request.look), animate = true)
        return menu
    }

    internal fun attach(view: TvNativeMenuLayerView) {
        layer = view
        view.check = check
        view.listener = object : TvNativeMenuLayerView.Listener {
            override fun onSelect(index: Int) {
                val menu = finish() ?: return
                menu.request.onSelect(index)
            }

            override fun onDismiss() {
                val menu = finish() ?: return
                menu.request.onDismissRequest()
            }
        }
        active?.let { view.show(it.request, styleOf(it.request.look), animate = false) }
    }

    internal fun detach(view: TvNativeMenuLayerView) {
        if (layer !== view) return
        view.listener = null
        layer = null
    }

    /** 收起当前菜单并交出它 (由它自己报选中或收起). */
    private fun finish(): Menu? {
        val menu = active ?: return null
        menu.closed = true
        active = null
        layer?.hide(animate = true)
        return menu
    }

    private fun styleOf(look: TvNativeMenuLook): TvNativeMenuStyle {
        style?.let { if (styleFor === look) return it }
        return look.toStyle().also {
            styleFor = look
            style = it
        }
    }

    /** 根部按键入口 ([tvNativeMenuKeys]). 返回 true = 吞掉. */
    internal fun onKey(event: KeyEvent, backLongPress: TvKeyLongPressHost?): Boolean {
        val native = event.nativeKeyEvent
        val code = native.keyCode
        val back = code == AndroidKeyEvent.KEYCODE_BACK || code == AndroidKeyEvent.KEYCODE_ESCAPE
        if (active == null) {
            if (code !in pendingUps) return false
            // 根部的长按宿主在我们后面, 吞掉的返回键要亲手交给它, 它才知道这次手势结束了
            if (back) backLongPress?.onRootKeyEvent(event)
            if (native.action == AndroidKeyEvent.ACTION_UP) pendingUps.remove(code)
            return true
        }
        if (code in PASS_THROUGH_KEYS) return false
        if (back && backLongPress?.onRootKeyEvent(event) == true) {
            if (native.action == AndroidKeyEvent.ACTION_UP) pendingUps.remove(code) else pendingUps.add(code)
            finish()?.request?.onDismissRequest()
            return true
        }
        when (native.action) {
            AndroidKeyEvent.ACTION_DOWN -> {
                if (native.repeatCount == 0) pendingUps.add(code)
                layer?.onKeyDown(code)
            }

            AndroidKeyEvent.ACTION_UP -> if (pendingUps.remove(code)) layer?.onKeyUp(code)
        }
        return true
    }

    private inner class Menu(var request: TvNativeMenuRequest) : TvNativeMenuSession {
        var closed = false

        override fun update(request: TvNativeMenuRequest) {
            if (closed) return
            val old = this.request
            this.request = request
            if (old.options != request.options || old.anchorBounds != request.anchorBounds || old.look !== request.look) {
                layer?.update(request, styleOf(request.look))
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            if (active === this) {
                active = null
                layer?.hide(animate = true)
            }
        }
    }
}

private val PASS_THROUGH_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
    AndroidKeyEvent.KEYCODE_VOLUME_UP,
    AndroidKeyEvent.KEYCODE_VOLUME_DOWN,
    AndroidKeyEvent.KEYCODE_VOLUME_MUTE,
)

/** 在主窗口的根部记住宿主 (它认这个窗口: 只有锚点在这里的菜单交给它). */
@Composable
fun rememberTvNativeMenuHost(): TvNativeMenuHost {
    val view = LocalView.current
    return remember(view) { TvNativeMenuHost(view) }
}

/**
 * 把原生菜单那一层挂到主窗口的内容层 (`android.R.id.content`) 上, 盖在整个 Compose 界面上面.
 * 不经 Compose 的 AndroidView: 那样即使没开菜单也是一块铺满的触摸目标, 而且显示 / 隐藏要等一次重组; 直接挂视图则开关都是当场的.
 */
@Composable
fun TvNativeMenuLayer(host: TvNativeMenuHost) {
    val check = rememberTvNativeIcon(Icons.Rounded.Check, TV_NATIVE_MENU_CHECK_SIZE)
    val view = LocalView.current
    DisposableEffect(host, view) {
        val content = view.rootView.findViewById<ViewGroup>(android.R.id.content)
        val layer = TvNativeMenuLayerView(view.context)
        content?.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (content != null) host.attach(layer)
        onDispose {
            host.detach(layer)
            content?.removeView(layer)
        }
    }
    SideEffect { host.check = check }
}

/**
 * 根部的按键截流 (见 [TvNativeMenuHost]). 要挂在长按宿主的截流之前 (外层): 菜单开着时返回键由宿主亲手转给 [backLongPress].
 */
fun Modifier.tvNativeMenuKeys(host: TvNativeMenuHost, backLongPress: TvKeyLongPressHost?): Modifier =
    onPreviewKeyEvent { host.onKey(it, backLongPress) }
