/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebCaptchaDialogHost
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.navigation.BrowserNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.tools.LocalTimeFormatter
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.LocalPlatform
import me.him188.ani.app.ui.foundation.LocalPlatformFontFamily
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.LocalUiScaleApplier
import me.him188.ani.app.ui.foundation.NoopUiScaleApplier
import me.him188.ani.app.ui.foundation.TrackAniDisplayTier
import me.him188.ani.app.ui.foundation.UiScaleApplier
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.input.ActiveInputSourceState
import me.him188.ani.app.ui.foundation.input.LocalActiveInputSource
import me.him188.ani.app.ui.foundation.input.trackActiveInputSource
import me.him188.ani.app.ui.foundation.interaction.clearFocusOnUnhandledTap
import me.him188.ani.app.ui.foundation.navigation.LocalBackDispatcher
import me.him188.ani.app.ui.foundation.navigation.onBackNavigationInput
import me.him188.ani.app.ui.foundation.rememberAniSharedSketch
import me.him188.ani.app.ui.foundation.rememberPlatformFontFamily
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.lang.LocaleZhCN
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isMobile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Stable
class AniAppState(
    val initialNavRoute: NavRoutes,
    val mainSceneInitialPage: MainScreenPage,
    val themeSettings: ThemeSettings,
    val imageLoaderClient: ScopedHttpClient,
    val overlayComposables: List<@Composable () -> Unit>,
    val platformFont: String?
)

@Stable
class AniAppViewModel : AbstractViewModel(), KoinComponent {
    private val settings: SettingsRepository by inject()
    private val httpClientProvider: HttpClientProvider by inject()
    private val mediaCacheManager: MediaCacheManager by inject()
    private val webSessionManager: WebSessionManager by inject()
    private val userRepository: UserRepository by inject()
    private val sessionStateProvider: SessionStateProvider by inject()

    private val imageLoaderClient = httpClientProvider.get(ScopedHttpClientUserAgent.ANI)

    private val mediaCacheComposablesFlow = mediaCacheManager.enabledStorages
        .map { storages ->
            storages.map { @Composable { it.engine.ComposeContent() } }
        }

    val browserNavigator by inject<BrowserNavigator>()

    val bangumiSessionExpired =
        combine(userRepository.selfInfoFlow, sessionStateProvider.stateFlow) { selfInfo, sessionState ->
            val isBound = selfInfo?.bangumiUsername?.isNotBlank() == true
            val serverTokenInvalid = selfInfo?.isBangumiSessionValid == false
            val localTokenMissing = sessionState is SessionState.Valid && !sessionState.bangumiConnected
            isBound && (serverTokenInvalid || localTokenMissing)
        }.distinctUntilChanged().stateInBackground(false)

    val appState: Flow<AniAppState?> = combine(
        settings.themeSettings.flow,
        settings.uiSettings.flow.take(1).map { it.mainSceneInitialPage }, // 只需要读取一次
        settings.uiSettings.flow,
        mediaCacheComposablesFlow,
    ) { themeSettings, mainSceneInitialPage, uiSettings, mediaCacheComposables ->
        AniAppState(
            NavRoutes.Main(mainSceneInitialPage),
            uiSettings.mainSceneInitialPage,
            themeSettings,
            imageLoaderClient,
            mediaCacheComposables + listOf(@Composable { WebCaptchaDialogHost(webSessionManager) }),
            // Windows 并且 ani 语言为中文的话, 显式使用 Microsoft YaHei UI.
            // 如果 Windows 语言不是中文, 那系统会使用 Microsoft JhengHei UI 作为中文字体, 这个字体对简体中文的支持不好.
            if (currentPlatform() is Platform.Windows && uiSettings.appLanguage == LocaleZhCN) {
                "Microsoft YaHei UI"
            } else null,
        )
    }.shareInBackground(
        started = SharingStarted.Eagerly,
        replay = 1,
    )

    suspend fun unbindBangumi() {
        userRepository.unbindBangumi()
    }

}

