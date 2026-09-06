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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.app.data.models.preference.DanmakuCacheStrategy
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.EndpointSelectionMode
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.network.TmdbImageEndpoints
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionRepository
import me.him188.ani.app.domain.foundation.BangumiMirrorConsent
import me.him188.ani.app.domain.foundation.BangumiMirrorListRepository
import me.him188.ani.app.domain.settings.ServiceConnectionTester.TestState
import me.him188.ani.app.domain.settings.ServiceConnectionTesters
import me.him188.ani.app.domain.torrent.peer.PeerFilterSubscription
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_torrent_download_rate_limit
import me.him188.ani.app.ui.lang.settings_media_torrent_peer_filter
import me.him188.ani.app.ui.lang.settings_media_torrent_peer_filter_description
import me.him188.ani.app.ui.lang.settings_media_torrent_share_ratio_description
import me.him188.ani.app.ui.lang.settings_media_torrent_share_ratio_limit
import me.him188.ani.app.ui.lang.settings_media_torrent_sharing_description
import me.him188.ani.app.ui.lang.settings_media_torrent_sharing_settings
import me.him188.ani.app.ui.lang.settings_media_torrent_speed_format
import me.him188.ani.app.ui.lang.settings_media_torrent_unlimited
import me.him188.ani.app.ui.lang.settings_media_torrent_upload_rate_limit
import me.him188.ani.app.ui.lang.settings_network_bangumi_auto
import me.him188.ani.app.ui.lang.settings_network_bangumi_auto_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_builtin_mirrors
import me.him188.ani.app.ui.lang.settings_network_bangumi_custom
import me.him188.ani.app.ui.lang.settings_network_bangumi_direct
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint
import me.him188.ani.app.ui.lang.settings_network_bangumi_endpoint_description
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_off_confirm
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_off_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_off_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_on
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_risk_confirm
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_risk_message
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror_credentials_risk_title
import me.him188.ani.app.ui.lang.settings_network_bangumi_mode
import me.him188.ani.app.ui.lang.settings_network_bangumi_no_login_notice
import me.him188.ani.app.ui.lang.settings_network_endpoint_auto
import me.him188.ani.app.ui.lang.settings_network_endpoint_auto_description
import me.him188.ani.app.ui.lang.settings_network_endpoint_custom
import me.him188.ani.app.ui.lang.settings_network_endpoint_fixed_description
import me.him188.ani.app.ui.lang.settings_network_proxy_custom
import me.him188.ani.app.ui.lang.settings_network_proxy_disabled
import me.him188.ani.app.ui.lang.settings_network_proxy_service_collection
import me.him188.ani.app.ui.lang.settings_network_proxy_service_comment
import me.him188.ani.app.ui.lang.settings_network_proxy_service_tmdb
import me.him188.ani.app.ui.lang.settings_network_proxy_service_tmdb_image
import me.him188.ani.app.ui.lang.settings_network_proxy_system
import me.him188.ani.app.ui.lang.settings_network_proxy_test_failed
import me.him188.ani.app.ui.lang.settings_network_proxy_title
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_disable
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_disable_description
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_endpoint
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_group
import me.him188.ani.app.ui.lang.settings_pikpak_description
import me.him188.ani.app.ui.lang.settings_pikpak_drive_usage_failed
import me.him188.ani.app.ui.lang.settings_pikpak_drive_usage_idle
import me.him188.ani.app.ui.lang.settings_pikpak_drive_usage_signed_out
import me.him188.ani.app.ui.lang.settings_pikpak_drive_usage_title
import me.him188.ani.app.ui.lang.settings_pikpak_drive_usage_value
import me.him188.ani.app.ui.lang.settings_pikpak_enabled
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_apply
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_message
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_title
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_description_cache_on_collection_doing_media_play
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_description_cache_on_media_cache
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_description_do_not_cache
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_title
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done
import me.him188.ani.app.ui.lang.settings_storage_delete_cache_when_done_description
import me.him188.ani.app.ui.lang.settings_storage_image_cache
import me.him188.ani.app.ui.lang.settings_storage_image_cache_cleared
import me.him188.ani.app.ui.lang.settings_storage_image_cache_usage
import me.him188.ani.app.ui.lang.settings_storage_play_cache_without_searching
import me.him188.ani.app.ui.lang.settings_storage_play_cache_without_searching_description
import me.him188.ani.app.ui.lang.tv_settings_bangumi_custom_hint
import me.him188.ani.app.ui.lang.tv_settings_danmaku_cache_collection
import me.him188.ani.app.ui.lang.tv_settings_danmaku_cache_media
import me.him188.ani.app.ui.lang.tv_settings_danmaku_cache_none
import me.him188.ani.app.ui.lang.tv_settings_endpoint_custom_hint
import me.him188.ani.app.ui.lang.tv_settings_milliseconds
import me.him188.ani.app.ui.lang.tv_settings_paragraphs
import me.him188.ani.app.ui.lang.tv_settings_peer_block_invalid_id
import me.him188.ani.app.ui.lang.tv_settings_peer_block_invalid_id_description
import me.him188.ani.app.ui.lang.tv_settings_peer_builtin_rules
import me.him188.ani.app.ui.lang.tv_settings_peer_filter_client
import me.him188.ani.app.ui.lang.tv_settings_peer_filter_id
import me.him188.ani.app.ui.lang.tv_settings_peer_filter_ip
import me.him188.ani.app.ui.lang.tv_settings_peer_rules_hint
import me.him188.ani.app.ui.lang.tv_settings_pikpak
import me.him188.ani.app.ui.lang.tv_settings_pikpak_account
import me.him188.ani.app.ui.lang.tv_settings_pikpak_account_hint
import me.him188.ani.app.ui.lang.tv_settings_pikpak_not_signed_in
import me.him188.ani.app.ui.lang.tv_settings_proxy_custom_address
import me.him188.ani.app.ui.lang.tv_settings_proxy_mode
import me.him188.ani.app.ui.lang.tv_settings_proxy_mode_description
import me.him188.ani.app.ui.lang.tv_settings_proxy_test
import me.him188.ani.app.ui.lang.tv_settings_proxy_test_all_ok
import me.him188.ani.app.ui.lang.tv_settings_proxy_test_description
import me.him188.ani.app.ui.lang.tv_settings_proxy_test_some_failed
import me.him188.ani.app.ui.lang.tv_settings_testing
import me.him188.ani.app.ui.settings.tabs.media.PikPakDriveUsagePresentation
import me.him188.ani.app.ui.settings.tabs.network.MirrorSwitchConsentDialog
import me.him188.ani.app.ui.settings.tabs.network.ProxyUIMode
import me.him188.ani.app.ui.settings.tabs.network.toDataSettings
import me.him188.ani.app.ui.settings.tabs.network.toUIConfig
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.Unspecified
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.FileSize.Companion.megaBytes
import me.him188.ani.utils.platform.format1f
import org.jetbrains.compose.resources.getString

