/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.github.panpf.sketch.Sketch
import kotlin.test.fail

/**
 * 原生页面导航测试的宿主: 起一个空 [ComponentActivity], 把原生视图直接挂上去 (不经 Compose), 按键走 [Instrumentation] 注入 ——
 * 与遥控器同一条派发路径 (ViewRootImpl → 焦点所在的视图链). 窗口先退出触摸模式, 同电视.
 *
 * 跑法: `./gradlew :app:shared:ui-tv:assembleAndroidDeviceTest -Pandroid.min.sdk=30` (commonTest 一起打进测试 APK, 反引号测试名里的空格
 * 要 DEX 040 才表示得了), 装到模拟器上用 `am instrument -w -e runnerBuilder de.mannodermaus.junit5.AndroidJUnit5Builder
 * me.him188.ani.app.tv.test/androidx.test.runner.AndroidJUnitRunner` 跑. 连着电视时别用 connectedAndroidDeviceTest: 它装到所有设备上.
 */
internal class TvNativeTestHost {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var activity: ComponentActivity
        private set
    lateinit var root: FrameLayout
        private set
    /**
     * 整个测试进程共用一个: Sketch 每建一个就向 ConnectivityManager 注册一个网络回调、不注销, 一个应用最多 100 个 —— 每条测试各建一个的话,
     * 整包跑到后面建 Sketch 就抛 TooManyRequestsException (在组合里抛就是整个测试进程崩掉).
     */
    val sketch: Sketch get() = sharedSketch

    fun launch() {
        instrumentation.setInTouchMode(false)
        val intent = Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as ComponentActivity
        onMain {
            root = FrameLayout(activity)
            activity.setContentView(root)
        }
        instrumentation.setInTouchMode(false)
        instrumentation.waitForIdleSync()
    }

    fun close() {
        onMain { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** 按一下 (按下 + 抬起), 等主线程把这一下处理完. */
    fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
    }

    /** 只发按下; [repeatCount] > 0 = 按住的自动连发. */
    fun keyDown(keyCode: Int, repeatCount: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeatCount))
        instrumentation.waitForIdleSync()
    }

    fun keyUp(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        instrumentation.waitForIdleSync()
    }

    /** 在主线程上反复看 [condition], [timeoutMillis] 内不成立就失败. */
    fun waitUntil(what: String, timeoutMillis: Long = 3000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            if (onMain(condition)) return
            if (SystemClock.uptimeMillis() > deadline) fail("等不到: $what")
            SystemClock.sleep(16)
        }
    }
}

private val sharedSketch: Sketch by lazy { Sketch.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build() }

internal fun testTextStyle(sizePx: Float, lineHeightPx: Int): TvNativeTextStyle =
    TvNativeTextStyle(sizePx = sizePx, lineHeightPx = lineHeightPx, letterSpacingEm = 0f, weight = 400, color = Color.WHITE)

/**
 * 1080p 电视 (320dpi, 960 × 540 dp) 上海报墙卡片的尺寸: 卡宽 257 (内容区 1744 排 6 列, 列距 40), 定高番名块. 过渡关掉, 测的只是焦点与停位.
 */
internal fun testWallStyle(): TvNativeWallStyle = TvNativeWallStyle(
    cardWidthPx = 257,
    cardHeightPx = 382,
    gapPx = 4,
    cornerPx = 12f,
    labelHeightPx = 84,
    titleTopGapPx = 12,
    title = testTextStyle(26f, 36),
    subtitleColor = Color.GRAY,
    rowSpacingPx = 40,
    columnSpacingPx = 40,
    focusScale = 1.12f,
    idleShadowColor = 0x66000000,
    idleShadowOffsetYPx = 4f,
    idleShadowBlurPx = 12f,
    focusedElevationPx = 40f,
    titleShiftPx = 23f,
    titleIdleAlpha = 0.5f,
    focusMillis = 0L,
    shadowColor = Color.BLACK,
    placeholderColor = Color.DKGRAY,
    edgeColor = 0x14FFFFFF,
    progressBarHeightPx = 6f,
    progressBarLengthPx = 120f,
    progressBarBottomGapPx = 12f,
    progressTrackColor = Color.GRAY,
    progressFillColor = Color.RED,
    crossfade = false,
    marquee = false,
    prefetchItems = 7,
)

internal fun testCards(count: Int, prefix: String = "卡"): List<TvNativeCard?> =
    List(count) { TvNativeCard(imageUrl = null, title = "$prefix $it") }

/** 卡片事件的记录. */
internal open class RecordingCardListener : TvNativeCardListener {
    val focused = mutableListOf<Int>()
    val clicked = mutableListOf<Int>()
    val longPressed = mutableListOf<Int>()

    override fun onFocused(index: Int) {
        focused += index
    }

    override fun onClick(index: Int) {
        clicked += index
    }

    override fun onLongPress(index: Int, anchor: Rect) {
        longPressed += index
    }
}
