/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.data.models.preference.TvPlayerChromeItem
import me.him188.ani.app.data.models.preference.TvPlayerChromeLayout
import me.him188.ani.app.data.models.preference.TvPlayerChromePresets
import me.him188.ani.app.data.models.preference.TvPlayerChromeRow
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_PILL_ICON_SIZE
import me.him188.ani.app.ui.foundation.tv.TvPillShell
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_tv_chrome_delete
import me.him188.ani.app.ui.lang.video_player_tv_chrome_duplicate
import me.him188.ani.app.ui.lang.video_player_tv_chrome_hidden
import me.him188.ani.app.ui.lang.video_player_tv_chrome_hint
import me.him188.ani.app.ui.lang.video_player_tv_chrome_hint_grabbed
import me.him188.ani.app.ui.lang.video_player_tv_chrome_preset_name
import me.him188.ani.app.ui.lang.video_player_tv_chrome_reset
import me.him188.ani.app.ui.lang.video_player_tv_chrome_subtitle
import me.him188.ani.app.ui.lang.video_player_tv_chrome_title
import org.jetbrains.compose.resources.stringResource

/**
 * 「自定义播放器按钮」页: 一个**不播放任何东西的播放器**.
 *
 * 屏幕下半照着 [TvPlayerControlsOverlay] 原样摆出胶囊行 / 进度条 / 图标行 —— 同一批外观组件、
 * 同一套间距常量, 所以这里排成什么样, 进播放器就是什么样. 区别只在按钮的按键语义:
 *
 * - **确认键 = 抓起 / 放下**. 抓起之后左右键把它挪到想要的位置 (真的在行里跟着走, 不是列表里的
 *   第几行), 再按确认放下, 按返回撤销这一次移动.
 * - **长按确认键 = 隐藏 / 显示**. 隐藏的按钮**不从这一页消失**, 而是变灰留在原位 —— 它的位置
 *   还在, 想要回来再长按一次即可; 换成"丢进隐藏池"的话, 放回来还得重排一遍.
 * - 返回键 (手上没抓东西时) = 离开本页.
 *
 * 为什么不做成一页设置项列表: 这些按钮的差别几乎全在"排在哪"和"挨着谁", 列表里只看得到名字的
 * 先后, 看不出真正关心的那件事 —— 遥控器从最左走到某一颗要按几下、哪几颗被竖线分在了一组.
 *
 * 页头那一行可以在**几套版式**之间切换 (见 [TvPlayerChromePresets]), 切过去当场就摆成那一套的样子.
 *
 * 条目本身的图标与名字见 [tvChromeItemAppearance]; 顺序与显隐存在 [TvPlayerChromeLayout].
 * 本页列的是**全集** (不像播放器那样筛掉"这一集没有下一集"之类), 唯独触屏专有的两颗在电视上不列.
 */