/** 网络、BT、存储用到的几份值. */
internal object TvNetworkSettingKeys {
    val proxy = TvSettingKey.Stored("proxy") { it.proxySettings }
    val tmdbDisabled = TvSettingKey.Stored("tmdbDisabled") { it.tmdbImagesDisabled }
    val tmdbEndpoint = TvSettingKey.Stored("tmdbEndpoint") { GlobalKoin.get<TmdbImageEndpoints>().selection }
    val tmdbHosts = TvSettingKey.Live("tmdbHosts") { GlobalKoin.get<TmdbImageEndpoints>().candidates }
    val bangumi = TvSettingKey.Stored("bangumiEndpoint") { it.bangumiEndpointSettings }
    val bangumiMirrors = TvSettingKey.Live("bangumiMirrors") { GlobalKoin.get<BangumiMirrorListRepository>().mirrors }
    val bangumiLoggedIn = TvSettingKey.Live("bangumiLoggedIn") { deps -> deps.settings.bangumiLoggedIn }

    val proxyTest = TvSettingKey.Live("proxyTest") { deps ->
        combine(deps.proxyTest.results, deps.proxyTest.running) { results, running -> TvProxyTestState(results, running) }
    }

    val torrent = TvSettingKey.Stored("torrent") { it.anitorrentConfig }
    val peer = TvSettingKey.Stored("torrentPeer") { it.torrentPeerConfig }
    val peerSubscriptions = TvSettingKey.Live("peerSubscriptions") { GlobalKoin.get<PeerFilterSubscriptionRepository>().presentationFlow }
    val pikpak = TvSettingKey.Stored("pikpak") { it.pikpakConfig }
    val pikpakUsage = TvSettingKey.Live("pikpakUsage") { deps -> snapshotFlow { deps.settings.pikpakDriveUsageState.presentation } }

