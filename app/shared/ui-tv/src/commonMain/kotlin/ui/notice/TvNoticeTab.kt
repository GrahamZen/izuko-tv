/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.widgets.selectedSemantics
import org.jetbrains.compose.resources.StringResource

/**
 * 通知弹窗顶上「通知」之外的一个签 (如「我的反馈」), 在 [TvNotices.tabs] 里登记. 签里同样是左边列表、右栏说明与操作 (见 [TvNoticeColumns]):
 * 这里只给列哪几行、每行右栏写什么, 焦点与按键 (列表与右栏之间、上键到签、右栏翻页) 由通知弹窗管.
 */
internal interface TvNoticeTab {
    /** 认这个签的 id ([TvNotices.open] 的 tab). */
    val id: String

    /** 签上的字. */
    val label: StringResource

    /** 签上画红点: 签里有没读过的. 只管签上这个点, 不算进侧边栏「通知」的数字. */
    val hasFresh: Flow<Boolean>

    /** 签里列的几行, 排好先后 (分段写在 [TvNoticeColumnItem.section]); 还在取为 null. 在通知弹窗里组合. */
    @Composable
    fun rememberItems(): List<TvNoticeColumnItem>?

    /** 一行都没有时列表里写的字. */
    @Composable
    fun emptyText(): String

    /** 右栏换成了 [key] 这一行 (焦点落在它上面). */
    fun onShown(key: String) {}

    /** 这个签成了选中的那个 (左右键换过来、在签那一排走过来、打开时就在这个签), 每次一下: 如把只在这个签里提醒的通知 ([TvNoticeKind.tab]) 标看过. */
    fun onTabShown() {}

    /** 右栏正文一打开停在末尾 (最新的写在最下面); 一行可以自己另定 ([TvNoticeColumnItem.bodyStartsAtEnd]). */
    val bodyStartsAtEnd: Boolean get() = false

    /** 按了当场做完的操作 (Run) 焦点留在按钮上, 不回那一行 (来往记录这类签: 做完多半还要接着看). 操作都画成按钮, 列表里按确定键进右栏. */
    val buttonsForSingleAction: Boolean get() = false

    companion object {
        /** 「通知」签 (通知本身, 不在 [TvNotices.tabs] 里登记). */
        const val NOTICES: String = "notices"
    }
}

/**
 * 两栏里列的一行 ([TvNoticeTab] 给的; 「通知」签里是一条通知) 与焦点在它上面时右栏写什么.
 *
 * @param icon 列表行左边的图标, [iconLabel] 是它的说明
 * @param atMillis 列表行上写的时间; null = 不写 (如「写新反馈」这种不是一件事的行)
 * @param fresh 没读过: 图标右上角一个红点 (「通知」签里右栏说它说满一会儿算读过, 见 [TvNoticeColumns])
 * @param section 分段的小标题: 与上一行不同时写在这一行前面 (如「处理中」「已结束」); null = 不分段
 * @param chips 右栏大标题上面的签 (类别、状态)
 * @param meta 签后面那一行小字; null = 写 [atMillis] 的时间 (它也是 null 就不写)
 * @param subject 右栏大标题下面一行 (是哪一个)
 * @param actions 处理它的操作 (按钮的画法见 [TvNoticeColumns])
 * @param rowAction 在这一行上按确定键 (触屏上点这一行) 直接做的那一件事, 右栏不画按钮 (如「写新反馈」: 电视上右栏放码, 触屏上点了开写反馈的弹窗);
 *   给了它就不看 [actions] 画不画按钮
 * @param blocks 右栏正文 (只是数据, 见 [TvNoticeBlock]): 安卓上原生视图照它画, iPad 上 Compose 照它画; 竖着排, 放不下时自己滚
 * @param bodyStartsAtEnd 这一行的正文一打开停不停在末尾; null = 照签的 ([TvNoticeTab.bodyStartsAtEnd]). 如「我的反馈」里单子的来往记录停在末尾,
 *   「写新反馈」(码与几段说明) 从开头看
 */
@Immutable
internal class TvNoticeColumnItem(
    val key: String,
    val icon: ImageVector,
    val iconLabel: String,
    val title: String,
    val atMillis: Long?,
    val summary: String? = null,
    val fresh: Boolean = false,
    val section: String? = null,
    val chips: List<TvNoticeChip> = emptyList(),
    val meta: String? = null,
    val subject: String? = null,
    val actions: List<TvNoticeAction> = emptyList(),
    val rowAction: TvNoticeAction? = null,
    val blocks: List<TvNoticeBlock> = emptyList(),
    val bodyStartsAtEnd: Boolean? = null,
    /** 这一行左边画的封面 (说的是哪一部作品的通知, 见 [TvNoticeKind.cover]); null = 画类别图标 [icon] */
    val cover: TvNoticeCoverImage? = null,
)

/** 右栏大标题上面的一个签; [accent] 用主色 (如「处理中」), 否则是淡底 (如类别). */
@Immutable
internal data class TvNoticeChip(val text: String, val accent: Boolean = false)

/** 通知弹窗顶上的一个签: [id] 与签上的字、有没有红点. */
@Immutable
internal class TvNoticeTabLabel(val id: String, val text: String, val fresh: Boolean)

