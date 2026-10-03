/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.pow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.ui.external.placeholder.PlaceholderHighlight
import me.him188.ani.app.ui.external.placeholder.fade
import me.him188.ani.app.ui.external.placeholder.placeholder
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.NSFW_OBSCURED_BACKDROP_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.NSFW_OBSCURED_COVER_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState
import me.him188.ani.app.ui.foundation.rememberImageCompletionGrace
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.theme.SubjectSeedColorCache
import me.him188.ani.app.ui.foundation.theme.subjectSeedColor
import me.him188.ani.app.ui.foundation.tvLongPressKey

/**
 * TV 竖版封面卡片 (时间表网格用): 聚焦时主题主色外圈 (外圈与封面之间留一圈空隙,
 * 不需要动态取色); 网格页放大样式下聚焦框与放大由 [TvGridFocusSlot] 统一画, 卡片这里关掉 ([showFocusRing] = false).
 * [imageUrl] 为 null 时显示加载占位. 长按 (遥控器确定键长按 / 触屏长按)
 * 弹出 [menu] (用于承载与详情页收藏按钮一致的收藏状态下拉).
 *
 * 焦点请求也可通过 [modifier] 挂 [FocusRequester]: 请求会委托给子树里第一个焦点目标
 * (卡片内容本体), 因此外部可以叠加多枚请求器 (如"首卡"与"恢复焦点"各一枚).
 */
@Composable
fun TvPortraitCard(
    imageUrl: String?,
    contentDescription: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChangedExtra: ((Boolean) -> Unit)? = null,
    menu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)? = null,
    /** 集数观看进度 (0..1): 贴卡片底缘画一条细进度条; null 不显示. */
    progress: Float? = null,
    /**
     * [menu] 的展开态变化 (长按弹出 / 关闭). 供调用方在长按期间做整页效果 —— 如时间表把
     * 其余卡片淡掉露出 backdrop. 只在真正变化时回调, 不会在每次组合时空报一次.
     */
    onMenuExpandedChange: ((Boolean) -> Unit)? = null,
    /**
     * false 时聚焦不自绘外圈 —— 聚焦框由容器统一画: 网格页 (时间表网格) 的放大样式由 [TvGridFocusSlot]
     * 画在聚焦格上, 上下翻页时框不动 (原版样式仍由卡片自己画, 见 [TvGridFocusSlot.usesCardRing]).
     */
    showFocusRing: Boolean = true,
    /** 给封面打码 (NSFW 模糊模式): 降采样成一张糊图, 见 AsyncImage 的 downsampleLongEdgePx. */
    obscureImage: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }
    // 菜单等第一次长按才组合, 之后一直留着 (收起动画照常): 收起的下拉菜单每张卡也要组合一份, 网格换行时新进来的一整行都得现建;
    // 弹出层本来就只在展开时才组合, 外壳晚到第一次长按才建, 弹出层的时机不变
    var menuComposed by remember { mutableStateOf(false) }
    val setMenuExpanded = { value: Boolean ->
        if (menuExpanded != value) {
            if (value) menuComposed = true
            menuExpanded = value
            onMenuExpandedChange?.invoke(value)
        }
    }
    Box(
        modifier
            .aspectRatio(TV_PORTRAIT_CARD_COVER_RATIO)
            // 自绘外圈 (焦点态只在绘制阶段读, 不牵动整卡重组, 见 tvFocusRing);
            // 由容器统一画框时 (网格页 TvGridFocusSlot) 整条跳过
            .tvFocusRing(TV_PORTRAIT_CARD_CORNER + TvFocusRing.Gap, enabled = showFocusRing),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(TvFocusRing.Gap),
            shape = RoundedCornerShape(TV_PORTRAIT_CARD_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .onFocusChanged {
                        if (it.isFocused) onFocused()
                        onFocusChangedExtra?.invoke(it.isFocused)
                    }
                    .then(
                        // 有菜单才接管确认键 (按住途中到阈值立即弹菜单, 短按仍是点击);
                        // 没有菜单时交回下面 combinedClickable 的原生处理.
                        // 长按残余的确认键由弹出的菜单自己吞掉 (调用方负责, 见各页 collectionMenuFor)
                        if (menu == null) {
                            Modifier
                        } else {
                            Modifier.tvLongPressKey(
                                onLongPress = { setMenuExpanded(true) },
                                onShortPress = onClick,
                            )
                        },
                    )
                    .tvTouchFocusOnTap()
                    .combinedClickable(
                        interactionSource = interactionSource,
                        // **不要 indication**: 默认涟漪在聚焦时给封面叠一层白色 scrim, 而它是
                        // 画在卡片上的 —— 跟着卡片一起滑, 聚焦框却钉在聚焦格上 (见 TvGridFocusSlot), 翻页途中两者分家
                        // (高亮已经瞬移到下一张, 框还在原地等卡片滑过来). 聚焦态一律只由描边
                        // 表达, 与选集卡一致.
                        indication = null,
                        onClick = onClick,
                        onLongClick = menu?.let { { setMenuExpanded(true) } },
                    ),
            ) {
                if (imageUrl != null) {
                    // 快速滑过的并发洪峰会让个别请求失败并卡在 Error, 卡片永久剩纯色底
                    // (见 rememberAsyncImageRetryState)
                    val retry = rememberAsyncImageRetryState(imageUrl)
                    // 窄带宽上一张封面要六七秒, 比导航节奏慢得多; 卡片被丢弃时若还没下完,
                    // 交给后台跑完写进磁盘缓存, 免得回来又从头下 (见 rememberImageCompletionGrace)
                    val completionGrace = rememberImageCompletionGrace(imageUrl)
                    AsyncImage(
                        if (retry.suppressed) null else imageUrl,
                        contentDescription = contentDescription,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = { retry.onError() },
                        completionGrace = completionGrace,
                        downsampleLongEdgePx = if (obscureImage) TV_OBSCURED_COVER_LONG_EDGE_PX else null,
                    )
                } else {
                    // 只有视觉效果完整档骨架才脉动 (否则 highlight=null). 无限 fade 高亮把动画值
                    // 读进组合 (thirdparty placeholder 旧 accompanist 写法), 一屏几十张骨架卡
                    // = 首屏加载最忙时段每帧几十次重组; 搜索页隐藏条目 imageUrl 恒为 null,
                    // 不关的话那些卡永远在跑
                    val fullEffects = LocalThemeSettings.current.visualEffects.ambient
                    Box(
                        Modifier.fillMaxSize()
                            .placeholder(
                                true,
                                shape = RoundedCornerShape(TV_PORTRAIT_CARD_CORNER),
                                highlight = if (fullEffects) ({ PlaceholderHighlight.fade() }) else null,
                            ),
                    )
                }
                // 集数观看进度条: 与详情页选集卡 (FocusEpisodeProgressBar) 同款悬浮胶囊条 ——
                // 圆头、白 30% 轨道 + 主题色填充, 条厚同值; 长度与离底空隙都是手调常量
                if (progress != null && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter) // 定宽 + 居中对齐 = 左右自动等距
                            .padding(bottom = TV_CARD_PROGRESS_BAR_BOTTOM_GAP)
                            .width(TV_CARD_PROGRESS_BAR_LENGTH)
                            .height(TV_CARD_PROGRESS_BAR_HEIGHT)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = TV_CARD_PROGRESS_TRACK_ALPHA)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(TV_CARD_PROGRESS_BAR_HEIGHT)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
        // 菜单以卡片右下角为锚点弹出 (DropdownMenu 默认从锚点向右/上下就近展开): 放一个对齐到
        // 卡片右下角的零尺寸锚点, 菜单即从右下角向右上方向弹出.
        if (menu != null && menuComposed) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                menu(menuExpanded) { setMenuExpanded(false) }
            }
        }
    }
}

