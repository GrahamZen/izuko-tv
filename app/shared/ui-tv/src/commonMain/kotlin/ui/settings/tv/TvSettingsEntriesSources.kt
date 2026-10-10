/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.VideoResolverSettings
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_subtitle_language_chinese_cantonese
import me.him188.ani.app.ui.lang.media_subtitle_language_chinese_simplified
import me.him188.ani.app.ui.lang.media_subtitle_language_chinese_traditional
import me.him188.ani.app.ui.lang.media_subtitle_language_english
import me.him188.ani.app.ui.lang.media_subtitle_language_japanese
import me.him188.ani.app.ui.lang.settings_account_logout
import me.him188.ani.app.ui.lang.settings_media_advanced_settings
import me.him188.ani.app.ui.lang.settings_media_always_full_search
import me.him188.ani.app.ui.lang.settings_media_always_full_search_description
import me.him188.ani.app.ui.lang.settings_media_any
import me.him188.ani.app.ui.lang.settings_media_auto_enable_last
import me.him188.ani.app.ui.lang.settings_media_auto_enable_last_description
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_15min
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_1d
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_1h
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_30min
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_5min
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_6h
import me.him188.ani.app.ui.lang.settings_media_cache_ttl_none
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_login_qr_description
import me.him188.ani.app.ui.lang.settings_media_fast_select_web
import me.him188.ani.app.ui.lang.settings_media_fast_select_web_description
import me.him188.ani.app.ui.lang.settings_media_hide_no_subtitle
import me.him188.ani.app.ui.lang.settings_media_hide_no_subtitle_description
import me.him188.ani.app.ui.lang.settings_media_hide_single_episode
import me.him188.ani.app.ui.lang.settings_media_hide_single_episode_description
import me.him188.ani.app.ui.lang.settings_media_image_captcha_auto_solve
import me.him188.ani.app.ui.lang.settings_media_image_captcha_auto_solve_description
import me.him188.ani.app.ui.lang.settings_media_max_wait_time
import me.him188.ani.app.ui.lang.settings_media_max_wait_time_description
import me.him188.ani.app.ui.lang.settings_media_none
import me.him188.ani.app.ui.lang.settings_media_prefer_seasons
import me.him188.ani.app.ui.lang.settings_media_prefer_seasons_description
import me.him188.ani.app.ui.lang.settings_media_prefer_source_type
import me.him188.ani.app.ui.lang.settings_media_prefer_source_type_description
import me.him188.ani.app.ui.lang.settings_media_preference_override_notice
import me.him188.ani.app.ui.lang.settings_media_preference_title
import me.him188.ani.app.ui.lang.settings_media_resolution
import me.him188.ani.app.ui.lang.settings_media_resolution_description
import me.him188.ani.app.ui.lang.settings_media_show_disabled
import me.him188.ani.app.ui.lang.settings_media_show_disabled_description
import me.him188.ani.app.ui.lang.settings_media_source_bt
import me.him188.ani.app.ui.lang.settings_media_source_no_preference
import me.him188.ani.app.ui.lang.settings_media_source_subscription
import me.him188.ani.app.ui.lang.settings_media_source_subscription_refresh_all
import me.him188.ani.app.ui.lang.settings_media_source_subscription_updating
import me.him188.ani.app.ui.lang.settings_media_source_web
import me.him188.ani.app.ui.lang.settings_media_subtitle_language
import me.him188.ani.app.ui.lang.settings_media_video_link_resolve_timeout
import me.him188.ani.app.ui.lang.settings_media_video_link_resolve_timeout_description
import me.him188.ani.app.ui.lang.settings_media_wait_time_10s
import me.him188.ani.app.ui.lang.settings_media_wait_time_15s
import me.him188.ani.app.ui.lang.settings_media_wait_time_20s
import me.him188.ani.app.ui.lang.settings_media_wait_time_30s
import me.him188.ani.app.ui.lang.settings_media_wait_time_3s
import me.him188.ani.app.ui.lang.settings_media_wait_time_5s
import me.him188.ani.app.ui.lang.settings_media_wait_time_8s
import me.him188.ani.app.ui.lang.settings_media_wait_time_infinite
import me.him188.ani.app.ui.lang.settings_media_wait_time_none
import me.him188.ani.app.ui.lang.settings_media_web_search_cache_ttl
import me.him188.ani.app.ui.lang.settings_media_web_search_cache_ttl_description
import me.him188.ani.app.ui.lang.settings_tab_media_source
import me.him188.ani.app.ui.lang.tv_settings_delete
import me.him188.ani.app.ui.lang.tv_settings_drive_login_hint
import me.him188.ani.app.ui.lang.tv_settings_drive_logout_confirm
import me.him188.ani.app.ui.lang.tv_settings_drive_logout_hint
import me.him188.ani.app.ui.lang.tv_settings_drive_not_logged_in
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_confirmed
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_expired
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_failed
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_success
import me.him188.ani.app.ui.lang.tv_settings_drives
import me.him188.ani.app.ui.lang.tv_settings_list_join
import me.him188.ani.app.ui.lang.tv_settings_on_phone
import me.him188.ani.app.ui.lang.tv_settings_paragraphs
import me.him188.ani.app.ui.lang.tv_settings_qr_preparing
import me.him188.ani.app.ui.lang.tv_settings_source_failed
import me.him188.ani.app.ui.lang.tv_settings_source_hint
import me.him188.ani.app.ui.lang.tv_settings_source_ok
import me.him188.ani.app.ui.lang.tv_settings_source_testing
import me.him188.ani.app.ui.lang.tv_settings_source_timeout
import me.him188.ani.app.ui.lang.tv_settings_sources_on_phone
import me.him188.ani.app.ui.lang.tv_settings_sources_on_phone_description
import me.him188.ani.app.ui.lang.tv_settings_sources_test_all
import me.him188.ani.app.ui.lang.tv_settings_sources_test_all_description
import me.him188.ani.app.ui.lang.tv_settings_sources_test_summary
import me.him188.ani.app.ui.lang.tv_settings_sources_testing
import me.him188.ani.app.ui.lang.tv_settings_subscription_count
import me.him188.ani.app.ui.lang.tv_settings_subscription_delete_confirm
import me.him188.ani.app.ui.lang.tv_settings_subscription_delete_hint
import me.him188.ani.app.ui.lang.tv_settings_subscription_failed_at
import me.him188.ani.app.ui.lang.tv_settings_subscription_status_failed
import me.him188.ani.app.ui.lang.tv_settings_subscription_status_never
import me.him188.ani.app.ui.lang.tv_settings_subscription_updated_at
import me.him188.ani.app.ui.lang.tv_settings_value_pair
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.Resolution
import me.him188.ani.datasources.api.topic.SubtitleLanguage
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 数据源、观看偏好用到的几份值. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object TvSourceSettingKeys {
    val selector = TvSettingKey.Stored("mediaSelector") { it.mediaSelectorSettings }
    val preference = TvSettingKey.Stored("mediaPreference") { it.defaultMediaPreference }
    val resolver = TvSettingKey.Stored("videoResolver") { it.videoResolverSettings }

    /** 数据源 (按用户排的顺序; 本地缓存那个不列). */
    val sources = TvSettingKey.Live("sources") {
        val manager = GlobalKoin.get<MediaSourceManager>()
        manager.allInstances.map { list -> list.filter { !manager.isLocal(it.factoryId) } }
    }

    val sourceTests = TvSettingKey.Live("sourceTests") { deps ->
        combine(deps.sourceTests.results, deps.sourceTests.running) { results, running -> TvSourceTestsState(results, running) }
    }

    val subscriptions = TvSettingKey.Live("subscriptions") { GlobalKoin.get<MediaSourceSubscriptionRepository>().flow }

    /** 订阅正在全部更新时的进度. */
    val subscriptionUpdate = TvSettingKey.Live("subscriptionUpdate") { deps ->
        val state = deps.settings.mediaSourceSubscriptionGroupState
        combine(state.isUpdateAllInProgress, snapshotFlow { state.updateProgress }) { running, progress ->
            TvSubscriptionUpdate(running, progress)
        }
    }

    /** 正在进行的网盘扫码登录 (按网盘 id). */
    val driveQr = TvSettingKey.Live("driveQr") { deps -> deps.driveQr }

    /** 网盘与各自登录的账号. */
    val drives = TvSettingKey.Live("drives") {
        GlobalKoin.get<CloudDriveRegistry>().drives.filterNotNull().flatMapLatest { services ->
            if (services.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(services.map { service -> service.account.map { TvDrive(service, it) } }) { it.toList() }
            }
        }
    }
}

