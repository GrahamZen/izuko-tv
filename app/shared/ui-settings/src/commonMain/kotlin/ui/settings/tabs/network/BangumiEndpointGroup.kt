/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom_url
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom_url_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom_url_invalid
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_cancel
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_off_confirm
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_off_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_off_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_on
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_risk_confirm
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_risk_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_risk_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_allow
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_logout
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_no_login_notice
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_custom_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_ech
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_ech_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_mirrors
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_mirrors_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_origin
import me.him188.ani.app.ui.lang.settings_network_bangumi_route_origin_description
import me.him188.ani.app.ui.settings.SettingChoiceDialog
import me.him188.ani.app.ui.settings.SettingChoiceDialogAction
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import org.jetbrains.compose.resources.stringResource

/**
 * bangumi 经哪些线路连: 官方、自建、加密直连、社区镜像各一个开关, 按这个顺序尝试 (见 [BangumiRouteKind]).
 *
 * 2026-05 起 bangumi 在中国大陆被整族阻断 (`api.bgm.tv` / `next.bgm.tv` 一起), 直连在那边不可用.
 * 推荐的是代理 (直连官方, 不经过第三方); 加密直连同样直达官方; 社区的反代镜像能救浏览, 但**第三方反代能看到经过它的一切**,
 * 所以登录与收藏同步默认不走它, 用户在弹窗里了解风险后可以自己打开 —— 界面上必须把这些说清楚,
 * 别让用户对着登录失败猜.
 *
 * 已登录时改成只剩镜像 (凭证不经过镜像) 或只剩镜像时关掉凭证那一项, 先问 (见 [BangumiMirrorConsent]):
 * 登录后连浏览都带着令牌, 这两种改法会让什么都用不了.
 */
