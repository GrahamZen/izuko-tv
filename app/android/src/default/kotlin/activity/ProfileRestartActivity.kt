/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.window.OnBackInvokedDispatcher
import androidx.core.content.IntentCompat
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.app.platform.ProfileSwitchFrame
import me.him188.ani.app.platform.ProfileSwitchFrameDrawable

/**
 * 换用户时重启应用的中转站 (思路同 ProcessPhoenix): 跑在独立进程 ([PROCESS_SUFFIX]) 里, 结束主进程后由它拉起新的主进程.
 *
 * 要中转是因为主进程没法自己结束了再把自己拉起来; 用 AlarmManager 定时拉起又会被 Android 10 起「后台不许启动界面」的限制拦下.
 * 这个 Activity 本身在前台, 由它启动界面是允许的. 种子下载在自己的服务进程里, 不跟着主进程结束.
 *
 * 画面接力 (见 [ProfileSwitchFrame]): 主题透明, 系统不给它画启动窗口, 下面选人页定格的那一帧一直露着; 自己把同一帧画上屏、系统确实显示出来之后 (见 WhenShown)
 * 才结束主进程, 再打开同样透明的落地页 [ProfileSwitchLandingActivity] (在新的主进程里, 进程初始化期间露着的仍是这里的这一帧).
 * 自己不 finish: 落地页打开主界面时带着 CLEAR_TASK, 连同这里一起清掉, 在主界面画出第一帧之前这一帧一直在屏上.
 */
class ProfileRestartActivity : Activity() {
    private var shown: WhenShown? = null

    private val fallback = Runnable {
        // 落地页一直没起来 (新进程启动就出错之类): 不能停在这一帧上, 直接打开主界面
        IntentCompat.getParcelableExtra(intent, EXTRA_LAUNCH_INTENT, Intent::class.java)?.let { startActivity(it) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // 被系统重建 (中转途中不该发生): 主进程早已换过, 什么都不再做
            finish()
            return
        }
        val launch = IntentCompat.getParcelableExtra(intent, EXTRA_LAUNCH_INTENT, Intent::class.java)
        val content = profileSwitchFrameView(this, ProfileSwitchFrameDrawable.FLOOR_RELAY)
        setContentView(content)
        swallowBack(this)
        shown = WhenShown(content, "Relay") {
            becomeOpaque(this)
            val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
            if (mainPid > 0) Process.killProcess(mainPid)
            // 等系统确认旧进程没了再开落地页: 不然系统还当它活着, 会把落地页往那个正在结束的进程里送 (报错后才另起新进程补上)
            whenProcessGone(mainPid) {
                startActivity(
                    Intent(this, ProfileSwitchLandingActivity::class.java).putExtra(EXTRA_LAUNCH_INTENT, launch),
                    noAnimation(this),
                )
            }
        }
        window.decorView.postDelayed(fallback, FALLBACK_MILLIS)
    }

    private fun whenProcessGone(pid: Int, action: () -> Unit) {
        val activityManager = getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.uptimeMillis() + PROCESS_GONE_TIMEOUT_MILLIS
        val check = object : Runnable {
            override fun run() {
                val alive = pid > 0 && activityManager?.runningAppProcesses?.any { it.pid == pid } == true
                if (alive && SystemClock.uptimeMillis() < deadline) {
                    window.decorView.postDelayed(this, PROCESS_GONE_POLL_MILLIS)
                } else {
                    action()
                }
            }
        }
        check.run()
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        shown?.onEnterAnimationComplete()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(fallback)
        super.onDestroy()
        // 这个进程只为这一页, 页面没了就退出
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    /** 过场期间不响应按键. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true

    companion object {
        /** 进程名后缀. 这个进程不做应用初始化 (见 AniApplication). */
        const val PROCESS_SUFFIX = "profile_restart"

        /** 重启后打开的主界面带着它: 刚选过人, 不再弹选人页. */
        const val EXTRA_PROFILE_CHOSEN = "me.him188.ani.profileChosen"

        private const val EXTRA_MAIN_PID = "mainPid"
        internal const val EXTRA_LAUNCH_INTENT = "launchIntent"

        private const val FALLBACK_MILLIS = 15_000L
        private const val PROCESS_GONE_POLL_MILLIS = 16L
        private const val PROCESS_GONE_TIMEOUT_MILLIS = 3_000L

        fun restart(context: Context) {
            val packageManager = context.packageManager
            val launch = packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                ?: packageManager.getLaunchIntentForPackage(context.packageName)
                ?: error("No launch intent for ${context.packageName}")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            launch.putExtra(EXTRA_PROFILE_CHOSEN, true)
            context.startActivity(
                Intent(context, ProfileRestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_MAIN_PID, Process.myPid())
                    .putExtra(EXTRA_LAUNCH_INTENT, launch),
                noAnimation(context),
            )
        }
    }
}

/**
 * 换人重启途中新的主进程里的第一页: 同样透明 (没有启动窗口, 进程初始化期间露着的是中转页的那一帧), 自己把同一帧画上屏、系统确实显示出来之后
 * 打开主界面. 主界面起在已经在跑的进程里, 系统不再给它画启动窗口, 等它画出第一帧才撤掉这里 (见 [ProfileRestartActivity]).
 */
