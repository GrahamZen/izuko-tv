/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIRectEdgeLeft
import platform.UIKit.UIScreenEdgePanGestureRecognizer
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * 从屏幕左边缘往右滑 = 返回 (同 iOS 系统的返回手势): 电视界面没有返回按钮, 靠返回键退出 hero 态、回上一页 (安卓平板上是系统的返回手势).
 * 滑过 [TV_EDGE_BACK_DISTANCE] 点才算, 松手时触发 [onBack]. 与 Compose 视图自己的手势同时识别, 不互相抢.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class IosEdgeBackGesture(private val onBack: () -> Unit) : NSObject(), UIGestureRecognizerDelegateProtocol {
    @ObjCAction
    fun onEdgePan(recognizer: UIScreenEdgePanGestureRecognizer) {
        if (recognizer.state != UIGestureRecognizerStateEnded) return
        val dx = recognizer.translationInView(recognizer.view).useContents { x }
        if (dx >= TV_EDGE_BACK_DISTANCE) onBack()
    }

    /** 装到 [view] 上. 手势识别器不持有目标, 调用方要留着本对象. */
    fun install(view: UIView) {
        val recognizer = UIScreenEdgePanGestureRecognizer(target = this, action = NSSelectorFromString("onEdgePan:"))
        recognizer.edges = UIRectEdgeLeft
        recognizer.delegate = this
        view.addGestureRecognizer(recognizer)
    }

    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer,
    ): Boolean = true
}

/** 往右滑多远 (点) 才算返回. */
private const val TV_EDGE_BACK_DISTANCE = 60.0
