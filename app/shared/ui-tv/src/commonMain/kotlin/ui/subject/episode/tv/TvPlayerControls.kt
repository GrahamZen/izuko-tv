/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.danmaku.DanmakuEditorState
import me.him188.ani.app.ui.episode.share.MediaShareData
import me.him188.ani.app.ui.external.placeholder.placeholder
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.animation.StandardAccelerateEasing
import me.him188.ani.app.ui.foundation.animation.StandardDecelerateEasing
import me.him188.ani.app.ui.foundation.theme.EasingDurations
import me.him188.ani.app.ui.foundation.tv.TV_PILL_ICON_SIZE
import me.him188.ani.app.ui.foundation.tv.TvPillShell
import me.him188.ani.app.ui.foundation.focus.restoreFocusAfter
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.icons.AniIcons
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.icons.Forward80
import me.him188.ani.app.ui.foundation.icons.Forward85
import me.him188.ani.app.ui.foundation.icons.Forward90
import me.him188.ani.app.ui.foundation.icons.SubtitleGear
import me.him188.ani.app.ui.foundation.watchtogether.LocalWatchTogetherEntry
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_comments
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_episode_cache
import me.him188.ani.app.ui.lang.subject_episode_danmaku_settings_title
import me.him188.ani.app.ui.lang.subject_episode_external_links
import me.him188.ani.app.ui.lang.subject_episode_fast_forward_seconds
import me.him188.ani.app.ui.lang.subject_episode_related_recommendations
import me.him188.ani.app.ui.lang.subject_episode_select_media_source
import me.him188.ani.app.ui.lang.video_player_disable_danmaku
import me.him188.ani.app.ui.lang.video_player_enable_danmaku
import me.him188.ani.app.ui.lang.video_player_next_episode
import me.him188.ani.app.ui.lang.video_player_stats_title_hide
import me.him188.ani.app.ui.lang.video_player_stats_title_show
import me.him188.ani.app.ui.lang.video_player_tv_cancel_skip_segment
import me.him188.ani.app.ui.lang.video_player_tv_collection
import me.him188.ani.app.ui.lang.video_player_tv_restart
import me.him188.ani.app.ui.lang.video_player_tv_skip_segment
import me.him188.ani.app.ui.lang.watch_together_title
import me.him188.ani.app.ui.mediaselect.common.SourceIcon
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummary
import me.him188.ani.app.ui.subject.collection.components.EditCollectionTypeDropDown
import me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeDialogsHost
import me.him188.ani.app.ui.subject.person.PeoplePreviewHost
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.app.ui.subject.episode.details.components.ShareEpisodeDropdown
import me.him188.ani.app.ui.subject.episode.video.SkipOpEdKind
import me.him188.ani.app.ui.subject.episode.video.SkipOpEdTip
import me.him188.ani.app.ui.subject.episode.video.components.EpisodeVideoSideSheetPage
import me.him188.ani.app.videoplayer.ui.PlaybackSpeedControllerState
import me.him188.ani.app.videoplayer.ui.VideoAspectRatioControllerState
import me.him188.ani.app.videoplayer.ui.VideoSideSheetsController
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressSlider
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressSliderDefaults
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults.SpeedSwitcher
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults.VideoAspectRatioSelector
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState
import me.him188.ani.app.videoplayer.ui.progress.ProgressSliderPreviewStyle
import me.him188.ani.app.videoplayer.ui.progress.SubtitleSwitcher
import me.him188.ani.app.videoplayer.ui.top.SystemTime
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.features.subtitleTracks
import kotlin.time.Duration

// ---- 布局调参 ----

/** 内容水平留白 (Prime 风格大留白). */
internal val TV_PLAYER_HORIZONTAL_PAD = 48.dp

/** 底部渐变 scrim 高度. */
private val TV_PLAYER_BOTTOM_SCRIM_HEIGHT = 380.dp

/**
 * 底部渐变 scrim 最深处不透明度.
 *
 * 只要托住白字可读即可, 不必压到近黑: 0.95 时屏幕下半条几乎是纯黑, 画面被切掉一块
 * (进度条一出来尤其明显). 控件都是白字/白图标, 0.72 下最亮的画面也只剩三成亮度, 对比够了.
 */
private const val TV_PLAYER_BOTTOM_SCRIM_ALPHA = 0.72f

/** 顶部渐变 scrim 高度 (标题可读性). */
private val TV_PLAYER_TOP_SCRIM_HEIGHT = 180.dp

/**
 * 顶部渐变 scrim 最深处不透明度.
 *
 * 比底部压得更狠: 顶部只有标题、数据源与时钟, 压黑没有代价; 而底部常年压着字幕,
 * **不能**跟着一起加深 —— 用户很多时候是边看字幕的.
 */
private const val TV_PLAYER_TOP_SCRIM_ALPHA = 0.64f

/** 顶部信息里数据源图标的尺寸. */
private val TV_PLAYER_SOURCE_ICON_SIZE = 18.dp

/**
 * 顶部那行"数据源 · 资源标题"里, **非精确匹配**的资源标题用色 (暖琥珀).
 *
 * 不用 error 色: 非精确匹配很常见 (合集/多季/别名), 报红会让人以为坏了; 只要比旁边的灰白抢眼
 * 一点, 足够让"播的根本不是这部番"这种情况被一眼看见.
 */
private val TV_PLAYER_INEXACT_MATCH_COLOR = Color(0xFFFFD180)

/**
 * 进度条行与其上下两行 (胶囊行 / 图标行) 的间距, 上下必须用同一个值 —— 原本是上 18dp 下 6dp,
 * 进度条明显偏下, 观感是三行没对齐.
 *
 * **屏幕上看到的空白比这个值大**: 进度条控件本体高 24dp 而可见轨道只有居中的 6dp, 上下各还有
 * 9dp 是控件内部的留白 (对称, 所以本常量一致 = 看到的空白一致). 也就是说实际观感 ≈ 本值 + 9dp,
 * 这一档已经贴得相当紧了; 进度条行本身那点 `padding(vertical)` 已经去掉, 别再加回来.
 */
private val TV_PLAYER_PROGRESS_ROW_GAP = 4.dp

/**
 * 控制层 (L1) + 浮出面板 (L2).
 *
 * 布局 (自下而上): 图标行 / 进度条行 / 胶囊按钮行 / (聚焦胶囊时) 浮出面板.
 * 顶部: 左上标题 (Prime 风格) + 右上系统时钟.
 */
