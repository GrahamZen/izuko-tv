/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.selector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_empty_sources
import me.him188.ani.app.ui.lang.tv_source_full_search_paused
import me.him188.ani.app.ui.lang.tv_source_reason_episode_unknown
import me.him188.ani.app.ui.lang.tv_source_reason_fuzzy_title
import me.him188.ani.app.ui.lang.tv_source_resume_source
import me.him188.ani.app.ui.lang.tv_source_show_others
import me.him188.ani.app.ui.mediafetch.AutoMatchExtras
import me.him188.ani.app.ui.mediaselect.bt.BtRowExclusion
import me.him188.ani.app.ui.mediaselect.bt.rememberBtLabelStrings
import org.jetbrains.compose.resources.stringResource

/**
 * 源的线路都没完全对上 (见 ui/mediafetch 的 AutoMatchExtras): [fuzzyTitle] = 有条目名不完全一致的, [episodeUnknown] = 有集数没认出来的.
 */
@Immutable
data class WebSourceInexact(val fuzzyTitle: Boolean, val episodeUnknown: Boolean)

/**
 * 卡片上线路后面的小字 (同电视选源面板的原因与入口): 暂停了 (点了接着查)、哪里没对上、为什么被排除、其余几条 (点了进这个源的全部结果).
 */
@Composable
internal fun WebSourceNotes(source: WebSource, onResume: () -> Unit, onOpenSource: (() -> Unit)?) {
    if (source.isPausedWithoutResults) {
        Note(stringResource(Lang.tv_source_full_search_paused))
        Note(stringResource(Lang.tv_source_resume_source), color = MaterialTheme.colorScheme.primary, onClick = onResume)
    }
    source.inexact?.let { inexact ->
        val reasons = listOfNotNull(
            if (inexact.fuzzyTitle) stringResource(Lang.tv_source_reason_fuzzy_title) else null,
            if (inexact.episodeUnknown) stringResource(Lang.tv_source_reason_episode_unknown) else null,
        )
        if (reasons.isNotEmpty()) Note(reasons.joinToString("、"))
    }
    if (source.excludedReasons.isNotEmpty()) {
        val strings = rememberBtLabelStrings()
        Note(source.excludedReasons.joinToString("、") { strings.exclusion(BtRowExclusion.Reason(it)) }, color = MaterialTheme.colorScheme.error)
    }
    if (source.hiddenCount > 0 && onOpenSource != null) {
        Note(stringResource(Lang.tv_source_show_others, source.hiddenCount), color = MaterialTheme.colorScheme.primary, onClick = onOpenSource)
    }
}

/** 源列表最后一行: 查完了一条能列的都没有的源. */
@Composable
internal fun EmptyWebSourcesNote(sources: List<AutoMatchExtras.EmptySource>, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    Text(
        stringResource(Lang.tv_source_empty_sources, sources.size) + ": " + sources.joinToString("、") { it.name },
        modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: (() -> Unit)? = null) {
    Text(
        text,
        Modifier.minimumInteractiveComponentSize()
            .ifThen(onClick != null) { clickable { onClick?.invoke() } }
            .padding(vertical = 8.dp),
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}