/**
 * TV Hero 样式的操作按钮 (首次引导页用; 列表页 hero 上的按钮是原生的 TvNativeHeroButton, 外观取同一套值). 两枚都是深/浅灰实心
 * (参考 Prime: 主按钮略亮, 次按钮接近底色), 按白天/黑夜主题分别取色. 聚焦时整颗按钮
 * 高亮为主题主色、文字/图标反色 (onPrimary), 与侧边栏选中一致.
 * [filled] = true 为主按钮 (略亮一档). 图标 + 文字单行.
 *
 * 未聚焦时描一圈细边 ([TV_HERO_BUTTON_OUTLINE_WIDTH]), 照 Apple TV App 压在剧照上的按钮 (`Dark` 样式: 底板之外一圈 1 pt 白 16%):
 * 按钮底下是剧照或它羽化出的页面底, 光靠底板与背景的明暗差, 灰底板贴着同样发灰的背景时看不出按钮在哪. 聚焦时底板换成主题色,
 * 边界本来就清楚, 不描边 (Apple 聚焦态同样没有描边).
 */
@Composable
fun TvHeroButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChangedExtra: ((Boolean) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 聚焦样式直接读真实焦点 (下面的 onFocusChanged), 不用 interactionSource 的 Focus/Unfocus 事件:
    // 那是一次性事件, 按钮刚进组合、收集还没开始时焦点就给了过来, 这一下就丢了, 样式从此停在未聚焦,
    // 而焦点本身正常 —— 看不到焦点框却按得动
    var focused by remember { mutableStateOf(false) }
    // 底板与描边按当前主题明暗取 (见 tvHeroButtonContainerColor)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val baseContainer = tvHeroButtonContainerColor(MaterialTheme.colorScheme, filled, posterWall = LocalTvPosterWallTheme.current)
    val container = if (focused) MaterialTheme.colorScheme.primary else baseContainer
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val outline = if (focused) null else BorderStroke(TV_HERO_BUTTON_OUTLINE_WIDTH, tvHeroButtonOutlineColor(dark))
    // 按 TV_HERO_BUTTON_SCALE 整体缩放内边距/图标/字号
    val scale = TV_HERO_BUTTON_SCALE
    val textStyle = MaterialTheme.typography.titleSmall.let {
        it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale)
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
                onFocusChangedExtra?.invoke(it.isFocused)
            }
            .tvTouchFocusOnTap(),
        shape = RoundedCornerShape(TV_HERO_BUTTON_CORNER),
        color = container,
        border = outline,
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(
                horizontal = TV_HERO_BUTTON_PADDING_HORIZONTAL * scale,
                vertical = TV_HERO_BUTTON_PADDING_VERTICAL * scale,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TV_HERO_BUTTON_ICON_GAP * scale),
        ) {
            Icon(icon, contentDescription = null, Modifier.size(TV_HERO_BUTTON_ICON_SIZE * scale), tint = content)
            Text(text, color = content, style = textStyle, maxLines = 1)
        }
    }
}

/**
 * hero 展示目标的**换挡合并** (两档相同; 2026-09-10 之前只在低特效档): hero 背景图按
 * [TvNavigationSettle] 的静默规则换挡 —— 空闲后的单次移动在方向键抬起 (+80ms 左右, [TvNavKeyTracker])
 * 就换, 连发 (按住方向键 / 快速连点) 期间一次都不换, 停下来静默 [TV_NAV_SETTLE_MILLIS] 后换到最后聚焦的
 * 那个. **不等卡片滚动停稳** (曾经等过: 2026-09-09 索尼实测连发时每帧绘制录制 p90 19ms -> 8ms, 但那份收益
 * 来自连发期间不换, 静默闸门已经保住; 单击等停稳只是让图比卡片晚到 —— 用户 2026-09-10 要图跟着卡片走).
 * 文字块另走 [rememberTvScrollHiddenProvider]: 同一条规则, 外加"等停稳"与"滚动 / 连发 / 按住期间藏起来".
 *
 * **卡片自身的动画 (滚动、压暗、淡出、聚焦框与放大) 完全不受影响**, 这里只推迟"背景图 + hero 文字"这两块整屏级的内容替换.
 *
 * ## 收 provider、还 provider
 *
 * 收 `() -> T`、还 `() -> T`, 全程不在调用方的 composable body 里读热状态: 调用方若先把 hero 目标读出来再传值,
 * 那次读记在调用方 body 上 —— 页面级 composable 于是每换一张卡就整体重跑一遍. 各页的 hero 状态都**只在子组件的
 * lambda 里读** (背景图层与 hero 文字都收 provider), 换卡只重组那一小块.
 *
 * 读取全部关在两个不属于组合的地方: 种子值用 `withoutReadObservation` 读一次, 之后由 `snapshotFlow` 在协程里观察.
 * 返回的 lambda 只读一个普通 `MutableState`, 谁调用谁订阅.
 *
 * ## 为什么值得做 (2026-08-13 索尼 BRAVIA v7a 实测)
 *
 * 每换一次 hero 会同时启动 600ms 的 backdrop `Crossfade` 与 500ms 的文字 `AnimatedContent`
 * 淡入淡出; 两者时长都远长于连发间隔 (250ms), 于是**连续导航时它们永不结束**, 应用被迫连续
 * 产帧, 且淡入淡出期间新旧两份内容同时存在 (两块约 1.2MP 的图层 + 两棵 CJK 文本树, 默认
 * `CompositingStrategy.Auto` 还各要一遍离屏缓冲).
 *
 * 12 次方向键的实测帧数: 原样 **98 帧**, 把两处淡入淡出时长改 0 后 **12 帧** —— 也就是每按一格
 * 键要陪跑约 8 帧 (该机每帧约 20ms), 而这 8 帧画的全是"背景图和文字正在互相淡入淡出".
 * 合并换挡把这份开销压到"每停一次一份".
 *
 * @param target 焦点驱动的真实目标 (每格键都变). 数据预取仍应读它, 不要读返回值 —— 停下来时
 * 数据已在缓存里, 换挡才不会跟着等网络.
 * @param flushOn 它的值一变就当场换到此刻的目标, 不等静默与抬键 (如进 hero 态那一刻: 焦点刚换到这张就按了确认 —— 返回键远跳落地后
 *   排队的确认正是这样 —— 等静默期的话 hero 先露出上一张的背景). 默认不用.
 */
