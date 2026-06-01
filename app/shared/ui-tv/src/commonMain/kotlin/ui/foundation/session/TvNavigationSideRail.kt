/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.getIcon
import me.him188.ani.app.navigation.getText
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.playback.LocalPlaybackSessionEntry
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_search
import me.him188.ani.app.ui.lang.login_sign_in
import me.him188.ani.app.ui.lang.playback_session_now_playing
import me.him188.ani.app.ui.lang.settings
import me.him188.ani.app.ui.user.SelfInfoUiState
import org.jetbrains.compose.resources.stringResource

/** TV 可展开左侧导航栏的默认尺寸. */
object TvNavigationRailDefaults {
    /**
     * 收起态占位宽度 (= start 16 + 图标 32): 调用方据此把内容右移让开收起的图标列.
     * 取 48dp 使内容左缘与详情页内容 (TV contentHorizontalPadding = 48dp) 对齐.
     */
    val CollapsedWidth = 48.dp
}

/** 侧边栏单个条目: 图标 + 文字, [selected] 时以次要容器色标记当前项. */
@Immutable
data class TvNavRailItem(
    val icon: ImageVector,
    val label: String,
    /**
     * 非空时替代 [icon] 画字形 (容器与聚焦底色仍由 [TvRailGlyphBox] 统一负责, 内容色经
     * `LocalContentColor` 下发, 照着它画就自动跟随聚焦态; 要自己按聚焦与否换色的用参数里那个
     * `focused`, 不要拿 `LocalContentColor` 去比对颜色值).
     *
     * 给"图标本身要表达状态"的条目用 (目前只有"正在播放"的均衡条). 状态**读在这个 lambda 里面**,
     * 不要读在 [buildTvRailItems] 的 body 里 —— 那会让状态每变一次就重组整个侧边栏.
     */
    val iconContent: (@Composable (focused: Boolean) -> Unit)? = null,
    /** true 时焦点进入侧边栏总是落到该条目上 (整栏至多标记一个, 如"探索"). */
    val defaultFocus: Boolean = false,
    /** 非 null 时把此 FocusRequester 挂到该条目 (如初始/切页后把焦点落到当前项). */
    val focusRequester: FocusRequester? = null,
    /**
     * true 时点击后**不**清焦点. 默认清是为了切页 (见 [TvRailIconItem] 里的注释);
     * 不切页、只是就地开个弹窗的条目 (如"一起看") 必须留住焦点, 否则弹窗关掉后
     * 焦点回不到本条目上.
     */
    val keepFocusOnClick: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 头像的关联动作 (编辑资料/播放记录/退出登录): 焦点在头像簇上时浮现于头像上方.
 *
 * 曾经条目也能带一簇往**下**长的同款按钮 (给"正在播放"的关闭用), 2026-08-16 随那个条目一起
 * 删掉了 —— 后台会话的入口与状态都收进了长按返回的动作面板 (见 TvActionPanelDialog),
 * 侧边栏不再承担它. 现在只剩头像这一簇, 所以 [TvRailFloatingActionCluster] 也只往上长.
 */
@Immutable
data class TvRailAvatarAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * 组装主页与详情页侧边栏共用的条目列表 (搜索 + 主页各 tab + 设置), 两处只差点击行为
 * (主页直接切 tab, 详情页先弹回主页再切). 焦点进入侧边栏时总是落到"探索"上.
 */
@Composable
fun buildTvRailItems(
    onSearch: () -> Unit,
    onNavigateToPage: (MainScreenPage) -> Unit,
    onSettings: () -> Unit,
): List<TvNavRailItem> = buildList {
    add(
        TvNavRailItem(
            icon = Icons.Rounded.Search,
            label = stringResource(Lang.exploration_search),
            onClick = onSearch,
        ),
    )
    for (entry in MainScreenPage.visibleEntries) {
        add(
            TvNavRailItem(
                icon = entry.getIcon(),
                label = entry.getText(),
                defaultFocus = entry == MainScreenPage.Exploration,
                onClick = { onNavigateToPage(entry) },
            ),
        )
    }
    add(
        TvNavRailItem(
            icon = Icons.Rounded.Settings,
            label = stringResource(Lang.settings),
            onClick = onSettings,
        ),
    )
    // 这里曾经还有一颗"一起看", 2026-08-17 挪进长按返回的动作面板 (与播放器胶囊行末尾那颗一起
    // 承担悬浮气泡原本的入口作用). 侧边栏那颗的路径是"按左 + 一路往下按到最底", 而面板是一个
    // 手势就到; 播放器内够不到面板, 但那里本来就有胶囊行那颗.

    // **"正在播放"**: 2026-08-16 删掉过一次 (理由是"一个看起来能点的东西会把导航吸过去", 而
    // 状态与入口都收进了动作面板), 2026-08-18 加回来 —— 真机用下来"界面上看得出后台在播"这件事
    // 值得那点注意力成本, 而面板要一个手势才看得到.
    //
    // 与删掉之前有三处不同:
    //
    // 1. **不再提供"关闭"** (那套往下浮出的按钮机制没有跟着回来): 关会话不常用, 面板里有;
    //    没有浮出按钮也就不必把整列居中的**下端预留高度**加回来 (见 TvNavigationSideRail 的 topReserve).
    // 2. **图标本身就是状态** (见 [TvNowPlayingRailGlyph]): 静态的 PlayCircle 只说明"有个入口",
    //    而这颗要回答的是"后台那一集怎么样了".
    // 3. 它现在是**冗余入口**而不是唯一入口 —— 长按播放键/长按返回都能开面板, 所以即使用户
    //    真的被它吸过去, 也只是走了条稍慢的路, 不像以前那样是"手势没人知道"的补救.
    //
    // 仍然放在整列**最后**: 会话来去是动态的, 夹在中间会让其余条目的位置随之上下移动 (遥控器上
    // 位置就是肌肉记忆), 而且不能放头像那一簇上方 —— 头像聚焦时本来就会在其上方浮出动作按钮.
    val playback = LocalPlaybackSessionEntry.current
    val playbackNavigator = LocalNavigator.current
    playback.session?.let { retained ->
        add(
            TvNavRailItem(
                icon = Icons.Rounded.PlayCircle,
                label = stringResource(Lang.playback_session_now_playing),
                // 状态读在 lambda 里面, 不读在这里: 否则每次状态变化 (换源/缓冲完成…) 都会重组
                // 整个侧边栏与它所在的页面
                iconContent = { focused -> TvNowPlayingRailGlyph(focused, status = { playback.status }) },
                onClick = {
                    // force: 这不是"去开另一集", 而是回到**已经在播的这一集**, 所以跳过跟随房主时
                    // 那道导航守卫 (WatchTogetherManager.observeFollowerMode). 那道守卫只在目标与
                    // 房主已发布的播放位置一字不差时放行 —— 房主还没发布 (还在选源/详情页) 时是
                    // null, 一律拒绝, 于是跟随者退出播放页后连回自己的会话都被挡住. 跟随该拦的是
                    // "播别的", 不是"回去接着看".
                    playbackNavigator.navigateEpisodeDetails(
                        retained.subjectId,
                        retained.episodeId,
                        force = true,
                    )
                },
            ),
        )
    }
}

/**
 * **"正在播放"条目的字形**: 三根竖条的均衡条 —— 后台还在忙 (找源/缓冲) 时跳动, 就绪后静止,
 * 出问题时静止并变色.
 *
 * 为什么不是一颗静态 PlayCircle: 那只说明"这里有个入口", 而这颗条目存在的理由是**让人一眼看出
 * 后台那一集怎么样了** —— 慢的源要十几秒, 中途还可能自动换源、最后卡在等手选. 一列单色图标里
 * 一个会动的东西正好承担"有东西在进行中"这句话, 而静止 = "停在那儿等你回来".
 *
 * **动的判据是"还在加载", 不是"时钟在走"**: 退出播放页必然把会话按成暂停 (见
 * RetainedPlaybackSessionHolder), 所以按 isPlaying 画的话它永远静止, 动画等于白做.
 *
 * 颜色: 平时跟随 `LocalContentColor` (聚焦时 [TvRailGlyphBox] 已经把它换成 onPrimary 反色);
 * **只在未聚焦时**才用状态色染 —— 聚焦时底已经是主题色实底, 再把字形换成警示色, 两种色压在一起
 * 既脏又看不清, 而"出了问题"这件事在焦点已经落上去的时候, 按一下就能在面板里读到整句话.
 *
 * @param status lambda 而非值: 见 [TvNavRailItem.iconContent].
 */
@Composable
private fun TvNowPlayingRailGlyph(focused: Boolean, status: () -> PlaybackSessionStatus?) {
    val current = status()
    val busy = current is PlaybackSessionStatus.Preparing || current is PlaybackSessionStatus.Buffering
    val content = LocalContentColor.current
    val color = if (focused) {
        content
    } else {
        when (playbackSessionStatusSeverityOf(current)) {
            TvRailStatusSeverity.Error -> MaterialTheme.colorScheme.error
            TvRailStatusSeverity.Attention -> MaterialTheme.colorScheme.tertiary
            TvRailStatusSeverity.Normal -> content
        }
    }
    val transition = rememberInfiniteTransition()
    Row(
        Modifier.size(TV_RAIL_ICON_GLYPH_SIZE),
        horizontalArrangement = Arrangement.spacedBy(TV_RAIL_EQUALIZER_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(TV_RAIL_EQUALIZER_BARS) { index ->
            // 每根一个自己的周期: 相同周期会让三根齐涨齐落, 看起来像一整块在缩放而不是均衡条.
            // 用**互质**的毫秒数而不是给同一个动画加相位延迟 —— 后者要 initialStartOffset, 而
            // 三根各跑各的周期天然永不重合
            // 动画**只在 busy 时才注册**: infiniteRepeatable 一挂上就每帧推进一次, 而这颗图标
            // 绝大多数时间是静止的 (会话退到后台就绪之后), 常挂着等于白烧一条帧回调
            val fraction = if (busy) {
                val animated by transition.animateFloat(
                    initialValue = TV_RAIL_EQUALIZER_MIN,
                    targetValue = TV_RAIL_EQUALIZER_MAX,
                    animationSpec = infiniteRepeatable(
                        animation = tween(TV_RAIL_EQUALIZER_PERIODS[index], easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
                animated
            } else {
                // 静止态: 中间那根高、两边矮 —— 一个"停住的均衡条"的形状, 而不是三根等高的方块
                // (那会读成进度条或别的什么)
                TV_RAIL_EQUALIZER_IDLE[index]
            }
            Box(
                Modifier
                    .width(TV_RAIL_EQUALIZER_BAR_WIDTH)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(TV_RAIL_EQUALIZER_BAR_WIDTH / 2))
                    .background(color),
            )
        }
    }
}

/** [TvNowPlayingRailGlyph] 的状态分档 (与动作面板里那行状态字用同一套判据, 只是这里不需要文案). */
private enum class TvRailStatusSeverity { Normal, Attention, Error }

private fun playbackSessionStatusSeverityOf(status: PlaybackSessionStatus?): TvRailStatusSeverity =
    when (status) {
        // 要用户处理才会继续的那一档: 不是错误 (再等也不会好, 但也没坏)
        is PlaybackSessionStatus.NeedsSelection -> TvRailStatusSeverity.Attention
        is PlaybackSessionStatus.NoMedia,
        is PlaybackSessionStatus.PlayerError,
        is PlaybackSessionStatus.LoadFailed,
            -> TvRailStatusSeverity.Error

        else -> TvRailStatusSeverity.Normal
    }

/**
 * TV 可展开左侧导航栏 (主页与详情页共用同一实现):
 * 收起态是一列图标 (头像置顶 + 若干图标条目); 焦点进入后展开为"图标 + 文字"并压一层左缘渐变遮罩,
 * 焦点离开自动收起. 头像点击进入设置的用户信息页 (由 [onAvatarClick] 决定); 未登录时头像退化成
 * 设置里那个默认人物符号 (AccountCircle), 尺寸/对齐与其他图标完全一致.
 *
 * @param selfInfo 头像用户信息; 传 null 则不显示头像/用户名, 但仍保留头像槽位的等高占位,
 *   使其余按钮位置不变 (如详情页不需要头像).
 * @param onExitFocus 非 null 时: 条目上按返回键/右键调用它 (如详情页把焦点送回 Hero 播放按钮),
 *   并吞掉该按键; null 时不拦截 (返回键正常逐层退, 右键交给空间焦点搜索进入右侧内容).
 */
@Composable
fun TvNavigationSideRail(
    selfInfo: SelfInfoUiState?,
    onAvatarClick: () -> Unit,
    items: List<TvNavRailItem>,
    modifier: Modifier = Modifier,
    onExitFocus: (() -> Unit)? = null,
    /** 头像关联动作 (按登录态由调用方组装); 焦点在头像簇上时于其上方浮现这些图标+文字按钮. */
    avatarActions: List<TvRailAvatarAction> = emptyList(),
    /** 展开遮罩面板底色覆盖 (如详情页按封面调色板取色, 使遮罩跟随背景/主题); null 用默认 surface. */
    scrimColor: Color? = null,
) {
    // hasFocus (含子节点): 任一条目聚焦即展开
    var expanded by remember { mutableStateOf(false) }
    // 头像那簇浮出按钮需要的高度: 它们不占布局 (见 TvRailAvatar), 整列纯居中时会被屏幕上边界
    // 切掉 —— 条目越多整列越高, 居中后头像越靠上, 上方那簇就露不全.
    //
    // 下端曾经也要留 (带浮出动作的条目排在整列最后), 随"正在播放"条目一起删了; 再给条目加
    // 往下长的浮出按钮的话, 这里要把那份预留加回来.
    val topReserve = if (selfInfo != null && avatarActions.isNotEmpty()) {
        floatingActionsStackHeight(avatarActions.size)
    } else {
        0.dp
    }
    // 垂直居中 (纵向位置由下面的 layout 自己算, 因此这里对齐到顶)
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.TopStart) {
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            // 展开底衬: 纯色面板 (取 TV 全屏背景色, 与主壳背景一致, 日夜自适应)
            // + 右缘多色标平滑羽化融入内容.
            // 不再用"加深/变暗"的半屏遮罩 —— 那在浅色下会像一块脏阴影压暗白底与标题;
            // 现在浅色是干净的白/浅灰面板, 深色是干净的深色面板, 都与主背景无缝衔接.
            val panelColor = scrimColor ?: AniThemeDefaults.shellBackgroundColor
            Box(
                Modifier.fillMaxHeight().width(TV_RAIL_SCRIM_WIDTH).background(
                    // 前 ~82% 纯色实心, 末段用多色标近似缓动曲线羽化到透明, 消除竖向明暗切线
                    Brush.horizontalGradient(
                        0.00f to panelColor,
                        0.82f to panelColor,
                        0.90f to panelColor.copy(alpha = 0.82f),
                        0.96f to panelColor.copy(alpha = 0.38f),
                        1.00f to panelColor.copy(alpha = 0f),
                    ),
                ),
            )
        }
        // 进入门控: 只有"按左"才能把焦点移进侧边栏 (从上/下/右方向的空间焦点搜索一律取消,
        // 否则详情页最上方按钮按上也会误入); 进入时焦点总是落到 defaultFocus 标记的条目
        // (如"探索"), 不随进入位置变化.
        val enterFocus = remember { FocusRequester() }
        val hasDefaultFocusItem = items.any { it.defaultFocus }
        // 每个条目一个落点: 条目会来去 (正在播放 / 一起看), 带着焦点消失时要有人接手, 见 TvRailIconItem.
        //
        // 按 label 缓存, 不能用 remember(items.size): 条目增删恰恰就是 size 变化的那一刻, 按 size 记
        // 会把整份列表重建 —— 消失的条目 onDispose 里握着的是**旧**对象, 而上一个条目此刻挂的已是新
        // 对象, 旧对象不挂在任何节点上, requestFocus 只会打一行警告返回 false (Compose 1.11 的
        // FocusRequester.findFocusTarget 不抛异常), 交接静默失效. 按 label 取则条目增删不影响其余
        // 条目的落点身份. (侧边栏条目的 label 两两不同, 可以当稳定标识用.)
        val itemAnchorCache = remember { mutableMapOf<String, FocusRequester>() }
        val itemAnchors = items.map { itemAnchorCache.getOrPut(it.label) { FocusRequester() } }
        Column(
            Modifier
                // 居中, 但上端给头像那簇浮出按钮留够高度: 居中位置放不下时整列往下让, 让到还是
                // 放不下 (屏幕太矮 / 条目太多) 就贴着预留位置.
                // 在测量阶段一次算完, 不读位置状态, 因此没有"改位置→再测量"的回路.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val top = topReserve.roundToPx()
                    val available = if (constraints.hasBoundedHeight) {
                        constraints.maxHeight
                    } else {
                        placeable.height + top
                    }
                    val centered = ((available - placeable.height) / 2).coerceAtLeast(0)
                    val maxY = (available - placeable.height).coerceAtLeast(0)
                    val y = if (maxY < top) top else centered.coerceIn(top, maxY)
                    layout(placeable.width, placeable.height) { placeable.place(0, y) }
                }
                .onFocusChanged { expanded = it.hasFocus }
                .focusProperties {
                    onEnter = {
                        when (requestedFocusDirection) {
                            // 按左进入: 落到 defaultFocus 标记的条目
                            FocusDirection.Left -> if (hasDefaultFocusItem) enterFocus.requestFocus()
                            // 显式请求 (FocusRequester.requestFocus / moveFocus(Enter)) 也放行:
                            // 页面丢焦点时 AniAppContent 的全局兜底就是在 NavHost 上发一次请求, 而
                            // Compose 把这种请求按 Enter 方向找**最左**的子树 —— 在有侧边栏的页面上
                            // 那就是本栏. 一起挡掉的话兜底会每 100ms 撞在这里一次、次次 Cancelled,
                            // 整页方向键永远救不回来 (2026-08-05 关掉"正在播放"后丢焦点就是这么来的).
                            FocusDirection.Enter -> if (hasDefaultFocusItem) enterFocus.requestFocus()
                            // 上/下/右的空间搜索一律取消, 否则详情页最上方按钮按上也会误入
                            else -> cancelFocusChange()
                        }
                    }
                }
                .focusGroup()
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selfInfo != null) {
                TvRailAvatar(selfInfo, expanded, onExitFocus, onAvatarClick, avatarActions)
            } else {
                // 不显示头像时保留等高占位, 使其余按钮位置不变
                Box(Modifier.size(TV_RAIL_ITEM_SIZE))
            }
            for ((index, item) in items.withIndex()) {
                TvRailIconItem(
                    icon = item.icon,
                    label = item.label,
                    iconContent = item.iconContent,
                    expanded = expanded,
                    onExitFocus = onExitFocus,
                    focusRequester = if (item.defaultFocus) {
                        enterFocus
                    } else {
                        item.focusRequester
                    },
                    keepFocusOnClick = item.keepFocusOnClick,
                    onClick = item.onClick,
                    anchor = itemAnchors[index],
                    // 本条目带着焦点消失时交给上一个条目; 它是第一个就交给默认落点 (再没有就
                    // 只能靠全局兜底了)
                    fallbackFocus = itemAnchors.getOrNull(index - 1)
                        ?: enterFocus.takeIf { hasDefaultFocusItem && !item.defaultFocus },
                )
            }
        }
    }
}

