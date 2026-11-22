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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvContainDirectionalKeys
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_GRAY4
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_GRAY6_LIGHT
import kotlin.time.Duration.Companion.seconds

/**
 * 右上角的通知横幅 (样子照 tvOS 的系统提醒): 左边图标 [icon], 右边两行 —— [title] (第一条通知的标题) 与 [hint] (怎么按, 几条时带「还有 N 条」).
 * 横幅本身就是一颗按钮: 按确定 [onAction]; 按返回、10 秒没动作 [onDismiss]. 内容由 [TvNoticeHost] 定 ([TvNoticeBubbles.show]), 两种都由显示的一方撤掉.
 */
@Immutable
internal class TvNoticeBubble(
    val icon: ImageVector,
    val title: String,
    val hint: String,
    val onAction: () -> Unit,
    val onDismiss: () -> Unit,
)

/** 此刻要画的通知横幅 (见 [TvNoticeBubbleHost]). */
internal object TvNoticeBubbles {
    private val _current = MutableStateFlow<TvNoticeBubble?>(null)
    val current: StateFlow<TvNoticeBubble?> = _current.asStateFlow()

    /** 显示 [bubble] (替换正在显示的). */
    fun show(bubble: TvNoticeBubble) {
        _current.value = bubble
    }

    /** 撤掉 [bubble] (正在显示的已经换成别的就不动). */
    fun hide(bubble: TvNoticeBubble) {
        _current.compareAndSet(bubble, null)
    }
}

/** 横幅没动作多久自己淡出. */
internal val TV_NOTICE_BUBBLE_AUTO_DISMISS = 10.seconds

/** 画 [TvNoticeBubbles.current]: 首页内容区的右上角, 盖在页面上面 (见 docs/contributing/code/notices.md). 只在首页弹, 跟着首页走. */
@Composable
fun BoxScope.TvNoticeBubbleHost() {
    val bubble by TvNoticeBubbles.current.collectAsState()
    val shown = bubble != null
    // 横幅撤掉后的淡出动画里接着画最后那一个
    val lastBubble = remember { mutableStateOf<TvNoticeBubble?>(null) }
    bubble?.let { lastBubble.value = it }
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation

    // 遥控器上: 横幅出现时把焦点送到它身上. 动画尚未组合出横幅时请求会悬挂, 等锚点真正附着后由 Resolver 送达, 不再逐帧重发 requestFocus.
    val focus = rememberTvFocusScope()
    LaunchedEffect(focusDriven, bubble) {
        if (focusDriven && bubble != null) focus.request(TvNoticeBubbleFocus.Banner)
    }

    // 没动作自己淡出 (同按返回), 不一直挡着右上角. 窗口没有焦点时不计时: 启动时弹出的 Web 控制台二维码、
    // 更新的授权弹窗 (都是独立窗口) 正好盖在这里, 计时照走的话, 用户关掉它们时横幅往往已经没了 (2026-09-23 真机); 应用切到后台同理
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(bubble, windowFocused) {
        val current = bubble
        if (current != null && windowFocused) {
            delay(TV_NOTICE_BUBBLE_AUTO_DISMISS)
            current.onDismiss()
        }
    }

    // 返回键关掉横幅: 不接的话返回会穿到下层页面 (退出当前页) 而横幅还挡在右上角 —— 遥控器上"返回 = 关掉眼前这个东西"是最强的预期.
    //
    // **焦点导航形态下不看焦点在哪, 横幅在场就接**: 横幅出现后要等动画组合出锚点, 那中间仍有一段几十到几百毫秒的窗口.
    // 按焦点判的话, 落在这段窗口里的一次返回不归横幅管, 而是穿到页面 —— 主页上那正好是"连按两次退出"的第一下, 于是弹出
    // 「再按一次返回键退出」; 等横幅抢到了焦点, 第二下却被它吃掉去关横幅了, toast 就成了谎话 (2026-08-18 用户实测).
    // 触屏形态看焦点 (那里基本没有焦点, 等于横幅不碰返回键): 用户按返回多半是想退出当前页, 不是关这个横幅.
    //
    // 横幅出来时才组合这个返回处理 (不是一直挂着、只切开关): 返回处理按注册先后、后注册的先接, 切过标签页的话那一页自己的处理
    // 比一直挂着的更晚注册, 会抢在前面
    var bannerFocused by remember { mutableStateOf(false) }
    if (shown && (focusDriven || bannerFocused)) BackHandler { bubble?.onDismiss() }

    // 焦点导航设备: 横幅在场期间焦点锁在横幅上, 方向键不动. 不锁的话横幅抢到焦点后用户随手一按方向键焦点就滑进下层页面,
    // 而横幅还挡在右上角. 10 秒无操作自动淡出是这个模态的兜底时限; 届时焦点由 NavHost 的兜底监视 (见 AniAppContent 的
    // navHostModifier) 送回页面. 光挡 Compose 的焦点搜索不够: 搜索被取消后方向键没人消费, 会交给 Android 的 FocusFinder
    // 按屏幕位置在整个窗口里找, 横幅背后是原生页面 (探索 / 追番 / 搜索) 时就挑中它们的视图, 焦点照样跑出去. 所以方向键在这里吞掉
    // (tvContainDirectionalKeys).
    val trapFocus = focusDriven && shown

    AniAnimatedVisibility(
        visible = shown,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = TV_NOTICE_BANNER_TOP, end = TV_NOTICE_BANNER_END)
            .tvFocusNavSignal(focus)
            .onFocusChanged { bannerFocused = it.hasFocus }
            .ifThen(trapFocus) {
                focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
                    .tvContainDirectionalKeys()
            },
    ) {
        lastBubble.value?.let { TvNoticeBanner(it, Modifier.tvFocusAnchor(focus, TvNoticeBubbleFocus.Banner)) }
    }
}

