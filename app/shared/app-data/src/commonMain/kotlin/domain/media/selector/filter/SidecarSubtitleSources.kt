/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MediaSelectorSubtitlePreferences
import me.him188.ani.app.domain.media.selector.SubtitleKindPreference
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.SubtitleKind
import me.him188.ani.datasources.api.source.MediaSourceKind

/**
 * 选源时一条资源的字幕种类按什么处理: 平台偏好 ([MediaSelectorSubtitlePreferences]) 之上补上应用自己找得到的外挂字幕.
 *
 * 平台偏好把标题写着「外挂」的资源 ([SubtitleKind.EXTERNAL_DISCOVER], 字幕是视频旁边的单独文件) 藏掉, 因为播放器不会自己去找.
 * BT 播放时会在种子里找视频旁边的字幕一起下载并挂上 (`TorrentSidecarSubtitles`), 这类不藏, 只排到后面:
 * 字幕可能打在压缩包里或另外发布, 种子里找不到时放出来没有字幕.
 * 网盘资源建的时候已经标成数据源提供的外挂字幕 (`DriveSidecarSubtitles.subtitleKindOf`), 不经过这里; 在线直链只有一个视频地址, 照旧藏.
 */
internal object SidecarSubtitleSources {
    fun preferenceOf(
        media: Media,
        kind: SubtitleKind,
        preferences: MediaSelectorSubtitlePreferences,
    ): SubtitleKindPreference {
        val preference = preferences[kind]
        if (preference == SubtitleKindPreference.HIDE
            && kind == SubtitleKind.EXTERNAL_DISCOVER
            && media.kind == MediaSourceKind.BitTorrent
        ) {
            return SubtitleKindPreference.LOW_PRIORITY
        }
        return preference
    }
}
