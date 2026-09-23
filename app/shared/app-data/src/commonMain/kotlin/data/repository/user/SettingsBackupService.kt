/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.user

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.danmaku.DanmakuConfigSerializer
import me.him188.ani.app.data.models.danmaku.DanmakuFilterConfig
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.models.preference.AnalyticsSettings
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.app.data.models.preference.DanmakuSettings
import me.him188.ani.app.data.models.preference.DebugSettings
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.preference.OneshotActionConfig
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.models.preference.PlayerKernelConfig
import me.him188.ani.app.data.models.preference.ProfileSettings
import me.him188.ani.app.data.models.preference.ProxySettings
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TorrentPeerConfig
import me.him188.ani.app.data.models.preference.UISettings
import me.him188.ani.app.data.models.preference.UpdateSettings
import me.him188.ani.app.data.models.preference.VideoResolverSettings
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.danmaku.ui.DanmakuConfig

/**
 * 设置备份的序列化与还原.
 *
 * 从设置页的 ViewModel 里抽出来是为了让**别的进程入口**也能用同一份逻辑 —— 改分发包名的迁移期,
 * 旧包要通过 ContentProvider 把备份交给新包 (见 `SettingsMigrationProvider`), 那里没有 ViewModel.
 *
 * 备份里**含登录凭据** ([tokenStore]): 导出到用户手上的那条路 (设置页/版本过期页) 由用户自己决定
 * 交给谁; 跨进程那条路只允许**同签名**的调用方读.
 */