internal data class TvSourceTestsState(val results: Map<String, TvSourceTestStatus>, val running: Boolean)
internal data class TvSubscriptionUpdate(val running: Boolean, val progress: Pair<Int, Int>?)
internal data class TvDrive(val service: CloudDriveService, val account: CloudDriveAccount)

// ---- 数据源 ----

/**
 * 数据源: 订阅 (全部刷新、逐个删除)、网盘 (退出登录)、数据源 (逐个启用 / 停用, 长按挪顺序, 一键测试). 新增与编辑要打字, 在手机控制台里.
 */
internal fun TvSettingsItemsBuilder.mediaSourceItems() {
    val keys = TvSourceSettingKeys
    // 数据源
    header(Lang.settings_tab_media_source)
    action(
        Lang.tv_settings_sources_on_phone, Lang.tv_settings_sources_on_phone_description,
        value = { tvText(Lang.tv_settings_on_phone) },
        qr = { consoleQr(it, "#sources") },
    ) { emptyList() }
    action(
        Lang.tv_settings_sources_test_all, Lang.tv_settings_sources_test_all_description,
        keys = listOf(keys.sources, keys.sourceTests),
        value = { v -> v[keys.sourceTests]?.let(::testSummary) },
    ) { v ->
        val sources = v[keys.sources].orEmpty()
        listOf(TvSettingsEdit.Run { env -> env.deps.sourceTests.testAll(sources) })
    }
    entries(
        listOf(keys.sources, keys.sourceTests),
        entries = { it[keys.sources].orEmpty() },
        key = { it.instanceId },
        title = { tvText(it.source.info.displayName) },
        value = { v, source ->
            val test = v[keys.sourceTests]?.results?.get(source.instanceId)
            val onOff = tvOnOff(source.isEnabled)
            if (test == null) onOff else tvText(Lang.tv_settings_value_pair, testLabel(test), onOff)
        },
        description = { _, source ->
            val description = source.source.info.description
            val hint = tvText(Lang.tv_settings_source_hint)
            if (description.isNullOrBlank()) hint else tvText(Lang.tv_settings_paragraphs, description, hint)
        },
        move = { order -> listOf(TvSettingsEdit.Run { GlobalKoin.get<MediaSourceManager>().partiallyReorderInstances(order) }) },
    ) { source ->
        listOf(TvSettingsEdit.Run { GlobalKoin.get<MediaSourceManager>().setEnabled(source.instanceId, !source.isEnabled) })
    }

    // 订阅
    header(Lang.settings_media_source_subscription)
    action(
        Lang.settings_media_source_subscription_refresh_all,
        keys = listOf(keys.subscriptions, keys.subscriptionUpdate),
        value = { v ->
            val update = v[keys.subscriptionUpdate]
            val progress = update?.progress
            if (update?.running == true && progress != null) tvText(Lang.settings_media_source_subscription_updating, progress.first, progress.second) else null
        },
        visible = { !it[keys.subscriptions].isNullOrEmpty() },
    ) { v ->
        if (v[keys.subscriptionUpdate]?.running == true) return@action emptyList()
        listOf(TvSettingsEdit.Run { env -> env.deps.settings.mediaSourceSubscriptionGroupState.updateAll() })
    }
    entries(
        listOf(keys.subscriptions),
        entries = { it[keys.subscriptions].orEmpty() },
        key = { it.subscriptionId },
        title = { tvText(subscriptionLabel(it.url)) },
        value = { _, sub -> subscriptionStatus(sub) },
        description = { _, sub ->
            val details = tvText(Lang.tv_settings_paragraphs, sub.url, subscriptionUpdatedAt(sub))
            tvText(Lang.tv_settings_paragraphs, details, tvText(Lang.tv_settings_subscription_delete_hint))
        },
        confirm = { sub ->
            TvSettingsConfirm(
                title = null,
                text = tvText(Lang.tv_settings_subscription_delete_confirm, subscriptionLabel(sub.url)),
                confirmLabel = tvText(Lang.tv_settings_delete),
                destructive = true,
            )
        },
    ) { sub -> listOf(TvSettingsEdit.Run { env -> env.deps.settings.mediaSourceSubscriptionGroupState.delete(sub) }) }

    // 网盘: 没登录的那一行, 焦点停上去就要一张登录二维码画在说明栏 (手机上的网盘 App 扫), 离开就作废; 过期了按确定换一张
    header(Lang.tv_settings_drives)
    entries(
        listOf(keys.drives, keys.driveQr),
        entries = { it[keys.drives].orEmpty() },
        key = { it.service.driveId },
        title = { tvText(it.service.protocol.displayName(Locale.getDefault().toLanguageTag())) },
        value = { _, drive ->
            if (drive.account.isLoggedIn) tvText(drive.account.nickname.ifBlank { "✓" }) else tvText(Lang.tv_settings_drive_not_logged_in)
        },
        description = { _, drive ->
            tvText(if (drive.account.isLoggedIn) Lang.tv_settings_drive_logout_hint else Lang.tv_settings_drive_login_hint)
        },
        qr = { v, drive -> if (drive.account.isLoggedIn) null else driveLoginQr(drive, v[keys.driveQr]?.get(drive.service.driveId)) },
        whileFocused = { drive ->
            if (drive.account.isLoggedIn) null else { env -> runDriveQrLogin(env, drive.service) }
        },
        confirm = { drive ->
            if (!drive.account.isLoggedIn) {
                null
            } else {
                TvSettingsConfirm(
                    title = null,
                    text = tvText(
                        Lang.tv_settings_drive_logout_confirm,
                        drive.service.protocol.displayName(Locale.getDefault().toLanguageTag()),
                        drive.account.nickname,
                    ),
                    confirmLabel = tvText(Lang.settings_account_logout),
                    destructive = true,
                )
            }
        },
    ) { drive ->
        if (drive.account.isLoggedIn) {
            listOf(TvSettingsEdit.Run { drive.service.logout() })
        } else {
            listOf(TvSettingsEdit.Run { env -> env.restartFocusTask() })
        }
    }
}

