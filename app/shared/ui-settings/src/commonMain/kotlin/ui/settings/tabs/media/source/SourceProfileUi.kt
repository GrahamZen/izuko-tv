/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import me.him188.ani.app.domain.mediasource.profile.SourceDeepTest
import me.him188.ani.app.domain.mediasource.profile.SourceProfileSummary
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.app.domain.mediasource.profile.SuggestReason
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniPanelCloseButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.source_deep_test_error_failed
import me.him188.ani.app.ui.lang.source_deep_test_error_missing
import me.him188.ani.app.ui.lang.source_deep_test_error_needs_webview
import me.him188.ani.app.ui.lang.source_deep_test_error_not_web
import me.him188.ani.app.ui.lang.source_deep_test_error_probe_failed
import me.him188.ani.app.ui.lang.source_deep_test_error_resolve_failed
import me.him188.ani.app.ui.lang.source_deep_test_error_search_empty
import me.him188.ani.app.ui.lang.source_deep_test_error_search_failed
import me.him188.ani.app.ui.lang.source_deep_test_error_search_timeout
import me.him188.ani.app.ui.lang.source_deep_test_found
import me.him188.ani.app.ui.lang.source_deep_test_hint
import me.him188.ani.app.ui.lang.source_deep_test_probe
import me.him188.ani.app.ui.lang.source_deep_test_resolve
import me.him188.ani.app.ui.lang.source_deep_test_running
import me.him188.ani.app.ui.lang.source_deep_test_search
import me.him188.ani.app.ui.lang.source_deep_test_seconds
import me.him188.ani.app.ui.lang.source_deep_test_title
import me.him188.ani.app.ui.lang.source_profile
import me.him188.ani.app.ui.lang.source_profile_ads
import me.him188.ani.app.ui.lang.source_profile_direct
import me.him188.ani.app.ui.lang.source_profile_failures
import me.him188.ani.app.ui.lang.source_profile_no_ads
import me.him188.ani.app.ui.lang.source_profile_reason_ads
import me.him188.ani.app.ui.lang.source_profile_reason_below_1080p
import me.him188.ani.app.ui.lang.source_profile_reason_failures
import me.him188.ani.app.ui.lang.source_profile_reason_search_fails
import me.him188.ani.app.ui.lang.source_profile_search
import me.him188.ani.app.ui.lang.source_profile_search_failures
import me.him188.ani.app.ui.lang.source_profile_startup
import me.him188.ani.app.ui.lang.source_profile_suggest
import me.him188.ani.app.ui.lang.source_profile_title
import me.him188.ani.app.ui.lang.source_profile_webview
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/*
 * 设置的数据源列表里的数据源画像 (见 SourceProfiles) 与「深度测试」(见 SourceDeepTest), 同 Web 控制台数据源页的那一行与那颗按钮:
 * 触屏设备上在这里看, 不用扫码去控制台。
 */