@Composable
fun AniApp(
    modifier: Modifier = Modifier,
    /**
     * 当前设备的界面行为, 由应用入口决定 (见 [AniUiBehavior]). 共享界面代码只读
     * [LocalAniUiBehavior], 不判断自己跑在什么设备上.
     */
    uiBehavior: AniUiBehavior = AniUiBehavior.Default,
    /**
     * 把界面缩放落到窗口层的平台能力, 由应用入口提供 (见 [UiScaleApplier]).
     * 缺省实现不做任何事, 此时界面缩放只在 Compose 层生效.
     */
    uiScaleApplier: UiScaleApplier = NoopUiScaleApplier,
    content: @Composable () -> Unit,
) {
    val viewModel = viewModel { AniAppViewModel() }
    // 图片档位跟随渲染分辨率 (原生 4K UI 要更高档, 见 AniDisplayTier): 排在最前, 首屏图片发请求
    // 之前就得定下来 —— 档位进 URL、URL 进缓存键, 首屏拿了低档这次启动就一直是低档
    TrackAniDisplayTier()
    // 主题读好再进入 APP, 防止黑白背景闪烁
    val appState = viewModel.appState.collectAsStateWithLifecycle(null).value ?: return

    // 界面缩放: 补偿部分电视/盒子上报错误的 densityDpi.
    //
    // 窗口层 (Activity 的 densityDpi) 已经按 appliedScale 缩放过了 —— 这里读到的 systemDensity
    // 就含着它, 弹窗等独立 window 也同此值. 所以只需补上「设置值与基线的差」, 直接乘 uiScale 会叠乘两次.
    // 差值仅在用户调整设置、窗口层尚未对齐的那段时间里不为 1 (见 UiScaleSyncEffect).
    //
    // LocalDensity 是 static composition local, 每次提供新实例都会重组整棵树, 因此必须 remember,
    // 且无需补偿时原样透传 (绝大多数情况走这条路, 完全没有额外开销).
    val systemDensity = LocalDensity.current
    val uiScale = appState.themeSettings.effectiveUiScale
    val appliedUiScale = uiScaleApplier.appliedScale
    val scaledDensity = remember(systemDensity, uiScale, appliedUiScale) {
        if (uiScale == appliedUiScale) systemDensity
        else Density(systemDensity.density / appliedUiScale * uiScale, systemDensity.fontScale)
    }

    CompositionLocalProvider(
        LocalSketch provides rememberAniSharedSketch(appState.imageLoaderClient),
        LocalTimeFormatter provides remember { TimeFormatter() },
        LocalThemeSettings provides appState.themeSettings,
        LocalPlatformFontFamily provides rememberPlatformFontFamily(appState.platformFont),
        LocalActiveInputSource provides remember { ActiveInputSourceState() },
        LocalAniUiBehavior provides uiBehavior,
        LocalDensity provides scaledDensity,
        LocalUiScaleApplier provides uiScaleApplier,
    ) {
        val focusManager by rememberUpdatedState(LocalFocusManager.current)
        val keyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
        val backDispatcher by rememberUpdatedState(LocalBackDispatcher.current)

        AniTheme {
            Box(
                modifier = modifier
                    .trackActiveInputSource(LocalActiveInputSource.current)
                    // 上游这条管的是 Escape 与鼠标侧键 (桌面); 下面 fork 那条管遥控器的 BACK 键,
                    // 两者按键不重叠, 必须都在
                    .onBackNavigationInput(backDispatcher::onBackPressed)
                    .ifThen(uiBehavior.focusDrivenNavigation) {
                        // 焦点导航下 Compose 框架会把未被任何组件消费的 BACK KeyDown 映射为 FocusDirection.Exit:
                        // 焦点退到不可见的父容器并消费事件, Activity 收不到按键, 表现为 "焦点丢失但页面不变".
                        // 在根部冒泡阶段兜底拦截: 页面内自己的 Back 处理 (如播放器) 和 Dialog (独立 window) 仍然优先.
                        //
                        // 模仿框架的 back-tracking 语义: 只有 KeyDown 也是这里消费的, KeyUp 才触发返回.
                        // 子组件 (如评论列表把焦点还给 tab 按钮) 常常只消费 KeyDown, 随后的 KeyUp 会从新焦点
                        // 冒泡到这里 —— 这种孤儿 KeyUp 必须忽略, 否则会额外触发一次真正的返回.
                        val sawBackDown = remember { mutableStateOf(false) }
                        onKeyEvent { event ->
                            if (event.key == Key.Back) {
                                when (event.type) {
                                    KeyEventType.KeyDown -> sawBackDown.value = true
                                    KeyEventType.KeyUp -> {
                                        if (sawBackDown.value) backDispatcher.onBackPressed()
                                        sawBackDown.value = false
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }
                    .ifThen(LocalPlatform.current.isMobile()) {
                        clearFocusOnUnhandledTap()
                    },
            ) {
                Box {
                    for (composable in appState.overlayComposables) {
                        composable()
                    }
                }

                Column {
                    content()
                }
            }
        }
    }
}