/**
 * 侧边栏统一的图标方块: 32dp 容器 + 20dp 字形, 聚焦时主色底 + 反色图标 (带颜色过渡动画).
 * 普通条目 / 头像动作按钮 / 未登录头像共用此视觉.
 */
@Composable
private fun TvRailGlyphBox(
    focused: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** 非空时替代 [icon]; 见 [TvNavRailItem.iconContent]. */
    iconContent: (@Composable (focused: Boolean) -> Unit)? = null,
) {
    val background by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
    )
    Box(
        modifier.size(TV_RAIL_ITEM_SIZE).clip(RoundedCornerShape(TV_RAIL_ITEM_CORNER)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (focused) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ) {
            Box(Modifier.size(TV_RAIL_ICON_GLYPH_SIZE), contentAlignment = Alignment.Center) {
                if (iconContent != null) iconContent(focused) else Icon(icon, null)
            }
        }
    }
}

/** 返回键/右键回退焦点的按键处理 (仅 [onExitFocus] 非 null 时拦截). */
private fun Modifier.railExitKeys(onExitFocus: (() -> Unit)?): Modifier {
    if (onExitFocus == null) return this
    return this.onPreviewKeyEvent { event ->
        when (event.key) {
            // 返回键不退出页面, 把焦点还给调用方指定的目标 (如 Hero 播放按钮)
            Key.Back, Key.Escape -> {
                if (event.type == KeyEventType.KeyUp) onExitFocus()
                true
            }

            // 右键直接回目标, 不走空间焦点搜索
            Key.DirectionRight -> {
                if (event.type == KeyEventType.KeyDown) onExitFocus()
                true
            }

            else -> false
        }
    }
}