@Composable
fun TvPlayerChromeLayoutPage(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    val scope = rememberCoroutineScope()
    val touchInput = LocalTvTouchInputEnabled.current

    // 配置只在进页面时读一次: 本页是这份设置的唯一写入方, 再订阅回来只会跟自己的编辑打架
    var draft by remember { mutableStateOf<TvPlayerChromePresets?>(null) }
    var opEdSkipSeconds by remember { mutableStateOf(85L) }
    LaunchedEffect(settings) {
        settings.videoScaffoldConfig.flow.collect { config ->
            opEdSkipSeconds = config.opEdSkipDuration.inWholeSeconds
            if (draft == null) draft = config.tvPlayerChrome
        }
    }

    // 正被抓着的那一颗; grabOrigin = 抓起那一刻的版式, 返回键据此撤销
    var grabbed by remember { mutableStateOf<TvPlayerChromeItem?>(null) }
    var grabOrigin by remember { mutableStateOf<TvPlayerChromeLayout?>(null) }

    // 每个条目一个请求器: 移动之后把焦点追回到被抓着的那一颗 (它换了位置, 光靠节点复用不保险)
    val requesters = remember { TvPlayerChromeItem.entries.associateWith { FocusRequester() } }
    val headerFocus = remember { FocusRequester() }
    val firstPillFocus = requesters.getValue(TvPlayerChromeItem.PILL_RECOMMENDATIONS)

    fun persist(new: TvPlayerChromePresets) {
        draft = new
        scope.launch { settings.videoScaffoldConfig.update { copy(tvPlayerChrome = new) } }
    }

    // 本页在电视上不列触屏专有的那两颗, 功能不存在的 (isRetired) 也不列, 但它们照样在版式里 ——
    // 移动时要把它们跨过去, 否则按一次左键会像没反应 (见 TvPlayerChromeLayout.moved 的 among)
    fun visibleItemsOf(layout: TvPlayerChromeLayout, row: TvPlayerChromeRow) =
        layout.orderOf(row).filterNot { it.isRetired || (it.isTouchOnly && !touchInput) }

    // 移动只改本地草稿, 放下那一刻才落盘 —— 一路按着左键走过去不该写十次设置
    fun move(delta: Int) {
        val item = grabbed ?: return
        val current = draft ?: return
        val layout = current.active
        draft = current.withActive(layout.moved(item, delta, visibleItemsOf(layout, item.row)))
    }

    fun drop(commit: Boolean) {
        if (grabbed == null) return
        grabbed = null
        val origin = grabOrigin
        grabOrigin = null
        if (commit) {
            draft?.let { persist(it) }
        } else if (origin != null) {
            draft = draft?.withActive(origin)
        }
    }

    // 移动之后焦点要跟着那一颗走 (Compose 在行里重排节点时焦点未必跟得住)
    LaunchedEffect(draft, grabbed) {
        val item = grabbed ?: return@LaunchedEffect
        runCatching { requesters.getValue(item).requestFocus() }
    }

    val presets = draft
    // **与播放器同一档强制深色** (播放器走的是 alwaysDarkInEpisodePage / forceDarkInPlayer, 见 EpisodePage):
    // 本页画的就是播放器那身白字白钮的控制层, 浅色配色对它没有任何用处 —— 而跟着主题走的那几个颜色
    // (抓起描边取的 colorScheme.primary) 在浅色主题下会翻成深紫, 落在这张近黑的页面上几乎看不见,
    // 也与播放器里真正显示的颜色不一致.
    AniTheme(darkModeOverride = DarkMode.DARK) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                // 抓起态下方向键归本页: 交给空间焦点搜索的话, 焦点会跑到邻居身上而被抓的那颗原地不动
                .onPreviewKeyEvent { event ->
                    if (grabbed == null) return@onPreviewKeyEvent false
                    if (event.key !in TV_CHROME_MOVE_KEYS) return@onPreviewKeyEvent false
                    // KeyUp 同样吞掉: 漏下去会被底下的按钮当成"按了一下"
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    when (event.key) {
                        Key.DirectionLeft -> move(-1)
                        Key.DirectionRight -> move(1)
                        // 上下键不跨行 (胶囊与圆钮的画法完全不同, 搬过去没有意义), 但也不能放行 ——
                        // 放行就等于"手上还抓着东西, 焦点却跑到了另一行"
                        else -> Unit
                    }
                    true
                },
        ) {
            // 假的"画面": 一层很淡的渐变, 只为让白色控件有个正常的衬底 —— 真播放器里这里是视频
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color(0xFF1A1D22), 1f to Color(0xFF07080A))),
            )
            // 底部渐变 scrim: 与播放器同一份参数
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(TV_PLAYER_BOTTOM_SCRIM_HEIGHT)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = TV_PLAYER_BOTTOM_SCRIM_ALPHA),
                        ),
                    ),
            )

            if (presets == null) return@Box // 配置还没读上来 (通常一帧都看不到)

            // 页头: 标题与说明在左, 版式切换 / 复制 / 删除 / 恢复默认与遥控键提示在右.
            // 占的正是播放器标题行那块地方 —— 那里本来也没有可聚焦的东西
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = TV_PLAYER_HORIZONTAL_PAD, vertical = 28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f).padding(end = 24.dp)) {
                    Text(
                        stringResource(Lang.video_player_tv_chrome_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(Lang.video_player_tv_chrome_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    TvChromePresetBar(
                        presets = presets,
                        onSwitch = { persist(presets.switchedTo(it)) },
                        onDuplicate = { persist(presets.duplicatedActive()) },
                        onDelete = { persist(presets.removedActive()) },
                        onReset = { persist(presets.withActive(TvPlayerChromeLayout.Default)) },
                        modifier = Modifier
                            .focusRequester(headerFocus)
                            .focusProperties { down = firstPillFocus },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(
                            if (grabbed != null) Lang.video_player_tv_chrome_hint_grabbed else Lang.video_player_tv_chrome_hint,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                    )
                }
            }

            val layout = presets.active
            // 手上已经抓着东西时, **任何一颗**的确认键都是"放下" —— 而不是换抓这一颗.
            // 移动之后焦点是靠请求器追回去的, 万一没追上, 那一下确认键会落在邻居身上:
            // 按"换抓"处理的话, 用户以为放下了, 实际上手里换成了别的一颗, 接着的左右键就开始搬它
            val onGrabOrDrop: (TvPlayerChromeItem) -> Unit = { item ->
                if (grabbed != null) {
                    drop(commit = true)
                } else {
                    grabOrigin = layout
                    grabbed = item
                }
            }
            val onToggleHidden: (TvPlayerChromeItem) -> Unit = { item ->
                persist(presets.withActive(layout.withHidden(item, !layout.isHidden(item))))
            }
            val bottomItems = visibleItemsOf(layout, TvPlayerChromeRow.BOTTOM)

            // 控制层: 与播放器同一套叠放与间距 (见 TvPlayerControlsOverlay 底部那一列)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
            ) {
                Column(Modifier.padding(horizontal = TV_PLAYER_HORIZONTAL_PAD)) {
                    TvChromeEditablePillsRow(
                        items = visibleItemsOf(layout, TvPlayerChromeRow.PILLS),
                        layout = layout,
                        grabbed = grabbed,
                        opEdSkipSeconds = opEdSkipSeconds,
                        requesters = requesters,
                        upFocus = headerFocus,
                        onGrabOrDrop = onGrabOrDrop,
                        onToggleHidden = onToggleHidden,
                        modifier = Modifier.padding(bottom = TV_PLAYER_PROGRESS_ROW_GAP),
                    )

                    TvChromeFakeProgressRow()

                    Spacer(Modifier.height(TV_PLAYER_PROGRESS_ROW_GAP))

                    TvChromeEditableBottomRow(
                        items = bottomItems,
                        layout = layout,
                        grabbed = grabbed,
                        opEdSkipSeconds = opEdSkipSeconds,
                        requesters = requesters,
                        upFocus = firstPillFocus,
                        onGrabOrDrop = onGrabOrDrop,
                        onToggleHidden = onToggleHidden,
                    )
                }
            }

            // 进页面把焦点送到图标行第一颗: 没有落点的页面会被全局兜底按几何乱挑一个
            LaunchedEffect(Unit) {
                val first = bottomItems.firstOrNull() ?: return@LaunchedEffect
                runCatching { requesters.getValue(first).requestFocus() }
            }
        }

    }

    // 返回键分两档: 手上抓着东西 = 撤销这一次移动 (放回抓起前的位置), 否则离开本页.
    // **不能写成 `enabled = grabbed == null`** —— 那样抓着东西时这一下会漏给系统, 当场退出页面
    BackHandler { if (grabbed != null) drop(commit = false) else onNavigateBack() }
}

