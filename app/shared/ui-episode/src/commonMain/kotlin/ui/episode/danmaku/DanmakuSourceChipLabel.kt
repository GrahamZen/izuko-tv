/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.episode.danmaku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.isBuiltIn

/** 名字最多占这么宽, 约 6 个汉字; 完整名字在长按菜单里. */
private val SERVER_NAME_MAX_WIDTH = 90.dp

/**
 * 弹幕源胶囊上的文字. 内置来源有弹幕时只写条数, 靠图标上的字认; 用户添加的弹幕服务器名字不固定, 首字可能撞车, 名字一直显示.
 */
@Composable
internal fun DanmakuSourceChipLabel(serviceId: DanmakuServiceId, count: Int) {
    when {
        !serviceId.isBuiltIn -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                renderDanmakuServiceId(serviceId),
                Modifier
                    .widthIn(max = SERVER_NAME_MAX_WIDTH)
                    .testTag("danmaku-source-name-${serviceId.value}"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != 0) Text("$count")
        }

        count == 0 -> Text(renderDanmakuServiceId(serviceId))
        else -> Text("$count")
    }
}