@Composable
private fun TvRailAvatar(
    selfInfo: SelfInfoUiState,
    expanded: Boolean,
    onExitFocus: (() -> Unit)?,
    onClick: () -> Unit,
    avatarActions: List<TvRailAvatarAction>,
    modifier: Modifier = Modifier,
) {
    val loggedIn = selfInfo.selfInfo != null && selfInfo.isSessionValid != false
    var avatarFocused by remember { mutableStateOf(false) }
    // 头像簇 (头像 + 上方动作按钮) 任一有焦点即展开动作按钮
    var clusterFocused by remember { mutableStateOf(false) }
    // 聚焦高亮: 已登录画圆环 (头像是圆的), 未登录用图标块反色底
    val focusHighlight by animateColorAsState(
        if (avatarFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
    )
    Box(modifier.onFocusChanged { clusterFocused = it.hasFocus }) {
        // 头像在整列最上, 只能往上长
        TvRailFloatingActionCluster(clusterFocused, avatarActions, onExitFocus = onExitFocus)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loggedIn) {
                // 圆形头像照片, 聚焦画圆环
                Box(
                    Modifier.size(TV_RAIL_ITEM_SIZE)
                        .onFocusChanged { avatarFocused = it.isFocused }
                        .railExitKeys(onExitFocus)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                        .border(2.dp, focusHighlight, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // 照片比高亮框 (32dp) 略小并居中, 使聚焦圆环成为其外圈, 不超出高亮尺寸
                    AvatarImage(
                        url = selfInfo.selfInfo?.avatarUrl,
                        modifier = Modifier.size(TV_RAIL_AVATAR_IMAGE_SIZE).clip(CircleShape),
                    )
                }
            } else {
                // 未登录: 退化成默认人物符号图标块 (与其它条目一致的反色高亮)
                TvRailGlyphBox(
                    focused = avatarFocused,
                    icon = Icons.Outlined.AccountCircle,
                    modifier = Modifier
                        .onFocusChanged { avatarFocused = it.isFocused }
                        .railExitKeys(onExitFocus)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
                )
            }
            if (expanded) {
                Text(
                    if (loggedIn) {
                        selfInfo.selfInfo?.nickname ?: stringResource(Lang.login_sign_in)
                    } else {
                        stringResource(Lang.login_sign_in)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * 浮现的单个动作按钮 (图标方块 + 文字), 聚焦反色, 与普通条目视觉一致.
 *
 * 头像上方那簇与带动作的条目下方那簇共用: 两处只差浮现方向, 按钮本身应当一模一样.
 */
@Composable
private fun TvRailFloatingActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onExitFocus: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .railExitKeys(onExitFocus)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvRailGlyphBox(focused, icon)
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            softWrap = false,
        )
    }
}

@Composable
private fun TvRailIconItem(
    icon: ImageVector,
    label: String,
    iconContent: (@Composable (focused: Boolean) -> Unit)?,
    expanded: Boolean,
    onExitFocus: (() -> Unit)?,
    focusRequester: FocusRequester?,
    keepFocusOnClick: Boolean,
    onClick: () -> Unit,
    /** 本条目自己的落点, 供下一个条目在消失时把焦点交回来 (见 [fallbackFocus]). */
    anchor: FocusRequester,
    /** 本条目在有焦点时消失后, 焦点交给谁. */
    fallbackFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // 本条目 (含子节点) 有没有焦点 —— 只用于下面那条"带着焦点消失时交接"的判断
    var clusterFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // 条目会来去 ("正在播放"被自己的关闭按钮关掉, "一起看"退出房间, 会话在后台结束…), 而焦点
    // 正落在它身上时 Compose 不会把焦点交给任何人 —— 整页方向键就此失效. 交回上一个条目.
    //
    // 这类丢焦点指望不上 AniAppContent 里那个全局兜底: 它是在 NavHost 上 requestFocus, 而
    // Compose 会把这种显式请求按 Enter 方向找**最左**的子树, 也就是本侧边栏, 又被下面 onEnter
    // 那道"只允许按左进入"的门挡掉 (Cancelled), 于是每 100ms 重试一次、次次失败.
    // 那道门现在放行 Enter 了, 但兜底落点是"探索", 不如就近交给上一个条目.
    //
    // onDispose 里不能直接读 clusterFocused: 节点被移除时 Compose 会在 onDispose **之前**就把
    // onFocusChanged 同步刷成"无焦点" (FocusTargetNode.onDetach → FocusOwnerImpl.clearFocus
    // (refreshFocusEvents = true) → dispatchFocusCallbacks, 全部在 applyChanges 里跑完, 而
    // onDispose 排在其后的 dispatchRememberObservers 里), 于是读到的永远是 false, 交接一次都不会
    // 发生. 这里改读"最后一次**重组**时的值": 真正的失焦 (用户把焦点移走) 会经过一次重组把它刷成
    // false, 而"带着焦点被移除"没有重组的机会, 它还停在 true —— 正好用来区分这两种情况.
    val clusterFocusedAtLastComposition = rememberUpdatedState(clusterFocused)
    DisposableEffect(fallbackFocus) {
        onDispose {
            if (clusterFocusedAtLastComposition.value && fallbackFocus != null) {
                runCatching { fallbackFocus.requestFocus() }
            }
        }
    }
    Box(modifier.focusRequester(anchor).onFocusChanged { clusterFocused = it.hasFocus }) {
        Row(
            Modifier
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { focused = it.isFocused }
                .railExitKeys(onExitFocus)
                // 自绘聚焦指示 (图标方块反色), 关掉默认 indication 避免整行水波.
                // 只保留焦点高亮: 不标记"当前页", 否则聚焦项与当前页两处高亮会误导用户.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onClick()
                        // 点击后清空焦点, 让 AniAppContent 全局兜底把焦点送入(可能刚切换/弹回的)当前页面
                        // 左上角可聚焦项; 不用 moveFocus(Right): 切页瞬间新内容还没组合出来, moveFocus 会
                        // 落到正在退场的旧页面或失败, 导致丢焦点.
                        // 就地开弹窗的条目不清 (见 TvNavRailItem.keepFocusOnClick).
                        if (!keepFocusOnClick) focusManager.clearFocus()
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvRailGlyphBox(focused, icon, iconContent = iconContent)
            if (expanded) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * **头像的那簇浮出按钮**: 焦点进入头像簇时从头像上方浮现, 离开即隐藏.
 *
 * 曾经条目也有一簇往下长的 (给"正在播放"的关闭用), 所以这里带过一个 `above` 参数; 那个条目
 * 2026-08-16 删掉之后只剩往上长这一种, 参数一并去掉 —— 再要往下长的话, 除了在这里加回方向,
 * 还要把整列的**下端预留**加回去 (见 topReserve 附近的注释).
 *
 * ## 为什么要 `layout(0, 0)`
 *
 * 按钮**不能占布局**: 占了会撑高头像簇, 让垂直居中的整栏重新居中, 表现为头像莫名上移.
 * 所以测量后一律向父级上报 0 尺寸, 再把内容摆到头像上方 (`y = -自身高度`).
 */
@Composable
private fun BoxScope.TvRailFloatingActionCluster(
    visible: Boolean,
    actions: List<TvRailAvatarAction>,
    onExitFocus: (() -> Unit)?,
) {
    if (actions.isEmpty()) return
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopStart)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(0, 0) { placeable.place(0, -placeable.height) }
            },
    ) {
        Column(
            // 与头像之间的间距挂在朝向它的那一侧
            Modifier.padding(bottom = TV_RAIL_FLOATING_ACTION_SPACING),
            verticalArrangement = Arrangement.spacedBy(TV_RAIL_FLOATING_ACTION_SPACING),
        ) {
            for (action in actions) {
                TvRailFloatingActionButton(action.icon, action.label, action.onClick, onExitFocus)
            }
        }
    }
}

/**
 * 一簇浮出按钮需要的高度: [count] 颗按钮 + 按钮间距 + 与锚点之间的间距, 末尾再留一点不贴屏幕边.
 *
 * 与 [TvRailFloatingActionCluster] 的实际排布一一对应 (那里只有一份了, 改布局就改这里).
 */
private fun floatingActionsStackHeight(count: Int): Dp =
    TV_RAIL_ITEM_SIZE * count +
            TV_RAIL_FLOATING_ACTION_SPACING * (count - 1) + // 按钮之间
            TV_RAIL_FLOATING_ACTION_SPACING + // 与锚点 (头像 / 条目) 之间
            TV_RAIL_FLOATING_ACTION_SPACING // 不贴屏幕边

/** 浮出按钮之间 (以及与锚点之间) 的间距. */
private val TV_RAIL_FLOATING_ACTION_SPACING = 8.dp

/** 侧边栏展开时的渐变遮罩宽度. */
private val TV_RAIL_SCRIM_WIDTH = 180.dp

/** 单个图标按钮 (聚焦反色方块 / 头像) 的边长. */
private val TV_RAIL_ITEM_SIZE = 32.dp

/** 图标按钮聚焦方块的圆角 (偏方, 不要太圆). */
private val TV_RAIL_ITEM_CORNER = 6.dp

/** 头像照片尺寸: 比高亮框 (32dp) 略小并居中, 使聚焦圆环成为其外圈, 不超出高亮尺寸. */
private val TV_RAIL_AVATAR_IMAGE_SIZE = 24.dp

/** 图标字形尺寸 (32dp 容器 / 20dp 字形). */
private val TV_RAIL_ICON_GLYPH_SIZE = 20.dp

/** [TvNowPlayingRailGlyph] 的竖条数. */
private const val TV_RAIL_EQUALIZER_BARS = 3

/** 竖条宽度 (3 根 + 2 道间隙 = 14dp, 居中放进 20dp 的字形格子里). */
private val TV_RAIL_EQUALIZER_BAR_WIDTH = 3.dp

private val TV_RAIL_EQUALIZER_GAP = 2.5.dp

/** 跳动区间: 不从 0 起跳 —— 条完全消失会让人以为图标在闪烁/坏了. */
private const val TV_RAIL_EQUALIZER_MIN = 0.3f

private const val TV_RAIL_EQUALIZER_MAX = 0.95f

/**
 * 三根各自的周期 (ms). 取互质值而不是给同一个动画加相位偏移: 相同周期会让三根齐涨齐落, 看起来
 * 像一整块在缩放而不是均衡条; 互质则永不重合, 也不必用 initialStartOffset.
 */
private val TV_RAIL_EQUALIZER_PERIODS = intArrayOf(620, 830, 730)

/** 静止态的高度: 中间高两边矮 = "停住的均衡条", 而不是三根等高的方块 (那会读成进度条). */
private val TV_RAIL_EQUALIZER_IDLE = floatArrayOf(0.45f, 0.75f, 0.5f)