    val mediaCache = TvSettingKey.Stored("mediaCache") { it.mediaCacheSettings }

    /** 图片磁盘缓存占用 (下载缓存 + 结果缓存), 清理后重算. */
    val imageCacheUsage = TvSettingKey.Live("imageCacheUsage") { deps ->
        deps.imageCacheRefresh.map {
            val caches = listOf(deps.sketch.downloadCache, deps.sketch.resultCache)
            TvImageCacheUsage(caches.sumOf { it.size }.bytes, caches.sumOf { it.maxSize }.bytes)
        }.flowOn(Dispatchers.IO)
    }
}

internal data class TvProxyTestState(val results: Map<String, TestState>?, val running: Boolean)
internal data class TvImageCacheUsage(val used: FileSize, val max: FileSize)

// ---- 网络 ----

/**
 * 网络: 代理方式与连通测试、TMDB 背景图、Bangumi 连接方式. 地址、账号、密码要打字, 在手机控制台里填; 填过之后这里才列出「自定义」那一档.
 */
internal fun TvSettingsItemsBuilder.networkItems() {
    val keys = TvNetworkSettingKeys
    header(Lang.settings_network_proxy_title)
    choiceOf(
        listOf(keys.proxy), Lang.tv_settings_proxy_mode,
        options = { v ->
            val config = v[keys.proxy]?.toUIConfig()
            buildList {
                add(ProxyUIMode.DISABLED)
                add(ProxyUIMode.SYSTEM)
                if (config != null && (config.manualUrl.isNotBlank() || config.mode == ProxyUIMode.CUSTOM)) add(ProxyUIMode.CUSTOM)
            }
        },
        label = {
            tvText(
                when (it) {
                    ProxyUIMode.DISABLED -> Lang.settings_network_proxy_disabled
                    ProxyUIMode.SYSTEM -> Lang.settings_network_proxy_system
                    ProxyUIMode.CUSTOM -> Lang.settings_network_proxy_custom
                },
            )
        },
        read = { it[keys.proxy]?.toUIConfig()?.mode },
        write = { mode -> listOf(TvSettingsEdit.Update(keys.proxy) { it.toUIConfig().copy(mode = mode).toDataSettings() }) },
        dynamicDescription = { v ->
            val url = v[keys.proxy]?.toUIConfig()?.manualUrl.orEmpty()
            val hint = tvText(Lang.tv_settings_proxy_mode_description)
            if (url.isBlank()) hint else tvText(Lang.tv_settings_paragraphs, tvText(Lang.tv_settings_proxy_custom_address, url), hint)
        },
        qr = { consoleQr(it, "#settings/network") },
    )
    action(
        Lang.tv_settings_proxy_test, Lang.tv_settings_proxy_test_description, keys = listOf(keys.proxyTest),
        value = { v ->
            val state = v[keys.proxyTest] ?: return@action null
            val results = state.results
            when {
                state.running -> tvText(Lang.tv_settings_testing)
                results == null -> null
                results.values.any { it !is TestState.Success } -> tvText(Lang.tv_settings_proxy_test_some_failed)
                else -> tvText(Lang.tv_settings_proxy_test_all_ok)
            }
        },
    ) { listOf(TvSettingsEdit.Run { env -> env.deps.proxyTest.start() }) }
    entries(
        listOf(keys.proxyTest),
        entries = { v -> v[keys.proxyTest]?.results?.entries?.toList().orEmpty() },
        key = { it.key },
        title = { tvText(serviceName(it.key)) },
        value = { _, (_, state) ->
            when (state) {
                is TestState.Success -> tvText(Lang.tv_settings_milliseconds, state.time.inWholeMilliseconds)
                TestState.Idle, TestState.Testing -> tvText(Lang.tv_settings_testing)
                else -> tvText(Lang.settings_network_proxy_test_failed)
            }
        },
    ) { emptyList() }

    header(Lang.settings_network_tmdb_images_group)
    toggle(
        keys.tmdbDisabled, Lang.settings_network_tmdb_images_disable, Lang.settings_network_tmdb_images_disable_description,
        read = { it },
        write = { it },
    )
    choiceOf(
        listOf(keys.tmdbEndpoint, keys.tmdbHosts, keys.tmdbDisabled), Lang.settings_network_tmdb_images_endpoint,
        options = { v -> endpointOptions(v[keys.tmdbEndpoint], v[keys.tmdbHosts].orEmpty()) },
        label = { it.label() },
        read = { v -> v[keys.tmdbEndpoint]?.option() },
        write = { option -> listOf(TvSettingsEdit.Update(keys.tmdbEndpoint) { option.applyTo(it) }) },
        visible = { it[keys.tmdbDisabled] == false },
        dynamicDescription = { v ->
            val selection = v[keys.tmdbEndpoint]
            val hosts = v[keys.tmdbHosts].orEmpty()
            val effectivelyAuto = selection == null || when (selection.mode) {
                EndpointSelectionMode.AUTO -> true
                EndpointSelectionMode.FIXED -> false
                EndpointSelectionMode.CUSTOM -> EndpointUrls.normalizeBaseUrl(selection.customBaseUrl) == null
            }
            val main = if (effectivelyAuto) {
                tvText(Lang.settings_network_endpoint_auto_description, hosts.joinToString("、") { EndpointUrls.displayName(it) })
            } else {
                tvText(Lang.settings_network_endpoint_fixed_description)
            }
            tvText(Lang.tv_settings_paragraphs, main, tvText(Lang.tv_settings_endpoint_custom_hint))
        },
        qr = { consoleQr(it, "#settings/network") },
    )

    header(Lang.settings_network_bangumi_endpoint)
    choiceOf(
        listOf(keys.bangumi, keys.bangumiMirrors, keys.bangumiLoggedIn), Lang.settings_network_bangumi_mode,
        options = { v ->
            val settings = v[keys.bangumi]
            buildList {
                add(BangumiEndpointMode.DIRECT)
                add(BangumiEndpointMode.AUTO)
                add(BangumiEndpointMode.MIRROR)
                if (settings != null &&
                    (BangumiMirrorHosts.normalizeMirrorRoot(settings.customBaseUrl) != null || settings.mode == BangumiEndpointMode.CUSTOM)
                ) {
                    add(BangumiEndpointMode.CUSTOM)
                }
            }
        },
        label = {
            tvText(
                when (it) {
                    BangumiEndpointMode.DIRECT -> Lang.settings_network_bangumi_direct
                    BangumiEndpointMode.AUTO -> Lang.settings_network_bangumi_auto
                    BangumiEndpointMode.MIRROR -> Lang.settings_network_bangumi_mirror
                    BangumiEndpointMode.CUSTOM -> Lang.settings_network_bangumi_custom
                },
            )
        },
        read = { it[keys.bangumi]?.mode },
        // 登录着改用第三方镜像要先问 (允许账号数据经过镜像 / 退出登录再切 / 不切), 同原来的设置页
        write = { mode -> listOf(TvSettingsEdit.Run { env -> switchBangumiMode(env, mode) }) },
        dynamicDescription = { v ->
            val settings = v[keys.bangumi]
            val mirrors = v[keys.bangumiMirrors].orEmpty()
            val parts = buildList {
                add(tvText(Lang.settings_network_bangumi_endpoint_description))
                if (settings?.mode == BangumiEndpointMode.AUTO) add(tvText(Lang.settings_network_bangumi_auto_description))
                if (mirrors.isNotEmpty() && (settings?.mode == BangumiEndpointMode.AUTO || settings?.mode == BangumiEndpointMode.MIRROR)) {
                    add(tvText(Lang.settings_network_bangumi_builtin_mirrors, mirrors.joinToString("、")))
                }
                add(tvText(Lang.tv_settings_bangumi_custom_hint))
            }
            parts.reduce { acc, text -> tvText(Lang.tv_settings_paragraphs, acc, text) }
        },
        qr = { consoleQr(it, "#settings/network") },
    )
    // 只有第三方镜像那两档要问: 自建的是可信的, 登录照样能用
    toggleOf(
        listOf(keys.bangumi, keys.bangumiLoggedIn), Lang.settings_network_bangumi_mirror_credentials,
        description = { v ->
            tvText(
                if (v[keys.bangumi]?.allowCredentialsViaMirror == true) Lang.settings_network_bangumi_mirror_credentials_on
                else Lang.settings_network_bangumi_no_login_notice,
            )
        },
        visible = { v -> v[keys.bangumi]?.mode.let { it == BangumiEndpointMode.AUTO || it == BangumiEndpointMode.MIRROR } },
        // 打开要先了解风险; 关掉时已登录且用着镜像要先提醒后果
        confirm = { v, on ->
            val settings = v[keys.bangumi]
            when {
                on -> TvSettingsConfirm(
                    title = tvText(Lang.settings_network_bangumi_mirror_credentials_risk_title),
                    text = tvText(Lang.settings_network_bangumi_mirror_credentials_risk_message),
                    confirmLabel = tvText(Lang.settings_network_bangumi_mirror_credentials_risk_confirm),
                    destructive = true,
                )

                settings != null && BangumiMirrorConsent.check(
                    settings, settings.copy(allowCredentialsViaMirror = false), v[keys.bangumiLoggedIn] == true,
                ) != null -> TvSettingsConfirm(
                    title = tvText(Lang.settings_network_bangumi_mirror_credentials_off_title),
                    text = tvText(Lang.settings_network_bangumi_mirror_credentials_off_message),
                    confirmLabel = tvText(Lang.settings_network_bangumi_mirror_credentials_off_confirm),
                )

                else -> null
            }
        },
        read = { it[keys.bangumi]?.allowCredentialsViaMirror == true },
        write = { on -> listOf(TvSettingsEdit.Update(keys.bangumi) { it.copy(allowCredentialsViaMirror = on) }) },
    )
}