/** 数据源名字下面那一行小字画像 (实际表现与建议层级); 没有记录时不画. 深度测试测完一次就重新读. */
@Composable
internal fun SourceProfileLine(mediaSourceId: String, modifier: Modifier = Modifier) {
    val jobs by SourceDeepTest.jobs.collectAsState()
    val summary = remember(mediaSourceId, jobs) { SourceProfiles.summary(mediaSourceId) } ?: return
    val text = profileText(summary)
    if (text.isEmpty()) return
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun profileText(s: SourceProfileSummary): String {
    val parts = buildList {
        videoText(s.width, s.height, s.codec, s.kbps)?.let(::add)
        s.webView?.let { add(stringResource(if (it) Lang.source_profile_webview else Lang.source_profile_direct)) }
        if (s.hlsPlays > 0) {
            add(if (s.adPlays > 0) stringResource(Lang.source_profile_ads, s.adPlays) else stringResource(Lang.source_profile_no_ads))
        }
        s.startupMillis?.let { add(stringResource(Lang.source_profile_startup, seconds(it))) }
        if (s.failures > 0) add(stringResource(Lang.source_profile_failures, s.failures))
        s.searchMillis?.let { add(stringResource(Lang.source_profile_search, seconds(it))) }
        if (s.searchFailures > 0) add(stringResource(Lang.source_profile_search_failures, s.searchFailures, s.searches))
    }
    val suggest = s.suggestedTier?.let { tier ->
        val why = s.reasons.map { reasonText(it) }.joinToString("、")
        stringResource(Lang.source_profile_suggest, tier) + if (why.isNotEmpty()) "（$why）" else ""
    }
    val n = s.plays + s.tests + s.failures
    val title = if (n > 0) stringResource(Lang.source_profile_title, n) else stringResource(Lang.source_profile)
    return (listOf(title) + parts + listOfNotNull(suggest)).joinToString(" · ")
}

@Composable
private fun reasonText(reason: SuggestReason): String = stringResource(
    when (reason) {
        SuggestReason.ADS -> Lang.source_profile_reason_ads
        SuggestReason.WEB_VIEW -> Lang.source_profile_webview
        SuggestReason.BELOW_1080P -> Lang.source_profile_reason_below_1080p
        SuggestReason.FAILURES -> Lang.source_profile_reason_failures
        SuggestReason.SEARCH_FAILS -> Lang.source_profile_reason_search_fails
    },
)

/**
 * 「深度测试」的进度: 打开时开始测 (这个源正在测时接着看), 三步 (搜索 → 解析视频地址 → 读视频信息) 一行一步, 测到哪写到哪。
 * 关掉也会在后台测完, 结果记进画像 (列表那一行跟着更新)。
 */
@Composable
internal fun SourceDeepTestDialog(item: MediaSourcePresentation, onDismissRequest: () -> Unit) {
    LaunchedEffect(item.instanceId) { SourceDeepTest.start(item.instanceId) }
    val jobs by SourceDeepTest.jobs.collectAsState()
    val p = jobs[item.instanceId] ?: SourceDeepTest.Progress()
    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Lang.source_deep_test_title, item.info.displayName), Modifier.weight(1f))
                AniPanelCloseButton(onDismissRequest, Modifier.padding(start = 12.dp))
            }
        },
        heightFraction = null,
        maxWidth = 560.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Lang.source_deep_test_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val searchMs = p.searchMs
            val count = p.count
            StepRow(
                stringResource(Lang.source_deep_test_search),
                when {
                    searchMs == null -> stringResource(Lang.source_deep_test_running)
                    count != null -> stringResource(Lang.source_deep_test_found, count, seconds(searchMs))
                    else -> stringResource(Lang.source_deep_test_seconds, seconds(searchMs))
                },
            )
            if (p.stage != SourceDeepTest.Stage.SEARCH || (p.done && p.resolveMs != null)) {
                HorizontalDivider()
                StepRow(
                    stringResource(Lang.source_deep_test_resolve),
                    p.resolveMs?.let { ms ->
                        stringResource(Lang.source_deep_test_seconds, seconds(ms)) +
                            (p.webView?.let { " · " + stringResource(if (it) Lang.source_profile_webview else Lang.source_profile_direct) } ?: "")
                    } ?: stringResource(Lang.source_deep_test_running),
                )
            }
            if (p.stage == SourceDeepTest.Stage.PROBE) {
                HorizontalDivider()
                val probe = p.probe
                StepRow(
                    stringResource(Lang.source_deep_test_probe),
                    when {
                        probe != null -> probeText(probe)
                        p.done -> "—"
                        else -> stringResource(Lang.source_deep_test_running)
                    },
                )
            }
            p.error?.let { error ->
                Text(
                    errorText(error) + (p.detail?.let { "：$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error == SourceDeepTest.Error.NEEDS_WEBVIEW || error == SourceDeepTest.Error.NOT_WEB) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun StepRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun errorText(error: SourceDeepTest.Error): String = stringResource(
    when (error) {
        SourceDeepTest.Error.MISSING -> Lang.source_deep_test_error_missing
        SourceDeepTest.Error.SEARCH_FAILED -> Lang.source_deep_test_error_search_failed
        SourceDeepTest.Error.SEARCH_TIMEOUT -> Lang.source_deep_test_error_search_timeout
        SourceDeepTest.Error.SEARCH_EMPTY -> Lang.source_deep_test_error_search_empty
        SourceDeepTest.Error.NOT_WEB -> Lang.source_deep_test_error_not_web
        SourceDeepTest.Error.NEEDS_WEBVIEW -> Lang.source_deep_test_error_needs_webview
        SourceDeepTest.Error.RESOLVE_FAILED -> Lang.source_deep_test_error_resolve_failed
        SourceDeepTest.Error.PROBE_FAILED -> Lang.source_deep_test_error_probe_failed
        SourceDeepTest.Error.FAILED -> Lang.source_deep_test_error_failed
    },
)

private fun probeText(p: MediaStreamProbe.Result): String =
    listOfNotNull(videoText(p.width, p.height, p.codec, p.kbps), p.container).joinToString(" · ")

/** `1080p HEVC · 4.2 Mbps` (同控制台): 宽高认分辨率档, 码率过 1000 kbps 写 Mbps. */
private fun videoText(width: Int?, height: Int?, codec: String?, kbps: Int?): String? {
    val w = width ?: 0
    val h = height ?: 0
    val res = when {
        w >= 3800 || h >= 2000 -> "4K"
        w >= 1800 || h >= 1000 -> "1080p"
        w >= 1200 || h >= 700 -> "720p"
        h > 0 -> "${h}p"
        else -> null
    }
    val video = listOfNotNull(res, codec).joinToString(" ").ifEmpty { null }
    val rate = kbps?.let { if (it >= 1000) "${(it / 100.0).roundToInt() / 10.0} Mbps" else "$it kbps" }
    return listOfNotNull(video, rate).joinToString(" · ").ifEmpty { null }
}

/** 毫秒写成一位小数的秒. */
private fun seconds(millis: Long): String = ((millis / 100.0).roundToInt() / 10.0).toString()
