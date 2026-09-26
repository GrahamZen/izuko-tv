/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.resolveSavedOrder
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
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
 * 标签行用的是与 `TvCollectionTabRow` 同一套样式与间距常量, 位置也取同一组页面留白 —— 这里排成
 * 什么样, 进追番页就是什么样. 区别只在按键语义:
 *
 * - **确认键 = 拿起 / 放下**; 拿起之后左右键把它挪到想要的位置, 再按确认放下, 按返回撤销这一次移动.
 * - 返回键 (手上没拿东西时) = 离开本页.
 *
 * 只排顺序, 不做隐藏: 五个分类都是收藏状态的一种, 藏掉任何一个都会带出"藏掉的正好是当前选中项"
 * 与"全藏光了进页落哪"这两种边界, 而收益只是少一个标签.
 *
 * 顺序存在 [ThemeSettings.tvCollectionTabOrder], 读取与补位与追番页共用
 * [rememberTvCollectionTabOrder] / [resolveSavedOrder], 两边才不会排出两个样子.
 */
@Composable
fun TvCollectionTabOrderPage(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    val scope = rememberCoroutineScope()

    // 进页读一次: 本页是这份设置的唯一写入方, 再订阅回来只会跟自己的编辑打架
    var draft by remember { mutableStateOf<List<UnifiedCollectionType>?>(null) }
    LaunchedEffect(settings) {
        draft = resolveSavedOrder(settings.themeSettings.flow.first().tvCollectionTabOrder, TV_COLLECTION_TABS)
    }

    // 正拿着的那个; grabOrigin = 拿起那一刻的顺序, 返回键据此撤销
    var grabbed by remember { mutableStateOf<UnifiedCollectionType?>(null) }
    var grabOrigin by remember { mutableStateOf<List<UnifiedCollectionType>?>(null) }
    // 焦点落在哪个标签上 —— 编辑页里"聚焦即选中", 与追番页一致, 指示条跟着它走
    var focusedType by remember { mutableStateOf<UnifiedCollectionType?>(null) }
    // 每个分类一个请求器: 挪完位置把焦点追回到拿着的那个 (它换了位置, 光靠节点复用不保险)
    val requesters = remember { TV_COLLECTION_TABS.associateWith { FocusRequester() } }

    fun persist(order: List<UnifiedCollectionType>) {
        draft = order
        scope.launch { settings.themeSettings.update { copy(tvCollectionTabOrder = order) } }
    }

    // 移动只改本地草稿, 放下那一刻才落盘 —— 一路按着左键走过去不该写五次设置
    fun move(delta: Int) {
        val item = grabbed ?: return
        val current = draft ?: return
        val from = current.indexOf(item)
        val to = from + delta
        if (from < 0 || to !in current.indices) return
        draft = current.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
    }

    fun drop(commit: Boolean) {
        if (grabbed == null) return
        grabbed = null
        val origin = grabOrigin
        grabOrigin = null
        if (commit) draft?.let { persist(it) } else if (origin != null) draft = origin
    }

    // 挪完焦点要跟着那个标签走 (行内重排节点时焦点未必跟得住)
    LaunchedEffect(draft, grabbed) {
        val item = grabbed ?: return@LaunchedEffect
        runCatching { requesters.getValue(item).requestFocus() }
    }

    val order = draft
    Box(
        modifier
            .fillMaxSize()
            .background(AniThemeDefaults.shellBackgroundColor)
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
        if (order == null) return@Box // 设置还没读上来 (通常一帧都看不到)

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
                tabs = order,
                grabbed = grabbed,
                focusedType = focusedType,
                requesters = requesters,
                onFocused = { focusedType = it },
                onToggleGrab = { type ->
                    // 手上已经拿着东西时, **任何一个**的确认键都是"放下" —— 而不是换拿这一个:
                    // 移动之后焦点是靠请求器追回去的, 万一没追上, 那一下会落在邻居身上
                    if (grabbed != null) {
                        drop(commit = true)
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
                onClick = { persist(TV_COLLECTION_TABS) },
            )
        }

        // 进页把焦点送到第一个标签: 没有落点的页面会被全局兜底按几何乱挑一个
        LaunchedEffect(Unit) {
            runCatching { requesters.getValue(order.first()).requestFocus() }
        }
    }

    // 返回键分两档: 手上拿着东西 = 撤销这一次移动, 否则离开本页.
    // **不能写成 `enabled = grabbed == null`** —— 那样拿着东西时这一下会漏给系统, 当场退出页面
    BackHandler { if (grabbed != null) drop(commit = false) else onNavigateBack() }
}

