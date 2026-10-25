/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.auto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_item_cache_not_ready
import me.him188.ani.app.ui.lang.subject_episode_cached
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.jetbrains.compose.resources.stringResource

/*
 * 自动匹配页顶上的「已缓存」一行: 这一集缓存过的资源 (BT 与在线源的缓存都算), 点了直接放. 自动匹配页其余只列在线源,
 * 缓存在上游只出现在「BT 资源」页里, 默认打开的这一页看不到正在放的缓存.
 */

/** 「已缓存」一行里的一项. [ready] = 下完了能放 (没下完的显示但不可点, 同 BT 页). */
@Immutable
class AutoMatchCachedItem(
    val media: Media,
    val label: String,
    val ready: Boolean,
)

/**
 * 从筛选结果里取这一集的缓存: 能放的与没下完的 ([MediaExclusionReason.CacheNotReady]); 别的集的缓存 (集号不符被排除的) 不列.
 * 名字 = 字幕组 · 分辨率, 都没有时用资源标题.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
fun autoMatchCachedItems(candidates: List<MaybeExcludedMedia>): List<AutoMatchCachedItem> = candidates.mapNotNull { item ->
    val media = item.original
    if (media.kind != MediaSourceKind.LocalCache) return@mapNotNull null
    val ready = when (item) {
        is MaybeExcludedMedia.Included -> true
        is MaybeExcludedMedia.Excluded -> if (item.exclusionReason == MediaExclusionReason.CacheNotReady) false else return@mapNotNull null
    }
    val label = listOf(media.properties.alliance, media.properties.resolution)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifEmpty { media.originalTitle }
    AutoMatchCachedItem(media, label, ready)
}

/** 「已缓存」一行: 没有缓存时什么都不画. [selected] = 正在放的那条. */
@Composable
fun AutoMatchCachedRow(
    items: List<AutoMatchCachedItem>,
    selected: Media?,
    onClickItem: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 诊断 (行首 `[CachedRow]`): 选源页里列出了这一集的哪些缓存
    val summary = items.joinToString { "${it.media.mediaId}${if (it.ready) "" else " (没下完)"}" }
    LaunchedEffect(summary) { cachedRowLogger.info { "[CachedRow] 这一集的缓存 ${items.size} 条: $summary" } }
    if (items.isEmpty()) return
    val notReady = stringResource(Lang.media_selector_item_cache_not_ready)
    Row(modifier.testTag(AutoMatchPageTestTags.CACHED_ROW), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // 同在线源那一行: 左边图标 + 名字, 右边每条一个 chip
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DownloadDone, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(Lang.subject_episode_cached), Modifier.padding(start = 8.dp))
        }
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy((-8).dp),
        ) {
            for (item in items) {
                InputChip(
                    selected = item.media.mediaId == selected?.mediaId,
                    onClick = { onClickItem(item.media) },
                    label = { Text(if (item.ready) item.label else "${item.label} · $notReady") },
                    enabled = item.ready,
                    modifier = Modifier.testTag(AutoMatchPageTestTags.cachedItem(item.media.mediaId)),
                )
            }
        }
    }
}

private val cachedRowLogger = logger("AutoMatchCachedRow")
