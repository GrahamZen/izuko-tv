/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.summary

import me.him188.ani.app.domain.media.ExternalLinkMedia
import me.him188.ani.app.domain.together.TOGETHER_LINK_ORIGIN_ID
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_source_external_link
import me.him188.ani.app.ui.lang.tv_together_shared_source
import me.him188.ani.datasources.api.Media

/**
 * 别处交来的播放地址 ([ExternalLinkMedia]) 在界面上显示成什么数据源名, 按是谁交来的给; 不是交来的地址时为 null。
 * 新加一种来源时在这里补它的名字, 没补的显示成「外部链接」。
 */
suspend fun externalLinkSourceName(media: Media): String? = when (ExternalLinkMedia.originIdOf(media)) {
    null -> null
    TOGETHER_LINK_ORIGIN_ID -> getStringOffMain(Lang.tv_together_shared_source)
    else -> getStringOffMain(Lang.media_source_external_link)
}