/** 收 [service] 的扫码登录 (要码、等扫、换登录态) 放进 [TvSettingsDeps.driveQr]; 焦点离开 (取消) 时作废, 走完了 (过期、失败) 留着结果. */
private suspend fun runDriveQrLogin(env: TvSettingsEnv, service: CloudDriveService) {
    val id = service.driveId
    try {
        service.qrLogin().collect { state -> env.deps.driveQr.update { it + (id to state) } }
    } catch (e: CancellationException) {
        env.deps.driveQr.update { it - id }
        throw e
    }
}

private fun driveLoginQr(drive: TvDrive, state: CloudDriveQrLoginState?): TvQr = when (state) {
    null, CloudDriveQrLoginState.Loading -> TvQr(null, status = tvText(Lang.tv_settings_qr_preparing))
    is CloudDriveQrLoginState.WaitingForScan -> {
        val app = drive.service.protocol.login.qr?.appName?.takeIf { it.isNotBlank() }
            ?: drive.service.protocol.displayName(Locale.getDefault().toLanguageTag())
        TvQr(state.qrContent, caption = tvText(Lang.settings_media_cloud_drive_login_qr_description, app))
    }

    CloudDriveQrLoginState.Confirmed -> TvQr(null, status = tvText(Lang.tv_settings_drive_qr_confirmed))
    is CloudDriveQrLoginState.Success -> TvQr(null, status = tvText(Lang.tv_settings_drive_qr_success, state.nickname))
    CloudDriveQrLoginState.Expired -> TvQr(null, status = tvText(Lang.tv_settings_drive_qr_expired))
    is CloudDriveQrLoginState.Failed -> TvQr(null, status = tvText(Lang.tv_settings_drive_qr_failed, state.message))
}