class ProfileSwitchLandingActivity : Activity() {
    private var shown: WhenShown? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = IntentCompat.getParcelableExtra(intent, ProfileRestartActivity.EXTRA_LAUNCH_INTENT, Intent::class.java)
        if (savedInstanceState != null || launch == null) {
            finish()
            return
        }
        val content = profileSwitchFrameView(this, ProfileSwitchFrameDrawable.FLOOR_LANDING)
        setContentView(content)
        swallowBack(this)
        // 不 finish: 主界面带着 CLEAR_TASK, 把这里和中转页一起清掉
        shown = WhenShown(content, "Landing") {
            becomeOpaque(this)
            startActivity(launch, noAnimation(this))
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        shown?.onEnterAnimationComplete()
    }

    /** 过场期间不响应按键. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true
}

class AndroidAppRestarter(private val context: Context) : AppRestarter {
    override val isSupported: Boolean get() = true
    override fun restart() = ProfileRestartActivity.restart(context)
}

/**
 * 铺满全屏的那一帧与进度条 (见 [ProfileSwitchFrameDrawable]), [floor] 是这一页的进度下限; 没截到时是选人页四周的那种深灰.
 */
private fun profileSwitchFrameView(context: Context, floor: Float): View = View(context).apply {
    val frame = ProfileSwitchFrame.read(context)
    if (frame != null) {
        background = ProfileSwitchFrameDrawable(frame, floor)
    } else {
        setBackgroundColor(FALLBACK_BACKGROUND)
    }
}

private const val FALLBACK_BACKGROUND = 0xFF1C1C1E.toInt()

/**
 * [view] 的第一帧真正上屏之后 (交给显示合成、再过一帧) 执行 [action]: 下面那一页要等这里盖住了才能撤.
 */
private fun whenFramePresented(view: View, action: () -> Unit) {
    var done = false
    val run = {
        if (!done) {
            done = true
            action()
        }
    }
    val afterNextFrame = { Choreographer.getInstance().postFrameCallback { run() } }
    view.viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    view.viewTreeObserver.registerFrameCommitCallback { view.post { afterNextFrame() } }
                } else {
                    // 旧系统没有「这一帧交给合成了」的回调: 多等一帧
                    Choreographer.getInstance().postFrameCallback { afterNextFrame() }
                }
                return true
            }
        },
    )
}

/**
 * 这一页确实显示在屏上之后执行 [action] (之后才能撤掉下面那一页): 自己的第一帧上屏 ([whenFramePresented]) 之外, 还要等系统把窗口
 * 显示出来 —— 以系统通知进场结束 ([Activity.onEnterAnimationComplete], 没有切换动画也会通知) 为准. 系统忙的时候 (换人那一刻常有一批
 * 进程同时被内存不够杀掉) 两者能差出一两百毫秒, 这期间结束旧进程, 旧进程的窗口一个个撤掉, 屏上会露出旧用户的首页 (Shield 上撞到过).
 * 两件事都要等到: 这一页几秒都没画出来时, 新系统到点就把进场算作结束, 通知反而先到.
 * 通知一直不来就在画好 [SHOWN_FALLBACK_MILLIS] 之后照常往下走. 两个时刻记一行日志, 过场出问题时对照系统的窗口记录用.
 */
private class WhenShown(private val view: View, private val page: String, private val action: () -> Unit) {
    private val createdAt = SystemClock.uptimeMillis()
    private var presentedAt = -1L
    private var enteredAt = -1L
    private var done = false
    private val fallback = Runnable { proceed(timedOut = true) }

    init {
        whenFramePresented(view) {
            presentedAt = SystemClock.uptimeMillis()
            if (enteredAt >= 0) proceed(timedOut = false) else view.postDelayed(fallback, SHOWN_FALLBACK_MILLIS)
        }
    }

    fun onEnterAnimationComplete() {
        if (enteredAt < 0) enteredAt = SystemClock.uptimeMillis()
        if (presentedAt >= 0) proceed(timedOut = false)
    }

    private fun proceed(timedOut: Boolean) {
        if (done) return
        done = true
        view.removeCallbacks(fallback)
        val entered = if (enteredAt >= 0) "+${enteredAt - createdAt}ms" else "not yet"
        Log.i(LOG_TAG, "$page shown: frame presented +${presentedAt - createdAt}ms, enter complete $entered" + if (timedOut) " (timed out)" else "")
        action()
    }
}

private const val SHOWN_FALLBACK_MILLIS = 500L
private const val LOG_TAG = "ProfileSwitch"

/**
 * 显示出来之后转成不透明. 主题透明只是为了没有启动窗口 (下面那一页一直露着); 画好之后还透明的话, 旧进程一结束系统就得把后面的桌面
 * 露出来 —— 桌面进程要是正好被内存不够杀了, 系统会把它重新拉起, 它一起来就把自己调到前台, 盖住刚起来的新主界面 (Shield 上撞到过).
 * 转成不透明之后后面什么都不用露. 老系统 (Android 10 及以下) 没有这个接口, 仍是透明的.
 */
private fun becomeOpaque(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.setTranslucent(false)
}

/** 过场的两页之间、到主界面都不要切换动画: 画面本来就是同一帧. */
private fun noAnimation(context: Context): Bundle? = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()

/** 过场期间的返回什么都不做 (新系统上返回不以按键送进来, 要另外拦). */
private fun swallowBack(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY) {}
    }
}