/** 抓起态下归本页处理的方向键 (上下也要吞, 见调用处). */
private val TV_CHROME_MOVE_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
)

/**
 * 页头右侧那一条: 「布局 1 / 2 / 3」+ 复制 / 删除 / 恢复默认.
 *
 * 版式的名字按位置算 (见 [TvPlayerChromePresets]) —— 遥控器上打字太苦, 而这几套的区别本来就
 * 靠底下摆出来的样子认.
 */
@Composable
private fun TvChromePresetBar(
    presets: TvPlayerChromePresets,
    onSwitch: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.resolved.forEachIndexed { index, _ ->
            val active = index == presets.activeIndexResolved
            TvChromePresetChip(
                text = stringResource(Lang.video_player_tv_chrome_preset_name, index + 1),
                selected = active,
                onClick = { onSwitch(index) },
            )
        }
        if (presets.resolved.size < TvPlayerChromePresets.MAX_PRESETS) {
            TvChromeHeaderIconButton(Icons.Rounded.Add, stringResource(Lang.video_player_tv_chrome_duplicate), onDuplicate)
        }
        if (presets.resolved.size > 1) {
            TvChromeHeaderIconButton(
                Icons.Rounded.DeleteOutline,
                stringResource(Lang.video_player_tv_chrome_delete),
                onDelete,
            )
        }
        TvChromeHeaderIconButton(Icons.Rounded.Restore, stringResource(Lang.video_player_tv_chrome_reset), onReset)
    }
}

@Composable
private fun TvChromePresetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = when {
            focused -> Color.White
            selected -> Color.White.copy(alpha = 0.3f)
            else -> Color.White.copy(alpha = 0.12f)
        },
        contentColor = if (focused) Color.Black else Color.White,
        interactionSource = interactionSource,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvChromeHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvBottomRowLabeled(label = contentDescription, modifier = modifier) {
        TvBottomRowIconButton(onClick) {
            Icon(icon, contentDescription, Modifier.size(TV_ICON_SIZE))
        }
    }
}