private fun testLabel(status: TvSourceTestStatus): TvText = tvText(
    when (status) {
        TvSourceTestStatus.Testing -> Lang.tv_settings_source_testing
        TvSourceTestStatus.Ok -> Lang.tv_settings_source_ok
        TvSourceTestStatus.Failed -> Lang.tv_settings_source_failed
        TvSourceTestStatus.Timeout -> Lang.tv_settings_source_timeout
    },
)

private fun testSummary(state: TvSourceTestsState): TvText? {
    if (state.results.isEmpty()) return null
    val done = state.results.values.count { it != TvSourceTestStatus.Testing }
    if (state.running) return tvText(Lang.tv_settings_sources_testing, done, state.results.size)
    val ok = state.results.values.count { it == TvSourceTestStatus.Ok }
    return tvText(Lang.tv_settings_sources_test_summary, ok, state.results.size - ok)
}

/** 行尾: 带来了几个数据源 / 更新失败 / 还没更新过 (时间写在说明栏, 见 [subscriptionUpdatedAt]). */
private fun subscriptionStatus(sub: MediaSourceSubscription): TvText {
    val last = sub.lastUpdated ?: return tvText(Lang.tv_settings_subscription_status_never)
    val count = last.mediaSourceCount
    return if (last.error != null || count == null) {
        tvText(Lang.tv_settings_subscription_status_failed)
    } else {
        tvText(Lang.tv_settings_subscription_count, count)
    }
}

