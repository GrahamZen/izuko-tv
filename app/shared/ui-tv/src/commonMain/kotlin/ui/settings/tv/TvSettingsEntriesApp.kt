/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.MeteredNetworkDetector
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.diagnostics.TvPerfDiagnostics
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.oauth_bangumi_help_activation_failed
import me.him188.ani.app.ui.lang.oauth_bangumi_help_activation_failed_content
import me.him188.ani.app.ui.lang.oauth_bangumi_help_bangumi_desc
import me.him188.ani.app.ui.lang.oauth_bangumi_help_bangumi_desc_content
import me.him188.ani.app.ui.lang.oauth_bangumi_help_cant_receive_email
import me.him188.ani.app.ui.lang.oauth_bangumi_help_cant_receive_email_content
import me.him188.ani.app.ui.lang.oauth_bangumi_help_register_choose
import me.him188.ani.app.ui.lang.oauth_bangumi_help_register_choose_content
import me.him188.ani.app.ui.lang.oauth_bangumi_help_title
import me.him188.ani.app.ui.lang.oauth_bangumi_help_website_blocked
import me.him188.ani.app.ui.lang.oauth_bangumi_help_website_blocked_content
import me.him188.ani.app.ui.lang.oauth_bangumi_help_wrong_captcha
import me.him188.ani.app.ui.lang.oauth_bangumi_help_wrong_captcha_content
import me.him188.ani.app.ui.lang.oauth_bangumi_stage_exchanging
import me.him188.ani.app.ui.lang.oauth_bangumi_via_mirror
import me.him188.ani.app.ui.lang.search_tv_remote_unavailable
import me.him188.ani.app.ui.lang.settings_account_choose_profile_on_launch
import me.him188.ani.app.ui.lang.settings_account_choose_profile_on_launch_description
import me.him188.ani.app.ui.lang.settings_account_clear_records
import me.him188.ani.app.ui.lang.settings_account_clear_records_description
import me.him188.ani.app.ui.lang.settings_account_convert_local
import me.him188.ani.app.ui.lang.settings_account_convert_local_description
import me.him188.ani.app.ui.lang.settings_account_local_description
import me.him188.ani.app.ui.lang.settings_account_local_title
import me.him188.ani.app.ui.lang.settings_account_logout
import me.him188.ani.app.ui.lang.settings_account_profile_nickname
import me.him188.ani.app.ui.lang.settings_account_profile_not_set
import me.him188.ani.app.ui.lang.settings_account_profile_user_id
import me.him188.ani.app.ui.lang.settings_danmaku_regex_filter_group
import me.him188.ani.app.ui.lang.settings_debug_logged_out
import me.him188.ani.app.ui.lang.settings_debug_logout
import me.him188.ani.app.ui.lang.settings_debug_metered_network
import me.him188.ani.app.ui.lang.settings_debug_mode
import me.him188.ani.app.ui.lang.settings_debug_mode_description
import me.him188.ani.app.ui.lang.settings_debug_show_all_episodes
import me.him188.ani.app.ui.lang.settings_debug_show_all_episodes_description
import me.him188.ani.app.ui.lang.settings_log_export_file
import me.him188.ani.app.ui.lang.settings_log_file_not_found
import me.him188.ani.app.ui.lang.settings_log_send_to_phone
import me.him188.ani.app.ui.lang.settings_log_send_to_phone_failed
import me.him188.ani.app.ui.lang.settings_log_send_to_phone_no_network
import me.him188.ani.app.ui.lang.settings_update_auto_check
import me.him188.ani.app.ui.lang.settings_update_auto_check_description
import me.him188.ani.app.ui.lang.settings_update_auto_download
import me.him188.ani.app.ui.lang.settings_update_auto_download_description
import me.him188.ani.app.ui.lang.settings_update_check
import me.him188.ani.app.ui.lang.settings_update_check_failed
import me.him188.ani.app.ui.lang.settings_update_checking
import me.him188.ani.app.ui.lang.settings_update_checking_mirror
import me.him188.ani.app.ui.lang.settings_update_current_version
import me.him188.ani.app.ui.lang.settings_update_new_version
import me.him188.ani.app.ui.lang.settings_update_type
import me.him188.ani.app.ui.lang.settings_update_type_stable_short
import me.him188.ani.app.ui.lang.settings_update_up_to_date
import me.him188.ani.app.ui.lang.settings_update_view_changelog
import me.him188.ani.app.ui.lang.tv_perf_diag_checking
import me.him188.ani.app.ui.lang.tv_perf_diag_dialog_hint
import me.him188.ani.app.ui.lang.tv_perf_diag_latest
import me.him188.ani.app.ui.lang.tv_perf_diag_none
import me.him188.ani.app.ui.lang.tv_perf_diag_recording
import me.him188.ani.app.ui.lang.tv_perf_diag_title
import me.him188.ani.app.ui.lang.tv_settings_account_bangumi
import me.him188.ani.app.ui.lang.tv_settings_danmaku_rule_delete_confirm
import me.him188.ani.app.ui.lang.tv_settings_danmaku_rule_hint
import me.him188.ani.app.ui.lang.tv_settings_danmaku_rules_add
import me.him188.ani.app.ui.lang.tv_settings_danmaku_rules_add_description
import me.him188.ani.app.ui.lang.tv_settings_danmaku_rules_delete
import me.him188.ani.app.ui.lang.tv_settings_debug_crash
import me.him188.ani.app.ui.lang.tv_settings_debug_refresh_failed
import me.him188.ani.app.ui.lang.tv_settings_debug_refresh_ok
import me.him188.ani.app.ui.lang.tv_settings_debug_refresh_session
import me.him188.ani.app.ui.lang.tv_settings_delete
import me.him188.ani.app.ui.lang.tv_settings_log_export_description
import me.him188.ani.app.ui.lang.tv_settings_log_send_description
import me.him188.ani.app.ui.lang.tv_settings_login_failed
import me.him188.ani.app.ui.lang.tv_settings_login_failed_short
import me.him188.ani.app.ui.lang.tv_settings_login_header
import me.him188.ani.app.ui.lang.tv_settings_login_on_tv
import me.him188.ani.app.ui.lang.tv_settings_login_on_tv_description
import me.him188.ani.app.ui.lang.tv_settings_login_qr
import me.him188.ani.app.ui.lang.tv_settings_login_qr_caption
import me.him188.ani.app.ui.lang.tv_settings_login_qr_description
import me.him188.ani.app.ui.lang.tv_settings_login_relay_unsupported
import me.him188.ani.app.ui.lang.tv_settings_login_success
import me.him188.ani.app.ui.lang.tv_settings_on_phone
import me.him188.ani.app.ui.lang.tv_settings_paragraphs
import me.him188.ani.app.ui.lang.tv_settings_qr_preparing
import me.him188.ani.app.ui.lang.tv_settings_update_download_failed
import me.him188.ani.app.ui.lang.tv_settings_update_downloaded
import me.him188.ani.app.ui.lang.tv_settings_update_downloading
import me.him188.ani.app.ui.lang.tv_settings_update_new_hint
import me.him188.ani.app.ui.lang.tv_settings_update_type_stable_description
import me.him188.ani.app.ui.lang.tv_settings_update_type_test
import me.him188.ani.app.ui.lang.tv_settings_update_type_test_description
import me.him188.ani.app.ui.remote.TvBangumiRelayLogin
import me.him188.ani.app.ui.settings.tabs.AniHelperDestination
import me.him188.ani.app.ui.settings.tabs.log.LogExportLauncher
import me.him188.ani.app.ui.settings.tabs.log.LogLanShareState
import me.him188.ani.app.ui.settings.tabs.log.hasCurrentLogFile
import me.him188.ani.app.ui.settings.tabs.log.runLogLanShare
import me.him188.ani.app.ui.update.AppUpdatePresentation
import me.him188.ani.app.ui.update.AppUpdateState
import me.him188.ani.app.ui.update.UpdateCheckProgress
import org.jetbrains.compose.resources.getString
import kotlin.math.roundToInt