@Composable
fun <T> rememberTvSettledHeroProvider(
    /**
     * 这一拍要不要等方向键抬起 (按住的第一格不换图, 见 [TvNavKeyTracker]). 默认要; 探索页在**轮播态**
     * (焦点在 hero 按钮上, 左右键翻轮播) 传 false —— 那里没有"按住扫过很多项"的手势, 等抬起只是让
     * 背景比按下晚 80~200ms 起步 (用户 2026-09-10: "按键的反应慢了一点"). 在协程里读, 不进组合.
     */
    awaitKeyRelease: () -> Boolean = { true },
    flushOn: () -> Any? = { null },
    target: () -> T,
): () -> T {
    val navKeys = LocalTvNavKeyTracker.current
    // lambda 每次重组换新实例, 必须经 rememberUpdatedState 再进 snapshotFlow, 否则永久留住首帧值
    val latest = rememberUpdatedState(target)
    val currentFlushOn = rememberUpdatedState(flushOn)
    // 种子值必须"不被观察地"读: 直接 target() 会把热状态的读算到调用方的 body 上, 那正是本函数
    // 要避免的事
    val settled = remember { mutableStateOf(Snapshot.withoutReadObservation { target() }) }
    LaunchedEffect(navKeys) {
        // 与文字块、四个 TV 页的媒体预取共用同一条静默规则, 见 [TvNavigationSettle]
        val settle = TvNavigationSettle(TV_NAV_SETTLE_MILLIS)
        snapshotFlow { latest.value.invoke() }.collectLatest { value ->
            // 屏幕上**还什么都没有**时不合并: 进页面的头两拍是 null -> 首个条目, 两者间隔远小于
            // 静默期, 按连发处理的话 hero 要凭空晚一个静默期才出现. 合并是为了不让两份真内容
            // 来回切, 从无到有没有可合并的对象.
            val bypass = settled.value == null
            settle.awaitTurn(bypass = bypass)
            // 只等方向键抬起, **不等卡片滚动停稳** (文字块等停稳, 背景图不等 —— 用户 2026-09-10: 图要
            // 跟着卡片走、早点换): 单击的抬起在 +80ms 左右, crossfade 从那时起步, 卡片到位 (+250) 时图
            // 基本已经到了; 按住则一直等到松手, 背景停在按下那张 (为什么不能靠时间猜, 见 TvNavKeyTracker).
            // 连发 (快速连点 / 按住的自动重复) 由上面的静默闸门挡住, 中途一张都不换 —— 2026-08-13 索尼
            // 实测里"crossfade 永远做不完"的状态不会回来.
            if (navKeys != null && !bypass && awaitKeyRelease()) snapshotFlow { navKeys.held }.first { !it }
            settled.value = value
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { currentFlushOn.value.invoke() }.drop(1).collect { settled.value = latest.value.invoke() }
    }
    return remember { { settled.value } }
}

/**
 * TV hero 区标题/正文文字色: 深色 #F1F1F1 (对齐 Prime 实测) —— 亮中性白, 无色相、无投影 (实测字形边缘无暗晕, 可读性靠文字够亮 +
 * backdrop 渐隐压暗). M3 的 onSurface/onSurfaceVariant 偏暗且带紫色相, 在深色 backdrop 上显得发糊. 浅色照 Apple TV App 浅色表的
 * LabelPrimary, 纯黑.
 */
@Composable
fun tvHeroContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFF1F1F1) else Color.Black

/**
 * TV hero 区次要信息文字色 (连载信息/日期等): 深色 #B4B5B7 中性灰 (对齐 Prime 实测); 浅色照 Apple 浅色表的 LabelSecondary
 * (黑 60%), 取它叠在浅色页面底上的实色 [TV_POSTER_WALL_SECONDARY_LABEL_LIGHT].
 */
@Composable
fun tvHeroSecondaryContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFB4B5B7) else TV_POSTER_WALL_SECONDARY_LABEL_LIGHT

/**
 * 详情页大标题 (白字) 压在背景图上的黑影. 详情页标题、放大 / 缩回的转场标题, 以及浅色主题下列表页 hero 标题点开时渐变成的样子
 * (见 TvNativeHeroTextView.titleLook) 共用这一份: 几处不同值的话, 交接那一帧阴影会跳.
 */
fun tvDetailsTitleShadow(density: Density): Shadow = with(density) {
    Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1.dp.toPx()), blurRadius = 6.dp.toPx())
}

/**
 * TV backdrop 下缘渐隐的渐变停点: 遮盖 alpha 在 [start]..[end] (绘制坐标 0..1)
 * 内从 0 平滑升到 1. 曲线 = quintic smootherstep (两端一、二阶导都为 0) 经 1-(1-s)^power
 * 反变换: 起点以零斜率极缓进入 (看不到"渐变开始"的分界线), 中段较快压暗, 尾段以指数级
 * 放缓渐近全遮、一直渐变到 [end] (通常传图的底边 1.0) —— 终点同样无分界线.
 * [power] 越大前段越快、尾巴越长. 采样多段生成停点, 避免手写折点产生马赫带.
 *
 * [color] 传页面底色时用普通 SrcOver 叠画即可 (图下面恰是该纯色时与 DstOut 擦除逐像素
 * 等价, 且不需要离屏合成); 传默认黑 + BlendMode.DstOut 则是擦除语义 (底下不是纯色时用).
 */
fun tvBackdropFadeToBlackStops(
    start: Float,
    end: Float,
    power: Float = 2.5f,
    samples: Int = 20,
    color: Color = Color.Black,
    maxAlpha: Float = 1f,
): Array<Pair<Float, Color>> = Array(samples + 1) { i ->
    val f = i / samples.toFloat()
    val s5 = f * f * f * (f * (f * 6f - 15f) + 10f)
    (start + (end - start) * f) to color.copy(alpha = maxAlpha * (1f - (1f - s5).pow(power)))
}

/**
 * TV backdrop 边缘渐隐的渐变停点: 遮盖 alpha 在 [start]..[end] 内从 [maxAlpha]
 * 平滑降到 0 ([start] 之前按 [maxAlpha] 遮盖, [end] 之后图完全清晰). smoothstep 采样,
 * 理由同上. [maxAlpha] < 1 时是"压暗"而非完全遮盖 (如顶缘给悬浮文字提高可读性).
 * [color] 语义见 [tvBackdropFadeToBlackStops].
 */
fun tvBackdropFadeFromBlackStops(
    start: Float,
    end: Float,
    maxAlpha: Float = 1f,
    samples: Int = 14,
    color: Color = Color.Black,
): Array<Pair<Float, Color>> = Array(samples + 1) { i ->
    val f = i / samples.toFloat()
    val s = f * f * (3f - 2f * f)
    (start + (end - start) * f) to color.copy(alpha = maxAlpha * (1f - s))
}

/**
 * backdrop 主图.
 *
 * 这里曾有一个**卡死重发** hedge (到点叠一张同 URL 的图, 赌第二条是新连接): 这台机器上单条
 * TCP 流假死是常态 (2026-08-14 实测背景图卡住 10.7s / 10.2s, 同期其它请求 200~300ms 正常完成),
 * coil 不合流并发的相同请求, 第二张真能开出一条独立连接. **sketch 下它是死代码** (2026-08-21
 * 从 4.6.0 字节码核实): `MemoryCacheInterceptor` 把整条加载链锁在内存缓存键上, `HttpUriFetcher`
 * 又按下载缓存键锁一次 —— 同 URL 同尺寸的第二张只能排队等第一条的锁, 永远开不出新连接.
 * 同一个病如今由 ktor 层治 (见 `ScopedHttpClientHttpStack`): 3s 读超时掐断假死流 + 200ms
 * 固定延迟重试三次, 比原来 4s hedge 到点才补射恢复得更快. 别再把 hedge 加回来.
 */
