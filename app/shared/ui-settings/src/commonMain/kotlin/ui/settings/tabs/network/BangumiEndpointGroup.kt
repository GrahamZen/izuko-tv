/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_network_bangumi_auto
import me.him188.ani.app.ui.lang.settings_network_bangumi_auto_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_builtin_mirrors
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom_url
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom_url_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom_url_invalid
import me.him188.ani.app.ui.lang.settings_network_bangumi_direct
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_auto_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_auto_title
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
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_later
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_logout
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_switch_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_mode
import me.him188.ani.app.ui.lang.settings_network_bangumi_no_login_notice
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import me.him188.ani.app.ui.settings.framework.components.TextItem
import org.jetbrains.compose.resources.stringResource

/**
 * bangumi 连接方式.
 *
 * 2026-05 起 bangumi 在中国大陆被整族阻断 (`api.bgm.tv` / `next.bgm.tv` 一起), 直连在那边不可用.
 * 推荐的是代理 (直连官方, 不经过第三方); 社区的反代镜像能救浏览, 但**第三方反代能看到经过它的一切**,
 * 所以登录与收藏同步默认不走它, 用户在弹窗里了解风险后可以自己打开 —— 界面上必须把这些说清楚,
 * 别让用户对着登录失败猜.
 *
 * 已登录时改成「用镜像」(凭证不经过镜像) 或用着镜像时关掉凭证那一项, 先问 (见 [BangumiMirrorConsent]):
 * 登录后连浏览都带着令牌, 这两种改法会让官方连不上时什么都用不了.
 */
