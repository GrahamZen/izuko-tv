/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusSelectableSurface
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.notification_empty
import me.him188.ani.app.ui.lang.notification_time_date
import me.him188.ani.app.ui.lang.notification_time_hours_ago
import me.him188.ani.app.ui.lang.notification_time_just_now
import me.him188.ani.app.ui.lang.notification_time_minutes_ago
import me.him188.ani.app.ui.lang.notification_time_month_day
import me.him188.ani.app.ui.lang.notification_time_yesterday
import me.him188.ani.app.ui.lang.notification_title
import me.him188.ani.app.ui.lang.tv_notice_footer
import me.him188.ani.app.ui.lang.tv_notice_qr_hint
import me.him188.ani.app.ui.lang.tv_notice_section_earlier
import me.him188.ani.app.ui.lang.tv_notice_section_new
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.TvRemoteControl
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 通知弹窗 (见 docs/contributing/code/notices.md): 一条一行 —— 新的前面一个点、一句话、时间、有去处的末尾「›」.
 * 一打开焦点在 [state] 指定的那条 (没有就第一条). 按确定交给 [onPick], 按返回 [onDismiss].
 * 一行在屏幕上露出一半以上时报 [onSeen] (每条一次): 显示到屏幕上的才算看过, 一页放不下的翻到才算.
 */
