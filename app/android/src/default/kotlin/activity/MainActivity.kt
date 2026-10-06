/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import me.him188.ani.android.BuildConfig
import me.him188.ani.android.FormFactorStartupPlaceholder
import me.him188.ani.android.InstallFormFactorUi
import me.him188.ani.android.formFactorUiBehavior
import me.him188.ani.android.onFormFactorActivityCreated
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.pip.PIPModeChangedListener
import me.him188.ani.app.pip.PictureInPictureHost
import me.him188.ani.app.pip.UserLeaveHintListener
import me.him188.ani.app.platform.AniComponentActivity
import me.him188.ani.app.platform.ProfileSwitchFrame
import me.him188.ani.app.platform.ProfileSwitchFrameDrawable
import me.him188.ani.app.platform.rememberPlatformWindow
import me.him188.ani.app.ui.exprovider.ExternalContentProviderFactory
import me.him188.ani.app.ui.exprovider.LocalExternalContentProvider
import me.him188.ani.app.ui.foundation.UiScaleApplier
import me.him188.ani.app.ui.foundation.tv.TvPolishFlags
import me.him188.ani.app.ui.foundation.layout.LocalPlatformWindow
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.SystemBarColorEffect
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.Toaster
import me.him188.ani.app.ui.main.AniApp
import me.him188.ani.app.ui.main.AniAppContent
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import org.koin.android.ext.android.inject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class MainActivity : AniComponentActivity(), PictureInPictureHost {
    private val logger = logger<MainActivity>()
    private val aniNavigator = AniNavigator()

    private val externalContentProviderFactory: ExternalContentProviderFactory by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val bangumiOAuthManager: BangumiOAuthManager by inject()

    /**
     * 本次 Activity 创建时落到窗口层的界面缩放, 由 [attachBaseContext] 定下, 之后不再变 ——
     * 主窗口与所有弹窗都按它渲染. 见 [UiScaleApplier].
     */
    private var appliedUiScale: Float = 1f

    /**
     * 已经请求过重建. `recreate()` 自己会销毁 Compose 树, 从而**再次**触发那个「离开设置页就对齐」的
     * onDispose —— 那时读到的仍是本 Activity 的旧 [appliedUiScale], 会对着一个正在销毁的 Activity
     * 再调一次 `recreate()`. 这个标志让重建请求只发一次.
     */
    private var uiScaleRestartRequested = false

    private val uiScaleApplier = object : UiScaleApplier {
        override val appliedScale: Float get() = appliedUiScale

        override fun apply(scale: Float) {
            if (scale == appliedUiScale || uiScaleRestartRequested) return
            if (isFinishing || isDestroyed) return
            uiScaleRestartRequested = true
            // 重建后的 attachBaseContext 会重新读镜像, 所以必须先落盘再 recreate
            UiScaleMirror.write(this@MainActivity, scale)
            recreate()
        }
    }

    /**
     * 界面缩放要改的是 Activity 的 `densityDpi` —— 只有这样弹窗 (各自独立 window) 才会跟着变.
     * 这是唯一能在 Activity 创建前介入的时机.
     */
    override fun attachBaseContext(newBase: Context) {
        val scale = UiScaleMirror.read(newBase)
        appliedUiScale = scale
        super.attachBaseContext(newBase.withUiScale(scale))
    }

    private val userLeaveHintListeners = CopyOnWriteArrayList<UserLeaveHintListener>()
    private val pipModeChangedListeners = CopyOnWriteArrayList<PIPModeChangedListener>()

    override fun registerUserLeaveHintListener(listener: UserLeaveHintListener): AutoCloseable {
        userLeaveHintListeners.add(listener)
        return AutoCloseable { userLeaveHintListeners.remove(listener) }
    }

    override fun registerPictureInPictureModeChangedListener(listener: PIPModeChangedListener): AutoCloseable {
        pipModeChangedListeners.add(listener)
        return AutoCloseable { pipModeChangedListeners.remove(listener) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeaveHintListeners.forEach { it.onUserLeaveHint() }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeChangedListeners.forEach { it.onChanged(isInPictureInPictureMode) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleStartIntent(intent)
    }

    private fun handleStartIntent(intent: Intent) {
        // TV 动效精修项的运行时开关 (A/B 录像对比用), 见 TvPolishFlags
        TvPolishFlags.pressDim = intent.getBooleanExtra("ani_polish_press_dim", TvPolishFlags.pressDim)
        TvPolishFlags.textStagger = intent.getBooleanExtra("ani_polish_text_stagger", TvPolishFlags.textStagger)
        TvPolishFlags.heroZoom = intent.getBooleanExtra("ani_polish_hero_zoom", TvPolishFlags.heroZoom)
        TvPolishFlags.shrinkPopFirst = intent.getBooleanExtra("ani_polish_shrink_pop_first", TvPolishFlags.shrinkPopFirst)
        TvPolishFlags.shrinkKeep = intent.getIntExtra("ani_polish_shrink_keep", TvPolishFlags.shrinkKeep)
        TvPolishFlags.shrinkCurve = intent.getIntExtra("ani_polish_shrink_curve", TvPolishFlags.shrinkCurve)
        TvPolishFlags.zoomScrimT = intent.getFloatExtra("ani_polish_zoom_scrim_t", TvPolishFlags.zoomScrimT)
        TvPolishFlags.shrinkScrimT = intent.getFloatExtra("ani_polish_shrink_scrim_t", TvPolishFlags.shrinkScrimT)
        TvPolishFlags.zoomSoftEdge = intent.getBooleanExtra("ani_polish_zoom_soft_edge", TvPolishFlags.zoomSoftEdge)
        TvPolishFlags.pagerSkipOffscreen = intent.getBooleanExtra("ani_polish_pager_skip_offscreen", TvPolishFlags.pagerSkipOffscreen)
        TvPolishFlags.startupLogo = intent.getBooleanExtra("ani_polish_startup_logo", TvPolishFlags.startupLogo)
        val data = intent.data ?: return
        if (data.scheme != "ani") return
        if (data.host == "bangumi-oauth-callback") {
            // 外部浏览器那条登录路的回调 (应用内浏览器是自己拦下来的, 不经过系统).
            // 授权页可能早就不在了, 所以喂给进程内的单例而不是某个页面
            val url = data.toString()
            lifecycleScope.launch {
                runCatching { bangumiOAuthManager.submitCallbackUrl(url) }
                    .onFailure { logger.error(it) { "Failed to handle bangumi oauth callback" } }
            }
            return
        }
        when (data.host) {
            "subjects" -> {
                val id = data.pathSegments.getOrNull(0)?.toIntOrNull() ?: return
                navigateWhenReady("subject details") { navigateSubjectDetails(id, placeholder = null) }
            }
        }
    }

    private fun navigateWhenReady(destination: String, action: AniNavigator.() -> Unit) {
        lifecycleScope.launch {
            try {
                if (!aniNavigator.isBackStackReady()) {
                    aniNavigator.awaitBackStack()
                    delay(1000) // 等待初始化好, 否则跳转可能无效
                }
                aniNavigator.action()
            } catch (e: Exception) {
                logger.error(e) { "Failed to navigate to $destination" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 用户主题的外壳底色先铺上: 清单主题只能是固定的纯黑, 浅色主题在第一帧之前也不该露黑
        WindowBackgroundMirror.read(this)?.let { window.setBackgroundDrawable(ColorDrawable(it)) }
        if (intent.getBooleanExtra(ProfileRestartActivity.EXTRA_PROFILE_CHOSEN, false)) {
            UserProfiles.launchedBySwitch = true
            // 换人重启进来的: 接力的那一帧先铺成窗口底 (界面组合出来之前露的就是它), 界面上的过场接着显示它, 见 ProfileSwitchFrame
            if (savedInstanceState == null) {
                ProfileSwitchFrame.read(this)?.let { frame ->
                    val landing = ProfileSwitchFrameDrawable(frame, ProfileSwitchFrameDrawable.FLOOR_MAIN)
                    ProfileSwitchFrame.showLanding(landing)
                    window.setBackgroundDrawable(landing)
                    holdBackWhileLanding()
                }
            }
        }
        handleStartIntent(intent)

        // 本形态 (phone / tv) 的附加初始化, 见各 flavor 下的 FormFactorSetup.kt
        onFormFactorActivityCreated(this)

        enableEdgeToEdge(
            // 透明状态栏
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            // 透明导航栏
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        // 允许画到 system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val toaster = object : Toaster {
            override fun toast(text: String) {
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
            }
        }

        val externalContentProvider = externalContentProviderFactory.create(this, lifecycleScope)

        // 把界面缩放抄进 SharedPreferences: attachBaseContext 拿不到 DataStore (还没有协程可用).
        // 挪到 IO: write 是同步落盘 (commit), 拖滑块每过一格都会触发一次, 放主线程会加剧拖动时的卡顿
        lifecycleScope.launch(Dispatchers.IO) {
            settingsRepository.themeSettings.flow
                .map { it.effectiveUiScale }
                .distinctUntilChanged()
                .collect { UiScaleMirror.write(this@MainActivity, it) }
        }

        setContent {
            // 界面行为由本形态决定, 共享界面代码不判断设备 (见 AniUiBehavior)
            AniApp(
                uiBehavior = formFactorUiBehavior,
                uiScaleApplier = uiScaleApplier,
                // 设置读出来之前: 电视端一打开就先画启动页
                loadingPlaceholder = { FormFactorStartupPlaceholder() },
            ) {
                val externalComponentProviderUpdated by rememberUpdatedState(externalContentProvider)

                SystemBarColorEffect()

                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalPlatformWindow provides rememberPlatformWindow(this),
                    LocalExternalContentProvider provides externalComponentProviderUpdated,
                ) {
                    // Expose Modifier.testTag as resource-id in accessibility/uiautomator dumps,
                    // so UI-automation agents can locate elements by stable ids (debug only).
                    @OptIn(ExperimentalComposeUiApi::class)
                    val rootModifier = if (BuildConfig.DEBUG) {
                        Modifier.semantics { testTagsAsResourceId = true }
                    } else {
                        Modifier
                    }
                    Box(rootModifier) {
                        // 本形态特有的页面变体装配 (见各 Local*Variant 插槽)
                        InstallFormFactorUi(aniNavigator) {
                            // 在形态的配色里读: 电视端浅色主题的页面底换成了海报墙那档浅灰 (见 TvPageBackgroundTheme), 窗口底色要跟页面实际铺的一致
                            WindowBackgroundSyncEffect()
                            AniAppContent(aniNavigator)
                        }
                    }
                }
            }
        }
    }

    /**
     * 换人进来的过场盖着 ([ProfileSwitchFrame.landing]) 的时候主界面不接按键与返回: 按下去的会落到被盖住的首页上
     * (比如首页的「再按一次返回退出」, 过场一撤提示就露出来). 按键在 [dispatchKeyEvent] 里吞掉; 新系统上返回不以按键送进来,
     * 在窗口的返回回调里以浮层优先级拦下 (先于应用里所有的返回处理), 过场撤了就撤掉.
     * 界面上的过场最多盖几秒 (见 TvProfileSwitchLandingHost); 它要是一直没撤 (界面没起来), 到点这里自己放掉, 遥控器不会一直没反应.
     */
    private fun holdBackWhileLanding() {
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnBackInvokedCallback {}.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, it)
            }
        } else {
            null
        }
        lifecycleScope.launch {
            val dismissed = withTimeoutOrNull(PROFILE_SWITCH_LANDING_MAX) {
                ProfileSwitchFrame.landing.first { it == null }
                true
            }
            if (dismissed == null) ProfileSwitchFrame.release(this@MainActivity)
            if (callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ProfileSwitchFrame.landing.value != null) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * 窗口底色跟着页面的外壳底色走, 并抄进 [WindowBackgroundMirror] 给下次启动 (与界面缩放的重建) 用.
     * 要在形态的页面配色里调用 (见 InstallFormFactorUi), 读到的才是页面实际铺的那个颜色.
     */
    @Composable
    private fun WindowBackgroundSyncEffect() {
        val color = AniThemeDefaults.shellBackgroundColor.toArgb()
        LaunchedEffect(color) {
            window.setBackgroundDrawable(ColorDrawable(color))
            WindowBackgroundMirror.write(this@MainActivity, color)
        }
    }
}

/** 换人进来的过场最多盖多久 (界面上的过场自己最多等 8 秒封面再淡出, 见 TvProfileSwitchLandingHost). */
private val PROFILE_SWITCH_LANDING_MAX = 12.seconds
