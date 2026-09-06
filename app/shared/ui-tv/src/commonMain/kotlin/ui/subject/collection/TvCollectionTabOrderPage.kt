/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.resolveSavedOrder
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.ProvideRingOnlyFocus
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme
import me.him188.ani.app.ui.foundation.tv.tvGlassBackground
import me.him188.ani.app.ui.foundation.tv.tvGlassColors
import me.him188.ani.app.ui.foundation.tv.tvGlassFocusLift
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_tab_order_hint
import me.him188.ani.app.ui.lang.collection_tv_tab_order_hint_grabbed
import me.him188.ani.app.ui.lang.collection_tv_tab_order_reset
import me.him188.ani.app.ui.lang.collection_tv_tab_order_subtitle
import me.him188.ani.app.ui.lang.collection_tv_tab_order_title
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource

/**
 * 「自定义追番页标签顺序」页: 把追番页顶部那排分类标签原样摆出来, 在上面直接排先后.
 *
 * 标签行用的是与 `TvCollectionTabRow` 同一个玻璃标签栏 ([TvCollectionGlassTabBar] / [TvCollectionGlassTab]), 位置也取同一组
 * 页面留白 —— 这里排成什么样, 进追番页就是什么样. 区别只在按键语义:
 *
 * - **确认键 = 拿起 / 放下**; 拿起之后左右键把它挪到想要的位置, 再按确认放下, 按返回撤销这一次移动.
 * - 返回键 (手上没拿东西时) = 离开本页.
 *
 * 只排顺序, 不做隐藏: 五个分类都是收藏状态的一种, 藏掉任何一个都会带出"藏掉的正好是当前选中项"
 * 与"全藏光了进页落哪"这两种边界, 而收益只是少一个标签.
 *
 * 顺序存在 [ThemeSettings.tvCollectionTabOrder], 读取与补位与追番页共用
 * [rememberTvCollectionTabOrder] / [resolveSavedOrder], 两边才不会排出两个样子.
 *
 * 入口在追番页里: 长按标签, 或动作面板「自定义标签顺序」; 本页作为窗口盖在追番页上 (见 TvAdjustWindows).
 * 调的时候只改草稿, **离开本页那一刻才写一次** ([onCommit] 在写之前报出要写的顺序): 底下那排标签不必跟着每一次放下重排.
 *
 * [initialFocus]: 在追番页上长按那个标签进来的 —— 进页焦点落在它上面 (不拿起), 按确认再拿起来挪; 按下键就到「恢复默认顺序」.
 */