@Composable
internal fun TvNoticeDialog(
    items: List<TvShownNotice>,
    state: TvNoticeDialogState,
    onPick: (index: Int, item: TvShownNotice) -> Unit,
    onSeen: (key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 照打开那一刻排: 打开就算看过, 照此刻的「新不新」排的话, 列表一打开就重排一遍、行在焦点底下跳
    val items = remember(items, state) {
        items.sortedWith(
            compareBy<TvShownNotice> { it.key !in state.fresh }
                .thenBy { !it.entry.pending }
                .thenByDescending { it.notice.atMillis },
        )
    }
    val initialKey = remember(state) {
        items.firstOrNull { it.key == state.focusKey }?.key
            ?: items.getOrNull(state.focusIndex.coerceAtMost(items.lastIndex))?.key
            ?: items.firstOrNull()?.key
    }
    val now = remember(state) { Clock.System.now().toEpochMilliseconds() }
    // 已经报过看过的 (同一次打开里每条只报一次)
    val reported = remember(state) { HashSet<String>() }
    // 控制台地址: 有就在右边单独一栏画个小码 (弹窗加宽让出这一栏, 列表不变窄); 没连局域网时没有这一栏
    val url by TvRemoteControl.url.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    val consoleUrl = url
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.notification_title)) },
        heightFraction = null,
        maxWidth = if (consoleUrl != null) TV_NOTICE_DIALOG_MAX_WIDTH_WITH_QR else TV_NOTICE_DIALOG_MAX_WIDTH,
    ) {
        val scrollState = rememberScrollState()
        ProvideScrollFadeFocusMargin {
            // 左边列表占满高度、自己滚; 右边一栏的码固定在底部, 通知多的时候也一直看得到
            Row(
                Modifier
                    .tvOverlayWindowKeys(onDismiss)
                    .layout { measurable, constraints ->
                        val maxHeight = if (constraints.hasBoundedHeight) {
                            (constraints.maxHeight * TV_NOTICE_DIALOG_MAX_HEIGHT_FRACTION).roundToInt().coerceAtLeast(constraints.minHeight)
                        } else {
                            constraints.maxHeight
                        }
                        val placeable = measurable.measure(constraints.copy(maxHeight = maxHeight))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .aniVerticalScrollFade(scrollState)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (items.isEmpty()) {
                        Text(
                            stringResource(Lang.notification_empty),
                            Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 新的与看过的都有时分两段, 各一行小标题 (同常见的「新 / 更早」); 只有一种时不写
                    val sectioned = items.any { it.key in state.fresh } && items.any { it.key !in state.fresh }
                    items.forEachIndexed { index, item ->
                        if (sectioned && (index == 0 || (item.key !in state.fresh && items[index - 1].key in state.fresh))) {
                            TvNoticeSectionHeader(
                                stringResource(if (item.key in state.fresh) Lang.tv_notice_section_new else Lang.tv_notice_section_earlier),
                                first = index == 0,
                            )
                        }
                        key(item.key) {
                            TvNoticeRow(
                                item,
                                fresh = item.key in state.fresh,
                                now = now,
                                onClick = { onPick(index, item) },
                                modifier = (if (item.key == initialKey) Modifier.tvWindowInitialFocus() else Modifier)
                                    .onGloballyPositioned { coordinates ->
                                        // 滚动区之外被裁掉的部分不算: 窗口里的边界已按各层父节点裁过
                                        if (item.key in reported || coordinates.size.height <= 0) return@onGloballyPositioned
                                        if (coordinates.boundsInWindow().height >= coordinates.size.height * TV_NOTICE_SEEN_FRACTION) {
                                            reported += item.key
                                            onSeen(item.key)
                                        }
                                    },
                            )
                        }
                    }
                    if (items.isNotEmpty()) {
                        Text(
                            stringResource(Lang.tv_notice_footer),
                            Modifier.padding(top = 8.dp, start = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (consoleUrl != null) TvNoticeQr(consoleUrl, Modifier.align(Alignment.Bottom))
            }
        }
    }
}

/**
 * 右边一栏的小码: 手机扫了在控制台里打开通知列表 (地址带 `#notices`, 见 RemoteNotices 的 NOTICE_SCRIPT).
 * 有新通知时进应用这一次不弹控制台二维码, 要连手机就扫这个.
 */
@Composable
private fun TvNoticeQr(consoleUrl: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RemoteQrCode(consoleUrl + TV_NOTICE_CONSOLE_FRAGMENT, TV_NOTICE_QR_SIZE, TV_NOTICE_QR_QUIET_ZONE)
        Text(
            stringResource(Lang.tv_notice_qr_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 一段的小标题: 「新通知」「之前的」. 不能聚焦. */
@Composable
private fun TvNoticeSectionHeader(text: String, first: Boolean) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = if (first) 0.dp else 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun TvNoticeRow(
    item: TvShownNotice,
    fresh: Boolean,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = false,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().testTag(TV_NOTICE_ROW_TAG),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(TV_NOTICE_DOT_SIZE)) {
                if (fresh) Box(Modifier.size(TV_NOTICE_DOT_SIZE).background(MaterialTheme.colorScheme.error, CircleShape))
            }
            Text(
                item.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                noticeTimeText(item.notice.atMillis, now),
                style = MaterialTheme.typography.labelMedium,
                // 跟着行的内容色压淡: 聚焦时行铺主色, 固定的灰字在上面看不清
                color = LocalContentColor.current.copy(alpha = TV_NOTICE_TIME_ALPHA),
                maxLines = 1,
            )
            if (item.destination != null) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

/** 今天的写「刚刚 / N 分钟前 / N 小时前」, 昨天写「昨天」, 今年的写月日, 更早的写年月日. */
@Composable
private fun noticeTimeText(atMillis: Long, nowMillis: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val diff = (nowMillis - atMillis).coerceAtLeast(0)
    return when {
        diff < MINUTE -> stringResource(Lang.notification_time_just_now)
        diff < HOUR -> stringResource(Lang.notification_time_minutes_ago, (diff / MINUTE).toString())
        at.date == today -> stringResource(Lang.notification_time_hours_ago, (diff / HOUR).toString())
        at.date == today.minus(1, DateTimeUnit.DAY) -> stringResource(Lang.notification_time_yesterday)
        else -> dateText(at.date, sameYear = at.date.year == today.year)
    }
}

@Composable
private fun dateText(date: LocalDate, sameYear: Boolean): String {
    val y = date.year.toString()
    val m = date.month.number.toString()
    val d = date.day.toString()
    return if (sameYear) stringResource(Lang.notification_time_month_day, y, m, d) else stringResource(Lang.notification_time_date, y, m, d)
}

private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L

internal const val TV_NOTICE_ROW_TAG = "tv-notice-row"

private val TV_NOTICE_DOT_SIZE = 8.dp

/** 控制台地址后面加上它: 网页一打开就是通知列表 (见 RemoteNotices 的 NOTICE_SCRIPT). */
internal const val TV_NOTICE_CONSOLE_FRAGMENT = "#notices"

/** 底下那个码的边长 (比启动时的控制台二维码小, 同动作面板右上角的码卡差不多). */
private val TV_NOTICE_QR_SIZE = 120.dp
private val TV_NOTICE_QR_QUIET_ZONE = 12.dp

/** 一行露出多少算显示到屏幕上了 (算看过). */
private const val TV_NOTICE_SEEN_FRACTION = 0.5f

/** 时间那几个字相对行的内容色的不透明度. */
private const val TV_NOTICE_TIME_ALPHA = 0.7f
private val TV_NOTICE_DIALOG_MAX_WIDTH = 720.dp

/** 有右边那一栏码时的宽度: 多出来的给码, 列表照旧宽. */
private val TV_NOTICE_DIALOG_MAX_WIDTH_WITH_QR = 880.dp

/** 弹窗内容最高占可用高度的多少 (其余留成上下边距). */
private const val TV_NOTICE_DIALOG_MAX_HEIGHT_FRACTION = 0.85f