private suspend fun switchBangumiMode(env: TvSettingsEnv, mode: BangumiEndpointMode) {
    val settings = env.repository.bangumiEndpointSettings
    val current = settings.flow.first()
    val target = current.copy(mode = mode)
    if (BangumiMirrorConsent.check(current, target, env.deps.settings.bangumiLoggedIn.value) == null) {
        settings.update { target }
        return
    }
    env.show(
        TvSettingsOverlay.Dialog { dismiss ->
            MirrorSwitchConsentDialog(
                automatic = false,
                onAllow = {
                    dismiss()
                    tvSettingsWriteScope.launch { settings.update { target.copy(allowCredentialsViaMirror = true) } }
                },
                onLogoutAndSwitch = {
                    dismiss()
                    tvSettingsWriteScope.launch {
                        env.deps.settings.logoutBangumi()
                        settings.update { target }
                    }
                },
                onDismissRequest = dismiss,
            )
        },
    )
}

private fun serviceName(id: String) = when (id) {
    ServiceConnectionTesters.ID_BANGUMI -> Lang.settings_network_proxy_service_collection
    ServiceConnectionTesters.ID_BANGUMI_NEXT -> Lang.settings_network_proxy_service_comment
    ServiceConnectionTesters.ID_TMDB -> Lang.settings_network_proxy_service_tmdb
    else -> Lang.settings_network_proxy_service_tmdb_image
}