@Composable
fun TvCollectionTabOrderPage(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: UnifiedCollectionType? = null,
    onCommit: (List<UnifiedCollectionType>) -> Unit = {},
) {
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    val scope = rememberCoroutineScope()

    // 正拿着的那个; grabOrigin = 拿起那一刻的顺序, 返回键据此撤销
    var grabbed by remember { mutableStateOf<UnifiedCollectionType?>(null) }
    var grabOrigin by remember { mutableStateOf<List<UnifiedCollectionType>?>(null) }

    // 进页读一次, 在组合里同步读 (第一帧就有标签行, 进页焦点当场落得下): 本页是这份设置的唯一写入方, 再订阅回来只会跟自己的编辑打架
    val initialOrder = resolveSavedOrder(LocalThemeSettings.current.tvCollectionTabOrder, TV_COLLECTION_TABS)
    var draft by remember { mutableStateOf(initialOrder) }
    // 设置里此刻存着的顺序: 离开时与它不同才写
    var saved by remember { mutableStateOf(initialOrder) }
    // 焦点落在哪个标签上 —— 编辑页里"聚焦即选中", 与追番页一致, 指示条跟着它走
    var focusedType by remember { mutableStateOf<UnifiedCollectionType?>(null) }
    // 每个分类一个请求器: 挪完位置把焦点追回到拿着的那个 (它换了位置, 光靠节点复用不保险)
    val requesters = remember { TV_COLLECTION_TABS.associateWith { FocusRequester() } }
    // 标签行按下键落这里: 右边几个标签正下方没有东西, 交给几何搜索不一定找得到它
    val resetFocus = remember { FocusRequester() }

    fun commit() {
        val order = draft
        if (order == saved) return
        saved = order
        onCommit(order)
        // 不随本页的组合一起取消: 写入正发生在离开的那一刻
        scope.launch(NonCancellable) { settings.themeSettings.update { copy(tvCollectionTabOrder = order) } }
    }

    fun leave() {
        commit()
        onNavigateBack()
    }
    // 不是按返回离开的 (窗口被 Web 控制台跳页撤掉、Activity 重建) 也照样写
    DisposableEffect(Unit) { onDispose { commit() } }

    // 移动只改草稿, 离开时才写 —— 一路按着左键走过去不该写五次设置
    fun move(delta: Int) {
        val item = grabbed ?: return
        val current = draft
        val from = current.indexOf(item)
        val to = from + delta
        if (from < 0 || to !in current.indices) return
        draft = current.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
    }

    fun drop(keep: Boolean) {
        if (grabbed == null) return
        grabbed = null
        val origin = grabOrigin
        grabOrigin = null
        if (!keep && origin != null) draft = origin
    }

    // 挪完焦点要跟着那个标签走 (行内重排节点时焦点未必跟得住)
    LaunchedEffect(draft, grabbed) {
        val item = grabbed ?: return@LaunchedEffect
        runCatching { requesters.getValue(item).requestFocus() }
    }

    val order = draft
    // 配色同追番页: 真页面外面包着海报墙配色 (见 TvPageVariants), 底色是主壳铺的卡片墙那档 (见 TvMainScreenLayout).
    // 标签栏是半透明的玻璃, 底色一换整排看起来就不一样了
    TvPosterWallTheme {
        Box(
            modifier
                .fillMaxSize()
                .background(tvPosterWallBackground())
                // 拿起态下方向键归本页: 交给空间焦点搜索的话, 焦点会跑到邻居身上而被拿的那个原地不动
                .onPreviewKeyEvent { event ->
                    if (grabbed == null) return@onPreviewKeyEvent false
                    if (event.key !in TV_TAB_ORDER_MOVE_KEYS) return@onPreviewKeyEvent false
                    // KeyUp 同样吞掉: 漏下去会被底下的标签当成"按了一下"
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    when (event.key) {
                        Key.DirectionLeft -> move(-1)
                        Key.DirectionRight -> move(1)
                        // 上下键这一行没有去处, 但也不能放行 —— 放行就等于"手上还拿着东西, 焦点却跑走了"
                        else -> Unit
                    }
                    true
                },
        ) {
            Column(
                // 与追番页同一组留白, 外加主壳给侧边栏让开的那一段 (见 TvMainScreenLayout): 本页不在
                // 主壳里, 不补上这段, 标签行会比真页左 48dp. 让开的位置留空, 不画侧边栏本身 ——
                // 这一页的方向键全归排序用, 摆一个进不去的侧边栏只会误导
                Modifier.padding(
                    start = TvNavigationRailDefaults.CollapsedWidth + TV_COLLECTION_START_PAD,
                    top = TV_COLLECTION_TOP_PAD,
                ),
            ) {
                // 与追番页同一个位置、同一套样式的标签行
                TvCollectionTabOrderRow(
                    modifier = Modifier.focusProperties { down = resetFocus },
                    tabs = order,
                    grabbed = grabbed,
                    focusedType = focusedType,
                    requesters = requesters,
                    onFocused = { focusedType = it },
                    onToggleGrab = { type ->
                        // 手上已经拿着东西时, **任何一个**的确认键都是"放下" —— 而不是换拿这一个:
                        // 移动之后焦点是靠请求器追回去的, 万一没追上, 那一下会落在邻居身上
                        if (grabbed != null) {
                            drop(keep = true)
                        } else {
                            grabOrigin = order
                            grabbed = type
                        }
                    },
                )

                Spacer(Modifier.height(44.dp))
                Text(
                    stringResource(Lang.collection_tv_tab_order_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Lang.collection_tv_tab_order_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(
                        if (grabbed != null) Lang.collection_tv_tab_order_hint_grabbed
                        else Lang.collection_tv_tab_order_hint,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(28.dp))
                TvCollectionTabOrderResetButton(
                    enabled = order != TV_COLLECTION_TABS,
                    onClick = { draft = TV_COLLECTION_TABS },
                    // 上键回刚才停的那个标签 (几何搜索会挑离按钮最近的那个)
                    modifier = Modifier
                        .focusRequester(resetFocus)
                        .focusProperties { up = requesters.getValue(focusedType ?: order.first()) },
                )
            }

            // 进页把焦点送到长按的那个或第一个标签: 没有落点的页面会被全局兜底按几何乱挑一个
            LaunchedEffect(Unit) {
                runCatching { requesters.getValue(initialFocus?.takeIf { it in order } ?: order.first()).requestFocus() }
            }
        }
    }

    // 返回键分两档: 手上拿着东西 = 撤销这一次移动, 否则离开本页.
    // **不能写成 `enabled = grabbed == null`** —— 那样拿着东西时这一下会漏给系统, 当场退出页面
    BackHandler { if (grabbed != null) drop(keep = false) else leave() }
}

/** 拿起态下归本页处理的方向键 (上下也要吞, 见调用处). */
private val TV_TAB_ORDER_MOVE_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
)

/**
 * 编辑用的标签行: 与追番页同一个玻璃标签栏 —— 差别只在"选中"这里等于"聚焦" (追番页上本来就是聚焦即选中),
 * 以及拿起的那个在聚焦的抬起之上再浮起一截.
 */
@Composable
private fun TvCollectionTabOrderRow(
    tabs: List<UnifiedCollectionType>,
    grabbed: UnifiedCollectionType?,
    focusedType: UnifiedCollectionType?,
    requesters: Map<UnifiedCollectionType, FocusRequester>,
    onFocused: (UnifiedCollectionType) -> Unit,
    onToggleGrab: (UnifiedCollectionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCollectionGlassTabBar(modifier.height(TV_COLLECTION_TAB_ROW_HEIGHT)) {
        tabs.forEach { type ->
            // 按分类记状态: 挪位置时状态 (聚焦、浮起) 跟着标签走, 不留在原来的格子上
            key(type) {
                val interactionSource = remember { MutableInteractionSource() }
                // 焦点按 onFocusChanged 记 (见 FocusHighlight.kt 开头: 收集交互事件会丢掉进页那一次 Focus)
                var focused by remember { mutableStateOf(false) }
                val lift by animateFloatAsState(if (grabbed == type) 1f else 0f, label = "tabLift")
                TvCollectionGlassTab(
                    label = type.displayTextTv(),
                    selected = focusedType == type,
                    focused = focused,
                    modifier = Modifier
                        .graphicsLayer {
                            translationY = -lift * TV_TAB_ORDER_GRAB_LIFT.toPx()
                            scaleX = 1f + lift * (TV_TAB_ORDER_GRAB_SCALE - 1f)
                            scaleY = scaleX
                        }
                        .focusRequester(requesters.getValue(type))
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onFocused(type)
                        }
                        .clickable(interactionSource, indication = null) { onToggleGrab(type) },
                )
            }
        }
    }
}

@Composable
private fun TvCollectionTabOrderResetButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val glass = tvGlassColors()
    // 同顶栏的玻璃按钮: 常态玻璃胶囊, 聚焦换成浅色实底配黑字并抬起; 不叠 M3 焦点态层 (见 TvGlassColors).
    // 已经是默认顺序时不禁用, 只压暗: 禁用即不可聚焦, 焦点会当场丢在这一页上
    ProvideRingOnlyFocus {
        Surface(
            onClick = { if (enabled) onClick() },
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .tvGlassFocusLift(focused, CircleShape)
                .tvGlassBackground(CircleShape),
            shape = CircleShape,
            color = if (focused) glass.focusedPlatter else Color.Transparent,
            contentColor = (if (focused) glass.focusedContent else MaterialTheme.colorScheme.onSurface)
                .copy(alpha = if (enabled) 1f else 0.5f),
            interactionSource = interactionSource,
        ) {
            Text(
                stringResource(Lang.collection_tv_tab_order_reset),
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 拿起时标签浮起的高度. */
private val TV_TAB_ORDER_GRAB_LIFT: Dp = 6.dp

/** 拿起时标签在聚焦的放大之上再放大的倍数. */
private const val TV_TAB_ORDER_GRAB_SCALE = 1.04f
