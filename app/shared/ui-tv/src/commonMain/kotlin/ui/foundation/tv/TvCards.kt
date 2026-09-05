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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import me.him188.ani.app.ui.foundation.resize
import me.him188.ani.app.ui.foundation.themeColor
import me.him188.ani.app.ui.foundation.theme.SubjectSeedColorCache
import me.him188.ani.app.ui.foundation.theme.subjectSeedColor
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.external.placeholder.PlaceholderHighlight
import me.him188.ani.app.ui.external.placeholder.fade
import me.him188.ani.app.ui.external.placeholder.placeholder
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState
import me.him188.ani.app.ui.foundation.rememberImageCompletionGrace
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tvLongPressKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * TV 竖版封面卡片 (探索页 / 追番页共用): 聚焦时主题主色外圈 (外圈与封面之间留一圈空隙,
 * 不需要动态取色). [imageUrl] 为 null 时显示加载占位. 长按 (遥控器确定键长按 / 触屏长按)
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
     * false 时聚焦不自绘外圈 —— 固定锚点轮播行 (探索页) 用: 聚焦框由行叠放的
     * [TvPortraitCardFocusRing] 钉在锚位统一画, 卡片只在框下滑动 (Prime Video 式).
     * 网格页 (追番/搜索/时间表) 焦点在二维空间移动, 保持默认自绘.
     */
    showFocusRing: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }
    val setMenuExpanded = { value: Boolean ->
        if (menuExpanded != value) {
            menuExpanded = value
            onMenuExpandedChange?.invoke(value)
        }
    }
    Box(
        modifier
            .aspectRatio(TV_PORTRAIT_CARD_COVER_RATIO)
            // 自绘外圈 (焦点态只在绘制阶段读, 不牵动整卡重组, 见 tvFocusRing);
            // 固定框模式下由行叠放的 TvPortraitCardFocusRing 统一画, 这里整条跳过
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
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
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
                    val loaded = rememberImageCompletionGrace(imageUrl)
                    AsyncImage(
                        if (retry.suppressed) null else imageUrl,
                        contentDescription = contentDescription,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = { loaded.value = true },
                        onError = { retry.onError() },
                    )
                } else {
                    // 非完整视觉效果档时骨架不脉动 (highlight=null). 无限 fade 高亮把动画值
                    // 读进组合 (thirdparty placeholder 旧 accompanist 写法), 一屏几十张骨架卡
                    // = 首屏加载最忙时段每帧几十次重组; 搜索页 NSFW/隐藏条目 imageUrl 恒为
                    // null, 不关的话那些卡永远在跑
                    val fullEffects = LocalThemeSettings.current.tvFullVisualEffects
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
        if (menu != null) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                menu(menuExpanded) { setMenuExpanded(false) }
            }
        }
    }
}

/**
 * [TvPortraitCard] 的固定锚位聚焦框 (Prime Video 式): 叠放在固定锚点轮播区的锚位上,
 * 框钉着不动, 导航时只有卡片列表在框下滑动 (卡片传 showFocusRing=false 关掉自绘外圈).
 * 尺寸与描边几何与卡片自绘外圈完全一致, 卡片停靠到位后与自绘视觉无差.
 */
@Composable
fun TvPortraitCardFocusRing(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(TV_PAGE_CARD_WIDTH)
            .aspectRatio(TV_PORTRAIT_CARD_COVER_RATIO)
            .tvFocusRingBorder(TV_PORTRAIT_CARD_CORNER + TvFocusRing.Gap, TvFocusRing.defaultBrush),
    )
}