@Composable
internal fun TvPlayerControlsOverlay(
    overlay: TvPlayerOverlayState,
    vm: EpisodeViewModel,
    page: EpisodePageState,
    /** 片尾「接下来播放」的状态: 选集条的倒计时态读它 (见 TvPlayerEpisodeStrip). */
    upNext: TvUpNextState,
    danmakuEditorState: DanmakuEditorState,
    progressSliderState: PlayerProgressSliderState,
    /** 拖拽预览的帧源 (小圆点上方缩略图); null = 设置里关掉了帧预览, 浮窗只剩时间. */
    framePreview: MediaProgressFramePreviewState?,
    playerFocus: TvFocusScope,
    sheetsController: VideoSideSheetsController<EpisodeVideoSideSheetPage>,
    /**
     * 控制层本体可见吗. false = 本层只是**为了托住 [pillsRowTrailing] 里那两个浮层控件而留在
     * 场上**: 除它们之外一切淡出到透明, 布局仍在 (胶囊行与进度条行), **但图标行会一并收掉** ——
     * 于是它们停在"只剩进度条与胶囊行"那一档的位置, 与焦点落在胶囊行时的收起态完全一致
     * (见 `showBelowProgress`).
     *
     * 为什么不让它们自己浮在屏幕上按实测坐标跟随: 试过, 位置得等下一帧才到, 图标行收起那段
     * 逐帧动画里总慢一拍. 当成行内一员由布局直接给出位置, 就没有"跟随"这回事了.
     */
    chromeVisible: Boolean,
    /**
     * [pillsRowTrailing] 此刻有没有东西 (OP/ED 提示按钮).
     *
     * 控制层不可见时本层留在场上的**唯一**理由就是托住它; 没有它就该整列一起退场 —— 否则
     * "选集条展开着按了点什么把整层收掉"的那一下, 这一列会因为 `!chromeVisible` 翻真而反过来
     * 淡入, 屏幕上是播放器组件凭空冒一下.
     */
    trailingVisible: Boolean,
    /**
     * 胶囊行最右的插槽 (OP/ED 提示按钮 / 片尾「接下来播放」卡片): 与胶囊同排靠右, **底边与胶囊
     * 对齐**, 位置由布局给出.
     *
     * 两个都放这里而不是各自找地方: 面板是从本行**上方**浮出的, 放在本行之上的东西会被面板顶得
     * 跳来跳去; 本行自己不动, 插槽也就不动. 高于胶囊的内容 (卡片) 向上长, 胶囊与进度条的间距不变.
     */
    pillsRowTrailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 控制层本体的淡入淡出. 整层的显隐原本由外面的 AniAnimatedVisibility 做, 现在本层要为提示
    // 按钮多活一会儿, 淡出就得挪到里面来 —— 只作用于"除那颗按钮之外"的部分, 时长与那边的
    // fadeIn/fadeOut 取同一档 (EasingDurations), 观感不变.
    //
    // **不能用 `by` 解构**: 那是在组合里读, 淡入淡出的每一帧都会重组整个控制层.
    // 留着 State 本体, 在 graphicsLayer 的 lambda 里读 —— 每帧只失效图层 (见本文件的重组纪律)
    val chromeAlpha = animateFloatAsState(
        targetValue = if (chromeVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (chromeVisible) {
                EasingDurations.standardDecelerate
            } else {
                EasingDurations.standardAccelerate
            },
            easing = if (chromeVisible) StandardDecelerateEasing else StandardAccelerateEasing,
        ),
        label = "TvPlayerControlsChrome",
    )
    // 淡入淡出期间的合成策略. 默认的 `CompositingStrategy.Auto` 在 alpha ∈ (0,1) 时会给**每个**
    // 挂了本修饰符的节点各开一次离屏缓冲 (saveLayerAlpha), 而本层有 8 个挂载点, 其中两条 scrim
    // 是全宽 × 380dp / 180dp 的大面积 —— 强制原生 4K UI 的机器上单条 scrim 就是约 3840×760 的
    // ARGB 缓冲 (十几 MB). 控制层每 5 秒自动隐藏一次、每次按键又唤出一次, 这是电视上最高频的
    // 动画路径.
    //
    // [CompositingStrategy.ModulateAlpha] 不开离屏缓冲, 直接把 alpha 乘进每个绘制调用; 代价是
    // **层内内容重叠处会互相透视**. 按这个前提分两档, 别整层一刀切:
    // - [chrome]: 两条渐变 scrim (单个 Box 一层渐变) 与文字/图标/胶囊行 (白字白图标平铺).
    //   重叠只发生在同色元素之间, 淡入淡出的两百毫秒里看不出来
    // - [chromeLayered]: 浮出面板与选集条. 卡片有底色 + 图片 + 阴影相叠, 透视是能看见的,
    //   老老实实开离屏缓冲
    //
    // 另外补一道 alpha == 0 的绘制闸门: 手动档下"整层为托住提示按钮而留在场上"这个窗口是整段
    // OP/ED (80~95 秒), 期间除按钮外一个像素都不该画 —— alpha=0 的图层只是不合成, 内容照样
    // 要走一遍绘制. 闸门读在绘制 lambda 里, 只失效绘制不重组.
    val chromeGate = Modifier.drawWithContent { if (chromeAlpha.value > 0f) drawContent() }
    val chrome = chromeGate.graphicsLayer {
        alpha = chromeAlpha.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    val chromeLayered = chromeGate.graphicsLayer { alpha = chromeAlpha.value }
    Box(modifier) {
        // 选集条展开态不整屏压暗: 内容全部贴底, 上下两条渐变 scrim 已足够托住可读性,
        // 画面中部保持通透
        // 底部渐变 scrim (托住控制行与面板).
        //
        // **IME 弹出时要长出键盘那一截**: 控制层内容被 imePadding 抬到键盘上方 (见下面那个 Box),
        // 而遮罩最暗的一端在屏幕底缘 —— 不管的话内容落在渐变中段, 字发飘看不清 (2026-08-25 用户反馈).
        // 补长的那段用**实色**而不是把渐变整体拉伸: 渐变段的观感与没有键盘时逐像素一致, 内容照旧
        // 压在暗端上; 而底缘仍被盖住, 不会像"整条遮罩跟着抬"那样在键盘上方切出一条硬边.
        val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottomScrimHeight = TV_PLAYER_BOTTOM_SCRIM_HEIGHT + imeBottomPadding
        val gradientEnd = if (bottomScrimHeight > 0.dp) {
            (TV_PLAYER_BOTTOM_SCRIM_HEIGHT / bottomScrimHeight).coerceIn(0f, 1f)
        } else {
            1f
        }
        Box(
            chrome
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomScrimHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        gradientEnd to Color.Black.copy(alpha = TV_PLAYER_BOTTOM_SCRIM_ALPHA),
                        1f to Color.Black.copy(alpha = TV_PLAYER_BOTTOM_SCRIM_ALPHA),
                    ),
                ),
        )
        // 顶部渐变 scrim (标题/时钟可读性)
        Box(
            chrome
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(TV_PLAYER_TOP_SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = TV_PLAYER_TOP_SCRIM_ALPHA),
                        1f to Color.Transparent,
                    ),
                ),
        )

        // 面板向上生长的边界实测: 顶部信息 (标题/时钟) 的下缘与胶囊行的上缘.
        // 面板最大高度 = 两者间距 (见 TvPlayerPanelHost), 4K 一类大逻辑分辨率下不再锁死小窗.
        // 写的是 window 坐标原始 px; 只在面板的测量阶段读, 位置变化不触发本层重组
        val topInfoBottomPx = remember { mutableFloatStateOf(Float.NaN) }
        val pillsRowTopPx = remember { mutableFloatStateOf(Float.NaN) }

        // 顶部信息: 左上标题 + 右上时钟
        TvPlayerTopInfo(
            page,
            chrome
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = TV_PLAYER_HORIZONTAL_PAD, vertical = 28.dp)
                // 放在 padding 之后: 量的是标题块本体的下缘 (可见元素边界), 不含留白
                .onGloballyPositioned { topInfoBottomPx.floatValue = it.boundsInWindow().bottom },
        )

        // 底部区域: 控制行 (胶囊/进度条/图标行) 与选集条二选一, 在 Box 里底对齐重叠
        // 淡切 —— 不能上下堆叠在 Column 里: 切换瞬间选集条先占位会把控制行整体上顶,
        // 观感是进度条向上闪动一下再消失.
        // 水平留白只加在控制行上, 选集条以全宽放置 (卡片行要出血画到屏幕右缘,
        // 停靠留边由轮播内部 contentPadding 提供).
        // 焦点在胶囊行/浮出面板时只露到进度条为止 (Prime 行为): 进度条以下的
        // 图标行暂隐, 焦点回到进度条/图标行再出现.
        // derivedStateOf: focusRegion 每次方向键都在变, 直接读会让整个覆盖层
        // (scrim/标题/胶囊/面板) 随每步导航重组; 收窄成布尔翻转才失效
        val hideBelowProgress by remember {
            derivedStateOf {
                overlay.focusRegion == TvPlayerFocusRegion.PILLS ||
                        overlay.focusRegion == TvPlayerFocusRegion.PANEL
            }
        }
        // **控制层淡出时图标行一并收掉** (2026-09-05 改): 那时本层留在场上只为托住 trailing 那两个
        // 浮层控件 (OP/ED 提示按钮 / 「接下来播放」卡片), 而它们该停在"只剩进度条与胶囊行"那一档的
        // 位置上 —— 那是它们离画面底缘最近、最不挡画面的位置, 也与焦点落在胶囊行时的收起态完全一致,
        // 于是这两种情形下位置一模一样, 不会因为控制层收没收起而上下跳.
        //
        // 这一条推翻了原来"图标行绝不能摘掉、否则按钮往下掉"的做法 —— 往下掉正是现在要的,
        // 而且 AniAnimatedVisibility 会把这一段高度变化做成动画, 唤出控制层时位置自己滑回去.
        // **进度条行仍然不许摘**: 摘了那两个控件就贴到屏幕底缘了.
        val showBelowProgress = !hideBelowProgress && chromeVisible
        // 每个胶囊按钮的焦点请求器: 面板最底项按下键显式回到"打开它的那个胶囊" ——
        // 靠空间搜索会落到面板正下方的任意按钮, 落错后该按钮又把面板切成自己的 (卡片跳变)
        val pillFocusRequesters = remember { TvPlayerPanel.entries.associateWith { FocusRequester() } }
        // 角色 / 制作人员胶囊按下确定: 弹与详情页同一个「查看全部」大网格 (见 TvPlayerPeopleViewAllDialog).
        // 只会是 STAFF / CHARACTERS 两颗; null = 没开.
        var peopleViewAll by remember { mutableStateOf<TvPlayerPanel?>(null) }
        // 人物预览是从那个弹窗点开的 (弹窗先关、预览再开), 关掉之后焦点要还给**胶囊**而不是面板条目.
        // 从面板条目点开的那条路不置这个标志, 仍旧还回条目
        var peoplePreviewFrom by remember { mutableStateOf<TvPlayerPanel?>(null) }
        // 进度条按下键的显式落点: 图标行最左按钮 (全宽进度条交给空间搜索会落到中间按钮)
        val bottomRowFirstFocus = remember { FocusRequester() }
        // 发弹幕弹出 IME 时**只抬这一列, 遮罩与画面都不动**:
        //  - 画面不动靠 TV 变体声明的 adjustNothing (见 tv/AndroidManifest.xml) —— 窗口一旦被系统
        //    顶起, SurfaceView 跟着缩放, 画面下半被拉长; 而暂停时不再产生新帧, 那一帧就一直错着;
        //  - 遮罩不动是因为 imePadding 必须挂在**内容**上而不是控制层根部: 挂根部会把两条贴边的
        //    渐变 scrim 一起抬走, 于是键盘上方切出一条硬边、下面的画面又亮回去 (2026-08-25 实测).
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .imePadding()
                .padding(vertical = 20.dp),
        ) {
            AniAnimatedVisibility(
                // 控制层不可见时只在**真有提示按钮**时留着这一列 (它此刻的唯一职责就是托住它);
                // 选集条的展开态在 hideAll 里并不复位, 所以这里不能只看那个标志
                visible = !overlay.episodeStripExpanded || (!chromeVisible && trailingVisible),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Column(Modifier.padding(horizontal = TV_PLAYER_HORIZONTAL_PAD)) {
                    // L2 浮出面板 (聚焦胶囊按钮时出现, 条目吸底, 从下往上导航).
                    // PeoplePreviewHost: 角色/制作人员面板点击卡片弹居中人物预览
                    // (与详情页同一弹窗), 开着期间抑制控制层自动隐藏
                    PeoplePreviewHost(
                        onPreviewOpenChanged = { open ->
                            overlay.onPopupExpandedChanged(open)
                            // 预览关掉后焦点还给刚点开的那张卡片: 弹窗节点被移除时 Compose 会清掉
                            // 整棵树的焦点 (不交给祖先), 不还的话面板还在但方向键全失效.
                            //
                            // 从「查看全部」弹窗点开的那条路除外: 那时面板里一张卡都没被点过,
                            // 塞进面板与用户离开时的位置对不上. 只清标志, 焦点由胶囊上的
                            // restoreFocusAfter 接手 (它等的正是"这一整段结束")
                            if (!open) {
                                if (peoplePreviewFrom != null) peoplePreviewFrom = null
                                else overlay.requestPanelItemFocus()
                            }
                        },
                    ) {
                        TvPlayerPanelHost(
                            // 卡片底色/图片/阴影相叠, 走整层合成 (见 chrome 处的分档)
                            modifier = chromeLayered,
                            overlay = overlay,
                            vm = vm,
                            page = page,
                            pillFocusRequesters = pillFocusRequesters,
                            // NaN (尚未测得) 由面板侧兜底成固定高度
                            availableHeightPx = { pillsRowTopPx.floatValue - topInfoBottomPx.floatValue },
                        )
                        // 弹窗要在 PeoplePreviewHost **之内**: 点卡片走的是 rememberPeopleClickHandler,
                        // 拿不到 host 时它会退化成"导航去人物详情页" —— 那是直接离开播放器
                        peopleViewAll?.let { panel ->
                            TvPlayerPeopleViewAllDialog(
                                vm = vm,
                                panel = panel,
                                onDismissRequest = { peopleViewAll = null },
                                onOpenPreview = {
                                    // 先记来路再关窗: 关窗那一步会触发胶囊的 restoreFocusAfter 判据,
                                    // 而这一段还没结束 (预览马上就要开)
                                    peoplePreviewFrom = panel
                                    peopleViewAll = null
                                },
                            )
                        }
                    }
                    // 弹窗是独立窗口, 按键不经根路由 -> interactionTick 不再自增, 控制层会被
                    // 5 秒自动隐藏吃掉 (与一起看弹窗同一套引用计数上报)
                    // 开合状态先落成本次组合的局部值: onDispose 里读 peopleViewAll 拿到的是**当前**值
                    // (它是 snapshot state), 关窗那一次 dispose 时它已经是 null, 计数就减不回去了
                    val viewAllOpen = peopleViewAll != null
                    DisposableEffect(viewAllOpen) {
                        if (viewAllOpen) overlay.onPopupExpandedChanged(true)
                        onDispose { if (viewAllOpen) overlay.onPopupExpandedChanged(false) }
                    }

                    // 胶囊按钮行 (+ 弹幕发送展开框).
                    //
                    // 拖拽预览中按成隐形: 缩略图浮窗就浮在圆点上方, 正好压在这一行上,
                    // 两层叠着看不清. 只按 alpha 不移出组合 —— 节点还在, 上键的落点请求器
                    // 保持附着, 而按上键会让焦点离开进度条, 预览随即提交, 这一行同帧就回来了.
                    // 状态读在 graphicsLayer 的 lambda 里, 圆点每走一步只失效图层不重组整层
                    TvPlayerPillsRow(
                        overlay = overlay,
                        danmakuEditorState = danmakuEditorState,
                        vm = vm,
                        pillFocusRequesters = pillFocusRequesters,
                        onNewComment = { openNewEpisodeComment(vm, page, overlay) },
                        onViewAllPeople = { peopleViewAll = it },
                        // 「查看全部」这条路占着焦点的整段: 弹窗开着, 以及从它点开的人物预览还开着.
                        // 中途还一次焦点会与正要打开的预览抢
                        viewAllPeopleActive = peopleViewAll ?: peoplePreviewFrom,
                        trailing = pillsRowTrailing,
                        // 胶囊本体跟着控制层淡出, 末尾那颗提示按钮不跟 (它有自己的显示时长)
                        pillsModifier = chrome,
                        modifier = Modifier
                            .padding(bottom = TV_PLAYER_PROGRESS_ROW_GAP)
                            // 放在 padding 之后: 量的是胶囊本体的上缘 (可见元素边界), 不含那段间距.
                            // 这是面板的下锚点 (面板在同一 Column 里紧贴本行之上)
                            .onGloballyPositioned { pillsRowTopPx.floatValue = it.boundsInWindow().top }
                            .graphicsLayer { alpha = if (progressSliderState.isPreviewing) 0f else 1f },
                    )

                    // 进度条行.
                    // 控制层不可见时**不能从组合里摘掉**, 只淡到透明: 它撑着胶囊行到屏幕底缘的距离,
                    // 摘掉的话那颗提示按钮会当场往下掉一截 (下面的图标行同理)
                    TvPlayerProgressRow(
                        live = chromeVisible,
                        vm = vm,
                        progressSliderState = progressSliderState,
                        framePreview = framePreview,
                        overlay = overlay,
                        // 图标行暂隐期间不能指向它 (节点已移出组合, 未附着的请求器会抛)
                        downFocus = bottomRowFirstFocus.takeIf { showBelowProgress },
                        upFocus = pillFocusRequesters.getValue(TV_PILL_VISUAL_ORDER.first()),
                        modifier = chrome.tvFocusAnchor(playerFocus, TvPlayerFocusTarget.PROGRESS),
                    )

                    // 图标行 (原顶栏按钮并入; 再往下 = 选集条, 由根路由处理).
                    // 拖拽预览中同样按成隐形 (同上: 那会儿唯一该看的是圆点和缩略图)
                    AniAnimatedVisibility(visible = showBelowProgress) {
                        Column(
                            chrome.graphicsLayer {
                                alpha = if (progressSliderState.isPreviewing) 0f else 1f
                            },
                        ) {
                            Spacer(Modifier.height(TV_PLAYER_PROGRESS_ROW_GAP))
                            TvPlayerBottomRow(
                                overlay = overlay,
                                vm = vm,
                                page = page,
                                sheetsController = sheetsController,
                                firstButtonFocus = bottomRowFirstFocus,
                                upFocus = playerFocus.requesterOf(TvPlayerFocusTarget.PROGRESS),
                                modifier = Modifier.tvFocusAnchor(
                                    playerFocus,
                                    TvPlayerFocusTarget.BOTTOM_ROW,
                                ),
                            )
                        }
                    }
                }
            }

            // 选集条: 仅展开态渲染 (无 peek), 图标行按下键唤出, 与控制行同位淡切
            TvPlayerEpisodeStrip(
                vm = vm,
                overlay = overlay,
                upNext = upNext,
                rowFocusModifier = Modifier.tvFocusAnchor(
                    playerFocus,
                    TvPlayerFocusTarget.EPISODE_STRIP,
                ),
                // 同 TvPlayerPanelHost: 卡片行要整层合成
                modifier = chromeLayered.align(Alignment.BottomStart).fillMaxWidth(),
            )
        }
    }
}

