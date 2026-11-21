/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.NSFW_OBSCURED_COVER_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.NsfwPolicy

/**
 * 通知说的是哪一部作品 ([TvNoticeKind.cover]): 列表行、横幅与手机控制台的列表左边画它的竖版封面 [url], 不画类别图标
 * (见 docs/contributing/code/notices.md 概念里的「封面」). 按 [subjectId] 照 NSFW 设置打码. [name] 是它的名字 (取不到为 null):
 * 横幅的第一行写它, 标题挪到第二行 (「某某」/「第 8 集已更新 · 按确定键查看」).
 */
@Immutable
data class TvNoticeCover(val subjectId: Int, val url: String, val name: String? = null)

/**
 * 交给界面画的封面: 地址, 要不要打码. 通知说的是用户自己收藏的作品: NSFW 设置为隐藏或模糊时都只打码、不换回图标
 * (同缓存管理、观看历史, 见 [NsfwPolicy.obscuresOwned]).
 */
@Immutable
internal data class TvNoticeCoverImage(val url: String, val obscure: Boolean)

internal fun TvNoticeCover.imageFor(nsfw: NsfwPolicy): TvNoticeCoverImage = TvNoticeCoverImage(url, nsfw.obscuresOwned(subjectId))

/**
 * 列表行里封面的框: 竖的 (2:3, 同竖版海报的比例; 比例不一样的封面缩进去、裁掉多出来的边); 行高照它留.
 * 横幅里那一块是方的, 海报裁成方形 (见 TvNoticeBanner).
 */
internal val TV_NOTICE_COVER_WIDTH = 32.dp
internal val TV_NOTICE_COVER_HEIGHT = 48.dp
internal val TV_NOTICE_COVER_CORNER = 6.dp

/**
 * 列表里有带封面的行时, 左边那一栏离行的左边、离文字各这么远: 比只画图标时收一点 (那时是 14dp 与 12dp), 封面比图标宽出来的
 * 就不把文字往右挤 —— 文字的起点同只画图标时差不多, 没封面的行图标也还在原来的位置.
 */
internal val TV_NOTICE_COVER_START = 10.dp
internal val TV_NOTICE_COVER_GAP = 10.dp

/**
 * 这个列表 ([items]) 里有带封面的行: 没封面的行左边也留封面那么宽的一栏、行也一样高 (字的起点与行高对齐).
 * 一行带封面的都没有时照旧是图标那么宽.
 */
internal fun tvNoticeCoverColumn(items: List<TvNoticeColumnItem>): Boolean = items.any { it.cover != null }

/**
 * Compose 画的封面 (触屏的列表行、横幅; 原生列表行里的见 TvNoticeRowCover): 中心裁剪进 [modifier] 给的框, 照 [shape] 裁角; 还没取到、取不到时画 [fallback] (类别图标).
 */
@Composable
internal fun TvNoticeCoverBox(image: TvNoticeCoverImage, modifier: Modifier, shape: Shape, fallback: @Composable () -> Unit) {
    var shown by remember(image) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!shown) fallback()
        AsyncImage(
            image.url,
            contentDescription = null,
            Modifier.fillMaxSize().clip(shape),
            contentScale = ContentScale.Crop,
            onSuccess = { shown = true },
            onError = { shown = false },
            downsampleLongEdgePx = if (image.obscure) NSFW_OBSCURED_COVER_LONG_EDGE_PX else null,
        )
    }
}