/**
 * TV Hero 操作按钮 (立即观看 / 更多详细内容 / 继续观看等). 两枚都是深/浅灰实心
 * (参考 Prime: 主按钮略亮, 次按钮接近底色), 按白天/黑夜主题分别取色. 聚焦时整颗按钮
 * 高亮为主题主色、文字/图标反色 (onPrimary), 与侧边栏选中一致.
 * [filled] = true 为主按钮 (略亮一档). 图标 + 文字单行.
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
    val focused by interactionSource.collectIsFocusedAsState()
    // 按当前主题明暗取底色 (由 surface 亮度判定, 兼容手动日夜切换):
    // 黑夜: 主按钮 rgb(49,54,61), 次按钮接近黑; 白天: 对应的浅灰两档.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val baseContainer = when {
        dark && filled -> Color(0xFF31363D)
        dark -> Color(0xFF17191C)
        filled -> Color(0xFFDBE0E6)
        else -> Color(0xFFF1F3F6)
    }
    val container = if (focused) MaterialTheme.colorScheme.primary else baseContainer
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
                if (it.isFocused) onFocused()
                onFocusChangedExtra?.invoke(it.isFocused)
            },
        shape = RoundedCornerShape(TV_HERO_BUTTON_CORNER),
        color = container,
        interactionSource = interactionSource,
    ) {
        Row(
            // 内边距对齐 Prime 实测 (单行按钮 30.5dp 高, 水平留白 ~13dp, 垂直墨迹留白 ~9dp)
            Modifier.padding(horizontal = 14.dp * scale, vertical = 8.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
        ) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp * scale), tint = content)
            Text(text, color = content, style = textStyle, maxLines = 1)
        }
    }
}

/**
 * hero 区常驻文本跑马灯的迭代次数: [ThemeSettings.tvFullVisualEffects] 关闭时滚固定次数后
 * 停在行首 —— 无限迭代让页面永远无法进入"无脏区"静止态 (溢出的文字行每帧重绘 + 整帧重合成,
 * 也阻止合成器跳帧省电), 是低端设备的常驻底噪. 换条目时文本重建, 会重新滚够次数, 信息不丢失.
 * 聚焦才出现的跑马灯 (单实例、用户明确在看) 不受此限.
 */
@Composable
fun tvHeroMarqueeIterations(): Int =
    if (LocalThemeSettings.current.tvFullVisualEffects) Int.MAX_VALUE else TV_HERO_MARQUEE_REDUCED_ITERATIONS

/** 非完整视觉效果档时 hero 跑马灯的滚动次数. */
private const val TV_HERO_MARQUEE_REDUCED_ITERATIONS = 3

/**
 * hero 展示目标的**换挡合并**: [ThemeSettings.tvFullVisualEffects] 关闭时, hero (背景图 + 文字块)
 * 按 [TvNavigationSettle] 的前沿节流换挡 —— 空闲后的单次移动立即换, 连发期间最多每
 * [TV_HERO_SWAP_SETTLE_MILLIS] 换一次, 停下来时最迟同样时长换到最后聚焦的那个;
 * 开启时原样直通 (每格键都换).
 *
 * **卡片自身的动画完全不受影响** —— 滚动、压暗、淡出、固定聚焦框走的是另一条路 (位置驱动的
 * graphicsLayer), 这里只推迟"背景图 + hero 文字"这两块整屏级的内容替换.
 *
 * ## 为什么值得做 (2026-08-13 索尼 BRAVIA v7a 实测)
 *
 * 每换一次 hero 会同时启动 600ms 的 backdrop `Crossfade` 与 500ms 的文字 `AnimatedContent`
 * 淡入淡出; 两者时长都远长于连发间隔 (250ms), 于是**连续导航时它们永不结束**, 应用被迫连续
 * 产帧, 且淡入淡出期间新旧两份内容同时存在 (两块约 1.2MP 的图层 + 两棵 CJK 文本树, 默认
 * `CompositingStrategy.Auto` 还各要一遍离屏缓冲, 见 `tvRowTopFade` 里对规则 9 的说明).
 *
 * 12 次方向键的实测帧数: 原样 **98 帧**, 把两处淡入淡出时长改 0 后 **12 帧** —— 也就是每按一格
 * 键要陪跑约 8 帧 (该机每帧约 20ms), 而这 8 帧画的全是"背景图和文字正在互相淡入淡出".
 * 合并换挡把这份开销压到"每停一次一份".
 *
 * @param target 焦点驱动的真实目标 (每格键都变). 数据预取仍应读它, 不要读返回值 —— 停下来时
 * 数据已在缓存里, 换挡才不会跟着等网络.
 */
