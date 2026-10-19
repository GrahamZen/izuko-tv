/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.ui.settings.danmaku.DanmakuRegexFilterGroup
import me.him188.ani.app.ui.settings.tabs.log.PlatformLoggingItems
import me.him188.ani.app.ui.settings.tabs.media.BackupSettings
import me.him188.ani.app.ui.settings.tabs.media.MediaSelectionGroup
import me.him188.ani.app.ui.settings.tabs.media.PikPakAcceleratorGroup
import me.him188.ani.app.ui.settings.tabs.media.TorrentEngineGroup
import me.him188.ani.app.ui.settings.tabs.network.BangumiEndpointGroup
import me.him188.ani.app.ui.settings.tabs.network.ConfigureProxyGroup
import me.him188.ani.app.ui.settings.tabs.network.TmdbImagesGroup

/**
 * 原来设置页里的几块内容, 给别的设置页整块嵌进去用 (平板模式的触屏设置页): 代理地址、自建镜像、字幕组、Peer 规则、PikPak 账号、
 * 弹幕屏蔽词、设置备份这些要打字的项在新样式的清单里没有, 嵌原来的这一块就能就地改. 每一块放在不自己滚动的容器里.
 */
enum class LegacySettingsSection {
    /** 「代理」那一类: 代理、TMDB 图片、Bangumi 连接方式. */
    NETWORK,

    /** 「BT」那一类: BT 引擎 (含额外 Tracker 与 Peer 过滤入口)、PikPak. */
    TORRENT,

    /** 「观看偏好」那一类 (含偏好与排除的字幕组). */
    MEDIA_SELECTION,

    /** 「播放器」那一类里的弹幕正则屏蔽规则 (增删改、导入导出). */
    DANMAKU_FILTER,

    /** 「设置备份」: 经剪贴板导出、导入. */
    BACKUP,

    /** 「日志」里这一端的日志项 (分享或复制日志等); 不含原来那一行反馈链接. */
    LOG,
}

/** 画 [section] 那一块, 接线同原来的设置页. */
@Composable
fun LegacySettingsSectionContent(
    vm: SettingsViewModel,
    section: LegacySettingsSection,
    modifier: Modifier = Modifier,
) {
    if (section == LegacySettingsSection.LOG) {
        Column(modifier.fillMaxWidth()) {
            PlatformLoggingItems(ListItemDefaults.colors(containerColor = Color.Transparent))
        }
        return
    }
    SettingsTab(modifier) {
        when (section) {
            LegacySettingsSection.NETWORK -> {
                ConfigureProxyGroup(
                    state = vm.configureProxyState,
                    onStartProxyTestLoop = { vm.startProxyTesterLoop() },
                )
                val tmdbImageHosts by vm.tmdbImageHosts.collectAsStateWithLifecycle()
                TmdbImagesGroup(vm.tmdbImagesDisabled, vm.tmdbImageEndpoint, tmdbImageHosts)
                val bangumiMirrors by vm.bangumiMirrors.collectAsStateWithLifecycle()
                val bangumiLoggedIn by vm.bangumiLoggedIn.collectAsStateWithLifecycle()
                BangumiEndpointGroup(
                    vm.bangumiEndpointSettings,
                    bangumiMirrors,
                    loggedIn = bangumiLoggedIn,
                    echAvailable = vm.bangumiEchAvailable,
                    onLogout = vm::logoutBangumi,
                )
            }

            LegacySettingsSection.TORRENT -> {
                TorrentEngineGroup(vm.torrentSettingsState)
                PikPakAcceleratorGroup(
                    vm.pikpakSettingsState,
                    vm.mediaSelectorSettingsState,
                    vm.pikpakDriveUsageState,
                    vm.pikpakLegacyNoticeState,
                )
            }

            LegacySettingsSection.MEDIA_SELECTION -> MediaSelectionGroup(vm.mediaSelectionGroupState)
            LegacySettingsSection.DANMAKU_FILTER -> DanmakuRegexFilterGroup(vm.danmakuRegexFilterState)
            LegacySettingsSection.BACKUP -> BackupSettings(vm.cacheDirectoryGroupState)
            LegacySettingsSection.LOG -> {}
        }
    }
}
