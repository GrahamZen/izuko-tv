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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_GRAY4
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_GRAY6_LIGHT
import me.him188.ani.app.ui.foundation.tv.tvAmbientMarquee
import me.him188.ani.app.ui.foundation.tvLongPressKey
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 右上角的通知横幅 (样子照 tvOS 的系统提醒): 左边图标 [icon] (第一条通知说的是哪一部作品时画它的封面 [cover], 裁成方的), 右边两行 —— [title] (第一条通知的标题) 与 [hint] (怎么按, 几条时带「还有 N 条」).
 * 横幅本身就是一颗按钮: 短按确定 [onAction] (出来后 [TV_NOTICE_BANNER_CONFIRM_GUARD] 内的不算), 长按 [onLongAction]; 按返回、10 秒没动作 [onDismiss].
 * 内容由 [TvNoticeHost] 定 ([TvNoticeBubbles.show]), 两种都由显示的一方撤掉.
 */
@Immutable
internal class TvNoticeBubble(
    val icon: ImageVector,
    val title: String,
    val hint: String,
    val onAction: () -> Unit,
    val onLongAction: () -> Unit,
    val onDismiss: () -> Unit,
    val cover: TvNoticeCoverImage? = null,
    /** 第一条通知说的是哪一部作品时它的名字: 第一行写它, [title] 挪到第二行 [hint] 前面; null = 第一行写 [title] */
    val lead: String? = null,
)

/** 此刻要画的通知横幅, 与眼前有没有地方画它 (见 [TvNoticeBubbleHost]). */
internal object TvNoticeBubbles {
    private val _current = MutableStateFlow<TvNoticeBubble?>(null)
    val current: StateFlow<TvNoticeBubble?> = _current.asStateFlow()

    /** 挂着的横幅位 (有侧边栏的页面各挂一个, 见 [TvNoticeBubbleSlots]). */
    val slots = MutableStateFlow(TvNoticeBubbleSlots())

    /** 眼前这一页画得出横幅: 它的侧边栏 (上面有「通知」) 在, 横幅位同它一起挂着. */
    val canShow: Boolean get() = slots.value.any

    /** 显示 [bubble] (替换正在显示的). */
    fun show(bubble: TvNoticeBubble) {
        _current.value = bubble
    }

    /** 撤掉 [bubble] (正在显示的已经换成别的就不动). */
    fun hide(bubble: TvNoticeBubble) {
        _current.compareAndSet(bubble, null)
    }
}

/**
 * 挂着的横幅位, 照挂上的先后. 有侧边栏的页面 (首页、搜索结果、条目详情页的首屏) 各挂一个 [TvNoticeBubbleHost]: 一个都没有时横幅不弹
 * (播放页、设置这些页面上只亮侧边栏「通知」的红点, 回到有侧边栏的页面再弹); 换页的过渡里两页的都在, 后挂上的那一个画.
 */
@Immutable
internal class TvNoticeBubbleSlots(private val hosts: List<Any> = emptyList()) {
    val any: Boolean get() = hosts.isNotEmpty()

    fun draws(host: Any): Boolean = hosts.lastOrNull() === host

    fun attached(host: Any): TvNoticeBubbleSlots = if (hosts.any { it === host }) this else TvNoticeBubbleSlots(hosts + host)

    fun detached(host: Any): TvNoticeBubbleSlots = if (hosts.none { it === host }) this else TvNoticeBubbleSlots(hosts.filterNot { it === host })
}

/** 横幅没动作多久自己淡出. */
internal val TV_NOTICE_BUBBLE_AUTO_DISMISS = 10.seconds

/** 横幅出来后这么久里的短按不算 (见 [TvNoticeBubble]). */
internal val TV_NOTICE_BANNER_CONFIRM_GUARD = 500.milliseconds

/**
 * 横幅位: 画 [TvNoticeBubbles.current], 在页面内容区的右上角, 盖在页面上面 (见 docs/contributing/code/notices.md). 有侧边栏的页面各挂一个,
 * 挂在侧边栏旁边、同它一起在 (侧边栏不画的时候这里也不挂): 眼前有横幅位才弹横幅 ([TvNoticeBubbles.canShow]). [enabled] 为 false 时
 * 这一页此刻不弹 (搜索页挑关键词时: 横幅会拿走输入框的焦点).
 */
@Composable
fun BoxScope.TvNoticeBubbleHost(enabled: Boolean = true) {
    val slot = remember { Any() }
    if (enabled) DisposableEffect(slot) {
        TvNoticeBubbles.slots.update { it.attached(slot) }
        onDispose { TvNoticeBubbles.slots.update { it.detached(slot) } }
    }
    val slots by TvNoticeBubbles.slots.collectAsState()
    val pending by TvNoticeBubbles.current.collectAsState()
    // 换页的过渡里两页的横幅位都在: 后挂上的那一页画
    val bubble = pending.takeIf { enabled && slots.draws(slot) }
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

    // 焦点导航设备: 横幅在场时按方向键 = 收起横幅 (同按返回, 焦点回到页面上原来那一项), 这一下不往页面里传; 再按才在页面上走.
    // 不锁着等 10 秒 (常见电视系统的提醒都不挡方向键); 也不放给焦点搜索: 横幅抢到焦点后随手一按, 焦点会从右上角滑进页面
    // 而横幅还挡着 —— 光挡 Compose 的焦点搜索不够, 搜索被取消后方向键没人消费, 会交给 Android 的 FocusFinder 按屏幕位置
    // 挑中背后原生页面的视图, 所以在这里直接吃掉
    val dismissOnArrows = focusDriven && shown

    AniAnimatedVisibility(
        visible = shown,
        modifier = Modifier
            .align(Alignment.TopEnd)
            // 页面里别的层有抬高的 (详情页的侧边栏): 横幅同它一档, 盖在内容上面
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = TV_NOTICE_BANNER_TOP, end = TV_NOTICE_BANNER_END)
            .tvFocusNavSignal(focus)
            .onFocusChanged { bannerFocused = it.hasFocus }
            .ifThen(dismissOnArrows) {
                onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) bubble?.onDismiss()
                            true
                        }

                        else -> false
                    }
                }
            },
    ) {
        lastBubble.value?.let { TvNoticeBanner(it, Modifier.tvFocusAnchor(focus, TvNoticeBubbleFocus.Banner)) }
    }
}