@Composable
fun <T> rememberTvSettledHero(target: T): T = rememberTvSettledHeroProvider { target }.invoke()

/**
 * 遥控器连发下的**前沿节流**闸门: 只回答"这一拍要不要等一等".
 *
 * 规则:
 * - **空闲之后的第一拍立即放行** —— 一次深思熟虑的单击不该为连发付延迟, 这是它与 `debounce`
 *   的全部区别 (防抖对单击也要等满);
 * - 连发期间最多每 [settleMillis] 放行一次, 中间划过去的目标由 `collectLatest` 取消掉;
 * - 停下来时最迟 [settleMillis] 放行最后那个目标.
 *
 * **必须配 `collectLatest`** (或别的会取消上一拍的收集器): [awaitTurn] 靠"被取消"丢掉中间目标,
 * 自己不做任何去重.
 *
 * hero 的展示换挡 ([rememberTvSettledHeroProvider]) 与四个 TV 页的媒体预取共用这一条规则, 是
 * 故意的: 两边错开的话, 要么预取把带宽花在划过去的卡上, 要么展示已经换到 B 而预取还停在 A ——
 * 后者正是"停下来还要再等一次网络"的来源.
 */
internal class TvNavigationSettle(private val settleMillis: Long) {
    private var lastPassMark: TimeMark? = null

    /**
     * @param bypass 这一拍不必合并, 直接放行 (例如屏幕上还什么都没有: 从无到有没有可合并的对象,
     * 按连发处理的话内容要凭空晚 [settleMillis] 才出现). 仍然记一次放行时刻.
     */
    suspend fun awaitTurn(bypass: Boolean = false) {
        val mark = lastPassMark
        if (!bypass && mark != null && mark.elapsedNow() < settleMillis.milliseconds) {
            delay(settleMillis)
        }
        lastPassMark = TimeSource.Monotonic.markNow()
    }
}

/**
 * [rememberTvSettledHero] 的 **provider 版本**: 收 `() -> T`、还 `() -> T`, 全程不在调用方的
 * composable body 里读热状态.
 *
 * ## 为什么需要它
 *
 * 值版本要求调用方先把 hero 目标读出来 (`val display = rememberTvSettledHero(heroTarget)`),
 * 那次读记在调用方 body 上 —— 页面级 composable 于是每换一张卡就整体重跑一遍. 而追番/搜索/
 * 时间表三页的 hero 状态本来就是**只在子组件的 lambda 里读**的 (backdrop 层与 hero 信息块都收
 * provider, 正是为了让换卡只重组那一小块), 套值版本等于把这份优化推翻.
 *
 * 本版本把读取全部关在两个不属于组合的地方: 种子值用 `withoutReadObservation` 读一次, 之后
 * 由 `snapshotFlow` 在协程里观察. 返回的 lambda 只读一个普通 `MutableState`, 谁调用谁订阅.
 *
 * @param target 焦点驱动的真实目标 (每格键都变). 数据预取仍应读它, 不要读返回值 —— 停下来时
 * 数据已在缓存里, 换挡才不会跟着等网络.
 */