@Composable
internal fun SettingsScope.BangumiEndpointGroup(
    state: SettingsState<BangumiEndpointSettings>,
    /** 自带的镜像清单, 见 SettingsViewModel.bangumiMirrors. 列出来让人知道不填地址也有镜像可用. */
    mirrors: List<String>,
    /** 是否登录着 Bangumi (有令牌, 不管现在连不连得上). */
    loggedIn: Boolean = false,
    /** 这台设备能不能加密直连 (见 `BangumiEndpointProvider.echAvailable`); 不能就不列这条线路. */
    echAvailable: Boolean = false,
    /** 「退出登录并改用镜像」里的退出登录; 退完才切. */
    onLogout: suspend () -> Unit = {},
) {
    val settings by state
    val scope = rememberCoroutineScope()
    // 等用户回答的改法: 改成只剩镜像 / 关掉凭证
    var askSwitch by remember { mutableStateOf<BangumiEndpointSettings?>(null) }
    var askCredentialsOff by remember { mutableStateOf(false) }
    fun setRoute(kind: BangumiRouteKind, enabled: Boolean) {
        val target = settings.withRoute(kind, enabled)
        if (BangumiMirrorConsent.check(settings, target, loggedIn, echAvailable) != null) askSwitch = target else state.update(target)
    }
    Group(
        title = { Text(stringResource(Lang.settings_network_bangumi_endpoint)) },
        description = { Text(stringResource(Lang.settings_network_bangumi_endpoint_description)) },
    ) {
        SwitchItem(
            checked = settings.isEnabled(BangumiRouteKind.ORIGIN),
            onCheckedChange = { setRoute(BangumiRouteKind.ORIGIN, it) },
            modifier = Modifier.testTag(BangumiEndpointGroupTestTags.route(BangumiRouteKind.ORIGIN)),
            title = { Text(stringResource(Lang.settings_network_bangumi_route_origin)) },
            description = { Text(stringResource(Lang.settings_network_bangumi_route_origin_description)) },
            enabled = !state.isLoading,
        )

        val customRoot = settings.customRoot
        SwitchItem(
            checked = settings.isEnabled(BangumiRouteKind.CUSTOM),
            onCheckedChange = { setRoute(BangumiRouteKind.CUSTOM, it) },
            modifier = Modifier.testTag(BangumiEndpointGroupTestTags.route(BangumiRouteKind.CUSTOM)),
            title = { Text(stringResource(Lang.settings_network_bangumi_custom)) },
            description = {
                Text(
                    if (customRoot != null) stringResource(Lang.settings_network_bangumi_route_custom_description, customRoot)
                    else stringResource(Lang.settings_network_bangumi_custom_url_description),
                )
            },
            enabled = !state.isLoading,
        )
        if (settings.isEnabled(BangumiRouteKind.CUSTOM)) {
            TextFieldItem(
                value = settings.customBaseUrl,
                title = { Text(stringResource(Lang.settings_network_bangumi_custom_url)) },
                description = { Text(stringResource(Lang.settings_network_bangumi_custom_url_description)) },
                placeholder = { Text(PLACEHOLDER_HOST) },
                // 填不成样子时红一下, 而不是等所有请求默默失败 —— 认不出来的地址等于没开这条线路,
                // 那在大陆表现为"设置了但一样连不上", 用户根本看不出是自己填错了
                isErrorProvider = { it.isNotBlank() && BangumiMirrorHosts.normalizeMirrorRoot(it) == null },
                textFieldDescription = {
                    Text(
                        if (it.isNotBlank() && BangumiMirrorHosts.normalizeMirrorRoot(it) == null) {
                            stringResource(Lang.settings_network_bangumi_custom_url_invalid)
                        } else {
                            stringResource(Lang.settings_network_bangumi_custom_url_description)
                        },
                    )
                },
                onValueChangeCompleted = { state.update(settings.copy(customBaseUrl = it)) },
            )
        }

        if (echAvailable) {
            SwitchItem(
                checked = settings.isEnabled(BangumiRouteKind.ECH),
                onCheckedChange = { setRoute(BangumiRouteKind.ECH, it) },
            modifier = Modifier.testTag(BangumiEndpointGroupTestTags.route(BangumiRouteKind.ECH)),
                title = { Text(stringResource(Lang.settings_network_bangumi_route_ech)) },
                description = { Text(stringResource(Lang.settings_network_bangumi_route_ech_description)) },
                enabled = !state.isLoading,
            )
        }

        SwitchItem(
            checked = settings.isEnabled(BangumiRouteKind.MIRRORS),
            onCheckedChange = { setRoute(BangumiRouteKind.MIRRORS, it) },
            modifier = Modifier.testTag(BangumiEndpointGroupTestTags.route(BangumiRouteKind.MIRRORS)),
            title = { Text(stringResource(Lang.settings_network_bangumi_route_mirrors)) },
            description = { Text(stringResource(Lang.settings_network_bangumi_route_mirrors_description, mirrors.joinToString("、"))) },
            enabled = !state.isLoading,
        )
        // 只有第三方镜像要问: 别的线路都直达官方或是用户自己的
        if (settings.isEnabled(BangumiRouteKind.MIRRORS)) {
            var confirming by remember { mutableStateOf(false) }
            SwitchItem(
                checked = settings.allowCredentialsViaMirror,
                // 打开要先在弹窗里了解风险; 关掉直接生效 (已登录且只剩镜像时先提醒后果)
                onCheckedChange = { on ->
                    val target = settings.copy(allowCredentialsViaMirror = on)
                    when {
                        on -> confirming = true
                        BangumiMirrorConsent.check(settings, target, loggedIn, echAvailable) != null -> askCredentialsOff = true
                        else -> state.update(target)
                    }
                },
                title = { Text(stringResource(Lang.settings_network_bangumi_mirror_credentials)) },
                modifier = Modifier.testTag(BangumiEndpointGroupTestTags.CREDENTIALS_SWITCH),
                description = {
                    Text(
                        stringResource(
                            if (settings.allowCredentialsViaMirror) Lang.settings_network_bangumi_mirror_credentials_on
                            else Lang.settings_network_bangumi_no_login_notice,
                        ),
                    )
                },
                enabled = !state.isLoading,
            )
            if (confirming) {
                MirrorCredentialsRiskDialog(
                    onConfirm = {
                        confirming = false
                        state.update(settings.copy(allowCredentialsViaMirror = true))
                    },
                    onDismissRequest = { confirming = false },
                )
            }
        }
    }

    askSwitch?.let { target ->
        MirrorSwitchConsentDialog(
            onAllow = {
                askSwitch = null
                state.update(target.copy(allowCredentialsViaMirror = true))
            },
            onLogoutAndSwitch = {
                askSwitch = null
                scope.launch {
                    onLogout()
                    state.update(target)
                }
            },
            onDismissRequest = { askSwitch = null },
        )
    }
    if (askCredentialsOff) {
        SettingChoiceDialog(
            title = stringResource(Lang.settings_network_bangumi_mirror_credentials_off_title),
            message = stringResource(Lang.settings_network_bangumi_mirror_credentials_off_message),
            actions = listOf(
                SettingChoiceDialogAction(stringResource(Lang.settings_network_bangumi_mirror_credentials_off_confirm)) {
                    askCredentialsOff = false
                    state.update(settings.copy(allowCredentialsViaMirror = false))
                },
            ),
            cancel = stringResource(Lang.settings_network_bangumi_mirror_credentials_cancel),
            onDismissRequest = { askCredentialsOff = false },
        )
    }
}