@Composable
private fun TvChromeEditablePillsRow(
    items: List<TvPlayerChromeItem>,
    layout: TvPlayerChromeLayout,
    grabbed: TvPlayerChromeItem?,
    opEdSkipSeconds: Long,
    requesters: Map<TvPlayerChromeItem, FocusRequester>,
    upFocus: FocusRequester,
    onGrabOrDrop: (TvPlayerChromeItem) -> Unit,
    onToggleHidden: (TvPlayerChromeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().focusProperties { up = upFocus },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            val appearance = tvChromeItemAppearance(item, opEdSkipSeconds)
            val hidden = layout.isHidden(item)
            val isGrabbed = grabbed == item
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            val contentAlpha = if (hidden) TV_CHROME_HIDDEN_ALPHA else 1f
            TvPillShell(
                highlighted = focused,
                onClick = { onGrabOrDrop(item) },
                interactionSource = interactionSource,
                border = tvChromeGrabbedBorder(isGrabbed),
                modifier = Modifier
                    .focusRequester(requesters.getValue(item))
                    .tvChromeGrabbedLift(isGrabbed)
                    .tvLongPressKey(
                        onLongPress = { onToggleHidden(item) },
                        onShortPress = { onGrabOrDrop(item) },
                    ),
            ) {
                (appearance.glyph as? TvChromeGlyph.Vector)?.let {
                    Icon(it.icon, null, Modifier.size(TV_PILL_ICON_SIZE).alpha(contentAlpha))
                }
                Text(
                    if (hidden) appearance.name + TV_CHROME_HIDDEN_SEPARATOR + stringResource(Lang.video_player_tv_chrome_hidden)
                    else appearance.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.alpha(contentAlpha),
                )
            }
        }
    }
}

@Composable
private fun TvChromeEditableBottomRow(
    items: List<TvPlayerChromeItem>,
    layout: TvPlayerChromeLayout,
    grabbed: TvPlayerChromeItem?,
    opEdSkipSeconds: Long,
    requesters: Map<TvPlayerChromeItem, FocusRequester>,
    upFocus: FocusRequester,
    onGrabOrDrop: (TvPlayerChromeItem) -> Unit,
    onToggleHidden: (TvPlayerChromeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalContentColor provides Color.White,
        LocalTextStyle provides MaterialTheme.typography.labelMedium,
    ) {
        Row(
            modifier.fillMaxWidth().focusProperties { up = upFocus },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (item in items) {
                TvChromeEditableBottomItem(
                    item = item,
                    appearance = tvChromeItemAppearance(item, opEdSkipSeconds),
                    hidden = layout.isHidden(item),
                    grabbed = grabbed == item,
                    focusRequester = requesters.getValue(item),
                    onGrabOrDrop = { onGrabOrDrop(item) },
                    onToggleHidden = { onToggleHidden(item) },
                )
            }
        }
    }
}

/**
 * 图标行里的一颗. 装饰条目 (分组竖线 / 弹性留白) 在播放器里是画上去的死物, 在本页却必须能被抓起来
 * —— 用户要排的正是"竖线画在哪儿"、"哪一段留白把两半推开", 所以它们在这里也是可聚焦的按钮.
 */
@Composable
private fun RowScope.TvChromeEditableBottomItem(
    item: TvPlayerChromeItem,
    appearance: TvChromeItemAppearance,
    hidden: Boolean,
    grabbed: Boolean,
    focusRequester: FocusRequester,
    onGrabOrDrop: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    val contentAlpha = if (hidden) TV_CHROME_HIDDEN_ALPHA else 1f
    val label = if (hidden) {
        appearance.name + TV_CHROME_HIDDEN_SEPARATOR + stringResource(Lang.video_player_tv_chrome_hidden)
    } else {
        appearance.name
    }
    // 弹性留白在播放器里占着两半之间的全部空档, 这里同样给它 weight —— 于是它在本页上的
    // 位置与宽度就是它在播放器里的样子, 按钮本体居中摆在那段空档中间
    val slotModifier = if (appearance.glyph is TvChromeGlyph.FlexibleSpace) Modifier.weight(1f) else Modifier
    TvBottomRowLabeled(label = label, modifier = slotModifier) {
        val buttonModifier = Modifier
            .focusRequester(focusRequester)
            .tvChromeGrabbedLift(grabbed)
            .tvLongPressKey(onLongPress = onToggleHidden, onShortPress = onGrabOrDrop)
        val border = tvChromeGrabbedBorder(grabbed)
        when (val glyph = appearance.glyph) {
            is TvChromeGlyph.Vector -> TvBottomRowIconButton(onGrabOrDrop, buttonModifier, border) {
                Icon(glyph.icon, null, Modifier.size(TV_ICON_SIZE).alpha(contentAlpha))
            }

            // 自描述的文字按钮 (字幕/倍速/画面比例): 播放器里宽度由文字撑开, 这里定宽,
            // 免得几种语言下一行忽宽忽窄
            is TvChromeGlyph.Label -> TvChromeTextButton(
                text = glyph.text,
                onClick = onGrabOrDrop,
                contentAlpha = contentAlpha,
                border = border,
                modifier = buttonModifier,
            )

            // 竖线: 圆钮的形状会让"这是一根线"彻底看不出来, 所以按钮里画的就是它在播放器里的样子,
            // 外面套着同一套聚焦反色与抓起描边
            TvChromeGlyph.Divider -> TvBottomRowIconButton(onGrabOrDrop, buttonModifier, border) {
                Box(
                    Modifier
                        .height(TV_BOTTOM_ROW_DIVIDER_HEIGHT)
                        .width(1.dp)
                        .alpha(contentAlpha)
                        .background(LocalContentColor.current.copy(alpha = TV_BOTTOM_ROW_DIVIDER_ALPHA)),
                )
            }

            TvChromeGlyph.FlexibleSpace -> TvBottomRowIconButton(onGrabOrDrop, buttonModifier, border) {
                Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(TV_ICON_SIZE).alpha(contentAlpha))
            }
        }
    }
}