/** 顶部信息: 左上大标题 + 集号副标题 (Prime 风格), 右上系统时钟. */
@Composable
private fun TvPlayerTopInfo(
    page: EpisodePageState,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        val episode = page.episodePresentation
        val subject = page.subjectPresentation
        Column(
            Modifier
                .weight(1f)
                .placeholder(episode.isPlaceholder || subject.isPlaceholder),
        ) {
            Text(
                subject.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "第 ${episode.ep} 集  ${episode.title}",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 当前数据源 (纯展示, 不进焦点树): 只为看清正在用哪个源, 换源入口仍是
            // 图标行的"选择数据源". 未选出结果时 (自动选择中/需手动) 整行不显示
            val summary = page.mediaSelectorSummary
            if (summary is MediaSelectorSummary.Selected) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (summary.source.sourceIconUrl.isNotEmpty()) {
                        SourceIcon(
                            iconUrl = summary.source.sourceIconUrl,
                            sourceName = summary.source.sourceName,
                            Modifier.size(TV_PLAYER_SOURCE_ICON_SIZE),
                        )
                    }
                    Text(
                        summary.source.sourceName,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 数据源后面跟上**这条资源自己的标题** (mediaTitle = media.originalTitle).
                    //
                    // 为什么值得占这行地方: web 源是"搜出什么就信什么"的 —— 站点搜不到本番时会返回
                    // 泛列表, 里面任何一条带「第01集」的都能过集号校验, 于是自动选源可能播的是**完全
                    // 无关的作品** (2026-08-25 实测: 找 BanG Dream 播出了《能帮我弄干净吗？》).
                    // 在那之前界面上没有任何线索, 用户只会觉得"这番怎么不对". 标题一摆出来就露馅.
                    //
                    // 非精确匹配时用暖色: 这类才是需要多看一眼的, 精确匹配照旧不抢眼.
                    Text(
                        "·",
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        summary.mediaTitle,
                        color = if (summary.isPerfectMatch) {
                            Color.White.copy(alpha = 0.6f)
                        } else {
                            TV_PLAYER_INEXACT_MATCH_COLOR
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 选中的这条**带着排除原因**时把原因也写出来: 自动选择的快速通道与用户手选都能
                    // 越过排除表, 而"条目名不匹配"意味着这根本不是当前这部番 —— 只把标题摆出来还要求
                    // 用户自己去比对, 直接说破更省事.
                    summary.exclusionReason?.let { reason ->
                        Text(
                            when (reason) {
                                MediaExclusionReason.SubjectNameMismatch -> "条目名不匹配"
                                MediaExclusionReason.FromSeriesSeason -> "其他季度"
                                else -> "已被过滤"
                            },
                            color = TV_PLAYER_INEXACT_MATCH_COLOR,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier
                                .background(
                                    TV_PLAYER_INEXACT_MATCH_COLOR.copy(alpha = 0.16f),
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(24.dp))
        SystemTime()
    }
}

/**
 * 胶囊按钮行的视觉顺序 (左→右), 与 [TvPlayerPillsRow] 的排列保持一致:
 * 面板条目上按左/右键路由到相邻胶囊 (见 [TvPlayerPanelHost]) 的依据.
 */
internal val TV_PILL_VISUAL_ORDER = listOf(
    TvPlayerPanel.RECOMMENDATIONS,
    TvPlayerPanel.STAFF,
    TvPlayerPanel.CHARACTERS,
    TvPlayerPanel.COMMENTS,
    TvPlayerPanel.DANMAKU_LIST,
)

/** 胶囊按钮行: 相关推荐 / 制作人员 / 角色 / 评论 / 弹幕列表 (+ 弹幕发送展开框) + 靠右的 [trailing]. */
@Composable
private fun TvPlayerPillsRow(
    overlay: TvPlayerOverlayState,
    danmakuEditorState: DanmakuEditorState,
    vm: EpisodeViewModel,
    pillFocusRequesters: Map<TvPlayerPanel, FocusRequester>,
    /** 评论胶囊按下确定: 发表本集评论 (见 [openNewEpisodeComment]). */
    onNewComment: () -> Unit,
    /** 角色 / 制作人员胶囊按下确定: 开对应的「查看全部」弹窗. */
    onViewAllPeople: (TvPlayerPanel) -> Unit,
    /** 上面那条路正占着焦点的是哪一颗胶囊 (弹窗或它点开的人物预览还开着); null = 都关了. */
    viewAllPeopleActive: TvPlayerPanel?,
    /** 行末靠右对齐的插槽 (OP/ED 提示按钮): 不受 [pillsModifier] 影响, 有自己的显示时长. */
    trailing: @Composable () -> Unit,
    /** 只作用于胶囊本体那一组 (控制层淡出), 不含 [trailing]. */
    pillsModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    // 正开着的是不是"发表评论"那个弹窗 (评论胶囊点出来的那个, 没有引用区): 关掉后焦点要还给
    // 那颗胶囊. 只在弹窗开合时变一次, 不是每帧都动的热状态
    val composingNewComment = overlay.replyingComment.let { it != null && it.quoted == null }
    // **底边对齐而不是居中**: trailing 里可能是比胶囊高的东西 (片尾「接下来播放」卡片).
    // 居中的话行一变高胶囊就往上挪, 而且卡片会往下探进胶囊与进度条之间那道间距里;
    // 底边对齐则胶囊纹丝不动、卡片一律向上长. 两边一样高时 (只有提示按钮) 与居中完全等价
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        // 胶囊本体单独成组占满剩余宽度, 把 trailing 顶到最右.
        // 焦点区域上报只挂这一组, 不含 trailing: 那颗按钮聚焦时不该把图标行收起
        // (hideBelowProgress 判的就是 PILLS) —— 提示一出现焦点就落过去, 控制层会当场塌一档
        Row(
            pillsModifier
                .weight(1f)
                .onFocusChanged { if (it.hasFocus) overlay.focusRegion = TvPlayerFocusRegion.PILLS },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvPlayerPill(
                icon = { Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(TV_PILL_ICON_SIZE)) },
                label = stringResource(Lang.subject_episode_related_recommendations),
                panel = TvPlayerPanel.RECOMMENDATIONS,
                overlay = overlay,
                focusRequester = pillFocusRequesters.getValue(TvPlayerPanel.RECOMMENDATIONS),
            )
            TvPlayerPill(
                icon = { Icon(Icons.Rounded.Groups, null, Modifier.size(TV_PILL_ICON_SIZE)) },
                label = stringResource(Lang.subject_details_staff),
                panel = TvPlayerPanel.STAFF,
                overlay = overlay,
                focusRequester = pillFocusRequesters.getValue(TvPlayerPanel.STAFF),
                // 与评论胶囊同一个道理 (见那颗的注释): 默认的"把焦点送进面板"与直接按上键完全重复.
                // 这一下改成弹详情页那份「查看全部」大网格 —— 聚焦时浮出的窄面板只够扫一眼,
                // 而这两类内容在播放器里没有别的入口 (内嵌详情页是精简版, 没有这两个区块)
                onClick = { onViewAllPeople(TvPlayerPanel.STAFF) },
                modifier = Modifier.restoreFocusAfter(
                    viewAllPeopleActive == TvPlayerPanel.STAFF,
                    abandon = { overlay.layer != TvPlayerLayer.CONTROLS },
                ),
            )
            TvPlayerPill(
                icon = { Icon(Icons.Rounded.Face, null, Modifier.size(TV_PILL_ICON_SIZE)) },
                label = stringResource(Lang.subject_details_characters),
                panel = TvPlayerPanel.CHARACTERS,
                overlay = overlay,
                focusRequester = pillFocusRequesters.getValue(TvPlayerPanel.CHARACTERS),
                onClick = { onViewAllPeople(TvPlayerPanel.CHARACTERS) },
                modifier = Modifier.restoreFocusAfter(
                    viewAllPeopleActive == TvPlayerPanel.CHARACTERS,
                    abandon = { overlay.layer != TvPlayerLayer.CONTROLS },
                ),
            )
            TvPlayerPill(
                icon = { Icon(Icons.AutoMirrored.Rounded.Comment, null, Modifier.size(TV_PILL_ICON_SIZE)) },
                label = stringResource(Lang.episode_comments),
                panel = TvPlayerPanel.COMMENTS,
                overlay = overlay,
                focusRequester = pillFocusRequesters.getValue(TvPlayerPanel.COMMENTS),
                // 本颗胶囊的点击另有其用: 发表本集评论.
                //
                // 默认的"把焦点送进面板"与直接按上键完全重复 (面板早在聚焦本胶囊时就浮出来了),
                // 这一下等于白按; 而发新评论此前在 TV 上没有任何入口 —— 只能回复已有评论,
                // 手机端那颗「发送评论」FAB 在遥控器形态下没有对应物
                onClick = onNewComment,
                // 弹窗关掉后焦点还给本胶囊: 弹窗抢焦点时本节点还在场 (控制层与面板都留在下面),
                // 但 Compose 不会自己还回来. 控制层已经收起时放弃 —— 那时焦点归属归根路由管
                modifier = Modifier.restoreFocusAfter(
                    composingNewComment,
                    abandon = { overlay.layer != TvPlayerLayer.CONTROLS },
                ),
            )
            // 「弹幕」一颗顶原来的两颗 (弹幕列表 + 发送弹幕): 聚焦浮出弹幕列表面板 (含源开关与
            // 延迟), 点击展开输入框发弹幕 —— 与「评论」那颗完全同一个模式 (聚焦看, 点击发),
            // 原来拆成两颗等于把同一件事的"看"和"发"摆成两个并列入口, 还多占一格横向导航
            TvDanmakuSendEntry(
                overlay = overlay,
                danmakuEditorState = danmakuEditorState,
                vm = vm,
                panelFocusRequester = pillFocusRequesters.getValue(TvPlayerPanel.DANMAKU_LIST),
            )
        }
        // **不参与行高**: trailing 里的「接下来播放」卡片比胶囊高一大截, 让它撑高本行的话,
        // 紧贴本行上方的浮出面板会被整个顶上去 (真机可见). 报 0 高、向上溢出绘制之后, 本行的
        // 高度永远只由胶囊决定, 卡片则从行的下边缘往上长 —— 面板与胶囊一动不动.
        // 横向照旧参与测量 (卡片要占住右边那块地方); 父容器不裁剪, 溢出部分正常绘制.
        Box(Modifier.hangAboveBaseline()) { trailing() }
    }
}

/**
 * 报 0 高、内容向上溢出: 放在 [Alignment.Bottom] 对齐的 Row 里, 效果是"底边贴着行的下边缘,
 * 高度不计入行高".
 */
private fun Modifier.hangAboveBaseline() = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
    )
    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
}

/**
 * "一起看"入口 (承担遥控器上没有的悬浮气泡的作用). 弹窗本体挂在应用根部, 这里只负责开与善后.
 *
 * 原先是胶囊行末尾那颗带文字的胶囊, 现已并入进度条下面的图标行, 与其余功能按钮同为圆钮.
 *
 * 显隐由 [TvPlayerBottomRow] 判断 —— 功能被关掉时本组合整个消失, 焦点善后只能由父级做.
 */
@Composable
private fun TvWatchTogetherButton(
    overlay: TvPlayerOverlayState,
    modifier: Modifier = Modifier,
) {
    val entry = LocalWatchTogetherEntry.current
    val dialogVisible = entry.dialogVisible
    // 弹窗是独立窗口, 根部那个唯一按键路由收不到它的按键 -> interactionTick 不再自增,
    // 五秒后控制层连同本按钮一起被自动隐藏吃掉. 按下拉弹层同一套引用计数上报住.
    DisposableEffect(dialogVisible) {
        if (dialogVisible) overlay.onPopupExpandedChanged(true)
        onDispose { if (dialogVisible) overlay.onPopupExpandedChanged(false) }
    }

    TvBottomRowIcon(
        icon = Icons.Rounded.SyncAlt,
        contentDescription = stringResource(Lang.watch_together_title),
        onClick = { entry.open(overDarkBackground = true) },
        // 关掉后焦点还给本按钮: 弹窗是独立窗口, 关闭时主窗口未必把焦点还到原处,
        // 不还的话控制层还在但方向键全失效.
        // 控制层已经收起时放弃: 那时焦点归属由根路由的解析器负责, 再抢就是打架
        modifier = modifier.restoreFocusAfter(
            dialogVisible,
            abandon = { overlay.layer != TvPlayerLayer.CONTROLS },
        ),
    )
}

/** 单个胶囊按钮: 聚焦白底黑字 (Prime 样式), 同时浮出对应面板. */
@Composable
private fun TvPlayerPill(
    icon: @Composable () -> Unit,
    label: String,
    panel: TvPlayerPanel,
    overlay: TvPlayerOverlayState,
    /** 面板最底项按下键经此回到本按钮 (空间搜索会落错按钮导致面板跳变). */
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    /** 默认是把焦点送进面板 (与上键一致); 另有动作的胶囊自己传 (见评论胶囊). */
    onClick: () -> Unit = { overlay.requestPanelFocus() },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    TvPillShell(
        highlighted = focused,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) overlay.activePanel = panel },
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * OP/ED 提示按钮 (Infuse / Prime Video 的 Skip Intro 同一位置与形态). 两副面孔:
 *
 * - `tip.canCancel` = true: 自动跳过正在倒计时, 按钮是"取消跳过 OP/ED";
 * - false: 人已经在 OP/ED 里 (刚按过取消, 或从别处 seek 进来), 按钮是"跳过 OP/ED", 按下直接
 *   跳到本段结尾. 于是整段 OP/ED 期间屏幕上始终有一颗可按的按钮.
 *
 * 文案里的 OP/ED 是**分开写**的 (`tip.kind`), 不含糊成"OP/ED": 哪一段由它在时间轴上的位置定
 * (见 PlayerSkipOpEdState), 既然判得出来就说清楚.
 *
 * 取代原来左下角那张 M3 浅色卡片 toast: 它按 `bottom = 140dp` 摆在左下, 正好压在胶囊按钮行上,
 * 且浅底深字与整个播放器的黑白控件完全不是一套.
 *
 * 它**就是胶囊行的最后一颗**, 与"相关推荐"那些按钮同排, 位置由布局直接给出 —— 那一行会上下动
 * (焦点落胶囊时图标行收起, 进度条连胶囊行一起下移, 还是逐帧动画), 作为行内一员天然跟得上.
 * 那条线的右半边永远是空的, 挡不到内容.
 *
 * 特殊之处只有两条: **显示时长自成一套** (由 PlayerSkipOpEdState 决定, 与改版前的 toast 一致),
 * 以及**出现时主动要焦点**. 控制层该 5 秒自动隐藏还是自动隐藏 —— 那时整行连同进度条淡到透明,
 * 但**布局照旧留在场上** (见 [TvPlayerControlsOverlay] 的 `chromeVisible`), 于是屏幕上只剩这颗
 * 按钮, 位置纹丝不动.
 *
 * 配色与胶囊完全一致 (未聚焦白 0.14, 聚焦白底黑字): 它就该看着像那一行的一员. 试过用面板条目
 * 那套黑玻璃让它更抓眼, 但控制层淡出后屏上只剩它一颗, 已经足够显眼, 反倒是两套底色摆在同一行
 * 上更扎眼.
 */
@Composable
internal fun TvSkipOpEdTipButton(
    tip: SkipOpEdTip,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    TvPillShell(
        highlighted = focused,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        Icon(
            if (tip.canCancel) Icons.Rounded.Close else Icons.Rounded.FastForward,
            null,
            Modifier.size(TV_PILL_ICON_SIZE),
        )
        Text(
            // 说清楚是 OP 还是 ED (按章节在时间轴上的位置判, 见 PlayerSkipOpEdState).
            // "OP"/"ED" 是原文照抄的行话, 各语言一样, 不进资源
            stringResource(
                if (tip.canCancel) {
                    Lang.video_player_tv_cancel_skip_segment
                } else {
                    Lang.video_player_tv_skip_segment
                },
                when (tip.kind) {
                    SkipOpEdKind.OP -> "OP"
                    SkipOpEdKind.ED -> "ED"
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/**
 * 遥控器形态下可调的倍速范围: 固定用播放器支持的全范围 (0.25x–4x), 见 [PlaybackSpeedControllerState] 处的注释.
 *
 * 直接开到硬上限而不是默认的 0.5x–2.5x: 既然"倍速范围"这条设置在遥控器上已经去掉了, 就别再替用户
 * 收窄. 代价是能选到 3 倍以上 —— 那时弹幕会跳 (上游 #1524, 长按倍速默认 2.5 就是为此), 低端盒子的
 * 解码与音频变速也会吃力, 但这是用户自己选的档位.
 */
private val TV_PLAYBACK_SPEED_RANGE =
    VideoScaffoldConfig.MIN_SUPPORTED_PLAYBACK_SPEED..VideoScaffoldConfig.MAX_SUPPORTED_PLAYBACK_SPEED

// ---- 控件尺寸 (Prime 密度: 初版的 80%) ----
// 胶囊那几个尺寸在 TvPillShell (ui/foundation/tv/TvPill.kt) 里, 与外壳放在一起
private val TV_ICON_BUTTON_SIZE = 38.dp
private val TV_ICON_SIZE = 20.dp

/** 图形几乎占满视口的图标 (如 Replay) 的补偿尺寸: 与留白多的图标视觉等大. */
private val TV_ICON_SIZE_VISUAL_COMPENSATED = 18.dp

/**
 * 进度条行: 左当前时间 + 中间进度条 + 右总时长; 整行是一个焦点节点 (左右键由根路由处理),
 * 示焦即进度圆点本身 —— 未聚焦时圆点隐藏, 聚焦时出现 (无光环, 不给整行加底色).
 *
 * 拖拽预览态 (根路由的 scrubStep) 下圆点脱离播放位置, 上方浮出缩略图 + 目标时间:
 * 那个浮窗是 [MediaProgressSlider] 里 `showPreviewTimeTextOnThumb` 那条分支画的,
 * 它锚在圆点上, 本来就是给"程序驱动的 detached slider"准备的, TV 这边只要把开关打开.
 */
@Composable
private fun TvPlayerProgressRow(
    vm: EpisodeViewModel,
    progressSliderState: PlayerProgressSliderState,
    framePreview: MediaProgressFramePreviewState?,
    overlay: TvPlayerOverlayState,
    /** 下键的显式落点 (图标行最左按钮): 整行全宽, 交给空间搜索会落到行中间的按钮. */
    downFocus: FocusRequester?,
    /** 上键的显式落点 (胶囊行最左按钮): 同 [downFocus], 空间搜索会落到行中间的胶囊上. */
    upFocus: FocusRequester,
    /**
     * 本行此刻看得见吗. false = 控制层已淡到透明, 本行只是**为了撑住胶囊行到屏幕底缘的距离**
     * 而留在布局里 (摘掉的话那颗 OP/ED 提示按钮会当场往下掉一截, 见调用处).
     *
     * 这时切断两条热订阅: 播放位置与缓存进度. 手动档下这个窗口是整段 OP/ED (80~95 秒),
     * 期间每次位置刷新都会重组一遍整行 —— 而一个像素都看不见.
     */
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .focusProperties {
                if (downFocus != null) down = downFocus
                up = upFocus
            }
            .onFocusChanged { if (it.isFocused) overlay.focusRegion = TvPlayerFocusRegion.PROGRESS }
            .focusable(interactionSource = interactionSource),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖拽预览中这里仍是**播放位置** (Prime 实测): 目标时间由圆点上方的浮窗给出,
        // 两处都显示目标就没人告诉用户"原来播到哪儿了", 返回取消后也失去了参照
        TvPlayerTimeText(live) { progressSliderState.currentPositionMillis }
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            // 不可见时不订阅: 缓存进度在下载期间刷得很勤, 而 by 解构是在本行的组合里读
            val cacheProgressInfo = if (live) {
                vm.cacheProgressInfoFlow.collectAsStateWithLifecycle(null)
            } else {
                null
            }
            MediaProgressSlider(
                progressSliderState,
                // 收 lambda: 读记在 slider 自己身上, 缓存进度每刷一次不带走整行
                { cacheProgressInfo?.value },
                colors = MediaProgressSliderDefaults.colors(
                    trackProgressColor = Color.White,
                    // 圆点即示焦: 未聚焦隐藏, 聚焦出现
                    thumbColor = if (focused) Color.White else Color.Transparent,
                    trackBackgroundColor = Color.White.copy(alpha = 0.3f),
                ),
                enabled = false, // 展示用; 快进退走遥控器左右键 (根路由)
                // 拖拽预览态时在圆点上方浮出"缩略图 + 目标时间"
                showPreviewTimeTextOnThumb = true,
                framePreview = framePreview,
                // 只显示画面, 时间叠在画面底部居中 (Prime 风格): 卡片样式那一圈底色 + 帧下方
                // 另占一行的文字, 在电视上比画面本身还显眼
                previewStyle = ProgressSliderPreviewStyle.FrameOnly,
            )
        }
        TvPlayerTimeText(live) { progressSliderState.totalDurationMillis }
    }
}

/**
 * 进度条行两端的时间文字.
 *
 * 单独一个 composable 而不是就地 [Text]: 播放位置每刷新一次只重组这一个文字节点, 不带走整行 ——
 * 同一行里还挂着进度条与拖拽预览浮窗. [millis] 收 lambda 是同一个理由, 别在调用处的 body 里读.
 *
 * [live] 为假时**冻住最后一次的值, 不能归零**: 控制层是淡出的 (alpha 走完 200ms 才到 0), 而
 * `live` 在层级一变就翻假 —— 归零的话这一行还在屏上时时间就"啪"地跳成 00:00, 表现为"按返回
 * 收起控制层的瞬间左边的时间变成 00:00" (2026-09-07 用户报). 冻住之后淡出途中显示的仍是离开
 * 那一刻的时间, 而不订阅热状态这条好处照旧.
 */
@Composable
private fun TvPlayerTimeText(live: Boolean, millis: () -> Long) {
    var frozen by remember { mutableLongStateOf(0L) }
    val shown = if (live) millis() else frozen
    if (live) {
        // 记账放 SideEffect: 组合期间写快照状态会让本次组合自己失效
        SideEffect { frozen = shown }
    }
    Text(
        renderTvPlayerTime(shown),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun renderTvPlayerTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

/**
 * 图标行: 播放组 (从头开始/下一集/跳OP) | 数据源 | 弹幕组 (开关/设置) ... 右侧文字选项组与低频组.
 * 再往下键 = 详情页 (根路由). 播放/暂停走遥控器确认键, 不再放按钮 (Prime 布局);
 * 选集走详情页覆盖层. 内容统一纯白高对比 (默认主题色在视频上看不清).
 */
@Composable
private fun TvPlayerBottomRow(
    overlay: TvPlayerOverlayState,
    vm: EpisodeViewModel,
    page: EpisodePageState,
    sheetsController: VideoSideSheetsController<EpisodeVideoSideSheetPage>,
    /** 最左按钮 (从头开始) 的请求器: 进度条按下键的固定落点. */
    firstButtonFocus: FocusRequester,
    /** 行内所有按钮按上键的显式落点 (进度条行): 空间搜索会越过细进度条落到胶囊按钮上. */
    upFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    // "一起看"按钮的显隐在本行 (而不是按钮自己) 判断: 用户可以在弹窗的 ⋮ 里关掉整个功能,
    // 那一下按钮连同它自己的焦点善后逻辑一起被移除, 只有留在场上的父级能接手 —— 把焦点送回
    // 进度条 (与从面板按返回同一个落点). 没有这一手就是按钮消失 + 焦点消失, 方向键全失效.
    val watchTogetherEnabled = LocalWatchTogetherEntry.current.enabled
    var watchTogetherWasEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(watchTogetherEnabled) {
        if (watchTogetherEnabled) {
            watchTogetherWasEnabled = true
            return@LaunchedEffect
        }
        // 一开始就没开 (或本行刚组合出来) 不算"刚被关掉", 不能抢焦点
        if (!watchTogetherWasEnabled) return@LaunchedEffect
        watchTogetherWasEnabled = false
        overlay.focusProgress()
    }
    CompositionLocalProvider(
        LocalContentColor provides Color.White,
        LocalTextStyle provides MaterialTheme.typography.labelMedium,
    ) {
        Row(
            modifier
                .onFocusChanged { if (it.hasFocus) overlay.focusRegion = TvPlayerFocusRegion.BOTTOM_ROW }
                .focusRestorer()
                .focusGroup()
                // 行内全部按钮的上键落点. 必须挂在 focusGroup 之后 (内侧):
                // 子节点向上收集焦点属性时遇到第一个焦点目标 (组节点) 即停,
                // 挂在组外侧只会作用于组自身, 按钮读不到
                .focusProperties { up = upFocus },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 从头开始 (Prime 同款). Replay 的图形几乎占满 24dp 视口 (环形箭头画到边),
            // 而 SkipNext 等图形留白多, 同尺寸下视觉显大 —— 缩一档做视觉等大
            TvBottomRowIcon(
                icon = Icons.Rounded.Replay,
                contentDescription = stringResource(Lang.video_player_tv_restart),
                onClick = { vm.player.seekTo(0) },
                modifier = Modifier.focusRequester(firstButtonFocus),
                iconSize = TV_ICON_SIZE_VISUAL_COMPENSATED,
            )
            if (vm.episodeSelectorState.hasNextEpisode) {
                TvBottomRowIcon(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(Lang.video_player_next_episode),
                    onClick = { vm.episodeSelectorState.selectNext() },
                )
            }
            // 跳过 OP/ED (快进配置的时长)
            TvSkipOpEdButton(vm)

            TvBottomRowDivider()

            // ---- 数据源 ---- (紧跟播放组: 卡顿/字幕不对时换源是看片途中最常走的一步,
            // 排在弹幕组之后要多按两下)
            TvBottomRowIcon(
                icon = Icons.Rounded.DisplaySettings,
                contentDescription = stringResource(Lang.subject_episode_select_media_source),
                onClick = { sheetsController.navigateTo(EpisodeVideoSideSheetPage.MEDIA_SELECTOR) },
            )

            TvBottomRowDivider()

            // ---- 弹幕组 ----
            TvBottomRowIcon(
                icon = if (page.danmakuEnabled) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff,
                contentDescription = stringResource(
                    if (page.danmakuEnabled) Lang.video_player_disable_danmaku else Lang.video_player_enable_danmaku,
                ),
                onClick = { vm.setDanmakuEnabled(!page.danmakuEnabled) },
            )
            TvBottomRowIcon(
                icon = AniIcons.SubtitleGear,
                contentDescription = stringResource(Lang.subject_episode_danmaku_settings_title),
                onClick = { sheetsController.navigateTo(EpisodeVideoSideSheetPage.PLAYER_SETTINGS) },
            )
            // 一起看: 与弹幕同属"和别人一起看"那一类, 所以并进本组末尾. 位置的取舍是 ——
            // 一次观看里最多开一次, 排不到从头开始/下一集/换源前面; 但再往右就是收藏/统计
            // 那些低频项与右半边的设置类按钮, 一个招牌功能埋在那儿要多按七八下才够得着
            if (watchTogetherEnabled) TvWatchTogetherButton(overlay)

            // 左右两块之间的弹性留白 (常用组靠左, 其余靠右)
            Spacer(Modifier.weight(1f))

            // ---- 文字选项组 (字幕轨/倍速/画面比例, 自描述文字按钮, 标签槽位仅为行内对齐) ----
            vm.player.subtitleTracks?.let {
                TvBottomRowLabeled(label = null) {
                    TvTextButtonInverse {
                        PlayerControllerDefaults.SubtitleSwitcher(
                            it,
                            modifier = Modifier.height(TV_ICON_BUTTON_SIZE),
                            onExpandedChanged = { open -> overlay.onPopupExpandedChanged(open) },
                        )
                    }
                }
            }
            // 倍速 / 画面比例 (下拉展开时上报, 抑制自动隐藏)
            //
            // rangeProvider / onCommitSpeed 必须给 (与手机端 EpisodePage 一致): 少了它们,
            // 滑块用的是默认 0.5x–2.5x 而不是用户设的范围, 且这里调的倍速既不写回配置也不写进
            // ViewModel 的 override —— 而 PlaybackSpeedExtension 会在切集/重新起播时按配置里的
            // playbackSpeed 重新应用, 于是用户在播放器里改的倍速会莫名其妙被弹回去
            val speedController = remember(vm) {
                vm.player.features[PlaybackSpeed]?.let {
                    PlaybackSpeedControllerState(
                        playbackSpeed = it,
                        // 固定用默认范围, 不读配置里的 min/max: 遥控器形态下"倍速范围"那条设置
                        // 已经不提供了 (见 AppSettingsTab.PlaybackSpeedItems), 而配置里可能还
                        // 留着以前被改窄的值, 读它就等于永远调不回来
                        rangeProvider = { TV_PLAYBACK_SPEED_RANGE },
                        onCommitSpeed = { speed -> vm.setPlaybackSpeed(speed) },
                        scope = scope,
                    )
                }
            }
            speedController?.let {
                TvBottomRowLabeled(label = null) {
                    TvTextButtonInverse {
                        SpeedSwitcher(
                            it,
                            modifier = Modifier.height(TV_ICON_BUTTON_SIZE),
                            onExpandedChanged = { open -> overlay.onPopupExpandedChanged(open) },
                        )
                    }
                }
            }
            val aspectController = remember(vm) {
                vm.player.features[VideoAspectRatio]?.let { VideoAspectRatioControllerState(it, scope = scope) }
            }
            aspectController?.let {
                TvBottomRowLabeled(label = null) {
                    TvTextButtonInverse {
                        VideoAspectRatioSelector(
                            it,
                            modifier = Modifier.height(TV_ICON_BUTTON_SIZE),
                            onExpandedChanged = { open -> overlay.onPopupExpandedChanged(open) },
                        )
                    }
                }
            }

            TvBottomRowDivider()

            // ---- 低频操作组 (收藏 + 原三个点菜单的三项) ----
            TvPlayerCollectionButton(vm, overlay)
            // 播放器统计开关
            TvBottomRowIcon(
                icon = Icons.Outlined.Analytics,
                contentDescription = stringResource(
                    if (overlay.showPlayerStats) Lang.video_player_stats_title_hide
                    else Lang.video_player_stats_title_show,
                ),
                onClick = { overlay.showPlayerStats = !overlay.showPlayerStats },
            )
            // 外部链接 (点击弹分享下拉)
            TvPlayerShareButton(overlay, page.shareData)
            // 缓存
            TvBottomRowIcon(
                icon = Icons.Rounded.Download,
                contentDescription = stringResource(Lang.subject_episode_cache),
                onClick = { navigator.navigateSubjectCaches(vm.subjectId) },
            )
        }
    }
}

/** 图标行分组隔栏: 竖细线, 高度与图标视觉对齐 (含底部标签槽位占位, 与按钮列同构). */
@Composable
private fun TvBottomRowDivider(modifier: Modifier = Modifier) {
    TvBottomRowLabeled(label = null, modifier.padding(horizontal = 6.dp)) {
        Box(
            Modifier.height(TV_ICON_BUTTON_SIZE).width(1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(1.dp)
                    .height(TV_BOTTOM_ROW_DIVIDER_HEIGHT)
                    .background(Color.White.copy(alpha = TV_BOTTOM_ROW_DIVIDER_ALPHA)),
            )
        }
    }
}

/** 图标行分组隔栏的可见高度. */
private val TV_BOTTOM_ROW_DIVIDER_HEIGHT = 16.dp

/** 图标行分组隔栏的不透明度. */
private const val TV_BOTTOM_ROW_DIVIDER_ALPHA = 0.35f

/**
 * 收藏按钮: 图标反映当前收藏状态 (实心/空心), 点击弹收藏状态下拉
 * ([EditCollectionTypeDropDown] 的 state 重载自带开合与错误 toast);
 * 设为"看过"且有未看剧集时的确认对话框由 [EditableSubjectCollectionTypeDialogsHost] 承担.
 */
@Composable
private fun TvPlayerCollectionButton(
    vm: EpisodeViewModel,
    overlay: TvPlayerOverlayState,
    modifier: Modifier = Modifier,
) {
    val state = vm.editableSubjectCollectionTypeState
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    // 展开状态由调用方持有 (上游 #3372 起): 放在 state 里会被列表重组顶掉, 菜单自己关上
    var dropdownExpanded by remember { mutableStateOf(false) }
    // 下拉/"看过"确认对话框打开期间上报弹窗计数, 抑制自动隐藏 —— 两者都是独立窗口,
    // 按键不重置计时, 不上报的话 5 秒后控制层连同对话框被 hideAll 连根卸载.
    // DisposableEffect: 本按钮意外随控制层卸载时计数如数归还 (LaunchedEffect 水位
    // 写法在打开状态下被取消会漏一次 -1, 自动隐藏从此永久失效)
    if (dropdownExpanded || presentation.showSetAllEpisodesDoneDialog) {
        DisposableEffect(Unit) {
            overlay.onPopupExpandedChanged(true)
            onDispose { overlay.onPopupExpandedChanged(false) }
        }
    }
    EditableSubjectCollectionTypeDialogsHost(state)
    Box(modifier) {
        TvBottomRowIcon(
            icon = if (presentation.selfCollectionType == UnifiedCollectionType.NOT_COLLECTED) {
                Icons.Rounded.FavoriteBorder
            } else {
                Icons.Rounded.Favorite
            },
            contentDescription = stringResource(Lang.video_player_tv_collection),
            onClick = { dropdownExpanded = true },
        )
        EditCollectionTypeDropDown(
            state,
            expanded = dropdownExpanded,
            onDismissRequest = { dropdownExpanded = false },
        )
    }
}

/** 图标行按钮下方的聚焦标签槽位高度 (常驻预留, 聚焦才显示文字 —— 布局不随聚焦跳动). */
private val TV_BOTTOM_ROW_LABEL_HEIGHT = 18.dp

/**
 * 图标行条目的聚焦标签: 按钮下方固定高度的槽位, 子树聚焦时浮现功能文字.
 * 文字按无界宽度测量并居中 (可比按钮宽, 向两侧出画), 不改变行内布局;
 * [label] 传 null 只预留槽位不显示文字 (字幕/倍速等自描述的文字按钮, 仅为行内对齐).
 */
@Composable
private fun TvBottomRowLabeled(
    label: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.onFocusChanged { focused = it.hasFocus },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        // 槽位零宽 (fillMaxWidth 会在 Row 里撑满整行, 把其余按钮挤出屏幕):
        // 列宽 = 按钮宽; 文字按无界宽度测量, 围绕零宽槽位居中, 向两侧出画
        Box(
            Modifier.height(TV_BOTTOM_ROW_LABEL_HEIGHT).width(0.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (focused && label != null) {
                Text(
                    label,
                    Modifier.wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * 文字按钮 (字幕/倍速/画面比例) 的聚焦反色容器: 子按钮聚焦时白底黑字
 * (与胶囊/图标按钮同款示焦; TextButton 文字色取 LocalContentColor, 直接换供给即可).
 */
@Composable
private fun TvTextButtonInverse(content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged { focused = it.hasFocus }
            .background(if (focused) Color.White else Color.Transparent, CircleShape),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (focused) Color.Black else Color.White,
        ) {
            content()
        }
    }
}

/**
 * 图标行圆钮容器: 聚焦时白底黑图标 (与胶囊按钮同款反色示焦), 未聚焦透明白图标.
 */
@Composable
private fun TvBottomRowIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.size(TV_ICON_BUTTON_SIZE),
        shape = CircleShape,
        color = if (focused) Color.White else Color.Transparent,
        contentColor = if (focused) Color.Black else Color.White,
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** 图标行标准按钮 (80% 密度: 38dp 按钮 / 20dp 图标); 聚焦时反色 + 按钮下方浮现功能文字. */
@Composable
private fun TvBottomRowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 图形占满视口的图标传 [TV_ICON_SIZE_VISUAL_COMPENSATED] 做视觉等大. */
    iconSize: Dp = TV_ICON_SIZE,
) {
    TvBottomRowLabeled(label = contentDescription, modifier) {
        TvBottomRowIconButton(onClick) {
            Icon(icon, contentDescription, Modifier.size(iconSize))
        }
    }
}

@Composable
private fun TvSkipOpEdButton(vm: EpisodeViewModel, modifier: Modifier = Modifier) {
    val duration: Duration = vm.videoScaffoldConfig.opEdSkipDuration
    val seconds = duration.inWholeSeconds
    val label = stringResource(Lang.subject_episode_fast_forward_seconds, seconds)
    TvBottomRowLabeled(label = label, modifier) {
        TvBottomRowIconButton({ vm.onClickSkipOpEd(vm.player.currentPositionMillis.value) }) {
            val icon = when (seconds) {
                85L -> AniIcons.Forward85
                90L -> AniIcons.Forward90
                else -> AniIcons.Forward80
            }
            Icon(icon, label, Modifier.size(TV_ICON_SIZE))
        }
    }
}

/** 外部链接按钮 (原三个点菜单项): 点击在按钮下方弹分享下拉 (数据源原链接等). */
@Composable
private fun TvPlayerShareButton(
    overlay: TvPlayerOverlayState,
    shareData: MediaShareData,
    modifier: Modifier = Modifier,
) {
    var showShareDropdown by rememberSaveable { mutableStateOf(false) }
    // 弹层打开时上报, 抑制自动隐藏 (弹层是独立窗口, 按键不会重置计时)
    if (showShareDropdown) {
        DisposableEffect(Unit) {
            overlay.onPopupExpandedChanged(true)
            onDispose { overlay.onPopupExpandedChanged(false) }
        }
    }
    Box(modifier) {
        TvBottomRowIcon(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = stringResource(Lang.subject_episode_external_links),
            onClick = { showShareDropdown = true },
        )
        ShareEpisodeDropdown(
            shareData,
            showShareDropdown,
            onDismissRequest = { showShareDropdown = false },
            // 下拉是独立窗口, 按键到不了根按键路由 —— 它盖在画面上, 播放暂停键仍该管用
            modifier = Modifier.tvOverlayWindowKeys { showShareDropdown = false },
        )
    }
}