@Composable
private fun TvBackdropImage(
    url: String,
    themeSeedSubjectId: Int? = null,
    /** 本页盖在这张图上的整层压暗 (全屏背景层有), 随登记交给放大转场, 见 TvHeroZoomHandoff.Session.dim. */
    zoomDim: Color = Color.Transparent,
    /**
     * 本页压在这张图上的那套遮罩 (见 [TvBackdropTreatment]), 随登记交给放大转场: 转场画的是"本页这份"与
     * "详情页那份"的插值, 于是起点逐像素等于本页. 在 onGloballyPositioned 里登记时才取, 不在组合里读.
     */
    zoomTreatment: () -> TvBackdropTreatment? = { null },
) {
    // 接管在途预热 (见 TV_BACKDROP_PREFETCH_HANDOFF_MILLIS): 这张图正被预热时先等它.
    // 在组合里取一次, 没有在途的常规情形一帧都不耽误
    val prefetch = remember(url) { TvHeroImagePrefetch.inFlight(url) }
    val handoffLeft = TV_BACKDROP_PREFETCH_HANDOFF_MILLIS - (prefetch?.elapsedMillis ?: 0)
    var waitingPrefetch by remember(url) { mutableStateOf(prefetch != null && handoffLeft > 0) }
    LaunchedEffect(url) {
        if (waitingPrefetch) {
            withTimeoutOrNull(handoffLeft) { prefetch?.job?.join() }
            waitingPrefetch = false
        }
    }
    if (waitingPrefetch) return
    val scope = rememberCoroutineScope()
    // 登记"这张图此刻在屏幕哪个框里", 给详情页的放大转场 (TvHeroZoomHandoff); 离开组合即撤销.
    // 撤销按**本组件这一枚标记**对认, 不按 URL: 两个页面同时显示同一张图时 (转场期间新旧两页并存的那几帧), 按 URL 会互相抹掉
    val zoomSourceOwner = remember { Any() }
    DisposableEffect(zoomSourceOwner) { onDispose { TvHeroZoomHandoff.retract(zoomSourceOwner) } }
    // NSFW 设为模糊时按条目打码: 解成小图糊掉 (同原生背景层的 obscure, 见 NsfwPolicy)
    val nsfw = rememberNsfwPolicy()
    val obscure = themeSeedSubjectId != null && nsfw.blurs(themeSeedSubjectId)
    AsyncImage(
        url,
        contentDescription = null,
        Modifier.fillMaxSize().onGloballyPositioned { coords ->
            themeSeedSubjectId?.let {
                TvHeroZoomHandoff.publish(zoomSourceOwner, it, url, coords.boundsInRoot(), zoomDim, zoomTreatment())
            }
        },
        contentScale = ContentScale.Crop,
        // 与详情页同一个缓存键 (见 tvHeroBackdropDecodeAtOriginalSize), 进详情页首帧就有图
        decodeAtOriginalSize = !obscure && tvHeroBackdropDecodeAtOriginalSize(url),
        downsampleLongEdgePx = if (obscure) TV_OBSCURED_BACKDROP_LONG_EDGE_PX else null,
        onSuccess = { success ->
            // 返回缩回撤层前要等列表页 hero 这张图画得出来 (见 TvHeroZoomHandoff.listReady)
            themeSeedSubjectId?.let { TvHeroZoomHandoff.markSourceLoaded(it, url) }
            // 提前取色: 已经算过的条目直接跳过; 取色本身在后台线程 (与详情页同一条 themeColor)
            val subjectId = themeSeedSubjectId ?: return@AsyncImage
            if (SubjectSeedColorCache[subjectId] != null) return@AsyncImage
            val bitmap = success.bitmap ?: return@AsyncImage
            // 与详情页共用同一个取色函数: 算法不一致的话进页会被重算的色顶掉, 观感是"跳两次"
            scope.launch { SubjectSeedColorCache[subjectId] = bitmap.subjectSeedColor() }
        },
    )
}

/**
 * TV 全屏背景 backdrop 层 (新番时间表用): 横版图铺满全屏 (Crop) + 一层整屏压暗
 * ([TV_FULLSCREEN_BACKDROP_DIM_ALPHA]), 换图 crossfade. 四缘都不做渐隐 —— 见下.
 *
 * 与列表页 hero 的背景图层 (TvNativeBackdropView: 16:9 贴右上, 只占屏顶一部分) 的区别: 本页整屏都铺着卡片与小字,
 * 因此整屏压暗; 也正因为压暗是均匀的一层, 左缘不再额外补 scrim —— 横向渐变收尾处总会留下
 * 一条肉眼可见的边界, 而侧边栏的白图标压在整屏压暗上本来就够清楚.
 *
 * 遮罩一律用页面背景色而非黑色: 黑色遮罩在浅色主题下会把整页压暗, 迫使文字改用白色 (详情页
 * 就是这么做的 —— 它首屏只有一个标题浮在图上); 而本页文字铺满全屏、焦点每移一格就换图,
 * 逐图切换文字明暗会闪. 用背景色遮罩后, 深色主题下等效于原来的黑色遮罩, 浅色主题下是一层白纱,
 * 两种主题都能直接用 [tvHeroContentColor] 那套随主题取色的文字色.
 *
 * [backdropUrl] 用 lambda 而非值传入: URL 由"聚焦条目"状态推导, 状态读取发生在本组件内部 —— 遥控器每移一格只重组
 * 这一小块, 不连带调用方整个页面作用域重组.
 *
 * 只能用在**整屏归自己**的页面上 (新番时间表是独立目的地): 主壳内的页面被让开了侧边栏那一条,
 * 而主页三个 tab 的 AnimatedContent 会把内容裁在这个边界上 (MainScreen 的 topLevelTransition
 * 用的是默认 SizeTransform, clip = true), 从内容侧无论怎么向左出血都会被裁掉.
 */
@Composable
fun TvFullScreenBackdropLayer(
    backdropUrl: () -> String?,
    modifier: Modifier = Modifier,
    /**
     * 这张图属于哪个条目 (不是它自己的图时给 null). 给了才登记放大转场 (点这个条目进详情页时图原地不动、压暗退掉、
     * 详情页 UI 一次出现, 不走整页交叉淡入) 并提前取主色写进 [SubjectSeedColorCache] (点进详情页第一帧就是动态色).
     */
    themeSeedSubjectId: () -> Int? = { null },
) {
    val background = MaterialTheme.colorScheme.background
    val dim = background.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    // 用库的 Crossfade (两张整屏图各开一块离屏): 本层只有一层均匀压暗, 2026-09-13 Shield 4K A/B 换成遮罩只画一次、
    // 逐张 ModulateAlpha 的自写交叉淡入测不出差别 (时间表连按 GPU 合计 1673 vs 1682ms)
    // 不做底缘渐隐: 本页整屏都是内容, 渐隐带那一段会被擦成纯背景色 —— 在实机上就是
    // 屏幕最下面横着一条黑边. 它原本是为了托住右下角那行遥控提示, 提示已经去掉了.
    // 图铺满整屏, 均匀压暗一层就够 (与列表页 hero 那种 16:9 图不同: 那种图只占屏顶一部分, 渐隐带落在
    // 屏幕中段, 是图与背景之间的过渡, 不是一条贴着屏底的边)
    val body: @Composable (String?) -> Unit = { url ->
        if (url != null) {
            Box(Modifier.fillMaxSize()) {
                // 条目 id 在这张图开始加载那一刻取 (remember(url)): 换图期间新旧两张共存, 旧图加载完时读到的
                // 若是新条目的 id, 色就串了
                TvBackdropImage(
                    url, remember(url) { themeSeedSubjectId() },
                    zoomDim = dim, zoomTreatment = { TvBackdropTreatment(dim = dim) },
                )
                // 整屏基础压暗: 亮部海报上压不住灰色小字. 这是唯一一层压暗 —— 左缘不再额外补
                // scrim: 任何"从左缘衰减到透明"的横向渐变都会在收尾处留下一条肉眼可见的边界,
                // 而侧边栏图标压在这层整屏压暗上本来就足够清楚 (白图标 + 深底)
                Box(Modifier.fillMaxSize().background(dim))
            }
        }
    }
    Crossfade(
        backdropUrl(),
        modifier,
        animationSpec = tween(TV_BACKDROP_CROSSFADE_MILLIS),
    ) { url -> body(url) }
}

/**
 * 把一份 [TvBackdropTreatment] 画到 backdrop 上 —— 详情页与放大转场用它, 列表页的原生背景图层 (TvNativeBackdropView) 按同一套
 * 画法与停点画; 放大转场画的是两份声明的插值.
 *
 * 保住原有的三项优化 (都有实测账, 别推翻):
 * - 每条渐变**只画它不透明的那一段** (clipRect / 定尺寸 drawRect, 渐变坐标仍按整层): 铺满整层时透明部分 GPU
 *   照样逐像素混合一遍, 4K 下每条全屏混合约 2~3ms;
 * - 停点由平滑曲线采样生成, 没有折点 (暗端的马赫带分界线);
 * - 下缘在有纯色垫底时画同色渐变、不用 DstOut, 不必开离屏缓冲 (见 [TvBackdropTreatment.bottomDstOut]).
 *
 * 画笔按尺寸 + 声明预备 (调用方用 drawWithCache / remember 缓存), 画的时候再乘一个总 alpha.
 */