/**
 * 通知弹窗顶上的几个签 (只有「通知」一个时不画, 照旧写标题). 没选中的字小、淡, 有没看过的画红点; 选中的字加粗、白, 下面一条主色短横线.
 * 按键同电视上的原生版 (TvNoticeView): 签能聚焦, 聚焦即选中 ([onSelect]); 左右键在签之间走, 最右的签再按右键到 [rightOfLast] (刷新);
 * 下键、确定键进内容 ([onEnter]: 这个签的第一行); 上键不动. 选中的那个签挂 [selectedFocus] (列表第一行按上、刷新上按左落到它).
 * 触屏上点签换签.
 */
@Composable
internal fun TvNoticeTabRow(
    tabs: List<TvNoticeTabLabel>,
    selected: String,
    onSelect: (id: String) -> Unit,
    selectedFocus: FocusRequester,
    onEnter: () -> Unit,
    rightOfLast: FocusRequester?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(TV_NOTICE_TAB_GAP), verticalAlignment = Alignment.Bottom) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = tab.id == selected
            TvNoticeTabItem(
                tab,
                selected = isSelected,
                onClick = { onSelect(tab.id) },
                modifier = Modifier
                    .ifThen(isSelected) { focusRequester(selectedFocus) }
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == tabs.lastIndex) right = rightOfLast ?: FocusRequester.Cancel
                    }
                    .onFocusChanged { if (it.isFocused && !isSelected) onSelect(tab.id) }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyDown) onEnter()
                                true
                            }

                            else -> false
                        }
                    },
            )
        }
    }
}

/** 一个签: 字 (与红点) 与下面 28dp 的短横线; 选中的字大、加粗、白, 横线主色. */
@Composable
private fun TvNoticeTabItem(tab: TvNoticeTabLabel, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            // 遥控器、键盘上聚焦的签铺一层淡底 (触屏上点了不画)
            .background(if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = TV_NOTICE_TAB_FOCUS_ALPHA) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .selectedSemantics(selected)
            .testTag(TV_NOTICE_TAB_TAG)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Text(
                tab.text,
                style = if (selected) {
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (tab.fresh && !selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp)
                        .size(TV_NOTICE_TAB_DOT_SIZE)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .width(TV_NOTICE_TAB_UNDERLINE_WIDTH)
                .height(3.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 通知弹窗顶栏右边的「刷新」(状态与文案由 TvNoticeRefresh 给, 每秒更新).
 *
 * @param label 按钮上的字 (「刷新」/「正在刷新」)
 * @param status 按钮左边那一行小字 (上次刷新的时间、在刷哪几样、刷完的结果、还要等多久); null = 不写
 * @param statusHighlight 那行小字用主色 (刷完有新通知时)
 * @param busy 正在刷新: 图标转圈
 * @param cooling 刚刷过、还不能再刷: 按钮变灰 (照样能聚焦、能按, 按了提示还要等多久)
 */
@Immutable
internal class TvNoticeRefreshUi(
    val label: String,
    val status: String?,
    val statusHighlight: Boolean,
    val busy: Boolean,
    val cooling: Boolean,
)

/**
 * 顶栏右边的刷新按钮: 图标 + 字, 焦点样式同签 (不铺底, 图标与字一下变主色), 冷却中变灰 (冷却中聚焦是淡一点的主色), 正在刷新时图标转圈.
 * 键怎么走由 [modifier] 给.
 */
@Composable
internal fun TvNoticeRefreshButton(ui: TvNoticeRefreshUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focused && !LocalTvTouchInputEnabled.current
    val color = when {
        showFocus && ui.cooling -> MaterialTheme.colorScheme.primary.copy(alpha = TV_NOTICE_REFRESH_COOLING_ALPHA)
        showFocus -> MaterialTheme.colorScheme.primary
        ui.cooling -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TV_NOTICE_REFRESH_COOLING_ALPHA)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val rotation = if (ui.busy) {
        val transition = rememberInfiniteTransition()
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(TV_NOTICE_REFRESH_SPIN_MILLIS, easing = LinearEasing))).value
    } else {
        0f
    }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .testTag(TV_NOTICE_REFRESH_TAG)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = null,
            Modifier.size(TV_NOTICE_REFRESH_ICON_SIZE).graphicsLayer { rotationZ = rotation },
            tint = color,
        )
        Text(ui.label, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
    }
}

internal const val TV_NOTICE_TAB_TAG = "tv-notice-tab"
internal const val TV_NOTICE_REFRESH_TAG = "tv-notice-refresh"

internal val TV_NOTICE_TAB_DOT_SIZE = 8.dp

/** 选中、聚焦的签下面那条横线的长度. */
internal val TV_NOTICE_TAB_UNDERLINE_WIDTH = 28.dp
internal val TV_NOTICE_TAB_GAP = 12.dp

/** 聚焦的签那层淡底相对字色的不透明度. */
private const val TV_NOTICE_TAB_FOCUS_ALPHA = 0.12f

/** 刷新按钮: 图标边长、冷却中字与图标的不透明度、正在刷新时转一圈多久. */
internal val TV_NOTICE_REFRESH_ICON_SIZE = 22.dp
internal const val TV_NOTICE_REFRESH_COOLING_ALPHA = 0.55f
internal const val TV_NOTICE_REFRESH_SPIN_MILLIS = 1000