private fun subscriptionUpdatedAt(sub: MediaSourceSubscription): TvText {
    val last = sub.lastUpdated ?: return tvText(Lang.tv_settings_subscription_status_never)
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(last.timeMillis))
    return if (last.error != null || last.mediaSourceCount == null) {
        tvText(Lang.tv_settings_subscription_failed_at, time)
    } else {
        tvText(Lang.tv_settings_subscription_updated_at, time)
    }
}

/** 订阅地址的短名: GitHub 上的取「用户/仓库」, 其余取域名 (订阅记录只存了地址, 没有名字). */
internal fun subscriptionLabel(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host?.removePrefix("www.") ?: return url
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when {
        (host == "github.com" || host == "raw.githubusercontent.com") && segments.size >= 2 -> "${segments[0]}/${segments[1]}"
        host == "cdn.jsdelivr.net" && segments.firstOrNull() == "gh" && segments.size >= 3 ->
            "${segments[1]}/${segments[2].substringBefore('@')}"

        else -> host
    }
}

// ---- 观看偏好 ----

/** 偏好哪类数据源: 在线 / BT / 不偏好 (设置里存的是可空的 [MediaSourceKind], 单选的值不能是 null). */
private enum class TvPreferKind(val kind: MediaSourceKind?) { Web(MediaSourceKind.WEB), Bt(MediaSourceKind.BitTorrent), None(null) }

/**
 * 观看偏好: 字幕语言与分辨率的优先顺序 (排序), 选源时的各项开关与等待时长. 偏好的字幕组 / 排除的字幕组要打字, 在手机控制台里.
 */
