/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isIos
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData

/**
 * 交给这台设备的播放器之前的最后一步. iOS 的 AVPlayer 不认解析器放在请求头里的提示 (并发分块、本机缓存, 见 [PlaybackRequestHints]),
 * 会把它们原样发给网盘, 这里去掉; 安卓的播放器自己读完再去掉.
 */
internal fun MediaData.forPlatformPlayer(): MediaData {
    if (currentPlatform().isIos() && this !is UriMediaData) ipadDiag("player data: ${this::class.simpleName}")
    if (this !is UriMediaData || !currentPlatform().isIos()) return this
    val stripped = PlaybackRequestHints.strip(headers)
    ipadDiag("player data: ${ipadDiagUrl(uri)} headers=${stripped.keys} stripped=${headers.keys - stripped.keys}")
    return if (stripped.size == headers.size) this else UriMediaData(uri, stripped, extraFiles)
}