class TvBackdropTreatmentPainter internal constructor(
    private val size: Size,
    private val dim: Color,
    private val top: Brush?,
    private val topEnd: Float,
    private val left: Brush?,
    private val leftEnd: Float,
    private val bottom: Brush?,
    private val bottomStart: Float,
    private val bottomDstOut: Boolean,
) {
    fun DrawScope.draw(alpha: Float = 1f) {
        if (alpha <= 0f) return
        val w = size.width
        val h = size.height
        if (dim.alpha > 0f) drawRect(dim, alpha = alpha)
        // 只画到不透明段的边界: 之后停点已全透明, 再画就是整层面积白走一遍混合
        if (top != null) drawRect(top, size = Size(w, h * topEnd), alpha = alpha)
        if (left != null) drawRect(left, size = Size(w * leftEnd, h), alpha = alpha)
        if (bottom != null) {
            drawRect(
                bottom,
                topLeft = Offset(0f, h * bottomStart),
                size = Size(w, h * (1f - bottomStart)),
                alpha = alpha,
                blendMode = if (bottomDstOut) BlendMode.DstOut else DrawScope.DefaultBlendMode,
            )
        }
    }
}

/**
 * 停点的**透明度剖面**按形状缓存 (与颜色无关): 曲线形状是固定的, 每个采样点一次 `pow` 才是贵的那部分,
 * `Color.copy(alpha = )` 很便宜. 于是跨颜色复用同一份剖面, 缓存不会因为插值出来的中间色无限增长
 * (2026-09-16 审查指出的风险: 遮罩色现在是连续插值的, 按 Color 做键会每帧攒一个新条目).
 */
internal val fadeOutProfile: FloatArray by lazy {
    FloatArray(15) { i -> val f = i / 14f; val sm = f * f * (3f - 2f * f); 1f - sm }
}
internal val fadeInProfile: FloatArray by lazy {
    FloatArray(21) { i ->
        val f = i / 20f
        val s5 = f * f * f * (f * (f * 6f - 15f) + 10f)
        1f - (1f - s5).pow(2.5f)
    }
}

/** 下缘用的 smoothstep (与 [fadeOutProfile] 同一条曲线, 方向相反), 采样数同 [fadeInProfile], 两者逐点混合, 见 [TvBackdropFade.smoothness]. */
internal val fadeInSmoothProfile: FloatArray by lazy {
    FloatArray(21) { i -> val f = i / 20f; f * f * (3f - 2f * f) }
}

internal fun fadeInProfileOf(smoothness: Float): FloatArray = when {
    smoothness <= 0f -> fadeInProfile
    smoothness >= 1f -> fadeInSmoothProfile
    else -> FloatArray(fadeInProfile.size) { i -> fadeInProfile[i] + (fadeInSmoothProfile[i] - fadeInProfile[i]) * smoothness }
}

/**
 * 停点**位置是均匀的** (profile 本身就按 `i / (n-1)` 采样), 所以交给 `Brush` 的均匀分布重载,
 * 不要走 `vararg Pair<Float, Color>` 那条 —— 后者每帧多装箱一组 Pair 与 Float, 而放大转场里
 * 这三条渐变每帧都要重建 (treatment 每帧插值, 见 [tvBackdropTreatmentPainter] 的说明, 缓存不了).
 */
private fun colorsOf(profile: FloatArray, color: Color, maxAlpha: Float): List<Color> =
    List(profile.size) { i -> color.copy(alpha = maxAlpha * profile[i]) }

/**
 * 按尺寸与声明预备画笔, 见 [TvBackdropTreatmentPainter].
 *
 * **画笔在这里就建好**, 不放到 `draw()` 里: 声明不变时调用方缓存的是本对象 (drawWithCache / remember), 画笔建在 draw 里
 * 等于缓存白做 (2026-09-16 审查). 转场途中声明每帧都在变, 本来就要重建, 不吃亏.
 */
fun tvBackdropTreatmentPainter(size: Size, tr: TvBackdropTreatment): TvBackdropTreatmentPainter {
    val h = size.height
    val w = size.width
    val top = tr.top?.takeIf { it.maxAlpha > 0f }?.let {
        Brush.verticalGradient(colorsOf(fadeOutProfile, it.color, it.maxAlpha), startY = h * it.start, endY = h * it.end)
    }
    val left = tr.left?.takeIf { it.maxAlpha > 0f }?.let {
        Brush.horizontalGradient(colorsOf(fadeOutProfile, it.color, it.maxAlpha), startX = w * it.start, endX = w * it.end)
    }
    val bottom = tr.bottom?.takeIf { it.maxAlpha > 0f }?.let {
        Brush.verticalGradient(
            colorsOf(fadeInProfileOf(it.smoothness), it.color, it.maxAlpha),
            startY = h * it.start,
            endY = h * it.end,
        )
    }
    return TvBackdropTreatmentPainter(
        size, tr.dim,
        top, tr.top?.end ?: 0f,
        left, tr.left?.end ?: 0f,
        bottom, tr.bottom?.start ?: 1f,
        tr.bottomDstOut,
    )
}

/**
 * 列表页 backdrop 遮罩的几何 (整层 0..1 比例): 左缘 / 下缘渐变带在 hero 态 (cardness = 0) 与卡片态 (1) 的端点, 以及下缘用哪条
 * 曲线. 由 [TvHeroTuning] 换算 (见 [tvHeroBackdropGeometry]): 追番 / 搜索用 [TV_CARD_HERO_BACKDROP_GEOMETRY]; 探索页的背景图在
 * 热门轮播 (0) 与聚焦卡 (1) 两套尺寸之间过渡, 用两套换算出的那份.
 */
@Immutable
data class TvPageBackdropGeometry(
    val leftStartHero: Float,
    val leftStart: Float,
    val leftEndHero: Float,
    val leftEnd: Float,
    val bottomStartHero: Float,
    val bottomStart: Float,
    /** 下缘曲线, 见 [TvBackdropFade.smoothness]. */
    val bottomSmoothness: Float,
)

/**
 * 列表页 backdrop 那套遮罩的声明 (顶缘 scrim / 左缘 / 下缘). 渐变带端点在 hero / 卡片两态间按 [cardness] 插值,
 * 曲线形状两态共用. 详情页那份见 `tvHeroBackdropTreatment`, 放大转场画的是两者的插值.
 */
fun tvPageBackdropTreatment(
    cardness: Float,
    topScrim: Boolean,
    fadeColor: Color,
    geometry: TvPageBackdropGeometry,
): TvBackdropTreatment =
    TvBackdropTreatment(
        top = if (topScrim) {
            TvBackdropFade(0f, TV_BACKDROP_TOP_SCRIM_END, TV_BACKDROP_TOP_SCRIM_ALPHA, fadeColor)
        } else {
            null
        },
        left = TvBackdropFade(
            start = lerp(geometry.leftStartHero, geometry.leftStart, cardness),
            end = lerp(geometry.leftEndHero, geometry.leftEnd, cardness),
            maxAlpha = 1f,
            color = fadeColor,
        ),
        // 下缘渐隐, 一直渐变到图底. 默认曲线: 零斜率极缓起步 + 指数级长尾渐近全遮
        bottom = TvBackdropFade(
            start = lerp(geometry.bottomStartHero, geometry.bottomStart, cardness),
            end = 1f,
            maxAlpha = 1f,
            color = fadeColor,
            toEdge = true,
            smoothness = geometry.bottomSmoothness,
        ),
    )

