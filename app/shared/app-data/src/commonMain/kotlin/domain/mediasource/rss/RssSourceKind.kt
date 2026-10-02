/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull

/**
 * RSS 源算哪一类: 按标题拼视频直链的 ([RssSearchConfig.titleToLink]) 结果全是直链, 算在线源; 其余是种子, 算 BT.
 *
 * 选源面板按数据源的类别分栏: 在线源那一排只收在线源, BT 栏只收种子. 拼直链的源若还算 BT, 它的直链结果两边都进不去, 面板上看不到.
 */
internal fun rssSourceKindOf(config: MediaSourceConfig): MediaSourceKind =
    if (config.deserializeArgumentsOrNull(RssMediaSourceArguments.serializer())?.searchConfig?.titleToLink != null) {
        MediaSourceKind.WEB
    } else {
        MediaSourceKind.BitTorrent
    }