private enum class TvNoticeBubbleFocus : TvFocusKey { Banner }

/**
 * 横幅本身: 小圆角条, 底色同首页没聚焦的按钮 (深色 Apple 的 Gray4、浅色那档浅灰; 不用主题色), 带一点阴影; 左边一个淡底方块里放图标,
 * 右边标题 (一行, 放不下截尾) 与淡一档的按键提示. 一直是这一个样子 (它在场时焦点总在它身上), 不画聚焦的样子.
 */
@Composable
private fun TvNoticeBanner(bubble: TvNoticeBubble, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background = (if (dark) TV_POSTER_WALL_GRAY4 else TV_POSTER_WALL_GRAY6_LIGHT).copy(alpha = TV_NOTICE_BANNER_ALPHA)
    val content = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (dark) TV_NOTICE_BANNER_SECONDARY_DARK else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(TV_NOTICE_BANNER_CORNER)
    Row(
        modifier
            .width(TV_NOTICE_BANNER_WIDTH)
            .heightIn(min = TV_NOTICE_BANNER_MIN_HEIGHT)
            .shadow(TV_NOTICE_BANNER_ELEVATION, shape)
            .background(background, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = bubble.onAction)
            .testTag(TV_NOTICE_BANNER_TAG)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(TV_NOTICE_BANNER_ICON_BOX).background(content.copy(alpha = TV_NOTICE_BANNER_ICON_BOX_ALPHA), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(bubble.icon, contentDescription = null, Modifier.size(24.dp), tint = content)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(bubble.title, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(bubble.hint, style = MaterialTheme.typography.bodyMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal const val TV_NOTICE_BANNER_TAG = "tv-notice-banner"

/** 横幅离内容区顶边、右边的距离. */
private val TV_NOTICE_BANNER_TOP = 24.dp
private val TV_NOTICE_BANNER_END = 28.dp

/** 横幅的宽、最矮的高、圆角、阴影. */
private val TV_NOTICE_BANNER_WIDTH = 320.dp
private val TV_NOTICE_BANNER_MIN_HEIGHT = 72.dp
private val TV_NOTICE_BANNER_CORNER = 16.dp
private val TV_NOTICE_BANNER_ELEVATION = 8.dp

/** 底色的不透明度 (盖在页面上, 透一点点). */
private const val TV_NOTICE_BANNER_ALPHA = 0.94f

/** 图标那块淡底: 边长与相对字色的不透明度. */
private val TV_NOTICE_BANNER_ICON_BOX = 44.dp
private const val TV_NOTICE_BANNER_ICON_BOX_ALPHA = 0.10f

/** 深色横幅上第二行的灰. */
private val TV_NOTICE_BANNER_SECONDARY_DARK = Color(0xFFB9B9BD)