internal fun TvSettingsItemsBuilder.mediaSelectorItems() {
    val keys = TvSourceSettingKeys
    val selector = keys.selector
    val preference = keys.preference
    header(Lang.settings_media_preference_title)
    sorter(
        preference, Lang.settings_media_subtitle_language, Lang.settings_media_preference_override_notice,
        read = { it.fallbackSubtitleLanguageIds.extendTo(SubtitleLanguage.matchableEntries.map { l -> l.id }) },
        label = { subtitleLanguageLabel(it) },
        summary = { list -> sorterSummary(list) { subtitleLanguageLabel(it) } },
        write = { list -> copy(fallbackSubtitleLanguageIds = list.filter { it.second }.map { it.first }) },
    )
    sorter(
        preference, Lang.settings_media_resolution, Lang.settings_media_resolution_description,
        read = { it.fallbackResolutions.extendTo(Resolution.entries.map { r -> r.id }) },
        label = { tvText(Resolution.tryParse(it)?.displayName ?: it) },
        summary = { list -> sorterSummary(list) { tvText(Resolution.tryParse(it)?.displayName ?: it) } },
        write = { list -> copy(fallbackResolutions = list.filter { it.second }.map { it.first }) },
    )

    header(Lang.settings_media_advanced_settings)
    choice(
        selector, Lang.settings_media_prefer_source_type, Lang.settings_media_prefer_source_type_description,
        options = { TvPreferKind.entries },
        label = {
            tvText(
                when (it) {
                    TvPreferKind.Web -> Lang.settings_media_source_web
                    TvPreferKind.Bt -> Lang.settings_media_source_bt
                    TvPreferKind.None -> Lang.settings_media_source_no_preference
                },
            )
        },
        read = { s -> TvPreferKind.entries.first { it.kind == s.preferKind } },
        write = { copy(preferKind = it.kind) },
    )
    // 下面四项只在偏好在线源时有意义 (讲的都是在线源的搜 / 选 / 解析)
    toggle(
        selector, Lang.settings_media_fast_select_web, Lang.settings_media_fast_select_web_description,
        visible = { it.preferKind == MediaSourceKind.WEB },
        read = { it.fastSelectWebKind },
        write = { copy(fastSelectWebKind = it) },
    )
    choice(
        selector, Lang.settings_media_max_wait_time, Lang.settings_media_max_wait_time_description,
        options = { listOf(0.seconds, 3.seconds, 5.seconds, 8.seconds, 10.seconds, 15.seconds, Duration.INFINITE) },
        label = { waitLabel(it) },
        read = { it.fastSelectWebLowTierToleranceDuration },
        write = { copy(fastSelectWebLowTierToleranceDuration = it) },
        visible = { it.preferKind == MediaSourceKind.WEB && it.fastSelectWebKind },
    )
    choiceOf(
        listOf(keys.resolver, selector),
        Lang.settings_media_video_link_resolve_timeout, Lang.settings_media_video_link_resolve_timeout_description,
        options = { VideoResolverSettings.ResourceExtractionTimeoutSecondsOptions },
        label = { waitLabel(it.seconds) },
        read = { it[keys.resolver]?.effectiveResourceExtractionTimeoutSeconds },
        write = { seconds -> listOf(TvSettingsEdit.Update(keys.resolver) { it.copy(resourceExtractionTimeoutSeconds = seconds) }) },
        visible = { it[selector]?.preferKind == MediaSourceKind.WEB },
    )
    choice(
        selector, Lang.settings_media_web_search_cache_ttl, Lang.settings_media_web_search_cache_ttl_description,
        options = { listOf(Duration.ZERO, 5.minutes, 15.minutes, 30.minutes, 1.hours, 6.hours, 1.days) },
        label = { cacheTtlLabel(it) },
        read = { it.webSearchCacheTtl },
        write = { copy(webSearchCacheTtl = it) },
        visible = { it.preferKind == MediaSourceKind.WEB },
    )
    toggle(
        selector, Lang.settings_media_image_captcha_auto_solve, Lang.settings_media_image_captcha_auto_solve_description,
        visible = { it.preferKind == MediaSourceKind.WEB },
        read = { it.enableImageCaptchaAutoSolve },
        write = { copy(enableImageCaptchaAutoSolve = it) },
    )
    toggle(
        selector, Lang.settings_media_show_disabled, Lang.settings_media_show_disabled_description,
        read = { it.showDisabled },
        write = { copy(showDisabled = it) },
    )
    toggle(
        preference, Lang.settings_media_hide_no_subtitle, Lang.settings_media_hide_no_subtitle_description,
        read = { !it.showWithoutSubtitle },
        write = { copy(showWithoutSubtitle = !it) },
    )
    toggle(
        selector, Lang.settings_media_hide_single_episode, Lang.settings_media_hide_single_episode_description,
        read = { it.hideSingleEpisodeForCompleted },
        write = { copy(hideSingleEpisodeForCompleted = it) },
    )
    toggle(
        selector, Lang.settings_media_prefer_seasons, Lang.settings_media_prefer_seasons_description,
        read = { it.preferSeasons },
        write = { copy(preferSeasons = it) },
    )
    toggle(
        selector, Lang.settings_media_auto_enable_last, Lang.settings_media_auto_enable_last_description,
        read = { it.autoEnableLastSelected },
        write = { copy(autoEnableLastSelected = it) },
    )
    toggle(
        selector, Lang.settings_media_always_full_search, Lang.settings_media_always_full_search_description,
        read = { it.alwaysFullSearch },
        write = { copy(alwaysFullSearch = it) },
    )
}

