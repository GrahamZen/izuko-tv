/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.ui.adaptive.AniTopAppBar
import me.him188.ani.app.ui.adaptive.AniTopAppBarDefaults
import me.him188.ani.app.ui.adaptive.ListDetailLayoutParameters
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.widgets.BackNavigationIconButton
import me.him188.ani.app.ui.settings.DetailPaneRoutes
import me.him188.ani.app.ui.settings.SettingsDetailPaneScope
import me.him188.ani.app.ui.settings.SettingsTab
import me.him188.ani.app.ui.settings.SettingsViewModel
import me.him188.ani.app.ui.settings.tabs.media.source.MediaSourceSelectionActions
import me.him188.ani.app.ui.settings.tabs.media.source.MediaSourceSelectionState
import me.him188.ani.app.ui.settings.tabs.media.source.MediaSourceSubscriptionPageActions
import me.him188.ani.app.ui.settings.tabs.media.source.MediaSourceSubscriptionPageContent
import me.him188.ani.app.ui.settings.tabs.media.source.MediaSourceTab
import me.him188.ani.app.ui.settings.tabs.media.source.pageTitle
import me.him188.ani.app.ui.settings.tabs.media.source.rememberMediaSourceSelectionState

/**
 * 原来设置页「数据源管理」那一类的内容 (网盘、订阅、数据源列表), 给别的设置页整块嵌进去用 (平板模式的触屏设置页).
 * 用户自己加的 WebDAV 服务器 (账号密码登录的网盘) 不在这里, 由嵌它的页面自己画.
 *
 * 嵌的页面要画三样: [Content] (这一类的内容, 放在不自己滚动的容器里), [SelectionBar] (长按数据源进多选后的操作条, 贴在底部),
 * 订阅详情页 [MediaSourceSubscriptionSettingsPage] (点订阅时盖在上面).
 */
@Stable
class MediaSourceSettingsSection internal constructor(
    internal val vm: SettingsViewModel,
    internal val selectionState: MediaSourceSelectionState,
)

@Composable
fun rememberMediaSourceSettingsSection(vm: SettingsViewModel): MediaSourceSettingsSection {
    val selectionState = rememberMediaSourceSelectionState()
    return remember(vm, selectionState) { MediaSourceSettingsSection(vm, selectionState) }
}

/** [backgroundColor]: 这一块铺在什么底色上 (见 MediaSourceGroup). */
@Composable
fun MediaSourceSettingsSection.Content(
    backgroundColor: Color,
    onOpenSubscription: (subscriptionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsTab(modifier) {
        val cloudDrives by vm.cloudDriveGroupStates.collectAsStateWithLifecycle()
        for (drive in cloudDrives) {
            if (drive.protocol.supportsPasswordLogin) continue
            key(drive.driveId) { CloudDriveGroup(drive) }
        }
        MediaSourceTab(
            vm.mediaSourceSubscriptionGroupState,
            vm.mediaSourceGroupState,
            vm.editMediaSourceState,
            selectionState,
            backgroundColor = backgroundColor,
            onOpenSubscription = onOpenSubscription,
        )
    }
}

/** 多选数据源时的操作条 (启用、停用、删除等), 不在多选时不画. */
@Composable
fun MediaSourceSettingsSection.SelectionBar(windowInsets: WindowInsets, modifier: Modifier = Modifier) {
    AniAnimatedVisibility(visible = selectionState.inSelection, modifier = modifier) {
        MediaSourceSelectionActions(
            mediaSources = vm.mediaSourceGroupState.mediaSources,
            selectionState = selectionState,
            editState = vm.editMediaSourceState,
            windowInsets = windowInsets,
        )
    }
}

/** 订阅详情页 (订阅里的数据源、更新、删除), 整页: 顶栏返回、标题与操作. 订阅被删除后自己返回. */
@Composable
fun MediaSourceSubscriptionSettingsPage(
    vm: SettingsViewModel,
    subscriptionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val parameters = ListDetailLayoutParameters(
        listPaneContentStartPadding = 0.dp,
        listPaneContentEndPadding = 0.dp,
        detailPaneContentStartPadding = 0.dp,
        detailPaneContentEndPadding = 0.dp,
        detailPaneShape = RectangleShape,
        detailPaneColors = CardDefaults.cardColors(containerColor = AniThemeDefaults.pageContentBackgroundColor),
        preferSinglePane = true,
    )
    val scope = remember(parameters, onBack) { SubscriptionPageScope(parameters, onBack) }
    Column(modifier.fillMaxSize().background(AniThemeDefaults.pageContentBackgroundColor)) {
        AniTopAppBar(
            title = {
                AniTopAppBarDefaults.Title(vm.mediaSourceSubscriptionGroupState.findSubscription(subscriptionId)?.pageTitle.orEmpty())
            },
            navigationIcon = { BackNavigationIconButton(onBack) },
            actions = {
                MediaSourceSubscriptionPageActions(
                    subscriptionId,
                    vm.mediaSourceSubscriptionGroupState,
                    vm.mediaSourceGroupState,
                    vm.editMediaSourceState,
                )
            },
            colors = AniThemeDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            with(scope) {
                MediaSourceSubscriptionPageContent(
                    subscriptionId,
                    vm.mediaSourceSubscriptionGroupState,
                    vm.mediaSourceGroupState,
                    vm.editMediaSourceState,
                )
            }
        }
    }
}

/** 订阅详情页单独成页时的详情栏作用域: 没有详情栏内部的导航, 返回就是关掉这一页. */
private class SubscriptionPageScope(
    override val listDetailLayoutParameters: ListDetailLayoutParameters,
    private val onBack: () -> Unit,
) : SettingsDetailPaneScope {
    override val paneContentWindowInsets: WindowInsets = WindowInsets(0)

    override fun Modifier.paneContentPadding(extraStart: Dp, extraEnd: Dp): Modifier =
        padding(start = extraStart, end = extraEnd)

    override fun navigateTo(route: DetailPaneRoutes) {}

    override fun navigateUp() = onBack()
}