/** TMDB 图片地址的一档 (同原来设置页的 EndpointSelectionItems). */
private sealed interface TvEndpointOption {
    data object Auto : TvEndpointOption
    data class Fixed(val baseUrl: String) : TvEndpointOption
    data object Custom : TvEndpointOption
}

/** 自动 + 清单里的地址 (+ 选着却已不在清单里的那个) + 填过地址时的自定义. */
private fun endpointOptions(selection: EndpointSelection?, candidates: List<String>): List<TvEndpointOption> = buildList {
    add(TvEndpointOption.Auto)
    candidates.forEach { add(TvEndpointOption.Fixed(it)) }
    if (selection != null && selection.mode == EndpointSelectionMode.FIXED && selection.fixedBaseUrl.isNotEmpty() &&
        selection.fixedBaseUrl !in candidates
    ) {
        add(TvEndpointOption.Fixed(selection.fixedBaseUrl))
    }
    if (selection != null && (selection.customBaseUrl.isNotBlank() || selection.mode == EndpointSelectionMode.CUSTOM)) {
        add(TvEndpointOption.Custom)
    }
}

private fun EndpointSelection.option(): TvEndpointOption = when (mode) {
    EndpointSelectionMode.AUTO -> TvEndpointOption.Auto
    EndpointSelectionMode.FIXED -> TvEndpointOption.Fixed(fixedBaseUrl)
    EndpointSelectionMode.CUSTOM -> TvEndpointOption.Custom
}