@Composable
fun <T> rememberTvSettledHeroProvider(target: () -> T): () -> T {
    val fullEffects = LocalThemeSettings.current.tvFullVisualEffects
    // lambda 每次重组换新实例, 必须经 rememberUpdatedState 再进 snapshotFlow, 否则永久留住首帧值
    val latest = rememberUpdatedState(target)
    // 种子值必须"不被观察地"读: 直接 target() 会把热状态的读算到调用方的 body 上, 那正是本函数
    // 要避免的事
    val settled = remember { mutableStateOf(Snapshot.withoutReadObservation { target() }) }
    LaunchedEffect(fullEffects) {
        if (fullEffects) {
            // 完整特效档不合并 (原样直通), 但仍写进同一个 State: 返回的 provider 只有一种读法,
            // 两档之间不会出现"有时读热状态有时读快照"的分叉
            snapshotFlow { latest.value.invoke() }.collect { settled.value = it }
            return@LaunchedEffect
        }
        // 与四个 TV 页的媒体预取共用同一条节流规则, 见 [TvNavigationSettle]
        val settle = TvNavigationSettle(TV_HERO_SWAP_SETTLE_MILLIS)
        snapshotFlow { latest.value.invoke() }.collectLatest { value ->
            // 屏幕上**还什么都没有**时不合并: 进页面的头两拍是 null -> 首个条目, 两者间隔远小于
            // 静默期, 按连发处理的话 hero 要凭空晚一个静默期才出现. 合并是为了不让两份真内容
            // 来回切, 从无到有没有可合并的对象.
            settle.awaitTurn(bypass = settled.value == null)
            settled.value = value
        }
    }
    return remember { { settled.value } }
}

/**
 * TV hero 区标题/正文文字色 (对齐 Prime 实测): 黑夜 #F1F1F1 —— 亮中性白, 无色相、无投影
 * (实测字形边缘无暗晕, 可读性靠文字够亮 + backdrop 渐隐压暗). M3 的 onSurface/onSurfaceVariant
 * 偏暗且带紫色相, 在深色 backdrop 上显得发糊. 白天用等效中性深灰.
 */
@Composable
fun tvHeroContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFF1F1F1) else Color(0xFF1A1C1E)