/** 拿起态下归本页处理的方向键 (上下也要吞, 见调用处). */
private val TV_TAB_ORDER_MOVE_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
)

/**
 * 编辑用的标签行: 样式与间距全取追番页那几个常量, 指示条也照画 —— 差别只在"选中"这里等于"聚焦"
 * (追番页上本来就是聚焦即选中), 以及拿起的那个会浮起来.
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
    val density = LocalDensity.current
    val tabBounds = remember(tabs.size) { mutableStateListOf(*Array(tabs.size) { 0.dp to 0.dp }) }
    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TV_COLLECTION_TAB_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, type ->
                val interactionSource = remember { MutableInteractionSource() }
                val focused by interactionSource.collectIsFocusedAsState()
                val isGrabbed = grabbed == type
                // 拿起的那个浮起来一点并放大 —— 这一行只有文字, 描边反而糊
                val lift by animateFloatAsState(if (isGrabbed) 1f else 0f, label = "tabLift")
                Row(
                    Modifier
                        .onGloballyPositioned { coords ->
                            tabBounds[index] = with(density) {
                                coords.positionInParent().x.toDp() to coords.size.width.toDp()
                            }
                        }
                        .graphicsLayer {
                            translationY = -lift * TV_TAB_ORDER_GRAB_LIFT.toPx()
                            scaleX = 1f + lift * (TV_TAB_ORDER_GRAB_SCALE - 1f)
                            scaleY = scaleX
                        }
                        .focusRequester(requesters.getValue(type))
                        .onFocusChanged { if (it.isFocused) onFocused(type) }
                        .clickable(interactionSource, indication = null) { onToggleGrab(type) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val selected = focusedType == type
                    Text(
                        type.displayTextTv(),
                        color = when {
                            isGrabbed || focused -> MaterialTheme.colorScheme.primary
                            selected -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurface
                                .copy(alpha = TV_COLLECTION_TAB_UNSELECTED_ALPHA)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isGrabbed || selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        // 指示条跟着焦点走 (= 追番页上的选中态). 量出来之前不画, 否则它会从最左滑过来
        val (targetX, targetWidth) = tabBounds[tabs.indexOf(focusedType).coerceAtLeast(0)]
        if (targetWidth > 0.dp) {
            val indicatorX by animateDpAsState(targetX, label = "tabOrderIndicatorX")
            val indicatorWidth by animateDpAsState(targetWidth, label = "tabOrderIndicatorWidth")
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .offset { IntOffset(indicatorX.roundToPx(), 0) }
                    .width(indicatorWidth)
                    .height(TV_COLLECTION_TAB_INDICATOR_HEIGHT)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
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
    val focused by interactionSource.collectIsFocusedAsState()
    // 已经是默认顺序时不禁用, 只压暗: 禁用即不可聚焦, 焦点会当场丢在这一页上
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        shape = CircleShape,
        color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (focused) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
        },
        interactionSource = interactionSource,
    ) {
        Text(
            stringResource(Lang.collection_tv_tab_order_reset),
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 拿起时标签浮起的高度. */
private val TV_TAB_ORDER_GRAB_LIFT: Dp = 6.dp

/** 拿起时标签放大的倍数. */
private const val TV_TAB_ORDER_GRAB_SCALE = 1.08f