/** 背景图层左缘 / 下缘压实的那一条的半宽 (跨在图边上, 见 TvNativeBackdropView). */
internal val TV_BACKDROP_EDGE_SEAM = 1.dp

// ============ TV 沉浸式页面 (探索/追番/搜索) 共享调参 ============
// 探索页轮播 (hero) 态的参数不在此列, 单独放在 TvExplorationPage 里.

// ---- backdrop ----

/** backdrop 宽高比. */
const val TV_BACKDROP_ASPECT_RATIO = 16f / 9f

/**
 * backdrop 换图的淡入淡出时长 (毫秒). **用户定的, 别为了"早点到"缩短它**: 2026-09-10 曾缩到 300 想让图跟上
 * 卡片, 用户要的是保持 600 只把起步提前 —— 起步由 `rememberTvSettledHeroProvider` 等什么信号决定 (现在是
 * 方向键抬起, 单击 +80ms 左右), 与时长无关.
 */
const val TV_BACKDROP_CROSSFADE_MILLIS = 600

/** 「按下即压暗」压到的不透明度 (页面背景色盖在图上), 与压下 / 放开的时长. 见 TvNativeBackdropView.triggerPressDim. */
const val TV_BACKDROP_PRESS_DIM_ALPHA = 0.55f
internal const val TV_BACKDROP_PRESS_DIM_IN_MILLIS = 180
internal const val TV_BACKDROP_PRESS_DIM_HOLD_MILLIS = 250L
internal const val TV_BACKDROP_PRESS_DIM_OUT_MILLIS = 450

/** backdrop 顶缘压暗带终点 (图片高度坐标 0..1; 顶部悬浮文字的可读性 scrim). */
const val TV_BACKDROP_TOP_SCRIM_END = 0.16f

/** backdrop 顶缘压暗强度 (1 = 完全擦除). */
const val TV_BACKDROP_TOP_SCRIM_ALPHA = 0.7f

/**
 * 应急垫底图 (竖版封面) 的不透明度, 见 TvNativeBackdropTarget.underlayUrl.
 * 压到半透明是为了让它读起来像氛围底色而不是"糊掉的背景图".
 */
const val TV_BACKDROP_UNDERLAY_ALPHA = 0.5f

/**
 * 显示端"接管"同 URL 在途预热的耐心上限, **从预热开始时刻算起**, 见 `TvBackdropImage`.
 *
 * 用户走到这张卡时, 它的预热常常正跑到一半 (邻居预热在上一次聚焦时发出, 实测单张 250~600ms).
 * 等它跑完再请求, 拿到的是磁盘命中 (60~145ms). (sketch 的下载层本来就按 URL 锁, 不等也不会
 * 双发 —— 显示请求会阻塞在预热那条的锁上, 效果等价; 这里显式等的价值是把"在等谁"写清楚,
 * 并给挂死的预热设一个不跟着陪葬的上限.)
 *
 * 算的是"还剩多久"而不是"再等多久": 预热已经跑了 900ms 就只等 100ms, 跑过 1s 还没完的直接
 * 不等 —— 那种多半已经挂死在断掉的连接上 (实测这台机器上假死是常态, 一挂就是 10 秒), 干等
 * 满一个固定窗口纯亏.
 *
 * 1s 这个总预算而不是两三百毫秒: 预算比预热本身还短的话, "刚开始预热"这种最该省的情形必然
 * 落空、照样双发. 预算之外还有 ktor 层的读超时与重试兜着 (见 `ScopedHttpClientHttpStack`).
 */
internal const val TV_BACKDROP_PREFETCH_HANDOFF_MILLIS = 1_000L

/**
 * backdrop 下缘渐隐起点 (图片高度坐标 0..1, 此处开始向下渐暗, 一直渐变到图底). 与 [TV_BACKDROP_LEFT_FADE_START] /
 * [TV_BACKDROP_LEFT_FADE_END] 是一套, 详情页放大期间给背景图补的左缘 / 下缘羽化按它们画.
 */
const val TV_BACKDROP_BOTTOM_FADE_START = 0.78f

/** TV hero backdrop 左缘渐隐窗口起点 (图片宽度坐标 0..1, 此前全擦除). 与 [TV_BACKDROP_BOTTOM_FADE_START] 同一套. */
const val TV_BACKDROP_LEFT_FADE_START = 0.02f

// ---- 全屏 backdrop (新番时间表; 见 [TvFullScreenBackdropLayer]) ----

/** 全屏 backdrop 的整屏基础压暗强度 (页面背景色的不透明度). 调大 = 图更淡、文字更清楚. */
const val TV_FULLSCREEN_BACKDROP_DIM_ALPHA = 0.46f

/** TV hero backdrop 左缘渐隐窗口终点 (此处起图完全清晰). 与 [TV_BACKDROP_BOTTOM_FADE_START] 同一套. */
const val TV_BACKDROP_LEFT_FADE_END = 0.3f

// ---- hero 文字 ----

/**
 * 三个列表页 (探索 / 追番 / 搜索) hero 简介块的下沿离页面顶多远, 三页对齐在这条线上: 探索页 = hero 顶边 + 信息块高; 追番 / 搜索页
 * 顶上还有标签行 / 搜索栏, 简介块从它们下面开始、同样止于这条线 —— 顶上的组件只让简介少几行.
 */
val TV_HERO_TEXT_BOTTOM: Dp = 268.dp

/**
 * 海报墙 hero 态里聚焦行 (海报顶边) 离页面顶多远, 三页对齐在这条线上 —— hero 背景图三页共用一套尺寸, 行对齐了, 图压住卡片的程度才一样.
 * 探索页: 简介块下沿 [TV_HERO_TEXT_BOTTOM] 之下是聚焦行的组标题, 行在标题下面. 追番 / 搜索没有组标题, 简介块往下长, 把这一截吃掉
 * (下沿停在行上方, 留出简介到网格的那段间距). 这是参照页面高下的值, 页面更高时三页一起按 [tvHeroScaleShift] 下移.
 */
val TV_POSTER_WALL_HERO_ROW_TOP: Dp = TV_HERO_TEXT_BOTTOM + 42.dp

/**
 * hero 几何的参照页面高: 1080p 电视 100% 界面缩放时的页面高. hero 背景图按页面高的比例画, 图下面的行、组标题、按钮与简介块是按这个
 * 高度定的 dp 值, 页面更高时由 [tvHeroScaleShift] 跟着背景图下移.
 */
val TV_HERO_REFERENCE_PAGE_HEIGHT: Dp = 540.dp

/**
 * 页面高 [pageHeight] 超过参照高 [TV_HERO_REFERENCE_PAGE_HEIGHT] 时 (界面缩放调小, 或设备上报的界面尺寸偏大), hero 背景图下面的东西
 * 往下挪多少: 背景图随页面高按比例变大, 参照高下落在 [line] 的那条线 (行的海报顶边) 按同一比例下移, 始终落在背景图的同一处, 行不压住图;
 * 挪出来的高度给简介. 页面不高于参照高 (100% 及更大的缩放) 时为 0, 排版不变.
 */
fun tvHeroScaleShift(pageHeight: Dp, line: Dp): Dp =
    if (pageHeight > TV_HERO_REFERENCE_PAGE_HEIGHT) line * (pageHeight / TV_HERO_REFERENCE_PAGE_HEIGHT - 1f) else 0.dp