/** 账号、更新、日志、关于、调试用到的几份值. */
internal object TvAppSettingKeys {
    val account = TvSettingKey.Live("account") { deps -> deps.profile.stateFlow }
    val oauth = TvSettingKey.Live("oauth") { GlobalKoin.get<BangumiOAuthManager>().state }
    val loginRelay = TvSettingKey.Live("loginRelay") { deps -> deps.loginRelay }

    /** 经第三方镜像连 Bangumi: 授权登录走不通 (镜像把授权页与换 token 挡在反爬验证后面), 只能在控制台用个人令牌登录. */
    val viaMirror = TvSettingKey.Live("viaMirror") { GlobalKoin.get<BangumiEndpointProvider>().viaThirdPartyMirror }
    val update = TvSettingKey.Stored("update") { it.updateSettings }
    val updatePresentation = TvSettingKey.Live("updatePresentation") { deps -> deps.appUpdate.presentationFlow }
    val metered = TvSettingKey.Live("metered") { GlobalKoin.get<MeteredNetworkDetector>().isMeteredNetworkFlow }

    /** 「扫码传到手机」的服务此刻的样子 (焦点在那一行时才起). */
    val logShare = TvSettingKey.Live("logShare") { deps -> deps.logShare.map { TvLogShare(it) } }

