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
import kotlinx.serialization.SerializationException
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
 * 设置备份: 应用设置、弹幕屏蔽词与 Bangumi 登录凭据编成一段 JSON, 导入时整份覆盖.
 *
 * 格式与设置页「备份」那一组复制到剪贴板的内容相同 (同样的字段、可空性与序列化写法, 见 ui-settings 的 `SettingsViewModel`),
 * 两边导出的可以互相导入; Web 控制台把它存成文件 (见 ui-tv 的 `RemoteSettingsBackup`). 字段对得上由 ui-tv 的
 * `SettingsBackupCodecTest` 对照设置页那份检查.
 *
 * PikPak 账号不进备份: 字段照留 (写默认值, 格式不变), 导入时也不读, 免得把账号带到别的设备上.
 * 备份里有 Bangumi 登录凭据, 不能发给别人.
 */
class SettingsBackupCodec(
    private val settingsRepository: SettingsRepository,
    private val danmakuRegexFilterRepository: DanmakuRegexFilterRepository,
    private val tokenRepository: TokenRepository,
) {
    /** 当前设置编成的备份 (一行 JSON). */
    suspend fun export(): String {
        val backup = SettingsBackupContent(
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
            pikpakConfig = PikPakConfig.Default,
            tokenStore = tokenRepository.getTokenSaveSnapshot(),
        )
        return json.encodeToString(SettingsBackupContent.serializer(), backup)
    }

    /**
     * 用备份 [content] 覆盖当前设置. 先整份解析, 解析不了时什么都不写.
     *
     * @throws SerializationException [content] 不是设置备份
     * @throws IllegalArgumentException [content] 不是设置备份
     */
    suspend fun restore(content: String) {
        val backup = json.decodeFromString(SettingsBackupContent.serializer(), content)

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
        // pikpakConfig 不导入: 较早的备份里可能还带着真实账号
        backup.tokenStore?.let { tokenRepository.restoreFromTokenSave(it) }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
        }
    }
}

/** 备份文件的内容. 字段名、可空性与有无默认值都是格式的一部分, 与设置页那份保持一致. */
@Serializable
internal data class SettingsBackupContent(
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
    val tokenStore: TokenSave?,
)