/** 存着的先后 (null = 任意 = 全选) 补全成全部选项: 选中的按存的顺序在前, 没选的跟在后面. 同原来设置页的排序. */
private fun List<String>?.extendTo(all: List<String>): List<Pair<String, Boolean>> {
    val fallback = this ?: return all.map { it to true }
    return fallback.map { it to true } + (all - fallback.toSet()).map { it to false }
}

private fun sorterSummary(list: List<Pair<String, Boolean>>, label: (String) -> TvText): TvText = when {
    list.all { it.second } -> tvText(Lang.settings_media_any)
    list.none { it.second } -> tvText(Lang.settings_media_none)
    else -> list.filter { it.second }.map { label(it.first) }.reduce { acc, text -> tvText(Lang.tv_settings_list_join, acc, text) }
}

private fun subtitleLanguageLabel(id: String): TvText = when (SubtitleLanguage.tryParse(id)) {
    SubtitleLanguage.ChineseSimplified -> tvText(Lang.media_subtitle_language_chinese_simplified)
    SubtitleLanguage.ChineseTraditional -> tvText(Lang.media_subtitle_language_chinese_traditional)
    SubtitleLanguage.ChineseCantonese -> tvText(Lang.media_subtitle_language_chinese_cantonese)
    SubtitleLanguage.Japanese -> tvText(Lang.media_subtitle_language_japanese)
    SubtitleLanguage.English -> tvText(Lang.media_subtitle_language_english)
    else -> tvText(id)
}

private fun waitLabel(duration: Duration): TvText = when (duration) {
    0.seconds -> tvText(Lang.settings_media_wait_time_none)
    3.seconds -> tvText(Lang.settings_media_wait_time_3s)
    5.seconds -> tvText(Lang.settings_media_wait_time_5s)
    8.seconds -> tvText(Lang.settings_media_wait_time_8s)
    10.seconds -> tvText(Lang.settings_media_wait_time_10s)
    15.seconds -> tvText(Lang.settings_media_wait_time_15s)
    20.seconds -> tvText(Lang.settings_media_wait_time_20s)
    30.seconds -> tvText(Lang.settings_media_wait_time_30s)
    Duration.INFINITE -> tvText(Lang.settings_media_wait_time_infinite)
    else -> tvText(duration.toString())
}

private fun cacheTtlLabel(duration: Duration): TvText = when (duration) {
    Duration.ZERO -> tvText(Lang.settings_media_cache_ttl_none)
    5.minutes -> tvText(Lang.settings_media_cache_ttl_5min)
    15.minutes -> tvText(Lang.settings_media_cache_ttl_15min)
    30.minutes -> tvText(Lang.settings_media_cache_ttl_30min)
    1.hours -> tvText(Lang.settings_media_cache_ttl_1h)
    6.hours -> tvText(Lang.settings_media_cache_ttl_6h)
    1.days -> tvText(Lang.settings_media_cache_ttl_1d)
    else -> tvText(duration.toString())
}