/**
 * hero 的背景图尺寸 / 羽化与文字宽度. 两套, 都写死: 探索页热门轮播 [TV_CAROUSEL_HERO_TUNING], 卡片 hero (探索页海报墙的 hero 态、追番、
 * 搜索三页共用) [TV_CARD_HERO_TUNING].
 *
 * 背景图: 16:9 贴右上角, 下缘与左缘同一条 smoothstep 羽化 —— 下缘从 [clearLine] 软到图底, 左缘从图的左边缘起羽化下缘深度的
 * [leftDepthRatio] 倍. 文字: 标题与简介各占内容区宽度的比例.
 */
@Immutable
data class TvHeroTuning(
    /** 背景图高占屏高. 放大进详情页的倍数 = 1 / 本值. */
    val backdropHeight: Float,
    /** 分界线 (占屏高): 背景图最后一条看得清的线, 下缘羽化从这里起. */
    val clearLine: Float,
    /** 左缘羽化深度相对下缘的倍数, 1 = 两边一样深. */
    val leftDepthRatio: Float,
    /** 标题宽度占内容区宽度的比例 (超出的跑马灯滚动). */
    val titleWidth: Float,
    /** 简介宽度占内容区宽度的比例. */
    val summaryWidth: Float,
)

/** 探索页热门轮播: 图比卡片 hero 大一档, 下面紧跟首行预览. */
val TV_CAROUSEL_HERO_TUNING: TvHeroTuning = TvHeroTuning(
    backdropHeight = 0.67f,
    clearLine = 0.48f,
    leftDepthRatio = 2.6f,
    titleWidth = 0.46f,
    summaryWidth = 0.4f,
)

/** 卡片 hero: 探索页海报墙的 hero 态、追番、搜索三页共用 (三页 hero 态的聚焦行对齐在 [TV_POSTER_WALL_HERO_ROW_TOP], 图压住卡片的程度一样). */
val TV_CARD_HERO_TUNING: TvHeroTuning = TvHeroTuning(
    backdropHeight = 0.6f,
    clearLine = 0.48f,
    leftDepthRatio = 2.6f,
    titleWidth = 0.46f,
    summaryWidth = 0.41f,
)

/**
 * 列表页 hero 背景图的遮罩几何, 由 [TvHeroTuning] 换算: 左缘与下缘同一条 smoothstep —— 下缘从分界线 ([TvHeroTuning.clearLine])
 * 软到图底, 左缘从图的左边缘起羽化下缘深度的 [TvHeroTuning.leftDepthRatio] 倍; hero 与卡片两侧一样. 换算成整张图的比例:
 * 图高 = [TvHeroTuning.backdropHeight] × 屏高, 图宽 = 图高 × 16/9.
 */
fun tvHeroBackdropGeometry(tuning: TvHeroTuning): TvPageBackdropGeometry {
    val hf = tuning.backdropHeight
    val leftDepth = ((hf - tuning.clearLine) * tuning.leftDepthRatio / (hf * TV_BACKDROP_ASPECT_RATIO)).coerceAtMost(1f)
    val bottomStart = tuning.clearLine / hf
    return TvPageBackdropGeometry(
        leftStartHero = 0f,
        leftStart = 0f,
        leftEndHero = leftDepth,
        leftEnd = leftDepth,
        bottomStartHero = bottomStart,
        bottomStart = bottomStart,
        bottomSmoothness = 1f,
    )
}

/**
 * 在两套尺寸之间过渡用的遮罩几何: hero 一侧取 [hero] 的, 卡片一侧取 [card] 的, 按 cardness 插值 (探索页海报墙: 0 = 热门轮播,
 * 1 = 聚焦卡的 hero 态). 比例都是相对整张图的, 图按图高缩放时不变.
 */
fun tvHeroBackdropGeometry(hero: TvHeroTuning, card: TvHeroTuning): TvPageBackdropGeometry {
    val h = tvHeroBackdropGeometry(hero)
    val c = tvHeroBackdropGeometry(card)
    return TvPageBackdropGeometry(
        leftStartHero = h.leftStartHero,
        leftStart = c.leftStart,
        leftEndHero = h.leftEndHero,
        leftEnd = c.leftEnd,
        bottomStartHero = h.bottomStartHero,
        bottomStart = c.bottomStart,
        bottomSmoothness = 1f,
    )
}

/** 卡片 hero 的遮罩几何 (追番 / 搜索页, 见 [TV_CARD_HERO_TUNING]). */
val TV_CARD_HERO_BACKDROP_GEOMETRY: TvPageBackdropGeometry = tvHeroBackdropGeometry(TV_CARD_HERO_TUNING)

/** TV hero 媒体 (backdrop/简介等) 请求防抖: 焦点在卡片间快速划过时不发请求. */
const val TV_HERO_MEDIA_DEBOUNCE_MILLIS = 300L

/**
 * 点卡片进详情页前, 等目标页首屏材料备齐的最长时间 (毫秒).
 *
 * 备齐了就立刻跳 (常见情形焦点在卡上停过一下, 预取早就完成, 实际等待 0ms), 备不齐则到点照跳,
 * 退化成从前的行为. 详见 `TvExplorationPage` 里 `navigateToSubject` 的注释.
 *
 * **不能再长**: 这段时间屏幕上没有任何反馈, 超过半秒就会被读成"按了没反应"而不是"在加载".
 */
const val TV_NAV_READY_BUDGET = 500L

/**
 * 一次跳转之后本页拒收后续跳转的时长 (毫秒).
 *
 * 导航发出后本页并不会立刻消失, 它要在转场动画里再活 400ms (`NavigationMotionScheme` 的
 * crossfade), 期间仍在组合、仍在收遥控器按键. 不锁的话连按两次确认就会连进两层 ——
 * 用户表现是"返回要按两下才回得来".
 *
 * 取值要盖住转场时长再留点余量; 上限则是"用户放弃并重按"的耐心. 正常情况下本页会随导航退出
 * 组合, 这个锁跟着 `remember` 一起消失, 定时解锁只是没退出组合时的自愈兜底.
 */
const val TV_NAV_LOCK_MILLIS = 800L

// ---- 卡片网格 ----

/** 竖版海报卡片的基准宽度 (外框): 继续观看进度条的长度按它算, 见 [TV_CARD_PROGRESS_BAR_LENGTH]. */
val TV_PAGE_CARD_WIDTH: Dp = 112.dp

/**
 * 网格页 (时间表网格) 卡片列距. 聚焦放大后卡片左右各伸出 (倍数 − 1) × 卡宽 / 2
 * (1.12 倍约 6.5dp), 列距要留够, 放大后与邻卡之间仍有一道看得见的缝.
 */
val TV_GRID_CARD_COLUMN_SPACING = 14.dp

/** 网格页卡片行距. 同上: 放大后上下各伸出 7.6~9dp, 离下一行仍留约 10dp. */
val TV_GRID_CARD_ROW_SPACING = 18.dp

/**
 * 网格页卡片区的向左出血 (见 [tvGridBleed]): 首列卡放大时向左伸出的那几 dp 不被网格左边界裁掉 ——
 * 网格 clipToBounds, 追番页换 tab 的滑动过渡也按这条边裁.
 */
val TV_GRID_START_BLEED = 16.dp

/** 聚焦放大倍数 ("放大 + 描边"样式). Compose for TV 的卡片与 Leanback 的中号卡都是 1.1. */
const val TV_CARD_FOCUS_SCALE = 1.10f

/** "仅放大"样式的倍数: 没有描边, 大一档补足醒目程度. */
const val TV_CARD_FOCUS_SCALE_WITHOUT_RING = 1.12f

/**
 * 聚焦格淡入淡出 / 卡片放大缩回的时长, 同 Leanback 放大的默认值 (也是 Material 小部件状态变化的短时长档).
 * 焦点反馈要跟手, 不跟 hero 文字的节奏走 (文字是停稳后的信息更新, 按键后 +510ms 才到位). 流畅档不做过渡.
 */
const val TV_CARD_FOCUS_TRANSITION_MILLIS = 150