private enum class TvNoticeBubbleFocus : TvFocusKey { Banner }

/**
 * 横幅本身: 小圆角条, 底色同首页没聚焦的按钮 (深色 Apple 的 Gray4、浅色那档浅灰; 不用主题色), 带一点阴影; 左边一个淡底方块里放图标,
 * 右边两行. 说的是哪一部作品时: 第一行作品名, 第二行这件事、后面接淡一档的按键提示 (「某某」/「第 8 集已更新 · 按确定键查看」);
 * 别的: 第一行标题, 第二行只有按键提示. 宽度跟着字走, 在 [TV_NOTICE_BANNER_WIDTH] 与 [TV_NOTICE_BANNER_MAX_WIDTH] 之间; 到了最宽还放不下时
 * 第一行滚着读完 (横幅在场时焦点在它身上, 同别处聚焦的那一项; 不滚跑马灯的档截尾), 第二行截的是末尾的按键提示, 这件事一直看得到.
 * 一直是这一个样子 (它在场时焦点总在它身上), 不画聚焦的样子.
 */
@Composable
private fun TvNoticeBanner(bubble: TvNoticeBubble, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background = (if (dark) TV_POSTER_WALL_GRAY4 else TV_POSTER_WALL_GRAY6_LIGHT).copy(alpha = TV_NOTICE_BANNER_ALPHA)
    val content = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (dark) TV_NOTICE_BANNER_SECONDARY_DARK else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(TV_NOTICE_BANNER_CORNER)
    // 横幅是突然弹出来抢了焦点的: 刚出来那一下的确定多半是按给下面页面的, 不算 (短按会直接开始播放这类事)
    val shownAt = remember(bubble) { TimeSource.Monotonic.markNow() }
    val onShortPress = { if (shownAt.elapsedNow() >= TV_NOTICE_BANNER_CONFIRM_GUARD) bubble.onAction() }
    Row(
        modifier
            .widthIn(min = TV_NOTICE_BANNER_WIDTH, max = TV_NOTICE_BANNER_MAX_WIDTH)
            .heightIn(min = TV_NOTICE_BANNER_MIN_HEIGHT)
            .shadow(TV_NOTICE_BANNER_ELEVATION, shape)
            .background(background, shape)
            // 遥控器: 按住到长按的门槛当场打开通知弹窗 (不等松手), 没到门槛松手算短按 (从别处按住带过来的那一下不认)
            .tvLongPressKey(onLongPress = bubble.onLongAction, onShortPress = onShortPress)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = bubble.onLongAction,
                onClick = onShortPress,
            )
            .testTag(TV_NOTICE_BANNER_TAG)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val iconShape = RoundedCornerShape(10.dp)
        Box(
            Modifier.size(TV_NOTICE_BANNER_ICON_BOX).background(content.copy(alpha = TV_NOTICE_BANNER_ICON_BOX_ALPHA), iconShape),
            contentAlignment = Alignment.Center,
        ) {
            val icon = @Composable { Icon(bubble.icon, contentDescription = null, Modifier.size(24.dp), tint = content) }
            // 说的是哪一部作品的: 这一块画它的封面, 竖版海报裁成方的; 取到之前、取不到时还是图标
            val cover = bubble.cover
            if (cover != null) TvNoticeCoverBox(cover, Modifier.fillMaxSize(), iconShape, icon) else icon()
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val lead = bubble.lead
            // 第一行: 作品名 (没有的写标题); 放不下滚着读完 (不滚跑马灯的档截尾)
            Text(
                lead ?: bubble.title,
                Modifier.tvAmbientMarquee(),
                style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
            // 第二行: 写了作品名的先写这件事, 后面接按键提示; 放不下时截的是按键提示
            val hintStyle = MaterialTheme.typography.bodyMedium
            Row {
                if (lead != null) {
                    Text(bubble.title, style = hintStyle, color = content, maxLines = 1, softWrap = false)
                    Text(TV_NOTICE_BANNER_SEPARATOR, style = hintStyle, color = secondary, maxLines = 1, softWrap = false)
                }
                Text(
                    bubble.hint,
                    Modifier.weight(1f, fill = false),
                    style = hintStyle, color = secondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal const val TV_NOTICE_BANNER_TAG = "tv-notice-banner"

/** 横幅离内容区顶边、右边的距离. */
private val TV_NOTICE_BANNER_TOP = 24.dp
private val TV_NOTICE_BANNER_END = 28.dp

/** 横幅的宽 (最窄与最宽: 跟着两行字里长的那一行走)、最矮的高、圆角、阴影. */
private val TV_NOTICE_BANNER_WIDTH = 320.dp
private val TV_NOTICE_BANNER_MAX_WIDTH = 480.dp

/** 第二行里这件事与按键提示之间的分隔 (同按键提示里「还有 N 条 · 按确定键查看」的那个点). */
private const val TV_NOTICE_BANNER_SEPARATOR = " · "
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