private fun TvEndpointOption.applyTo(selection: EndpointSelection): EndpointSelection = when (this) {
    TvEndpointOption.Auto -> selection.copy(mode = EndpointSelectionMode.AUTO)
    is TvEndpointOption.Fixed -> selection.copy(mode = EndpointSelectionMode.FIXED, fixedBaseUrl = baseUrl)
    TvEndpointOption.Custom -> selection.copy(mode = EndpointSelectionMode.CUSTOM)
}

private fun TvEndpointOption.label(): TvText = when (this) {
    TvEndpointOption.Auto -> tvText(Lang.settings_network_endpoint_auto)
    is TvEndpointOption.Fixed -> tvText(EndpointUrls.displayName(baseUrl))
    TvEndpointOption.Custom -> tvText(Lang.settings_network_endpoint_custom)
}

// ---- BT ----

/**
 * BT: 上下行限速与分享率 (档位), Peer 过滤的开关 (规则文本在手机控制台里), PikPak (账号密码在手机控制台里).
 * 「计费网络限制上传」不列: 电视接网线或 Wi-Fi, 系统不会报按流量计费.
 */
internal fun TvSettingsItemsBuilder.btItems() {
    val keys = TvNetworkSettingKeys
    val torrent = keys.torrent
    choice(
        torrent, Lang.settings_media_torrent_download_rate_limit,
        options = { rateOptions(it.downloadRateLimit) },
        label = ::rateLabel,
        read = { it.downloadRateLimit },
        write = { copy(downloadRateLimit = it) },
    )
    header(Lang.settings_media_torrent_sharing_settings)
    choice(
        torrent, Lang.settings_media_torrent_upload_rate_limit, Lang.settings_media_torrent_sharing_description,
        options = { rateOptions(it.uploadRateLimit) },
        label = ::rateLabel,
        read = { it.uploadRateLimit },
        write = { copy(uploadRateLimit = it) },
    )
    choice(
        torrent, Lang.settings_media_torrent_share_ratio_limit, Lang.settings_media_torrent_share_ratio_description,
        options = { config ->
            val tiers = (1..9).map { it.toFloat() } + AnitorrentConfig.SHARE_RATIO_LIMIT_INFINITE
            if (config.shareRatioLimit in tiers) tiers else (tiers + config.shareRatioLimit).sorted()
        },
        label = {
            if (it >= AnitorrentConfig.SHARE_RATIO_LIMIT_INFINITE) tvText(Lang.settings_media_torrent_unlimited) else tvText(String.format1f(it))
        },
        read = { it.shareRatioLimit },
        write = { copy(shareRatioLimit = it) },
    )

    header(Lang.settings_media_torrent_peer_filter)
    val subscriptions = keys.peerSubscriptions
    toggleOf(
        listOf(subscriptions), Lang.tv_settings_peer_builtin_rules,
        description = { tvText(Lang.settings_media_torrent_peer_filter_description) },
        visible = { it[subscriptions].builtIn() != null },
        read = { it[subscriptions].builtIn()?.enabled == true },
        write = { on ->
            listOf(
                TvSettingsEdit.Run {
                    val repository = GlobalKoin.get<PeerFilterSubscriptionRepository>()
                    val builtIn = repository.presentationFlow.first().builtIn() ?: return@Run
                    if (on) repository.enable(builtIn.subscriptionId) else repository.disable(builtIn.subscriptionId)
                },
            )
        },
    )
    val peer = keys.peer
    toggle(
        peer, Lang.tv_settings_peer_filter_ip, Lang.tv_settings_peer_rules_hint,
        qr = { consoleQr(it, "#settings/resources") },
        read = { it.enableIpFilter },
        write = { copy(enableIpFilter = it) },
    )
    toggle(
        peer, Lang.tv_settings_peer_filter_id, Lang.tv_settings_peer_rules_hint,
        qr = { consoleQr(it, "#settings/resources") },
        read = { it.enableIdFilter },
        write = { copy(enableIdFilter = it) },
    )
    toggle(
        peer, Lang.tv_settings_peer_block_invalid_id, Lang.tv_settings_peer_block_invalid_id_description,
        visible = { it.enableIdFilter },
        read = { it.blockInvalidId },
        write = { copy(blockInvalidId = it) },
    )
    toggle(
        peer, Lang.tv_settings_peer_filter_client, Lang.tv_settings_peer_rules_hint,
        qr = { consoleQr(it, "#settings/resources") },
        read = { it.enableClientFilter },
        write = { copy(enableClientFilter = it) },
    )

    header(Lang.tv_settings_pikpak)
    val pikpak = keys.pikpak
    toggle(
        pikpak, Lang.settings_pikpak_enabled, Lang.settings_pikpak_description,
        // 刚打开且选源还没偏好 BT 时, 问要不要一起改 (PikPak 只处理 BT 资源)
        after = { on ->
            TvSettingsEdit.Run { env ->
                if (!on) return@Run
                val selector = env.repository.mediaSelectorSettings
                if (selector.flow.first().preferKind == MediaSourceKind.BitTorrent) return@Run
                env.ask(
                    TvSettingsConfirm(
                        title = tvText(Lang.settings_pikpak_recommend_title),
                        text = tvText(Lang.settings_pikpak_recommend_message),
                        confirmLabel = tvText(Lang.settings_pikpak_recommend_apply),
                    ),
                ) { selector.update { copy(preferKind = MediaSourceKind.BitTorrent) } }
            }
        },
        read = { it.enabled },
        write = { copy(enabled = it) },
    )
    action(
        Lang.tv_settings_pikpak_account, Lang.tv_settings_pikpak_account_hint, keys = listOf(pikpak),
        value = { v -> v[pikpak]?.username?.takeIf { it.isNotEmpty() }?.let { tvText(it) } ?: tvText(Lang.tv_settings_pikpak_not_signed_in) },
        visible = { it[pikpak]?.enabled == true },
        qr = { consoleQr(it, "#settings/resources") },
    ) { emptyList() }
    val usage = keys.pikpakUsage
    action(
        Lang.settings_pikpak_drive_usage_title, keys = listOf(pikpak, usage),
        value = { v ->
            when (val presentation = v[usage]) {
                PikPakDriveUsagePresentation.Idle, null -> tvText(Lang.settings_pikpak_drive_usage_idle)
                PikPakDriveUsagePresentation.SignedOut -> tvText(Lang.settings_pikpak_drive_usage_signed_out)
                is PikPakDriveUsagePresentation.Failed -> tvText(Lang.settings_pikpak_drive_usage_failed, presentation.message)
                is PikPakDriveUsagePresentation.Loaded -> tvText(Lang.settings_pikpak_drive_usage_value, presentation.free.toString())
            }
        },
        visible = { it[pikpak]?.enabled == true },
    ) { listOf(TvSettingsEdit.Run { env -> env.deps.settings.pikpakDriveUsageState.check() }) }
}