/** 换标签时新旧两份内容整体水平滑过的时长 (完整动画档; 追番页的网格). */
internal const val TV_TAB_CONTENT_SLIDE_MILLIS = 560

/** hero 信息行 (评分 · 连载 / 标签 · 开播) 各段之间的间距. */
internal val TV_HERO_META_GAP = 16.dp

/** hero 信息行里评分的星标边长与它到分数的间距 (见 rememberTvNativeHeroTextStyle). */
internal val TV_HERO_RATING_STAR_SIZE = 18.dp
internal val TV_HERO_RATING_STAR_GAP = 4.dp

// ---- 底部提示 / 页面留白 ----

/** 贴页面底缘的提示文字 (探索页左下角的推荐重算进度) 的底部留白. */
val TV_PAGE_HINT_BOTTOM_PAD = 12.dp

/** 内容右侧留白. */
val TV_PAGE_END_PAD = 48.dp

/** 竖版封面宽高比 (与详情页封面一致; 网格行高估算也用它). */
const val TV_PORTRAIT_CARD_COVER_RATIO = 0.72f

/** 卡片圆角. */
internal val TV_PORTRAIT_CARD_CORNER = 8.dp

// 聚焦外圈的描边宽度与空隙全仓唯一一份, 见 [TvFocusRing].
//
// [TvFocusRing.Gap] 同时是**卡片外框与焦点目标之间的偏差**: 可聚焦节点是内缩后的封面, 而聚焦框按外框画.
// 按焦点目标矩形定位框或滚动位置的地方必须把它加回去, 否则框与卡片差这一点点对不齐 —— 真机肉眼可见.

/** 继续观看卡片底部集数进度条 (样式对齐详情页 FocusEpisodeProgressBar): 条厚, 同选集卡 3dp. */
internal val TV_CARD_PROGRESS_BAR_HEIGHT = 3.dp

/**
 * **进度条长度 (手调)** —— 条是定宽 + `BottomCenter` 居中放置, 左右自动等距, 改这一个数就行.
 *
 * 现值 92dp = 封面宽 (112 外框 − 2×[TvFocusRing.Gap] = 108dp) − 2×[TV_PORTRAIT_CARD_CORNER],
 * 即**底边去掉两个圆角之后的直线段长度**, 两端正好落在圆角的切点上.
 *
 * 两条边界, 调之前先看:
 * - **上限 108dp** (封面宽). 超过就被 `Surface` 裁掉.
 * - **超过 92dp 后两端会被圆角啃**: 圆角在距底 d 处的横向内切量是 `r − sqrt(r² − (r−d)²)`,
 *   d=0 时取到最大值恰为 r=8dp. 停在 92dp 等于把条卡在最坏情况的边界上, 于是条**想贴多低
 *   都不缺角**, [TV_CARD_PROGRESS_BAR_BOTTOM_GAP] 才能纯按观感调.
 *
 * 别改回"占卡宽百分之几"那种写法: 竖版卡封面 108dp、选集卡 240dp, 早先两边各写死绝对值
 * (10dp / 6dp), 竖版卡的条只占 81% 而选集卡 95%, 观感对不上 —— 现在两边同一条圆角规则.
 */
internal val TV_CARD_PROGRESS_BAR_LENGTH =
    TV_PAGE_CARD_WIDTH - TvFocusRing.Gap * 2 - TV_PORTRAIT_CARD_CORNER * 2 + 2.dp

/**
 * **进度条与卡片底边的空隙 (手调)** —— 纯观感值, 没有几何下限 (见 [TV_CARD_PROGRESS_BAR_LENGTH]),
 * 调大=整条往上抬.
 *
 * 竖版卡上取 2dp: 5dp (选集卡那档) 用户实测"太高", Prime 的条也基本贴底 (0~1dp).
 * 选集卡不跟改 —— 它那 5dp 是为了不与聚焦描边糊在一起调出来的, 两者卡高与描边观感不同.
 */
internal val TV_CARD_PROGRESS_BAR_BOTTOM_GAP = 2.dp

/** 进度条轨道 (未看部分) 的白色不透明度. */
internal const val TV_CARD_PROGRESS_TRACK_ALPHA = 0.3f

/** NSFW 打码封面的解码长边 (px), 见 [NSFW_OBSCURED_COVER_LONG_EDGE_PX]. */
internal const val TV_OBSCURED_COVER_LONG_EDGE_PX = NSFW_OBSCURED_COVER_LONG_EDGE_PX

/** NSFW 打码背景图的解码长边 (px), 见 [NSFW_OBSCURED_BACKDROP_LONG_EDGE_PX]. */
internal const val TV_OBSCURED_BACKDROP_LONG_EDGE_PX = NSFW_OBSCURED_BACKDROP_LONG_EDGE_PX

/** Hero 操作按钮圆角. */
internal val TV_HERO_BUTTON_CORNER = 8.dp

/** hero 按钮未聚焦时的描边宽度: Apple 的 1 pt (1080p 坐标, 本应用 1 pt = 0.5 dp). */
internal val TV_HERO_BUTTON_OUTLINE_WIDTH = 0.5.dp

/**
 * hero 按钮未聚焦时的描边色: 深色照 Apple TV App `Dark` 按钮样式的白 16%; 浅色取对称的黑 16% (Apple 的按钮样式不分主题, 浅色表里
 * 通用描边 hairline 是黑 10%, 这里压在剧照羽化出的灰底上要比通用描边清楚一档).
 */
internal fun tvHeroButtonOutlineColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.16f)

/**
 * hero 按钮未聚焦时的底板色, 按当前主题明暗取 (由 surface 亮度判定, 兼容手动日夜切换): 黑夜主按钮 rgb(49,54,61)、次按钮接近黑,
 * 白天对应的浅灰两档; 海报墙页面 ([posterWall], 见 TvPosterWallTheme) 换成海报墙配色里 Apple 那几档灰, 主按钮仍亮一档 —— 深色是
 * Gray3 / Gray4, 浅色是白 20% (Apple 浅色按钮底板, surfaceContainer) / 白 10%.
 */
internal fun tvHeroButtonContainerColor(colors: ColorScheme, filled: Boolean, posterWall: Boolean): Color {
    val dark = colors.surface.luminance() < 0.5f
    return when {
        posterWall && filled && dark -> colors.surfaceContainerHigh
        posterWall && filled -> colors.surfaceContainer
        posterWall -> colors.surfaceContainerLow
        dark && filled -> Color(0xFF31363D)
        dark -> Color(0xFF17191C)
        filled -> Color(0xFFDBE0E6)
        else -> Color(0xFFF1F3F6)
    }
}

/** 操作按钮整体缩放比例 (内边距/图标/字号统一乘此值). 调小让按钮更紧凑. */
internal const val TV_HERO_BUTTON_SCALE = 0.9f

/** 操作按钮缩放前的内边距、图标与图标到字的间距: 对齐 Prime 实测 (单行按钮 30.5dp 高, 水平留白 ~13dp, 垂直墨迹留白 ~9dp). */
internal val TV_HERO_BUTTON_PADDING_HORIZONTAL = 14.dp
internal val TV_HERO_BUTTON_PADDING_VERTICAL = 8.dp
internal val TV_HERO_BUTTON_ICON_SIZE = 20.dp
internal val TV_HERO_BUTTON_ICON_GAP = 8.dp

/** hero 操作按钮离场淡出时长 (焦点进卡片区): 要快 —— 离场行会从按钮区域上滑过. */
internal const val TV_HERO_BUTTON_FADE_OUT_MILLIS = 90

/** hero 操作按钮回场淡入时长. 起点是"卡片区已滚回顶部", 可以从容一点. */
internal const val TV_HERO_BUTTON_FADE_IN_MILLIS = 150