    /** 最近一次性能诊断的结论 (没有时为 null). */
    val perfLatest = TvSettingKey.Live("perfLatest") { TvPerfDiagnostics.latestHeadline.map { TvOptionalText(it?.let(::tvText)) } }

    /** 性能诊断此刻在做什么 (录制时每半秒更新剩余秒数). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val perfStatus = TvSettingKey.Live("perfStatus") {
        TvPerfDiagnostics.status.flatMapLatest { status ->
            when (status) {
                is TvPerfDiagnostics.Status.Recording -> flow {
                    while (true) {
                        val left = ((status.endsAtMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
                        emit(TvOptionalText(tvText(Lang.tv_perf_diag_recording, left)))
                        delay(500)
                    }
                }

                TvPerfDiagnostics.Status.CheckingHealth -> flowOf(TvOptionalText(tvText(Lang.tv_perf_diag_checking)))
                TvPerfDiagnostics.Status.Idle -> flowOf(TvOptionalText(null))
            }
        }
    }
}

/** 可能没有的一段字 (实时值不能是 null). */
internal data class TvOptionalText(val text: TvText?)

/** 「扫码传到手机」的服务; null = 没起 (实时值不能是 null, 包一层). */
internal data class TvLogShare(val state: LogLanShareState?)

// ---- 账号 ----

/**
 * 账号: 你是谁、退出登录; 本地用户可以清除收藏记录. 多用户的增删改在手机控制台里.
 * 没登录 (或登录失效) 时这里就是登录页: 扫码登录 (手机扫码授权, 经 Worker 跳回电视)、在电视上登录、注册与登录的常见问题.
 */
internal fun TvSettingsItemsBuilder.accountItems() {
    val account = TvAppSettingKeys.account
    val keys = listOf(account)
    fun TvSettingsValues.isLocal() = this[account]?.selfInfo?.isLocalProfile == true
    loginItems()
    action(
        Lang.settings_account_profile_nickname, keys = keys,
        visible = { !it.needsLogin() },
        value = { v ->
            v[account]?.selfInfo?.selfInfo?.nickname?.takeIf { it.isNotBlank() }?.let { tvText(it) }
                ?: tvText(Lang.settings_account_profile_not_set)
        },
    ) { emptyList() }
    action(
        Lang.settings_account_local_title, Lang.settings_account_local_description, keys = keys,
        visible = { it.isLocal() },
    ) { emptyList() }
    action(
        Lang.tv_settings_account_bangumi, keys = keys,
        value = { v ->
            v[account]?.selfInfo?.selfInfo?.bangumiUsername?.let { tvText(it) } ?: tvText(Lang.settings_account_profile_not_set)
        },
        visible = { !it.isLocal() && !it.needsLogin() },
    ) { emptyList() }
    action(
        Lang.settings_account_profile_user_id, keys = keys,
        value = { v -> v[account]?.selfInfo?.selfInfo?.id?.let { tvText(it.toString()) } ?: tvText(Lang.settings_account_profile_not_set) },
        visible = { !it.isLocal() && !it.needsLogin() },
    ) { emptyList() }
    run(
        Lang.settings_account_logout, keys = keys,
        visible = { !it.isLocal() && it[account]?.selfInfo?.isSessionValid == true },
    ) { env -> env.show(TvSettingsOverlay.Dialog { dismiss -> TvLogoutDialog(env.deps.profile, dismiss) }) }
    run(
        Lang.settings_account_convert_local, Lang.settings_account_convert_local_description, keys = keys,
        visible = { !it.isLocal() && it[account]?.canConvertToLocal == true },
    ) { env -> env.show(TvSettingsOverlay.Dialog { dismiss -> TvConvertToLocalDialog(env.deps.profile, dismiss) }) }
    run(
        Lang.settings_account_clear_records, Lang.settings_account_clear_records_description, keys = keys,
        visible = { it.isLocal() },
    ) { env -> env.show(TvSettingsOverlay.Dialog { dismiss -> TvClearRecordsDialog(env.deps.profile, dismiss) }) }
    // 整台电视一份, 不跟着当前用户; 两个以上用户时才有
    toggleOf(
        keys, Lang.settings_account_choose_profile_on_launch,
        description = { tvText(Lang.settings_account_choose_profile_on_launch_description) },
        visible = { it[account]?.chooseProfileOnLaunch != null },
        read = { it[account]?.chooseProfileOnLaunch == true },
        write = { on -> listOf(TvSettingsEdit.Run { env -> env.deps.profile.setChooseProfileOnLaunch(on) }) },
    )
}

/** 是 Bangumi 用户 (不是本地用户) 而且没登录 / 登录失效了. */
private fun TvSettingsValues.needsLogin(): Boolean {
    val info = this[TvAppSettingKeys.account]?.selfInfo ?: return false
    return !info.isLocalProfile && info.isSessionValid == false
}

/** 登录 Bangumi (没登录时出现在账号那一类的最前面). */
private fun TvSettingsItemsBuilder.loginItems() {
    val account = TvAppSettingKeys.account
    val oauth = TvAppSettingKeys.oauth
    val relay = TvAppSettingKeys.loginRelay
    val mirror = TvAppSettingKeys.viaMirror
    val keys = listOf(account, oauth, relay, mirror)
    header(Lang.tv_settings_login_header, visible = { it.needsLogin() })
    // 焦点停上去就发起一次经 Worker 中转的授权, 授权页地址画成二维码; 离开不取消 (手机上可能正授权到一半), 确定换一张
    action(
        Lang.tv_settings_login_qr, keys = keys,
        value = { v -> loginStatus(v[oauth]) },
        // 说明只写怎么做 (码占了说明栏一大块, 长了会被截掉)
        details = { v -> tvText(if (v[mirror] == true) Lang.oauth_bangumi_via_mirror else Lang.tv_settings_login_qr_description) },
        visible = { it.needsLogin() },
        qr = { v -> loginQr(v) },
        whileFocused = { env -> ensureLoginRelay(env) },
    ) { listOf(TvSettingsEdit.Run { env -> startLoginRelay(env) }) }
    run(
        Lang.tv_settings_login_on_tv, Lang.tv_settings_login_on_tv_description, keys = keys,
        visible = { v -> v.needsLogin() && v[mirror] != true && GlobalKoin.get<BangumiOAuthManager>().inAppBrowserSupported },
    ) {
        val manager = GlobalKoin.get<BangumiOAuthManager>()
        manager.resetIfFinished()
        manager.startInAppBrowser()
    }
    header(Lang.oauth_bangumi_help_title, visible = { it.needsLogin() })
    for ((question, answer) in LOGIN_HELP) {
        action(question, answer, keys = keys, visible = { it.needsLogin() }) { emptyList() }
    }
}

/** 注册与登录的常见问题 (同原来授权页的「帮助」): 标题是问题, 说明栏是回答. */
private val LOGIN_HELP = listOf(
    Lang.oauth_bangumi_help_bangumi_desc to Lang.oauth_bangumi_help_bangumi_desc_content,
    Lang.oauth_bangumi_help_register_choose to Lang.oauth_bangumi_help_register_choose_content,
    Lang.oauth_bangumi_help_wrong_captcha to Lang.oauth_bangumi_help_wrong_captcha_content,
    Lang.oauth_bangumi_help_cant_receive_email to Lang.oauth_bangumi_help_cant_receive_email_content,
    Lang.oauth_bangumi_help_activation_failed to Lang.oauth_bangumi_help_activation_failed_content,
    Lang.oauth_bangumi_help_website_blocked to Lang.oauth_bangumi_help_website_blocked_content,
)

private fun loginStatus(state: BangumiOAuthManager.State?): TvText? = when (state) {
    BangumiOAuthManager.State.Exchanging -> tvText(Lang.oauth_bangumi_stage_exchanging)
    is BangumiOAuthManager.State.Failed -> tvText(Lang.tv_settings_login_failed_short)
    else -> null
}

private fun loginQr(v: TvSettingsValues): TvQr {
    // 经镜像时授权走不通: 码换成控制台的账号页 (在那里用个人令牌登录)
    if (v[TvAppSettingKeys.viaMirror] == true) return consoleQr(v, "#settings/account")
    val relay = v[TvAppSettingKeys.loginRelay] ?: TvLoginRelay()
    relay.problem?.let { return TvQr(null, status = it) }
    return when (val state = v[TvAppSettingKeys.oauth]) {
        BangumiOAuthManager.State.Exchanging -> TvQr(null, status = tvText(Lang.oauth_bangumi_stage_exchanging))
        BangumiOAuthManager.State.Success -> TvQr(null, status = tvText(Lang.tv_settings_login_success))
        is BangumiOAuthManager.State.Failed -> TvQr(null, status = tvText(Lang.tv_settings_login_failed))
        is BangumiOAuthManager.State.Authorizing ->
            if (relay.url != null && state.url == relay.url) TvQr(relay.url, caption = tvText(Lang.tv_settings_login_qr_caption))
            else TvQr(null, status = tvText(Lang.tv_settings_qr_preparing))

        else -> TvQr(null, status = tvText(Lang.tv_settings_qr_preparing))
    }
}

/** 焦点到了扫码登录那一行: 这一页发起的授权还在等着就接着用那张码, 否则发起一次. */
private fun ensureLoginRelay(env: TvSettingsEnv) {
    val state = GlobalKoin.get<BangumiOAuthManager>().state.value
    val current = env.deps.loginRelay.value.url
    if (state is BangumiOAuthManager.State.Exchanging) return
    if (current != null && state is BangumiOAuthManager.State.Authorizing && state.url == current) return
    startLoginRelay(env)
}

/** 发起一次经 Worker 中转的授权 (见 [TvBangumiRelayLogin]), 授权页地址或起不了的原因放进 [TvSettingsDeps.loginRelay]. */
private fun startLoginRelay(env: TvSettingsEnv) {
    if (GlobalKoin.get<BangumiEndpointProvider>().viaThirdPartyMirror.value) return
    env.deps.loginRelay.value = when (val start = TvBangumiRelayLogin.start()) {
        is TvBangumiRelayLogin.Start.Ready -> TvLoginRelay(start.url)
        TvBangumiRelayLogin.Start.Unsupported -> TvLoginRelay(problem = tvText(Lang.tv_settings_login_relay_unsupported))
        TvBangumiRelayLogin.Start.NoLan -> TvLoginRelay(problem = tvText(Lang.search_tv_remote_unavailable))
    }
}

// ---- 弹幕过滤规则 (播放器那一类的末尾) ----

/** 规则逐条开关、删除; 新规则要打字, 在手机控制台里加. */
internal fun TvSettingsItemsBuilder.danmakuRuleItems() {
    val rules = TvSettingKeys.danmakuRules
    header(Lang.settings_danmaku_regex_filter_group)
    action(
        Lang.tv_settings_danmaku_rules_add, Lang.tv_settings_danmaku_rules_add_description,
        value = { tvText(Lang.tv_settings_on_phone) },
        qr = { consoleQr(it, "#settings/resources") },
    ) { emptyList() }
    entries(
        listOf(rules),
        entries = { it[rules].orEmpty() },
        key = { it.id },
        title = { tvText(it.name.ifBlank { it.regex }) },
        value = { _, rule -> tvOnOff(rule.enabled) },
        description = { _, rule -> tvText(Lang.tv_settings_danmaku_rule_hint, rule.regex) },
    ) { rule ->
        listOf(
            TvSettingsEdit.Run {
                val repository = GlobalKoin.get<DanmakuRegexFilterRepository>()
                val current = repository.flow.first().find { it.id == rule.id } ?: return@Run
                repository.update(rule.id, current.copy(enabled = !current.enabled))
            },
        )
    }
    choiceOf(
        listOf(rules), Lang.tv_settings_danmaku_rules_delete,
        options = { it[rules].orEmpty() },
        label = { tvText(it.name.ifBlank { it.regex }) },
        read = { null },
        write = { rule ->
            listOf(
                TvSettingsEdit.Run {
                    // 仓库按整个对象相等来删: 用刚读出来的那份
                    val repository = GlobalKoin.get<DanmakuRegexFilterRepository>()
                    repository.flow.first().find { it.id == rule.id }?.let { repository.remove(it) }
                },
            )
        },
        visible = { !it[rules].isNullOrEmpty() },
        confirm = { rule ->
            TvSettingsConfirm(
                title = null,
                text = tvText(Lang.tv_settings_danmaku_rule_delete_confirm, rule.name.ifBlank { rule.regex }),
                confirmLabel = tvText(Lang.tv_settings_delete),
                destructive = true,
            )
        },
    )
}

// ---- 更新 ----

/**
 * 软件更新. 新版本的提示、下载进度、装不装都在「检查更新」那一行上 (行尾写状态, 确定做下一步); 下载总在应用内 (电视上没有浏览器,
 * 「应用内下载」不列), 下完自动安装 (见 TvSettingsUpdateHost).
 */
internal fun TvSettingsItemsBuilder.updateItems() {
    val update = TvAppSettingKeys.update
    val presentation = TvAppSettingKeys.updatePresentation
    action(Lang.settings_update_current_version, value = { tvText(currentAniBuildConfig.versionName) }) { emptyList() }
    action(
        Lang.settings_update_check, keys = listOf(presentation),
        value = { v -> v[presentation]?.let(::updateStatus) },
        details = { v -> v[presentation]?.let(::updateDetails) },
    ) { v ->
        val p = v[presentation] ?: return@action emptyList()
        if (p.isCheckingUpdate || p.state is AppUpdateState.Downloading || p.state is AppUpdateState.Installing) return@action emptyList()
        listOf(TvSettingsEdit.Run { env -> onUpdateRowClicked(env, p) })
    }
    toggle(
        update, Lang.settings_update_auto_check, Lang.settings_update_auto_check_description,
        read = { it.autoCheckUpdate },
        write = { copy(autoCheckUpdate = it) },
    )
    toggle(
        update, Lang.settings_update_auto_download, Lang.settings_update_auto_download_description,
        read = { it.autoDownloadUpdate },
        write = { copy(autoDownloadUpdate = it) },
    )
    // 本项目只发测试版 (版本号带 alpha, 归为 ALPHA) 与正式版: 两档. 以前选过 BETA / RC 的按正式版显示 (不发 beta, 两者收到的一样)
    choice(
        update, Lang.settings_update_type,
        options = { listOf(ReleaseClass.ALPHA, ReleaseClass.STABLE) },
        label = { if (it == ReleaseClass.ALPHA) tvText(Lang.tv_settings_update_type_test) else tvText(Lang.settings_update_type_stable_short) },
        read = { if (it.releaseClass == ReleaseClass.ALPHA) ReleaseClass.ALPHA else ReleaseClass.STABLE },
        write = { copy(releaseClass = it) },
        optionDescription = {
            if (it == ReleaseClass.ALPHA) tvText(Lang.tv_settings_update_type_test_description)
            else tvText(Lang.tv_settings_update_type_stable_description)
        },
    )
    link(Lang.settings_update_view_changelog) { AniHelperDestination.RELEASE_PREFIX + currentAniBuildConfig.versionName }
}

private fun updateStatus(p: AppUpdatePresentation): TvText? {
    val state = p.state
    return when {
        p.isCheckingUpdate -> (p.checkProgress as? UpdateCheckProgress.Mirror)
            ?.let { tvText(Lang.settings_update_checking_mirror, it.index, it.total) }
            ?: tvText(Lang.settings_update_checking)

        state is AppUpdateState.Downloading -> tvText(Lang.tv_settings_update_downloading, (state.progress * 100).roundToInt())
        state is AppUpdateState.Downloaded -> tvText(Lang.tv_settings_update_downloaded)
        state is AppUpdateState.DownloadFailed -> tvText(Lang.tv_settings_update_download_failed)
        state is AppUpdateState.HasNewVersion -> tvText(Lang.settings_update_new_version, state.version.name)
        p.checkUpdateError != null -> tvText(Lang.settings_update_check_failed)
        state is AppUpdateState.AlreadyUpToDate -> tvText(Lang.settings_update_up_to_date)
        else -> null
    }
}

/** 有新版本时说明栏写它的主要改动. */
private fun updateDetails(p: AppUpdatePresentation): TvText? {
    val version = (p.state as? AppUpdateState.HasNewVersion)?.version ?: return null
    val changes = version.majorChanges.joinToString("\n") { "· $it" }
    return tvText(Lang.tv_settings_update_new_hint, changes)
}

private fun onUpdateRowClicked(env: TvSettingsEnv, p: AppUpdatePresentation) {
    val viewModel = env.deps.appUpdate
    val uriHandler = env.uriHandler()
    when (val state = p.state) {
        is AppUpdateState.Downloaded -> viewModel.install(env.context)
        is AppUpdateState.DownloadFailed -> viewModel.restartDownload(uriHandler)
        is AppUpdateState.HasUpdate -> viewModel.startDownload(state.version, uriHandler)
        else -> viewModel.startCheckLatestVersion(uriHandler, manual = true)
    }
}

/** 打开链接 (没有浏览器时弹二维码, 见 [TvSettingsEnv.browser]). */
private fun TvSettingsEnv.uriHandler(): UriHandler = object : UriHandler {
    override fun openUri(uri: String) {
        browser.openBrowser(context, uri)
    }
}

// ---- 日志 ----

/**
 * 日志: 扫码传到手机 (焦点停在那一行就起服务, 说明栏画码, 离开就停)、导出到 U 盘之类的位置、性能诊断 (说明栏画控制台「维护」页的码).
 */
internal fun TvSettingsItemsBuilder.logItems() {
    val logShare = TvAppSettingKeys.logShare
    action(
        Lang.settings_log_send_to_phone, Lang.tv_settings_log_send_description, keys = listOf(logShare),
        qr = { v -> logShareQr(v[logShare]?.state) },
        whileFocused = { env ->
            try {
                runLogLanShare(env.context) { env.deps.logShare.value = it }
            } finally {
                env.deps.logShare.value = null
            }
        },
    ) { emptyList() }
    run(Lang.settings_log_export_file, Lang.tv_settings_log_export_description) { env ->
        if (!env.context.hasCurrentLogFile()) {
            env.toast(getString(Lang.settings_log_file_not_found))
        } else {
            // 系统选择器开着时要一直留在组合里 (结果才收得到), 导完再关
            env.show(TvSettingsOverlay.Dialog { dismiss -> LogExportLauncher(onDone = dismiss) })
        }
    }
    val perf = TvAppSettingKeys.perfStatus
    val latest = TvAppSettingKeys.perfLatest
    action(
        Lang.tv_perf_diag_title, keys = listOf(perf, latest),
        value = { it[perf]?.text },
        details = { v ->
            val last = v[latest]?.text?.let { tvText(Lang.tv_perf_diag_latest, it) } ?: tvText(Lang.tv_perf_diag_none)
            tvText(Lang.tv_settings_paragraphs, tvText(Lang.tv_perf_diag_dialog_hint), last)
        },
        // 网页按地址里的 #settings/maintain 直接停在「维护」那一组
        qr = { consoleQr(it, "#settings/maintain") },
    ) { emptyList() }
}

private fun logShareQr(state: LogLanShareState?): TvQr = when (state) {
    null, LogLanShareState.Starting -> TvQr(null, status = tvText(Lang.tv_settings_qr_preparing))
    LogLanShareState.NoNetwork -> TvQr(null, status = tvText(Lang.settings_log_send_to_phone_no_network))
    LogLanShareState.Failed -> TvQr(null, status = tvText(Lang.settings_log_send_to_phone_failed))
    // 地址也印出来: 扫不了码 (相机没网络权限之类) 还能手敲
    is LogLanShareState.Ready -> TvQr(state.url, caption = tvText(state.url))
}

// ---- 调试 (调试模式下才有) ----

internal fun TvSettingsItemsBuilder.debugItems() {
    val debug = TvSettingKeys.debug
    toggle(debug, Lang.settings_debug_mode, Lang.settings_debug_mode_description, read = { it.enabled }, write = { copy(enabled = it) })
    toggle(
        debug, Lang.settings_debug_show_all_episodes, Lang.settings_debug_show_all_episodes_description,
        read = { it.showAllEpisodes },
        write = { copy(showAllEpisodes = it) },
    )
    val metered = TvAppSettingKeys.metered
    action(Lang.settings_debug_metered_network, keys = listOf(metered), value = { it[metered]?.let(::tvOnOff) }) { emptyList() }
    run(Lang.settings_debug_logout) { env ->
        GlobalKoin.get<UserRepository>().clearSelfInfo()
        env.toast(getString(Lang.settings_debug_logged_out))
    }
    // 直连之后 token 只活 7 天, 续期平时六天才跑一次: 这里当场跑一遍 (成功会把轮换后的新 token 写回, 与自动续期同一条代码)
    run(Lang.tv_settings_debug_refresh_session) { env ->
        val result = runCatching { GlobalKoin.get<SessionManager>().refreshSession() }
        env.toast(
            result.fold(
                onSuccess = { getString(Lang.tv_settings_debug_refresh_ok) },
                onFailure = { getString(Lang.tv_settings_debug_refresh_failed, "${it::class.simpleName} ${it.message}") },
            ),
        )
    }
    // 抛在主线程的下一轮, 不被写设置的出错处理接住
    run(Lang.tv_settings_debug_crash) { Handler(Looper.getMainLooper()).post { throw TvManualCrashException() } }
}

private class TvManualCrashException : Throwable("Manual crash for testing")

/**
 * 电视上没有浏览器: 应用更新一律在应用内下载 (设置页不列「应用内下载」). 以前关掉过的 (存着关) 在这里打开, 否则更新只会弹二维码.
 * TV 根组合启动时调一次.
 */
suspend fun ensureTvInAppUpdateDownload() {
    GlobalKoin.get<SettingsRepository>().updateSettings.update { if (inAppDownload) this else copy(inAppDownload = true) }
}