@Composable
internal fun SettingsScope.BangumiEndpointGroup(
    state: SettingsState<BangumiEndpointSettings>,
    /** 自带的镜像清单, 见 SettingsViewModel.bangumiMirrors. 列出来让人知道不填地址也有镜像可用. */
    mirrors: List<String>,
    /** 是否登录着 Bangumi (有令牌, 不管现在连不连得上). */
    loggedIn: Boolean = false,
    /** 「退出登录并改用镜像」里的退出登录; 退完才切. */
    onLogout: suspend () -> Unit = {},
) {
    val settings by state
    val scope = rememberCoroutineScope()
    // 等用户回答的改法: 改用镜像 / 关掉凭证
    var askSwitch by remember { mutableStateOf<BangumiEndpointSettings?>(null) }
    var askCredentialsOff by remember { mutableStateOf(false) }
    Group(
        title = { Text(stringResource(Lang.settings_network_bangumi_endpoint)) },
        description = { Text(stringResource(Lang.settings_network_bangumi_endpoint_description)) },
    ) {
        DropdownItem(
            selected = { settings.mode },
            values = { BangumiEndpointMode.entries },
            itemText = { Text(stringResource(bangumiEndpointModeLabel(it))) },
            onSelect = {
                val target = settings.copy(mode = it)
                if (BangumiMirrorConsent.check(settings, target, loggedIn) != null) askSwitch = target else state.update(target)
            },
            title = { Text(stringResource(Lang.settings_network_bangumi_mode)) },
            enabled = !state.isLoading,
        )

        if (settings.mode == BangumiEndpointMode.AUTO) {
            TextItem(title = { Text(stringResource(Lang.settings_network_bangumi_auto_description)) })
        }

        // 只有第三方镜像那两档要问: 自建的是可信的, 登录照样能用
        if (settings.mode == BangumiEndpointMode.AUTO || settings.mode == BangumiEndpointMode.MIRROR) {
            if (mirrors.isNotEmpty()) {
                TextItem(
                    title = { Text(stringResource(Lang.settings_network_bangumi_builtin_mirrors, mirrors.joinToString("、"))) },
                )
            }
            var confirming by remember { mutableStateOf(false) }
            SwitchItem(
                checked = settings.allowCredentialsViaMirror,
                // 打开要先在弹窗里了解风险; 关掉直接生效 (已登录且用着镜像时先提醒后果)
                onCheckedChange = { on ->
                    val target = settings.copy(allowCredentialsViaMirror = on)
                    when {
                        on -> confirming = true
                        BangumiMirrorConsent.check(settings, target, loggedIn) != null -> askCredentialsOff = true
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

        if (settings.mode == BangumiEndpointMode.CUSTOM) {
            TextFieldItem(
                value = settings.customBaseUrl,
                title = { Text(stringResource(Lang.settings_network_bangumi_custom_url)) },
                description = { Text(stringResource(Lang.settings_network_bangumi_custom_url_description)) },
                placeholder = { Text(PLACEHOLDER_HOST) },
                // 填不成样子时红一下, 而不是等所有请求默默失败 —— 认不出来的地址会退回直连,
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
    }

    askSwitch?.let { target ->
        MirrorSwitchConsentDialog(
            automatic = false,
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
        MirrorChoiceDialog(
            title = stringResource(Lang.settings_network_bangumi_mirror_credentials_off_title),
            message = stringResource(Lang.settings_network_bangumi_mirror_credentials_off_message),
            actions = listOf(
                MirrorDialogAction(stringResource(Lang.settings_network_bangumi_mirror_credentials_off_confirm)) {
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
 * 已登录的人改用第三方镜像之前的选择 (见 [BangumiMirrorConsent]): 允许凭证经过镜像 / 退出登录再切 / 不切.
 *
 * @param automatic `true` = 「官方连不上时用镜像」要自动切过去 (标题与说明换成「连不上官方」那套, 取消叫「暂不」)
 */
@Composable
fun MirrorSwitchConsentDialog(
    automatic: Boolean,
    onAllow: () -> Unit,
    onLogoutAndSwitch: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    MirrorChoiceDialog(
        title = stringResource(
            if (automatic) Lang.settings_network_bangumi_mirror_auto_title else Lang.settings_network_bangumi_mirror_switch_title,
        ),
        message = stringResource(
            if (automatic) Lang.settings_network_bangumi_mirror_auto_message else Lang.settings_network_bangumi_mirror_switch_message,
        ),
        actions = listOf(
            MirrorDialogAction(stringResource(Lang.settings_network_bangumi_mirror_switch_allow), onClick = onAllow),
            MirrorDialogAction(stringResource(Lang.settings_network_bangumi_mirror_switch_logout), onClick = onLogoutAndSwitch),
        ),
        cancel = stringResource(
            if (automatic) Lang.settings_network_bangumi_mirror_switch_later else Lang.settings_network_bangumi_mirror_credentials_cancel,
        ),
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
    MirrorChoiceDialog(
        title = stringResource(Lang.settings_network_bangumi_mirror_credentials_risk_title),
        message = stringResource(Lang.settings_network_bangumi_mirror_credentials_risk_message),
        actions = listOf(
            MirrorDialogAction(
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

private class MirrorDialogAction(val text: String, val testTag: String? = null, val onClick: () -> Unit)

/**
 * 这一组里的询问弹窗: 说明 + 几个动作 + 取消.
 *
 * 默认焦点在「取消」上 —— 遥控器上顺手按一下确定, 不该就把账号交给第三方或改掉连接方式.
 * 遥控器形态用居中大面板 (与其余面板同一形态), 指针设备用普通对话框. 按钮放不下一行时折行.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MirrorChoiceDialog(
    title: String,
    message: String,
    actions: List<MirrorDialogAction>,
    cancel: String,
    onDismissRequest: () -> Unit,
    cancelTestTag: String? = null,
    heightFraction: Float = 0.6f,
) {
    val buttons = @Composable {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (action in actions) {
                TextButton(onClick = action.onClick, modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier) {
                    Text(action.text)
                }
            }
            // 弹窗是独立窗口, 不指定的话遥控器上焦点不在任何按钮上
            Button(
                onClick = onDismissRequest,
                modifier = Modifier.tvWindowInitialFocus().then(cancelTestTag?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                Text(cancel)
            }
        }
    }
    if (LocalAniUiBehavior.current.panelsAsCenteredDialogs) {
        AniCenteredPanelDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            widthFraction = 0.6f,
            heightFraction = heightFraction,
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                buttons()
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { buttons() },
        )
    }
}

internal object BangumiEndpointGroupTestTags {
    const val CREDENTIALS_SWITCH = "bangumi_mirror_credentials_switch"
    const val RISK_CONFIRM = "bangumi_mirror_credentials_risk_confirm"
    const val RISK_CANCEL = "bangumi_mirror_credentials_risk_cancel"
}

private const val PLACEHOLDER_HOST = "bangumi.example.com"

private fun bangumiEndpointModeLabel(mode: BangumiEndpointMode) = when (mode) {
    BangumiEndpointMode.DIRECT -> Lang.settings_network_bangumi_direct
    BangumiEndpointMode.AUTO -> Lang.settings_network_bangumi_auto
    BangumiEndpointMode.MIRROR -> Lang.settings_network_bangumi_mirror
    BangumiEndpointMode.CUSTOM -> Lang.settings_network_bangumi_custom
}