/** TV hero 区次要信息文字色 (连载信息/日期等, 对齐 Prime 实测): 黑夜 #B4B5B7 中性灰; 白天等效. */
@Composable
fun tvHeroSecondaryContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFB4B5B7) else Color(0xFF5B5D60)

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
): Array<Pair<Float, Color>> = Array(samples + 1) { i ->
    val f = i / samples.toFloat()
    val s5 = f * f * f * (f * (f * 6f - 15f) + 10f)
    (start + (end - start) * f) to color.copy(alpha = 1f - (1f - s5).pow(power))
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
 * TV 页面背景 backdrop 层 (探索 / 追番 / 搜索三页共用): 16:9 贴右上角, 高度为屏高固定比例
 * ([heightFraction]), 顶缘轻度压暗 + 左缘/下缘渐隐入页面背景. 调用方通常
 * `Modifier.align(Alignment.TopEnd)`.
 *
 * [fadeColor] 必须传**图层正下方的实际页面底色** (追番页是 shellBackgroundColor, 搜索页是
 * colorScheme.background): 渐隐是直接在图上叠画该色的渐变 (SrcOver). 旧实现用
 * DstOut 擦除露底色, 视觉等价但要求整块图层先渲染进离屏缓冲
 * (CompositingStrategy.Offscreen, 4K 下 ~14MB、每次换图重光栅化) —— 低端 GPU 上是
 * 白付的填充率 (2026-07-31 性能整改).
 *
 * [backdropUrl] 用 lambda 而非值传入: URL 由"聚焦条目"状态推导, 状态读取发生在本组件
 * 内部 —— 遥控器每移一格只重组这一小块, 不连带调用方整个页面作用域重组.
 *
 * [cardness] 同理用 lambda: 它是动画值, 读取发生在**绘制** lambda 里 (draw 观察快照读, 值一变
 * 只重绘不重组), 值传入的话每帧都要重组一次本组件.
 *
 * @param heightFraction 图层高度占屏高比例. 探索页因轮播布局单独一档.
 * @param topScrim 顶缘可读性压暗带. 顶部压着悬浮文字的页面 (追番/搜索) 需要; 探索页顶部是空的.
 * @param cardness 0 = hero 态 (下缘收得晚、左缘浅), 1 = 卡片态. 只有探索页在两态间插值 (焦点在
 * hero / 在卡片区), 其余页恒为卡片态, 于是默认 `{ 1f }` 下两处 lerp 恰好退化成常量本身.
 */
@Composable
fun TvPageBackdropLayer(
    backdropUrl: () -> String?,
    fadeColor: Color,
    modifier: Modifier = Modifier,
    heightFraction: Float = TV_BACKDROP_HEIGHT_FRACTION,
    topScrim: Boolean = true,
    cardness: () -> Float = { 1f },
    /**
     * 垫底图: 主图 (backdropUrl) 下载太慢时先显示的应急底图 (探索页传"聚焦超过 2.5s 主图还
     * 没上屏"时的竖版封面). 画在主图**下面**, 主图一旦解码完成自然盖住它 —— 不需要"主图已
     * 上屏"的回调信号. null = 不垫 (默认, 其余页不受影响).
     */
    underlayUrl: () -> String? = { null },
    /**
     * 非 null 时: 这张 backdrop 解码完之后顺手算出条目主色写进 [SubjectSeedColorCache], 于是
     * 点进详情页第一帧就是动态色, 不再"先主题色再跳".
     *
     * 传当前 hero 那个条目的 id. **详情页的主题色取的就是这张 backdrop** (同一条
     * `tvHeroBackdropUrl` 解析规则, 没有横版图时两边一起回落到竖版封面), 所以两边同源;
     * 换成别的图 (比如卡片的竖版封面) 会从"跳一次"变成"跳成另一个色".
     *
     * 时机天然就是"焦点停稳": hero 的图本来就要等 [TV_HERO_MEDIA_DEBOUNCE_MILLIS] 才发请求,
     * 长按方向键飞掠过去的条目根本走不到这里.
     */
    themeSeedSubjectId: () -> Int? = { null },
) {
    Crossfade(
        backdropUrl(),
        modifier,
        animationSpec = tween(TV_BACKDROP_CROSSFADE_MILLIS),
    ) { url ->
        if (url != null) {
            Box(
                Modifier
                    .fillMaxHeight(heightFraction)
                    .aspectRatio(TV_BACKDROP_ASPECT_RATIO, matchHeightConstraintsFirst = true)
                    .drawWithContent {
                        drawContent()
                        // 停点由平滑曲线采样生成 (无折点, 避免暗色端可见的马赫带分界线);
                        // 渐变带端点在 hero / 卡片两态间插值, 曲线形状两态共用.
                        val t = cardness()
                        // 顶缘轻度压暗 (非全遮): 给悬浮在 backdrop 上的顶部文字一层可读性 scrim
                        if (topScrim) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    *tvBackdropFadeFromBlackStops(
                                        start = 0f, end = TV_BACKDROP_TOP_SCRIM_END,
                                        maxAlpha = TV_BACKDROP_TOP_SCRIM_ALPHA,
                                        color = fadeColor,
                                    ),
                                ),
                            )
                        }
                        drawRect(
                            brush = Brush.horizontalGradient(
                                *tvBackdropFadeFromBlackStops(
                                    start = lerp(0f, TV_BACKDROP_LEFT_FADE_START, t),
                                    end = lerp(TV_BACKDROP_LEFT_FADE_END_HERO, TV_BACKDROP_LEFT_FADE_END, t),
                                    color = fadeColor,
                                ),
                            ),
                        )
                        // 下缘渐隐: 零斜率极缓起步 + 指数级长尾渐近全遮, 一直渐变到图底
                        drawRect(
                            brush = Brush.verticalGradient(
                                *tvBackdropFadeToBlackStops(
                                    start = lerp(TV_BACKDROP_BOTTOM_FADE_START_HERO, TV_BACKDROP_BOTTOM_FADE_START, t),
                                    end = 1f,
                                    color = fadeColor,
                                ),
                            ),
                        )
                    },
            ) {
                // 应急垫底 (见参数说明): 与主图同裁切, 同受上面的渐隐/scrim 遮罩.
                // 半透明是刻意的: 垫的是竖版封面 Crop 进 16:9, 几倍上采样, 满不透明时糊得
                // 一眼可辨、还会被误当成"这就是背景图". 压到这个透明度后它更像一层氛围底色,
                // 真图一到照样盖住 —— 目的只是别让 hero 全黑, 不是冒充 backdrop
                underlayUrl()?.let { underlay ->
                    AsyncImage(
                        underlay,
                        contentDescription = null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = TV_BACKDROP_UNDERLAY_ALPHA,
                    )
                }
                // 交叉淡入期间新旧两张图共存: 条目 id 必须在**这张图开始加载那一刻**取
                // (remember(url)), 否则旧图加载完时读到的是新条目的 id, 色就串了
                TvBackdropImage(url, remember(url) { themeSeedSubjectId() })
            }
        }
    }
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
private fun TvBackdropImage(url: String, themeSeedSubjectId: Int? = null) {
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
    AsyncImage(
        url,
        contentDescription = null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        onSuccess = { success ->
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
 * 与 [TvPageBackdropLayer] (16:9 贴右上, 只占屏顶七成) 的区别: 本页整屏都铺着卡片与小字,
 * 因此整屏压暗; 也正因为压暗是均匀的一层, 左缘不再额外补 scrim —— 横向渐变收尾处总会留下
 * 一条肉眼可见的边界, 而侧边栏的白图标压在整屏压暗上本来就够清楚.
 *
 * 遮罩一律用页面背景色而非黑色: 黑色遮罩在浅色主题下会把整页压暗, 迫使文字改用白色 (详情页
 * 就是这么做的 —— 它首屏只有一个标题浮在图上); 而本页文字铺满全屏、焦点每移一格就换图,
 * 逐图切换文字明暗会闪. 用背景色遮罩后, 深色主题下等效于原来的黑色遮罩, 浅色主题下是一层白纱,
 * 两种主题都能直接用 [tvHeroContentColor] 那套随主题取色的文字色.
 *
 * [backdropUrl] 用 lambda 传入, 理由同 [TvPageBackdropLayer].
 *
 * 只能用在**整屏归自己**的页面上 (新番时间表是独立目的地): 主壳内的页面被让开了侧边栏那一条,
 * 而主页三个 tab 的 AnimatedContent 会把内容裁在这个边界上 (MainScreen 的 topLevelTransition
 * 用的是默认 SizeTransform, clip = true), 从内容侧无论怎么向左出血都会被裁掉.
 */
@Composable
fun TvFullScreenBackdropLayer(
    backdropUrl: () -> String?,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    Crossfade(
        backdropUrl(),
        modifier,
        animationSpec = tween(TV_BACKDROP_CROSSFADE_MILLIS),
    ) { url ->
        if (url != null) {
            // 不做底缘渐隐: 本页整屏都是内容, 渐隐带那一段会被擦成纯背景色 —— 在实机上就是
            // 屏幕最下面横着一条黑边. 它原本是为了托住右下角那行遥控提示, 提示已经去掉了.
            // 图铺满整屏, 均匀压暗一层就够 (与 16:9 那版不同: 那版图只占屏顶七成, 渐隐带落在
            // 屏幕中段, 是图与背景之间的过渡, 不是一条贴着屏底的边)
            Box(Modifier.fillMaxSize()) {
                TvBackdropImage(url)
                // 整屏基础压暗: 亮部海报上压不住灰色小字. 这是唯一一层压暗 —— 左缘不再额外补
                // scrim: 任何"从左缘衰减到透明"的横向渐变都会在收尾处留下一条肉眼可见的边界,
                // 而侧边栏图标压在这层整屏压暗上本来就足够清楚 (白图标 + 深底)
                Box(
                    Modifier.fillMaxSize()
                        .background(background.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA)),
                )
            }
        }
    }
}

// ============ TV 沉浸式页面 (探索/追番/搜索) 共享调参 ============
// 探索页轮播 (hero) 态的参数不在此列, 单独放在 TvExplorationPage 里.

// ---- backdrop ----

/** backdrop 宽高比. */
const val TV_BACKDROP_ASPECT_RATIO = 16f / 9f

/** backdrop 高度占屏高比例 (追番/搜索; 探索页因轮播布局单独一档). */
const val TV_BACKDROP_HEIGHT_FRACTION = 0.70f

/** backdrop 换图的淡入淡出时长 (毫秒). */
const val TV_BACKDROP_CROSSFADE_MILLIS = 600

/** backdrop 顶缘压暗带终点 (图片高度坐标 0..1; 顶部悬浮文字的可读性 scrim). */
const val TV_BACKDROP_TOP_SCRIM_END = 0.16f

/** backdrop 顶缘压暗强度 (1 = 完全擦除). */
const val TV_BACKDROP_TOP_SCRIM_ALPHA = 0.7f

/**
 * 应急垫底图 (竖版封面) 的不透明度, 见 [TvPageBackdropLayer] 的 `underlayUrl`.
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
private const val TV_BACKDROP_PREFETCH_HANDOFF_MILLIS = 1_000L

/**
 * backdrop 下缘渐隐起点 (图片高度坐标 0..1, 此处开始向下渐暗, 一直渐变到图底).
 * 卡片聚焦态共用; hero 态那一档见 [TV_BACKDROP_BOTTOM_FADE_START_HERO].
 */
const val TV_BACKDROP_BOTTOM_FADE_START = 0.78f

/**
 * backdrop 下缘渐隐起点的 **hero 态**一档 (探索页焦点在轮播按钮上时): 比卡片态收得晚,
 * 图露得更多. 只有探索页会取到这一端, 其余页 [TvPageBackdropLayer] 的 `cardness` 恒为 1.
 */
const val TV_BACKDROP_BOTTOM_FADE_START_HERO = 0.88f

/**
 * TV hero backdrop 左缘渐隐窗口起点 (图片宽度坐标 0..1, 此前全擦除).
 * 探索 (卡片态) / 追番 / 搜索三页共用, 改这里三页一起变.
 */
const val TV_BACKDROP_LEFT_FADE_START = 0.02f

// ---- 全屏 backdrop (新番时间表; 见 [TvFullScreenBackdropLayer]) ----

/** 全屏 backdrop 的整屏基础压暗强度 (页面背景色的不透明度). 调大 = 图更淡、文字更清楚. */
const val TV_FULLSCREEN_BACKDROP_DIM_ALPHA = 0.46f

/** TV hero backdrop 左缘渐隐窗口终点 (此处起图完全清晰). 三页共用. */
const val TV_BACKDROP_LEFT_FADE_END = 0.3f

/**
 * 左缘渐隐窗口终点的 **hero 态**一档: 焦点在轮播按钮上时左缘擦得更宽 (那一片压着 hero 文字块,
 * 需要更长的过渡才读得清). 取到这一端的只有探索页, 见 [TV_BACKDROP_BOTTOM_FADE_START_HERO].
 */
const val TV_BACKDROP_LEFT_FADE_END_HERO = 0.46f

// ---- hero 文字 ----

/** TV hero 标题占屏宽比例 (右侧留给 backdrop 清晰区). */
const val TV_HERO_TITLE_WIDTH_FRACTION = 0.5f

/** TV hero 简介/状态行文字占内容列宽比例 (右边界之外留给 backdrop 清晰区). 三页共用. */
const val TV_HERO_SUMMARY_WIDTH_FRACTION = 0.4f

/** TV hero 信息块换条目时文字的渐隐渐现时长 (毫秒). */
const val TV_HERO_TEXT_FADE_MILLIS = 500

/** TV hero 媒体 (backdrop/简介等) 请求防抖: 焦点在卡片间快速划过时不发请求. */
const val TV_HERO_MEDIA_DEBOUNCE_MILLIS = 300L

/**
 * hero 展示内容 (背景图 + 文字块) 换条目的**按键静默期**: 见 [rememberTvSettledHero].
 *
 * 必须长于长按连发的最短间隔 (`tvFocusMoveRateLimit` 横向 4 次/秒 = 250ms), 否则连发期间仍会
 * 中途换一次.
 */
const val TV_HERO_SWAP_SETTLE_MILLIS = 300L

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

/** 竖版海报卡片宽度 (Adaptive 网格按此为最小宽度自动决定列数). */
val TV_PAGE_CARD_WIDTH: Dp = 112.dp

/** 卡片间距. */
val TV_PAGE_CARD_SPACING = 10.dp

// ---- 底部遮罩 / 右下角提示 / 页面留白 ----

/** 底缘渐变遮罩高度 (覆盖被视口截断的下一行卡片露出的整段). */
val TV_PAGE_BOTTOM_SCRIM_HEIGHT = 90.dp

/** 底缘遮罩在最底边的不透明度 (1 = 底边完全融入页面背景). */
const val TV_PAGE_BOTTOM_SCRIM_MAX_ALPHA = 0.95f

/** 右下角遥控键提示的底部留白. */
val TV_PAGE_HINT_BOTTOM_PAD = 12.dp

/** 右下角遥控键提示的图标尺寸. */
val TV_PAGE_HINT_ICON_SIZE = 14.dp

/** 内容右侧留白. */
val TV_PAGE_END_PAD = 48.dp

/** 竖版封面宽高比 (与详情页封面一致; 网格行高估算也用它). */
const val TV_PORTRAIT_CARD_COVER_RATIO = 0.72f

/** 卡片圆角. */
private val TV_PORTRAIT_CARD_CORNER = 8.dp

// 聚焦外圈的描边宽度与空隙全仓唯一一份, 见 [TvFocusRing].
//
// [TvFocusRing.Gap] 同时是**卡片外框与焦点目标之间的偏差**: 可聚焦节点是内缩后的封面, 而
// [TvPortraitCardFocusRing] 画在外框上. 于是"把聚焦项滚到锚位"的 pivot 式 `BringIntoViewSpec`
// (拿到的是焦点目标矩形) 必须把锚加上它, 否则框与卡片差这一点点对不齐 —— 真机肉眼可见
// (2026-08-10 探索页踩到), 见 TvExplorationPage 的 tvAnchorBringIntoViewSpec 调用处.

/** 继续观看卡片底部集数进度条 (样式对齐详情页 FocusEpisodeProgressBar): 条厚, 同选集卡 3dp. */
private val TV_CARD_PROGRESS_BAR_HEIGHT = 3.dp

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
private val TV_CARD_PROGRESS_BAR_LENGTH =
    TV_PAGE_CARD_WIDTH - TvFocusRing.Gap * 2 - TV_PORTRAIT_CARD_CORNER * 2 + 2.dp

/**
 * **进度条与卡片底边的空隙 (手调)** —— 纯观感值, 没有几何下限 (见 [TV_CARD_PROGRESS_BAR_LENGTH]),
 * 调大=整条往上抬.
 *
 * 竖版卡上取 2dp: 5dp (选集卡那档) 用户实测"太高", Prime 的条也基本贴底 (0~1dp).
 * 选集卡不跟改 —— 它那 5dp 是为了不与聚焦描边糊在一起调出来的, 两者卡高与描边观感不同.
 */
private val TV_CARD_PROGRESS_BAR_BOTTOM_GAP = 2.dp

/** 进度条轨道 (未看部分) 的白色不透明度. */
private const val TV_CARD_PROGRESS_TRACK_ALPHA = 0.3f

/** Hero 操作按钮圆角. */
private val TV_HERO_BUTTON_CORNER = 8.dp

/** 操作按钮整体缩放比例 (内边距/图标/字号统一乘此值). 调小让按钮更紧凑. */
private const val TV_HERO_BUTTON_SCALE = 0.9f