/** 文字按钮 (字幕/倍速/画面比例) 的本页版本: 与圆钮同高同配色, 只是宽度按文字给. */
@Composable
private fun TvChromeTextButton(
    text: String,
    onClick: () -> Unit,
    contentAlpha: Float,
    border: BorderStroke? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.height(TV_ICON_BUTTON_SIZE),
        shape = CircleShape,
        color = if (focused) Color.White else Color.Transparent,
        contentColor = if (focused) Color.Black else Color.White,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp).alpha(contentAlpha),
            )
            border?.let { Box(Modifier.matchParentSize().border(it, CircleShape)) }
        }
    }
}

/** 抓起态的浮起: 与描边 ([tvChromeGrabbedBorder]) 一起标出"这颗正在手里". */
@Composable
private fun Modifier.tvChromeGrabbedLift(grabbed: Boolean): Modifier {
    val scale by animateFloatAsState(if (grabbed) TV_CHROME_GRABBED_SCALE else 1f, label = "grabbedScale")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 抓起态的主题色描边: 与聚焦反色叠着看 —— 白底黑图标之外再套一圈主题色.
 *
 * 交给各按钮外壳的 `border` 参数, 由它画在按钮自己的形状上、且盖在内容之上 (两条理由见 `TvPillShell` 的同名参数):
 * 挂成外层 `Modifier.border` 会被最小触控区撑大, 交给 Surface 的 `border` 则会被聚焦时的白色背景盖住.
 *
 * 取 `inversePrimary` 而不是 `primary`: 抓起的那颗同时也是聚焦态, 底色是白的, 而深色配色里的 `primary`
 * 是浅紫 (tone 80), 压在白底上对比不够; `inversePrimary` 是中深紫, 在白底上立得起来, 外沿落在近黑的
 * 页面上也还分得清. 整页强制深色 (见上面), 所以它不会随 app 主题漂.
 */
@Composable
private fun tvChromeGrabbedBorder(grabbed: Boolean): BorderStroke? =
    if (grabbed) BorderStroke(TV_CHROME_GRABBED_BORDER_WIDTH, MaterialTheme.colorScheme.inversePrimary) else null

/** 静态进度条行: 本页不播放任何东西, 这一行只为把上下两行按真实间距撑开. */
@Composable
private fun TvChromeFakeProgressRow(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().alpha(0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("00:00", style = MaterialTheme.typography.labelLarge, color = Color.White)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(TV_CHROME_FAKE_PROGRESS)
                    .height(4.dp)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
        Text("24:00", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** 隐藏态的内容不透明度: 看得出是"灰掉了", 又不至于在聚焦反色下糊成一片. */
private const val TV_CHROME_HIDDEN_ALPHA = 0.32f

/** 抓起时按钮放大的倍数. */
private const val TV_CHROME_GRABBED_SCALE = 1.14f

/**
 * 抓起态描边的粗细.
 *
 * 3dp 而不是 2dp: 2dp 在电视的密度下只有三四个像素宽, 左右各被抗锯齿吃掉一个, 中间几乎没有实心像素 ——
 * 肉眼偏淡, 取色器也只取得到与背景混过的边缘值.
 */
private val TV_CHROME_GRABBED_BORDER_WIDTH = 3.dp

private const val TV_CHROME_FAKE_PROGRESS = 0.36f

private const val TV_CHROME_HIDDEN_SEPARATOR = " · "
