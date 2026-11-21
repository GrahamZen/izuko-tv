/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest

/**
 * 小米电视上独立窗口 (Dialog / Popup) 第一帧之后的更新不上屏的补救: 这类窗口每画一次, 紧接着把窗口里的 Compose 图层全部重录一遍.
 *
 * 真机 (工单 #36 / #41, MiTV-MFFU0, Android 14) 读回窗口缓冲区: 弹窗和菜单的第一帧正常; 之后只录进子图层的新内容 (挪焦点后的高亮)
 * 进不了窗口, 要等上面各层重录才出来 —— 焦点已经在「重启」上, 画面上的高亮还在「取消」, 这时按确定点中的是看不见的那颗.
 * 对窗口或 Compose 视图 invalidate 都不行, 重录全部图层 ([ViewRootForTest.invalidateDescendants]) 才行. 主窗口没有这个问题.
 * 系统日志里有小米渲染的 `RenderLite trim renderNode`, 它的开关 (`persist.sys.enable_trim_rendernode`) 应用读不到, 所以按厂商 + 电视判断;
 * 几台小米电视 (联发科与晶晨芯片) 都报过同类现象.
 *
 * 代价是窗口每画一帧多重录一遍图层, 只在独立窗口里、只在小米电视上.
 */
object TvSecondaryWindowLayerRefresh {
    private var installed = false
    /** 窗口根视图 → 它的重录器; 窗口里最后一个 Compose 根视图离开时撤掉. */
    private val windows = HashMap<View, WindowRerecorder>()

    /** 进程里调一次, 要在第一个弹窗建出来之前. */
    fun installIfNeeded(context: Context) {
        if (installed) return
        installed = true
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || !isTelevision(context)) return
        install()
    }

    /** 不看机型直接装上, 返回撤销 (设备测试在模拟器上用). */
    internal fun install(): () -> Unit {
        // Compose 每建一个窗口的根视图都调它 (测试框架也用它登记根视图, 所以接着调原来那个)
        val previous = ViewRootForTest.onViewCreatedCallback
        val callback: (ViewRootForTest) -> Unit = { root ->
            previous?.invoke(root)
            watch(root)
        }
        ViewRootForTest.onViewCreatedCallback = callback
        return { if (ViewRootForTest.onViewCreatedCallback === callback) ViewRootForTest.onViewCreatedCallback = previous }
    }

    /** 一共重录过几次 (设备测试看重录发生了、窗口静止后停住). */
    internal var rerecordCount = 0
        private set

    private fun isTelevision(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun watch(root: ViewRootForTest) {
        root.view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                private var window: View? = null

                override fun onViewAttachedToWindow(v: View) {
                    val windowRoot = v.rootView
                    val type = (windowRoot.layoutParams as? WindowManager.LayoutParams)?.type ?: return
                    if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return
                    window = windowRoot
                    windows.getOrPut(windowRoot) { WindowRerecorder(windowRoot) }.add(root)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    val windowRoot = window ?: return
                    window = null
                    val rerecorder = windows[windowRoot] ?: return
                    if (rerecorder.remove(root)) windows.remove(windowRoot)
                }
            },
        )
    }

    /**
     * 一个窗口一份 (窗口里有几个 Compose 根视图也只一份, 否则各自的重录互相当成新的一帧, 停不下来):
     * 每画一帧排一次重录; 重录引起的那一帧不再排.
     */
    private class WindowRerecorder(private val window: View) : ViewTreeObserver.OnDrawListener, Runnable {
        private val roots = LinkedHashSet<ViewRootForTest>()
        private var pending = false
        private var causedByRerecord = false

        fun add(root: ViewRootForTest) {
            if (roots.isEmpty()) window.viewTreeObserver.addOnDrawListener(this)
            roots.add(root)
        }

        /** 返回 true = 窗口里没有 Compose 根视图了, 已经撤掉. */
        fun remove(root: ViewRootForTest): Boolean {
            roots.remove(root)
            if (roots.isNotEmpty()) return false
            if (window.viewTreeObserver.isAlive) window.viewTreeObserver.removeOnDrawListener(this)
            window.removeCallbacks(this)
            pending = false
            return true
        }

        override fun onDraw() {
            if (causedByRerecord) {
                causedByRerecord = false
                return
            }
            if (pending) return
            pending = true
            window.post(this)
        }

        override fun run() {
            pending = false
            if (roots.isEmpty()) return
            rerecordCount++
            causedByRerecord = true
            for (root in roots) {
                root.invalidateDescendants()
                root.view.invalidate()
            }
        }
    }
}