/**
 * 已登录的人自己改成只剩第三方镜像之前的选择 (见 [BangumiMirrorConsent]): 允许凭证经过镜像 / 退出登录再切 / 不切.
 * 官方连不上时应用自己问的那一句不在这里 (见 SettingAdjustments 与电视的 TvSettingAdjustmentHost).
 */
@Composable
fun MirrorSwitchConsentDialog(
    onAllow: () -> Unit,
    onLogoutAndSwitch: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    SettingChoiceDialog(
        title = stringResource(Lang.settings_network_bangumi_mirror_switch_title),
        message = stringResource(Lang.settings_network_bangumi_mirror_switch_message),
        actions = listOf(
            SettingChoiceDialogAction(
                stringResource(Lang.settings_network_bangumi_mirror_switch_allow),
                testTag = BangumiEndpointGroupTestTags.SWITCH_ALLOW,
                onClick = onAllow,
            ),
            SettingChoiceDialogAction(stringResource(Lang.settings_network_bangumi_mirror_switch_logout), onClick = onLogoutAndSwitch),
        ),
        cancel = stringResource(Lang.settings_network_bangumi_mirror_credentials_cancel),
        onDismissRequest = onDismissRequest,
        heightFraction = 0.75f,
    )
}

/**
 * 打开「登录与收藏同步也经过镜像」之前的风险确认: 镜像方能看到并使用账号, 由用户自己承担; 同时建议改用代理.
 */
@Composable
private fun MirrorCredentialsRiskDialog(
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    SettingChoiceDialog(
        title = stringResource(Lang.settings_network_bangumi_mirror_credentials_risk_title),
        message = stringResource(Lang.settings_network_bangumi_mirror_credentials_risk_message),
        actions = listOf(
            SettingChoiceDialogAction(
                stringResource(Lang.settings_network_bangumi_mirror_credentials_risk_confirm),
                testTag = BangumiEndpointGroupTestTags.RISK_CONFIRM,
                onClick = onConfirm,
            ),
        ),
        cancel = stringResource(Lang.settings_network_bangumi_mirror_credentials_cancel),
        cancelTestTag = BangumiEndpointGroupTestTags.RISK_CANCEL,
        onDismissRequest = onDismissRequest,
    )
}

internal object BangumiEndpointGroupTestTags {
    fun route(kind: BangumiRouteKind) = "bangumi_route_${kind.id}"
    const val SWITCH_ALLOW = "bangumi_mirror_switch_allow"
    const val CREDENTIALS_SWITCH = "bangumi_mirror_credentials_switch"
    const val RISK_CONFIRM = "bangumi_mirror_credentials_risk_confirm"
    const val RISK_CANCEL = "bangumi_mirror_credentials_risk_cancel"
}

private const val PLACEHOLDER_HOST = "bangumi.example.com"