class SettingsBackupService(
    private val settingsRepository: SettingsRepository,
    private val danmakuRegexFilterRepository: DanmakuRegexFilterRepository,
    private val tokenRepository: TokenRepository,
) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun export(): String {
        val backup = SettingsBackup(
            danmakuEnabled = settingsRepository.danmakuEnabled.flow.first(),
            danmakuConfig = settingsRepository.danmakuConfig.flow.first(),
            danmakuFilterConfig = settingsRepository.danmakuFilterConfig.flow.first(),
            danmakuRegexFilters = danmakuRegexFilterRepository.flow.first(),
            mediaSelectorSettings = settingsRepository.mediaSelectorSettings.flow.first(),
            defaultMediaPreference = settingsRepository.defaultMediaPreference.flow.first(),
            profileSettings = settingsRepository.profileSettings.flow.first(),
            proxySettings = settingsRepository.proxySettings.flow.first(),
            mediaCacheSettings = settingsRepository.mediaCacheSettings.flow.first(),
            danmakuSettings = settingsRepository.danmakuSettings.flow.first(),
            uiSettings = settingsRepository.uiSettings.flow.first(),
            themeSettings = settingsRepository.themeSettings.flow.first(),
            updateSettings = settingsRepository.updateSettings.flow.first(),
            videoScaffoldConfig = settingsRepository.videoScaffoldConfig.flow.first(),
            playerKernelConfig = settingsRepository.playerKernelConfig.flow.first(),
            videoResolverSettings = settingsRepository.videoResolverSettings.flow.first(),
            anitorrentConfig = settingsRepository.anitorrentConfig.flow.first(),
            torrentPeerConfig = settingsRepository.torrentPeerConfig.flow.first(),
            oneshotActionConfig = settingsRepository.oneshotActionConfig.flow.first(),
            analyticsSettings = settingsRepository.analyticsSettings.flow.first(),
            debugSettings = settingsRepository.debugSettings.flow.first(),
            // Account credentials must not leak into exported settings; keep
            // the field present so the backup schema stays stable, but write
            // a credential-free Default. restoreSettingsBackup intentionally
            // ignores it for the same reason.
            pikpakConfig = PikPakConfig.Default,
            tokenStore = tokenRepository.getTokenSaveSnapshot(),
        )

        return json.encodeToString(SettingsBackup.serializer(), backup)
    }

    @Suppress("DuplicatedCode")
    suspend fun restore(content: String): Boolean {
        val backup = json.decodeFromString(SettingsBackup.serializer(), content)

        backup.danmakuEnabled?.let { settingsRepository.danmakuEnabled.set(it) }
        backup.danmakuConfig?.let { settingsRepository.danmakuConfig.set(it) }
        backup.danmakuFilterConfig?.let { settingsRepository.danmakuFilterConfig.set(it) }
        backup.danmakuRegexFilters?.let { danmakuRegexFilterRepository.replaceAll(it) }
        backup.mediaSelectorSettings?.let { settingsRepository.mediaSelectorSettings.set(it) }
        backup.defaultMediaPreference?.let { settingsRepository.defaultMediaPreference.set(it) }
        backup.profileSettings?.let { settingsRepository.profileSettings.set(it) }
        backup.proxySettings?.let { settingsRepository.proxySettings.set(it) }
        backup.mediaCacheSettings?.let { settingsRepository.mediaCacheSettings.set(it) }
        backup.danmakuSettings?.let { settingsRepository.danmakuSettings.set(it) }
        backup.uiSettings?.let { settingsRepository.uiSettings.set(it) }
        backup.themeSettings?.let { settingsRepository.themeSettings.set(it) }
        backup.updateSettings?.let { settingsRepository.updateSettings.set(it) }
        backup.videoScaffoldConfig?.let { settingsRepository.videoScaffoldConfig.set(it) }
        backup.playerKernelConfig?.let { settingsRepository.playerKernelConfig.set(it) }
        backup.videoResolverSettings?.let { settingsRepository.videoResolverSettings.set(it) }
        backup.anitorrentConfig?.let { settingsRepository.anitorrentConfig.set(it) }
        backup.torrentPeerConfig?.let { settingsRepository.torrentPeerConfig.set(it) }
        backup.oneshotActionConfig?.let { settingsRepository.oneshotActionConfig.set(it) }
        backup.analyticsSettings?.let { settingsRepository.analyticsSettings.set(it) }
        backup.debugSettings?.let { settingsRepository.debugSettings.set(it) }
        // pikpakConfig deliberately not restored: see serializeSettingsBackup.
        // Older backups produced before that change may still carry real
        // credentials, and we don't want a restore to silently re-introduce
        // them on a different device.
        // 旧流程 (经 Ani 服务器) 写下的会话直连 Bangumi 时续不了期, 启动时本来也要作废 (见 TokenRepository.clearLegacySession):
        // 不接, 免得这次运行里一直带着失效的凭据
        backup.tokenStore?.takeIf { it.loginFlowVersion >= CURRENT_LOGIN_FLOW_VERSION }
            ?.let { tokenRepository.restoreFromTokenSave(it) }

        return true
    }
}


@Serializable
internal data class SettingsBackup(
    val danmakuEnabled: Boolean?,
    @Serializable(with = DanmakuConfigSerializer::class) val danmakuConfig: DanmakuConfig?,
    val danmakuFilterConfig: DanmakuFilterConfig?,
    val danmakuRegexFilters: List<DanmakuRegexFilter>? = null,
    val mediaSelectorSettings: MediaSelectorSettings?,
    val defaultMediaPreference: MediaPreference?,
    val profileSettings: ProfileSettings?,
    val proxySettings: ProxySettings?,
    val mediaCacheSettings: MediaCacheSettings?,
    val danmakuSettings: DanmakuSettings?,
    val uiSettings: UISettings?,
    val themeSettings: ThemeSettings?,
    val updateSettings: UpdateSettings?,
    val videoScaffoldConfig: VideoScaffoldConfig?,
    val playerKernelConfig: PlayerKernelConfig? = null,
    val videoResolverSettings: VideoResolverSettings?,
    val anitorrentConfig: AnitorrentConfig?,
    val torrentPeerConfig: TorrentPeerConfig?,
    val oneshotActionConfig: OneshotActionConfig?,
    val analyticsSettings: AnalyticsSettings?,
    val debugSettings: DebugSettings?,
    val pikpakConfig: PikPakConfig? = null,
    val tokenStore: TokenSave?
)

