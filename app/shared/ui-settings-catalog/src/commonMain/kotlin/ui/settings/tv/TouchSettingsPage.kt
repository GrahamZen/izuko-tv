/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.navigation.rememberAsyncBrowserNavigator
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.effects.rememberNoticeSoundPlayer
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_source_done
import me.him188.ani.app.ui.lang.settings_mediasource_cancel
import me.him188.ani.app.ui.settings.SettingsViewModel
import me.him188.ani.app.ui.settings.UiScaleSyncEffect
import me.him188.ani.app.ui.settings.account.ProfileViewModel
import me.him188.ani.app.ui.settings.tabs.media.Content
import me.him188.ani.app.ui.settings.tabs.media.MediaSourceSubscriptionSettingsPage
import me.him188.ani.app.ui.settings.tabs.media.SelectionBar
import me.him188.ani.app.ui.settings.tabs.media.rememberMediaSourceSettingsSection
import me.him188.ani.app.ui.update.AppUpdateViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * 触屏设备的设置页: 分类与设置项同电视版 (画的是同一份清单 [TvSettingsCatalogEntries], 状态是 [TvSettingsState]), 样子照原来的设置页 ——
 * 左栏分类, 右栏设置项 (开关、单选进一层、动作), 说明写在每一项下面. 窄屏一次只显示一栏.
 *
 * 电视上要扫码到手机控制台去设的项 (要打字), 这里换成打开原来的设置页的那一类 ([legacy]), 盖在上面, 返回就关.
 * [platform] 是这一端独有的东西 (见 [TvSettingsPlatform]): 电视包装在平板上时给电视版那一份, 用到的项照常显示.
 */
@Composable
fun TouchSettingsPage(
    vm: SettingsViewModel,
    initialTab: SettingsTab?,
    legacy: @Composable (SettingsTab) -> Unit,
    loadOpenSourceLibrariesJsons: suspend () -> List<ByteArray>,
    platform: TvSettingsPlatform,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appUpdate = viewModel { AppUpdateViewModel() }
    val profile = viewModel { ProfileViewModel() }
    val sketch = LocalSketch.current
    val navigator = LocalNavigator.current
    val deps = remember {
        TvSettingsDeps(
            context, vm, appUpdate, profile, sketch, loadOpenSourceLibrariesJsons, scope, platform,
            touch = TvSettingsTouch(openBangumiLogin = navigator::navigateBangumiAuthorize),
        )
    }
    val state = remember {
        TvSettingsState(
            catalog = TvSettingsCatalogEntries,
            repository = GlobalKoin.get<SettingsRepository>(),
            deps = deps,
            initialCategory = initialTab?.name ?: TouchSettingsLastPosition.categoryId,
            scope = scope,
            extraKeys = listOf(TvSettingKeys.debug),
            loadTemplate = { getStringOffMain(TouchSettingsTextOverrides[it] ?: it) },
        )
    }
    val playNoticeSound = rememberNoticeSoundPlayer()
    val toaster = LocalToaster.current
    val browser = rememberAsyncBrowserNavigator()
    LaunchedEffect(state) {
        state.attach(TvSettingsHost(playNoticeSound, toast = { toaster.toast(it) }, browser))
    }
    // 界面缩放改了: 离开设置页时把窗口层 (弹窗 / 菜单) 一并对齐 (同原来的设置页)
    UiScaleSyncEffect()

    // 「数据源管理」那一类的右栏嵌原来设置页的那一块 (网盘、订阅、数据源列表); 清单里只剩它没有的几项 (见 TvSettingKeys.touchPage)
    val mediaSources = rememberMediaSourceSettingsSection(vm)
    val mediaSourcesEmbedded = remember(mediaSources) {
        TouchSettingsEmbedded(
            content = { paneColor ->
                mediaSources.Content(
                    backgroundColor = paneColor,
                    onOpenSubscription = { id ->
                        state.overlay.value = TvSettingsOverlay.Dialog { dismiss -> MediaSourceSubscriptionSettingsPage(vm, id, onBack = dismiss) }
                    },
                )
            },
            bottomBar = {
                mediaSources.SelectionBar(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom), Modifier.align(Alignment.BottomCenter))
            },
        )
    }

    val content by state.content.collectAsStateWithLifecycle()
    val overlay by state.overlay.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(AniThemeDefaults.pageContentBackgroundColor)) {
        content?.let {
            TouchSettingsLayout(it, state, initialTab != null, embedded = { id -> mediaSourcesEmbedded.takeIf { id == SettingsTab.MEDIA_SOURCE.name } })
        }
        when (val current = overlay) {
            is TvSettingsOverlay.Legacy -> TouchLegacySettingsCover(onClose = state::dismissOverlay) { legacy(current.tab) }
            is TvSettingsOverlay.Dialog -> current.content(state::dismissOverlay)
            null -> {}
        }
    }

    TvSettingsUpdateHost(appUpdate)
    TvPikPakLegacyNoticeHost(vm)

    val pending by state.pendingConfirm.collectAsStateWithLifecycle()
    pending?.let { p -> TouchSettingsConfirmDialog(p.confirm, onConfirm = state::confirmPending, onDismiss = state::dismissPending) }
}

/**
 * 原来的设置页 (打开某一类) 整页盖在上面, 右上角「完成」关掉它回到这里, 返回键也关它.
 * 它自己顶栏上的返回是退出整个设置页 (同原来), 所以不再叠一条顶栏.
 */
@Composable
private fun TouchLegacySettingsCover(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(AniThemeDefaults.pageContentBackgroundColor)) {
        content()
        FilledTonalButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(12.dp),
        ) {
            Text(stringResource(Lang.settings_media_source_done))
        }
    }
}

/** 写之前先问的一句 (见 [TvSettingsConfirm]): 确认按钮在右, 危险的用红字. */
@Composable
private fun TouchSettingsConfirmDialog(confirm: TvSettingsConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AniAlertDialog(
        onDismissRequest = onDismiss,
        title = confirm.title?.let { title -> { Text(rememberTvText(title)) } },
        text = { Text(rememberTvText(confirm.text)) },
        confirmButton = {
            AniTextButton(onClick = onConfirm) {
                Text(
                    rememberTvText(confirm.confirmLabel),
                    color = if (confirm.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            AniTextButton(onClick = onDismiss) { Text(stringResource(Lang.settings_mediasource_cancel)) }
        },
    )
}

/** 在组合里把 [TvText] 换成字 (弹窗用; 页面本身的字由状态里读好的模板换). */
@Composable
private fun rememberTvText(text: TvText): String = when (text) {
    is TvText.Plain -> text.text
    is TvText.Res -> {
        val args = text.args.map { if (it is TvText) rememberTvText(it) else it }
        stringResource(TouchSettingsTextOverrides[text.res] ?: text.res, *args.toTypedArray())
    }

    is TvText.Join -> text.parts.map { rememberTvText(it) }.joinToString(text.separator)
}

/** 设置页上次停在哪一类 (进程内记着, 再进设置页落回去). */
internal object TouchSettingsLastPosition {
    var categoryId: String? = null
}

/**
 * 触屏版设置页给清单的东西 ([TvSettingsDeps.touch]). 有它时 ([TvSettingKeys.touchPage]): 登录只给一行进整页的登录页 ([openBangumiLogin]),
 * 「数据源管理」只列嵌进来的原来设置页那一块没有的项 (见 mediaSourceItems), 不给多用户的设置.
 */
class TvSettingsTouch(
    /** 打开整页的登录页: 按线路给授权、账号密码或个人令牌. */
    val openBangumiLogin: () -> Unit,
)