private fun List<PeerFilterSubscription>?.builtIn(): PeerFilterSubscription? =
    this?.firstOrNull { it.subscriptionId == PeerFilterSubscription.BUILTIN_SUBSCRIPTION_ID }

/** 限速档位: 1~9 MB/s 与不限 (原来是 1~10 的滑块, 10 = 不限); 存着的值不在档位上时也列出来, 不改它就一直是它. */
private fun rateOptions(current: FileSize): List<FileSize> {
    val tiers = (1..9).map { it.megaBytes } + Unspecified
    return if (current in tiers) tiers else (tiers.dropLast(1) + current).sortedBy { it.inBytes } + Unspecified
}

private fun rateLabel(rate: FileSize): TvText =
    if (rate == Unspecified) tvText(Lang.settings_media_torrent_unlimited)
    else tvText(Lang.settings_media_torrent_speed_format, String.format1f(rate.inMegaBytes))

// ---- 存储 ----

/** 存储: 图片缓存 (占用与清理)、弹幕缓存策略、标记看过后删缓存、有缓存时直接播. */
internal fun TvSettingsItemsBuilder.storageItems() {
    val keys = TvNetworkSettingKeys
    val usage = keys.imageCacheUsage
    action(
        Lang.settings_storage_image_cache, keys = listOf(usage),
        value = { v -> v[usage]?.let { tvText("${it.used} / ${it.max}") } },
        details = { v -> tvText(Lang.settings_storage_image_cache_usage, v[usage]?.used?.toString() ?: "…") },
    ) {
        listOf(
            TvSettingsEdit.Run { env ->
                val sketch = env.deps.sketch
                withContext(Dispatchers.IO) {
                    sketch.downloadCache.clear()
                    sketch.resultCache.clear()
                    sketch.memoryCache.clear()
                }
                env.deps.imageCacheRefresh.update { it + 1 }
                env.toast(getString(Lang.settings_storage_image_cache_cleared))
            },
        )
    }
    val mediaCache = keys.mediaCache
    choice(
        mediaCache, Lang.settings_storage_danmaku_cache_strategy_title,
        options = { DanmakuCacheStrategy.entries },
        label = {
            tvText(
                when (it) {
                    DanmakuCacheStrategy.DON_NOT_CACHE -> Lang.tv_settings_danmaku_cache_none
                    DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY -> Lang.tv_settings_danmaku_cache_collection
                    DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE -> Lang.tv_settings_danmaku_cache_media
                },
            )
        },
        read = { it.danmakuCacheStrategy },
        write = { copy(danmakuCacheStrategy = it) },
        optionDescription = {
            tvText(
                when (it) {
                    DanmakuCacheStrategy.DON_NOT_CACHE -> Lang.settings_storage_danmaku_cache_strategy_description_do_not_cache
                    DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY ->
                        Lang.settings_storage_danmaku_cache_strategy_description_cache_on_collection_doing_media_play

                    DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE -> Lang.settings_storage_danmaku_cache_strategy_description_cache_on_media_cache
                },
            )
        },
    )
    toggle(
        mediaCache, Lang.settings_storage_delete_cache_when_done, Lang.settings_storage_delete_cache_when_done_description,
        read = { it.deleteWhenMarkedDone },
        write = { copy(deleteWhenMarkedDone = it) },
    )
    toggle(
        mediaCache, Lang.settings_storage_play_cache_without_searching, Lang.settings_storage_play_cache_without_searching_description,
        read = { it.playCacheWithoutSearching },
        write = { copy(playCacheWithoutSearching = it) },
    )
}
