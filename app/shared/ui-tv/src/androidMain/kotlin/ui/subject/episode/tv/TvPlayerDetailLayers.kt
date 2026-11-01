/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.rememberImageViewerHandler
import me.him188.ani.app.ui.foundation.tv.LocalTvReadableBlocks
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.TvZoomedImageOverlay
import me.him188.ani.app.ui.foundation.tv.nativeview.LocalTvGalleryExitSide
import me.him188.ani.app.ui.foundation.tv.nativeview.LocalTvGalleryGridHandle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvGalleryGridHandle
import me.him188.ani.app.ui.foundation.tv.tvImageZoomKeys
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE
import me.him188.ani.app.ui.foundation.widgets.ProvideAniGlassPanelContent
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.subject.details.sections.LocalViewAllGalleries
import me.him188.ani.app.ui.subject.details.sections.LocalViewAllBackHandlers
import me.him188.ani.app.ui.subject.details.sections.LocalViewAllImageZoom
import me.him188.ani.app.ui.subject.details.sections.LocalViewAllPanelHost
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_COMPACT_COLUMN_SPACING
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_COMPACT_VERTICAL_INSET
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_COMPACT_SIDE_INSET
import me.him188.ani.app.ui.subject.details.sections.ViewAllGalleries
import me.him188.ani.app.ui.subject.details.sections.ViewAllBackHandlers
import me.him188.ani.app.ui.subject.details.sections.ViewAllPanelHost
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.person.LocalPeoplePreviewRows
import me.him188.ani.app.ui.subject.person.PeoplePreviewBody
import me.him188.ani.app.ui.subject.person.PeoplePreviewRows
import me.him188.ani.app.ui.subject.person.PeoplePreviewTarget
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem
import kotlin.math.roundToInt

/*
 * 播放器胶囊上方的叠层 (贴着屏幕左缘, 下沿贴胶囊行, 往上一直铺到屏幕顶, 上下两头渐隐), 画面不缩小:
 * - 相关推荐 / 角色 / 制作人员三颗胶囊的面板是一张两列画廊 (格子大小同人物页里的三列画廊, 叠层窄一截), 胶囊聚焦就显示;
 * - 画廊里点开的人物页、人物页里的「查看全部」「N 条评论」叠在画廊上面;
 * - 评论详情 / 回复 (TvCommentReplyDialog) 也摆在这个位置, 由 TvPlayerOverlayState.replyingComment 驱动, 外壳同 [TvPlayerLayerPanel].
 *
 * 焦点 (按键在根路由, 见 TvEpisodeScreen): 胶囊上按确定进画廊第一格; 按上进画廊, 第一次落在看得见的最下一行里离胶囊最近的那格, 之后落回离开时那格.
 * 画廊 (连同人物页里「查看全部」的画廊) 最左列按左 / 最右边按右跨到相邻胶囊 (见 [TvPlayerDetailLayersState.crossGallery]); 人物页里的画廊快速连点到边上的那一下不跨
 * (跨走就丢掉人物页), 胶囊的画廊之间误跨了反方向一按就回到原来那格.
 * 别的叠层里左右上到边停住; 往下出边回到胶囊, 叠着的人物页留着, 再按上回去. 返回退一层, 退到画廊再按返回回胶囊; 胶囊上返回清掉人物页.
 * 左右换胶囊清掉人物页; 画廊只组合当前那一张 (换胶囊再回来落回离开时那格), 控制层收起时连停的位置一起清掉.
 */

/**
 * 人物页这一类叠层的宽 (从屏幕左缘算) 占屏宽多少. 按一排三格的卡片尺寸定: 960dp 宽时约 403dp, 去掉左右内边距与 20dp 的卡间距
 * 一格约 108dp —— 落在 tvOS 卡片网格的常用档 (带侧栏的资料库 100、7 列 108) 里, 与人物页横滑行的格宽 (106) 对得齐; 头像圆取格宽的
 * 0.9 (见 VIEW_ALL_GALLERY_COMPACT_CIRCLE_FRACTION), 海报 2:3. 再宽就压到画面将近一半 (YouTube TV 的评论栏约占屏宽 40%).
 * 胶囊的画廊同样大的格子只排两列, 叠层窄一截 (见 [galleryLayerWidth]).
 */
private const val TV_PLAYER_PEOPLE_LAYER_WIDTH_FRACTION = 0.42f

/** 叠层的底在上下两头渐隐多长 (同胶囊浮出面板列表顶边的渐隐): 看不出上下边, 像本来就铺在画面上的一列. */
private val TV_PLAYER_LAYER_EDGE_FADE = ANI_SCROLL_FADE

/**
 * 叠层的内边距 (回复评论、表情选择器). 叠层从屏幕顶铺起, 上面让过渐隐那一截, 内容从这里往下排.
 * 人物页自带头部与列的左右留白, 只给上下一点; 画廊自己上下各让出一截, 只给左右.
 */
internal val TV_PLAYER_LAYER_CONTENT_PADDING =
    PaddingValues(start = 20.dp, top = TV_PLAYER_LAYER_EDGE_FADE, end = 20.dp, bottom = 20.dp)

/**
 * 叠层在布局里占到离屏幕顶多远, 再往上那一截是画出界的 (见 [tvPlayerLayerHeight]). **不能小于控制层底部那一列的上下留白
 * (TvPlayerControlsOverlay 里的 padding(vertical = 20.dp))**: 叠层高按胶囊行的实测位置算, 自己又排在胶囊行上面的同一列里; 这一截比留白小,
 * 整列就比给的空间高, 胶囊行被挤动、量到的位置跟着变、叠层的高又变 —— 来回振荡, 叠层与胶囊行一闪一闪. 不小于留白时整列高 = 屏高 - 留白 - 这一截,
 * 与胶囊行在哪无关 (输入法弹出时同理).
 */
private val TV_PLAYER_LAYER_TOP_GAP = 24.dp

/** 还没量到高度 (首帧) 时叠层多高. */
private val TV_PLAYER_LAYER_FALLBACK_HEIGHT = 360.dp


/** 胶囊的面板是不是胶囊上方的画廊 (相关推荐 / 角色 / 制作人员). */
internal val TvPlayerPanel.showsGallery: Boolean
    get() = this == TvPlayerPanel.RECOMMENDATIONS || this == TvPlayerPanel.STAFF || this == TvPlayerPanel.CHARACTERS

/**
 * 胶囊上方的叠层: 相关推荐 / 角色 / 制作人员胶囊的画廊 (胶囊聚焦就显示, 见 [showPanel]), 上面一层层叠人物页. 播放器里点人物都开在这里 ([openPeople]):
 * 叠层里点人是往上叠一层, 返回退一层 (不像页面上的预览那样原地换人).
 */
@Stable
internal class TvPlayerDetailLayersState {
    private val stack = mutableStateListOf<PeoplePreviewTarget>()

    /** 由下到上的人物页. */
    val pages: List<PeoplePreviewTarget> get() = stack

    /** 当前胶囊的画廊 (相关推荐 / 角色 / 制作人员胶囊聚焦时); null = 当前胶囊不是这三颗. */
    var gallery: TvPlayerPanel? by mutableStateOf(null)
        private set

    /**
     * 正摆着的那张画廊 (排好了的). 换胶囊时新画廊排出格来之前, 旧的这张照旧摆着, 排好了再一下换过去 ([galleryReady]) —— 新画廊要现建现排
     * (分页数据第一帧是空的、原生网格现建), 直接换过去是先空一下再闪出来. 离开这三颗胶囊时清掉, 回来时 (没有旧的可摆) 新画廊淡入.
     */
    var shownGallery: TvPlayerPanel? by mutableStateOf(null)
        private set

    /** [shownGallery] 换上来时要不要淡入: 前面没有画廊摆着 (从进度条 / 别的胶囊上来) 时淡入, 画廊换画廊时一下换过去. */
    internal var shownGalleryFadeIn: Boolean = true
        private set

    /** [panel] 的画廊排好了 (排出了格, 或者确定没有内容): 它还是当前胶囊的话换上来. */
    internal fun galleryReady(panel: TvPlayerPanel) {
        if (gallery != panel || shownGallery == panel) return
        // 正跨过来、这张没有内容 (没排出格): 焦点先交给这颗胶囊, 再换下旧画廊 —— 焦点还在旧画廊的格里, 先拆掉它焦点就没了
        if (crossing && (gridHandle(panel).view?.childCount ?: 0) == 0) focusPillOf(panel)
        shownGalleryFadeIn = shownGallery == null
        shownGallery = panel
    }

    /** 叠层属于哪颗胶囊: 换了胶囊人物页就清掉. */
    var owner: TvPlayerPanel? by mutableStateOf(null)
        private set

    val isOpen: Boolean get() = gallery != null || stack.isNotEmpty()

    /** 各画廊的「查看全部」宿主: 画廊组件自己调 ViewAllPanelDialog, 由它摆进叠层. */
    private val galleryHosts = HashMap<TvPlayerPanel, TvLayerViewAllHost>()

    internal fun galleryHost(panel: TvPlayerPanel): TvLayerViewAllHost = galleryHosts.getOrPut(panel) { TvLayerViewAllHost() }

    /** 各画廊的网格把手: 焦点进来过没有、按上时选哪一格、拆掉时停在哪一格 (换胶囊再回来落回去, 见 TvGalleryGridHandle). */
    private val gridHandles = HashMap<TvPlayerPanel, TvGalleryGridHandle>()

    internal fun gridHandle(panel: TvPlayerPanel): TvGalleryGridHandle = gridHandles.getOrPut(panel) { TvGalleryGridHandle() }

    /** 各胶囊中心的窗口横坐标 (胶囊排好时上报): 第一次按上进画廊时落在横向离它最近的那一列. */
    internal val pillCenterX = HashMap<TvPlayerPanel, Float>()

    /** 每层人物页里的「查看全部」宿主 (按层号, 组合时登记). */
    internal val peopleHosts = HashMap<Int, TvLayerViewAllHost>()

    /** 评论详情那一层的焦点组 (从胶囊回去时从下面进). */
    val replyFocus = FocusRequester()

    /** 焦点回到 [owner] 那颗胶囊 (由控制层设). */
    internal var focusPill: () -> Unit = {}

    /** 焦点从胶囊进最上面那层人物页 (由那一层设). */
    internal var enterTopPage: () -> Unit = {}

    /** 焦点从胶囊进各张画廊 (各自登记, 参数见 [enterTop]). */
    internal val galleryEnter = HashMap<TvPlayerPanel, (first: Boolean) -> Unit>()

    /**
     * 焦点从旁边那张画廊跨进各张画廊 (各自登记, 前两个参数见 TvGalleryGridHandle.selectEdgeNearest); restore = 落回网格自己记着的那格
     * (离开时那格, 见 [crossGallery]), 不按第一屏挑.
     */
    internal val galleryEnterSide = HashMap<TvPlayerPanel, (rightColumn: Boolean, windowY: Float, restore: Boolean) -> Unit>()

    /** 胶囊此刻的视觉顺序 (左→右, 由控制层设): 画廊之间左右跨的依据. */
    internal var pillOrder: List<TvPlayerPanel> = emptyList()

    /** 换到哪颗胶囊 (由控制层设: 改 activePanel, 胶囊的选中描边与画廊随之换). */
    internal var selectPanel: (TvPlayerPanel) -> Unit = {}

    /** 焦点送到哪颗胶囊上 (由控制层设). */
    internal var focusPillOf: (TvPlayerPanel) -> Unit = {}

    /** 正在跨 (等目标画廊排出格): 按住左右键连发时不再跨第二次. */
    private var crossing = false

    /**
     * 跨画廊在这个 scope 里跑 (叠层那一级, 由 [TvPlayerDetailLayers] 设): 不能用发起跨的那张画廊自己的 —— 跨过去之后它就被拆掉,
     * 跨到一半的协程跟着取消, 目标画廊没有内容时「焦点落到那颗胶囊上」那一步就做不到了.
     */
    internal var crossScope: CoroutineScope? = null

    /** 见 [crossGallery]. */
    internal fun launchCross(from: TvPlayerPanel, toRight: Boolean, windowY: Float) {
        crossScope?.launch { crossGallery(from, toRight, windowY) }
    }

    /** 上一次在胶囊的画廊之间跨: 从 [from] 跨到 [to], 落在 [to] 的第 [landed] 格 (见 [crossGallery] 的「立刻跨回」). */
    private class TvGalleryCross(val from: TvPlayerPanel, val to: TvPlayerPanel, val landed: Int)

    private var lastCross: TvGalleryCross? = null

    /** [panel] 的画廊停在哪一格: 网格还在就是它选中的那格, 拆了就是拆时记下的那格 (重建时落回去); -1 = 没有. */
    private fun keptSelection(panel: TvPlayerPanel): Int = gridHandle(panel).let { it.view?.selectedPosition ?: it.savedSelection }

    /**
     * 焦点在 [from] 那颗胶囊的画廊 (或叠在它上面的人物页里「查看全部」的画廊) 最左列按左 / 最右边按右: 相邻那颗胶囊也是画廊时换过去,
     * 焦点落在它第一屏里贴着来路那一列、纵向离 [windowY] 最近的那格 (往左跨落最右列, 往右跨落最左列); 那张画廊排不出格来 (没有内容) 时
     * 焦点落到那颗胶囊上. 相邻的不是画廊 (评论 / 弹幕) 时焦点也落到那颗胶囊上 (同面板条目上按左右). 旁边没有胶囊了就停住.
     * 叠着的人物页跨走就丢掉, 跨回来进的是那颗胶囊自己的画廊.
     *
     * 立刻跨回: 刚从 A 跨到 B、在 B 里还停在落下的那格就按反方向跨回 A 时, 落回离开 A 时那格 (不按第一屏挑) —— 胶囊的画廊之间误跨了
     * 反方向一按就原样回来, 所以不像人物页里的画廊那样防快速连点 (见 TvNativeGalleryGridView.guardHurriedSideExit).
     */
    internal suspend fun crossGallery(from: TvPlayerPanel, toRight: Boolean, windowY: Float) {
        if (crossing || owner != from || gallery != from) return
        val index = pillOrder.indexOf(from)
        if (index < 0) return
        val neighbor = pillOrder.getOrNull(index + if (toRight) 1 else -1) ?: return
        if (!neighbor.showsGallery) {
            // 换了胶囊, 叠着的人物页随之清掉 (showPanel)
            lastCross = null
            focusPillOf(neighbor)
            return
        }
        crossing = true
        try {
            // 先换胶囊 (只组合当前那一张画廊, 这一下才开始排), 排出格来再把焦点送进去. 叠着人物页时先留着它们: 焦点还在上面那张画廊里,
            // 新画廊在底下排; 现在就清的话持焦那格没了, 排好之前焦点无处可落
            val fromPages = stack.isNotEmpty()
            // 立刻跨回落到的那格: 换胶囊之前读 (那张画廊重建后才落回去, 落回是排完版才做的, 送焦点时可能还没到)
            val restoreTo = keptSelection(neighbor)
            val back = lastCross?.takeIf {
                !fromPages && it.to == from && it.from == neighbor && keptSelection(from) == it.landed && restoreTo >= 0
            }
            lastCross = null
            selectPanel(neighbor)
            showPanel(neighbor, keepPages = true)
            val handle = gridHandle(neighbor)
            // 排出格来, 或者已经报了排好 (没有内容, 见 galleryReady) 就不再等
            withTimeoutOrNull(TV_GALLERY_CROSS_WAIT_MILLIS) {
                while ((handle.view?.childCount ?: 0) == 0 && shownGallery != neighbor) delay(16)
            }
            if (gallery != neighbor) return
            val enter = galleryEnterSide[neighbor]
            val ready = enter != null && (handle.view?.childCount ?: 0) > 0
            if (fromPages) {
                // 人物页清掉后底下的画廊露出来, 自己把焦点还进选中那格 (TvLayerSlot), 这里先选好
                if (ready) handle.selectEdgeNearest(!toRight, windowY)
                clearPages()
                if (!ready) focusPillOf(neighbor)
            } else if (ready) {
                enter(!toRight, windowY, back != null)
                lastCross = TvGalleryCross(from = from, to = neighbor, landed = if (back != null) restoreTo else keptSelection(neighbor))
            } else {
                focusPillOf(neighbor)
            }
        } finally {
            crossing = false
        }
    }

    /**
     * 焦点从胶囊进最上面那层: [first] = 胶囊上按确定, 画廊落在第一格; 否则是按上, 画廊落回离开时那一格,
     * 第一次进来落在看得见的最下一行里离胶囊最近的那格. 人物页不分这两种.
     */
    fun enterTop(first: Boolean) {
        if (stack.isNotEmpty()) enterTopPage() else gallery?.let { galleryEnter[it] }?.invoke(first)
    }

    /** 请焦点进当前画廊 (跨导航回来时控制层带着画廊出来, 见 TvPlayerFocusTarget.PANEL); 画廊排出格来再送. */
    var galleryFocusRequest: Int by mutableIntStateOf(0)
        private set

    fun requestGalleryFocus() {
        galleryFocusRequest++
    }

    /**
     * 胶囊聚焦变了 (控制层上报 activePanel, null = 焦点不在胶囊与面板上): 换了胶囊人物页清掉; 那三颗胶囊显示各自的画廊.
     * [keepPages] = 人物页先留着, 由调用方稍后清 (见 [crossGallery]).
     */
    fun showPanel(panel: TvPlayerPanel?, keepPages: Boolean = false) {
        if (panel != owner && !keepPages) clearPages()
        owner = panel
        gallery = panel?.takeIf { it.showsGallery }
        if (gallery == null) shownGallery = null
    }

    /** 开一个人: 叠层开着就往上叠, 没开就从这个人开起. */
    fun openPeople(target: PeoplePreviewTarget, owner: TvPlayerPanel?) {
        if (!isOpen) this.owner = owner
        stack += target
    }

    /**
     * 返回退一层: 最上面那层开着「查看全部」先关它, 否则退掉最上面那个人. 返回 true = 焦点该回胶囊了 (人退完了、底下没有画廊,
     * 或者本来就在画廊里: 画廊是胶囊的面板, 不关), 由调用方还.
     */
    fun back(): Boolean {
        val top = stack.lastIndex
        if (top < 0) return true
        val viewAll = peopleHosts[top]?.shown
        when {
            // 「查看全部」里面自己还有一层 (评论列表点开的整楼): 先退它
            viewAll != null -> if (!viewAll.back.back()) viewAll.dismiss()
            else -> stack.removeAt(top)
        }
        return stack.isEmpty() && gallery == null
    }

    /** 最上面那层开着放大的图就关掉它, 返回 true. */
    fun closeZoom(): Boolean {
        val zoom = topViewAll()?.imageZoom ?: return false
        if (!zoom.zooming) return false
        zoom.close()
        return true
    }

    private fun topViewAll(): TvLayerViewAll? =
        if (stack.isNotEmpty()) peopleHosts[stack.lastIndex]?.shown else gallery?.let { galleryHosts[it] }?.shown

    /** 清掉叠着的人物页 (胶囊上按返回), 画廊留着. */
    fun clearPages() {
        stack.clear()
    }

    /** 全部清掉, 不还焦点 (控制层收起): 画廊停的位置也不留, 下次唤出从头排. */
    fun reset() {
        clearPages()
        gallery = null
        shownGallery = null
        owner = null
        galleryHosts.clear()
        gridHandles.clear()
        galleryEnter.clear()
        galleryEnterSide.clear()
        lastCross = null
    }
}

/** 叠层往下出边时调: 焦点回到打开它的胶囊 (由控制层提供); null = 不在播放器的叠层里, 往下也停住. */
internal val LocalTvPlayerLayerExit = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 画廊那一层的底: 偏中性的烟灰, 很淡, 画面的颜色透得出来 —— 画廊里头像 / 海报本身够醒目, 要护住的只是格子下面那两行字 (字自己垫阴影, 见 overVideo);
 * 人物页、评论详情字多, 仍用半透明黑.
 */
private val TV_PLAYER_GALLERY_BACKDROP_COLOR = Color(0xFF56616A).copy(alpha = 0.32f)

/** 画廊那一层的底在右边多长之内渐隐到透明: 不是一块竖直切边的板. */
private val TV_PLAYER_GALLERY_BACKDROP_RIGHT_FADE = 64.dp

/**
 * 叠层的外壳: 底 (一整条, 上下两头渐隐; 同胶囊浮出面板条目的半透明黑, [gallery] 时是淡烟灰、右边也渐隐) + 内边距 + 焦点规则.
 * 尺寸由 [modifier] 定.
 * 焦点: 左右上到边停住; 往下出边交给 [LocalTvPlayerLayerExit] (回胶囊). [lockDown] = 往下也停住 (表情选择器: 它盖在评论详情上,
 * 回胶囊后再按上会落进被它挡住的详情里).
 *
 * @param fillHeight 撑满给的高 (人物页、画廊、回复评论); false = 按内容收高.
 * @param backdrop false = 不铺底, 内容自己一块块带底 (只读的评论详情: 主楼与回复各一张卡, 同评论面板的条目).
 * @param gallery 画廊那一层 (见 [TV_PLAYER_GALLERY_BACKDROP_COLOR]).
 * @param contentHidden 底与内容画成透明 (上面又盖了一层不透底的叠层, 如表情选择器): 半透明的底叠两层会透出下面那层的字.
 * @param overlay 盖在本层之上的内容 (与本层同在一个 Box 里, 可以 matchParentSize).
 */
@Composable
internal fun TvPlayerLayerPanel(
    modifier: Modifier = Modifier,
    fillHeight: Boolean = true,
    backdrop: Boolean = true,
    gallery: Boolean = false,
    contentPadding: PaddingValues = TV_PLAYER_LAYER_CONTENT_PADDING,
    lockDown: Boolean = false,
    contentHidden: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val currentExit by rememberUpdatedState(LocalTvPlayerLayerExit.current)
    Box(
        modifier
            .focusProperties {
                onExit = {
                    when (requestedFocusDirection) {
                        FocusDirection.Down -> {
                            val exit = currentExit
                            if (lockDown || exit == null) cancelFocusChange() else exit()
                        }

                        FocusDirection.Left, FocusDirection.Right, FocusDirection.Up -> cancelFocusChange()
                        else -> {}
                    }
                }
            }
            .focusGroup(),
    ) {
        val backdropColor = if (gallery) TV_PLAYER_GALLERY_BACKDROP_COLOR else TV_PANEL_ITEM_COLOR
        Surface(
            Modifier
                .fillMaxWidth()
                .ifThen(fillHeight) { fillMaxHeight() }
                .graphicsLayer { alpha = if (contentHidden) 0f else 1f }
                .ifThen(backdrop) { tvPlayerLayerBackdrop(backdropColor, rightFade = if (gallery) TV_PLAYER_GALLERY_BACKDROP_RIGHT_FADE else 0.dp) },
            color = Color.Transparent,
            // 底是自己画的渐隐条, 在配色表里查不到 "on" 色, 必须显式给
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            ProvideAniGlassPanelContent {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .ifThen(fillHeight) { fillMaxHeight() }
                        .padding(contentPadding),
                    content = content,
                )
            }
        }
        overlay()
    }
}

/** 叠层的底: 一整条 [color], 上下两头各在 [TV_PLAYER_LAYER_EDGE_FADE] 之内渐隐; [rightFade] 大于 0 时右边也在这么长之内渐隐. */
private fun Modifier.tvPlayerLayerBackdrop(color: Color, rightFade: Dp = 0.dp): Modifier = drawBehind {
    if (size.height <= 0f || size.width <= 0f) return@drawBehind
    val fade = (TV_PLAYER_LAYER_EDGE_FADE.toPx() / size.height).coerceAtMost(0.5f)
    val clear = color.copy(alpha = 0f)
    val vertical = Brush.verticalGradient(0f to clear, fade to color, 1f - fade to color, 1f to clear)
    val edge = rightFade.toPx().coerceAtMost(size.width)
    if (edge <= 0f) {
        drawRect(vertical)
        return@drawBehind
    }
    // 两个方向的渐隐相乘: 先画上下渐隐的底, 再按横向渐变擦淡右边那一截 (DstIn 只在这一块离屏缓冲里擦, 不碰后面的画面)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(size.toRect(), Paint())
        drawRect(vertical)
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, (size.width - edge) / size.width to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}

/**
 * 叠层的高: 下沿贴着胶囊行 (摆在胶囊行正上方), 撑满时往上一直铺到屏幕顶; [pillsRowTopPx] 是胶囊行上沿的窗口坐标,
 * 还没量到 (NaN) 时给兜底高. 撑满还是按内容收由叠层自己定 (这里只给上限).
 * 在布局里只占到离屏幕顶 [TV_PLAYER_LAYER_TOP_GAP], 再往上那一截画出界, 不撑高所在的那一列.
 *
 * 里面的内容按 [settledPillsRowTopPx] (图标行收起后胶囊行停在哪) 定高, 不按现在的位置: 焦点进胶囊时图标行收起、胶囊行逐帧往下移,
 * 内容的高要是跟着变, 里面的原生画廊每帧都得重排 (对齐重算), 画面一抖一抖. 按停下的位置定, 动画途中内容尺寸不变、只跟着胶囊行平移
 * (上面多出来的那截画出界). 布局里报的高仍按现在的位置, 不撑高所在的那一列.
 */
internal fun Modifier.tvPlayerLayerHeight(
    pillsRowTopPx: () -> Float,
    settledPillsRowTopPx: () -> Float = pillsRowTopPx,
): Modifier = layout { measurable, constraints ->
    val pillsTop = pillsRowTopPx()
    val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
    val slot: Int
    val bleed: Int
    val contentSlot: Int
    if (pillsTop.isNaN()) {
        slot = TV_PLAYER_LAYER_FALLBACK_HEIGHT.roundToPx().coerceAtMost(maxHeight)
        bleed = 0
        contentSlot = slot
    } else {
        bleed = TV_PLAYER_LAYER_TOP_GAP.roundToPx()
        slot = (pillsTop.roundToInt() - bleed).coerceIn(0, maxHeight)
        val settledTop = settledPillsRowTopPx().takeUnless { it.isNaN() } ?: pillsTop
        contentSlot = (settledTop.roundToInt() - bleed).coerceAtLeast(0)
    }
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = contentSlot + bleed))
    val height = placeable.height.coerceAtMost(slot)
    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
}

/**
 * 人物页、画廊这一类叠层贴着屏幕左缘: 控制层那一列左边留着 [TV_PLAYER_HORIZONTAL_PAD], 叠层往左画出这一截,
 * 布局里占的宽照旧少这一截, 不挤动那一列.
 */
internal fun Modifier.tvPlayerLayerLeftBleed(): Modifier = layout { measurable, constraints ->
    val bleed = TV_PLAYER_HORIZONTAL_PAD.roundToPx()
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + bleed else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth))
    val width = (placeable.width - bleed).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-bleed, 0) }
}

/** 人物页这一类叠层的宽 (屏宽的 [TV_PLAYER_PEOPLE_LAYER_WIDTH_FRACTION]). */
@Composable
private fun peopleLayerWidth(): Dp {
    val windowWidth = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { (windowWidth * TV_PLAYER_PEOPLE_LAYER_WIDTH_FRACTION).toDp() }
}

/**
 * 胶囊的画廊那一层的宽: 格子与人物页里一排 [TV_PLAYER_LAYER_GALLERY_COLUMNS] 格的画廊一样大, 只排 [TV_PLAYER_PILL_GALLERY_COLUMNS] 列.
 * 格宽的算法同 viewAllGalleryCellWidth (网格往两侧出血的那一截与它两侧让出的一截抵掉).
 */
@Composable
private fun galleryLayerWidth(): Dp {
    val content = peopleLayerWidth() - TV_PLAYER_GALLERY_CONTENT_PADDING_H * 2
    val spacing = VIEW_ALL_GALLERY_COMPACT_COLUMN_SPACING
    val cell = (content - spacing * (TV_PLAYER_LAYER_GALLERY_COLUMNS - 1)) / TV_PLAYER_LAYER_GALLERY_COLUMNS
    return cell * TV_PLAYER_PILL_GALLERY_COLUMNS + spacing * (TV_PLAYER_PILL_GALLERY_COLUMNS - 1) + TV_PLAYER_GALLERY_CONTENT_PADDING_H * 2
}

/**
 * 人物叠层的宿主: 画廊 (有的话) 垫在最底下, 人物页一层层叠上去. 每一层都留在组合里, 只画最上面那层 (下面的不画、不收焦点与触摸):
 * 退回时下面那层原样露出来. 人物页里的「查看全部」也是这一层里盖上来的一张 (见 [TvLayerViewAllHost]).
 *
 * @param pillsRowTopPx 见 [tvPlayerLayerHeight].
 * @param settledPillsRowTopPx 见 [tvPlayerLayerHeight].
 */
@Composable
internal fun TvPlayerDetailLayers(
    overlay: TvPlayerOverlayState,
    vm: EpisodeViewModel,
    layers: TvPlayerDetailLayersState,
    pillsRowTopPx: () -> Float,
    settledPillsRowTopPx: () -> Float,
    modifier: Modifier = Modifier,
) {
    // 只组合当前胶囊那一张画廊, 离开就拆掉: 不在屏上的画廊留在组合里 (连原生网格) 会拖慢焦点在胶囊与进度条之间来回 (Shield 上对比过).
    // 换回来停在原处靠网格把手记下的那一格 (见 TvGalleryGridHandle)
    val gallery = layers.gallery
    // 换胶囊时旧画廊留到新的排好 (见 shownGallery)
    val outgoing = layers.shownGallery?.takeIf { gallery != null && it != gallery }
    val crossScope = rememberCoroutineScope()
    SideEffect { layers.crossScope = crossScope }
    if (gallery == null && layers.pages.isEmpty()) return
    TvLayerAutoHideHold(overlay, layers)
    Box(
        modifier
            .tvPlayerLayerLeftBleed()
            .width(peopleLayerWidth())
            .tvPlayerLayerHeight(pillsRowTopPx, settledPillsRowTopPx)
            .onFocusChanged { if (it.hasFocus) overlay.focusRegion = TvPlayerFocusRegion.LAYER },
    ) {
        for (panel in listOfNotNull(outgoing, gallery)) {
            key(panel) {
                TvGalleryLayer(overlay, vm, layers, panel, isTop = layers.pages.isEmpty())
            }
        }
        layers.pages.forEachIndexed { index, target ->
            key(index, target) {
                TvPeopleLayer(layers, index, target, isTop = index == layers.pages.lastIndex)
            }
        }
    }
}

/** 叠层开着时 (当前胶囊有画廊, 或叠着人物页) 控制层不自动隐藏 (同其他弹层). 单独一个组合: 换胶囊时只重组这一小块. */
@Composable
private fun TvLayerAutoHideHold(overlay: TvPlayerOverlayState, layers: TvPlayerDetailLayersState) {
    if (!layers.isOpen) return
    DisposableEffect(Unit) {
        overlay.onPopupExpandedChanged(true)
        onDispose { overlay.onPopupExpandedChanged(false) }
    }
}

/**
 * 叠层里的一层: 被上面一层盖住时不画、焦点不许进、触摸吞掉; 又成了最上面一层时 (上面那层退掉了) 调 [restoreFocus] 把焦点还回离开时那一格.
 */
@Composable
private fun TvLayerSlot(
    isTop: Boolean,
    restoreFocus: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentIsTop by rememberUpdatedState(isTop)
    val currentRestore by rememberUpdatedState(restoreFocus)
    var covered by remember { mutableStateOf(false) }
    LaunchedEffect(isTop) {
        if (!isTop) {
            covered = true
            return@LaunchedEffect
        }
        if (!covered) return@LaunchedEffect
        covered = false
        // 上面那层这一帧才离开组合
        withFrameNanos { }
        currentRestore()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (currentIsTop) 1f else 0f }
            .focusProperties { onEnter = { if (!currentIsTop) cancelFocusChange() } }
            .focusGroup()
            .ifThen(!isTop) { swallowPointer() },
        content = content,
    )
}

/**
 * 一颗胶囊的画廊: 角色 / 制作人员同详情页「查看全部」的那个弹窗 ([TvPlayerPeopleGallery]), 相关推荐是作品海报画廊
 * ([TvPlayerRecommendationsGallery]), 都经 [LocalViewAllPanelHost] 摆进叠层. 排好之前不画 (换胶囊时旧的那张还摆着, 见
 * TvPlayerDetailLayersState.shownGallery), 排好了报上去换上来. 不是当前胶囊的 (等着被换下去的旧画廊) 焦点进不去.
 * 焦点进来过之前网格只排看得见的格 (见 TvGalleryGridHandle).
 */
@Composable
private fun TvGalleryLayer(
    overlay: TvPlayerOverlayState,
    vm: EpisodeViewModel,
    layers: TvPlayerDetailLayersState,
    panel: TvPlayerPanel,
    isTop: Boolean,
) {
    val host = remember(layers, panel) { layers.galleryHost(panel) }
    val grid = remember(layers, panel) { layers.gridHandle(panel) }
    val viewAllFocus = remember { FocusRequester() }
    host.onDismissed = {}
    // 排好了 (排出了格; 或者过了几帧还没有「查看全部」内容 = 没有内容, 推荐为空时那一句也算) 报上去
    LaunchedEffect(Unit) {
        withTimeoutOrNull(TV_GALLERY_READY_WAIT_MILLIS) {
            var frames = 0
            while ((grid.view?.childCount ?: 0) == 0 && !(frames >= TV_GALLERY_EMPTY_FRAMES && host.shown == null)) {
                withFrameNanos { }
                frames++
            }
        }
        layers.galleryReady(panel)
    }
    val isCurrent = { layers.gallery == panel }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { layers.shownGallery == panel }.collect { shown ->
            when {
                !shown -> appear.snapTo(0f)
                layers.shownGalleryFadeIn -> appear.animateTo(1f, tween(TV_GALLERY_APPEAR_MILLIS))
                else -> appear.snapTo(1f)
            }
        }
    }
    val enter: (Boolean) -> Unit = { first ->
        if (first) {
            grid.selectFirst()
        } else if (!grid.entered) {
            layers.pillCenterX[panel]?.let { grid.selectNearestBottom(it) }
        }
        runCatching { viewAllFocus.requestFocus(FocusDirection.Up) }
    }
    val currentEnter by rememberUpdatedState(enter)
    DisposableEffect(layers, panel) {
        layers.galleryEnter[panel] = { first -> currentEnter(first) }
        layers.galleryEnterSide[panel] = { rightColumn, windowY, restore ->
            // restore: 网格重建时已落回离开时那格 (TvGalleryGridHandle.savedSelection), 直接送焦点
            if (restore || grid.selectEdgeNearest(rightColumn, windowY)) runCatching { viewAllFocus.requestFocus() }
        }
        // 左右跨到相邻胶囊的画廊 (见 TvPlayerDetailLayersState.crossGallery)
        grid.onExitSide = { toRight, windowY -> layers.launchCross(panel, toRight, windowY) }
        onDispose {
            layers.galleryEnter.remove(panel)
            layers.galleryEnterSide.remove(panel)
            grid.onExitSide = null
        }
    }
    // 跨导航回来时控制层带着这张画廊出来: 数据与网格可能还在路上, 排出格来再送焦点
    LaunchedEffect(layers.galleryFocusRequest) {
        if (layers.galleryFocusRequest == 0) return@LaunchedEffect
        withTimeoutOrNull(TV_GALLERY_FOCUS_WAIT_MILLIS) {
            while ((grid.view?.childCount ?: 0) == 0) delay(100)
            currentEnter(false)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .focusProperties { onEnter = { if (!isCurrent()) cancelFocusChange() } }
            .focusGroup(),
    ) {
        // 人物页退完 (焦点还在叠层里) 才还焦点; 换胶囊时人物页也清掉, 那时焦点在胶囊上, 不抢
        TvLayerSlot(
            isTop,
            restoreFocus = {
                if (isCurrent() && overlay.focusRegion == TvPlayerFocusRegion.LAYER) runCatching { viewAllFocus.requestFocus() }
            },
        ) {
            CompositionLocalProvider(LocalViewAllPanelHost provides host) {
                if (panel == TvPlayerPanel.RECOMMENDATIONS) TvPlayerRecommendationsGallery(vm) else TvPlayerPeopleGallery(vm, panel)
            }
            host.shown?.let { shown ->
                CompositionLocalProvider(LocalTvGalleryGridHandle provides grid) {
                    TvLayerViewAllPanel(
                        shown,
                        Modifier.width(galleryLayerWidth()).fillMaxHeight().focusRequester(viewAllFocus),
                        takeInitialFocus = false,
                        columns = TV_PLAYER_PILL_GALLERY_COLUMNS,
                    )
                }
            }
        }
    }
}

/** 跨导航回来时等画廊排出格来最多等多久 (见 [TvGalleryLayer]). */
private const val TV_GALLERY_FOCUS_WAIT_MILLIS = 3_000L

/** 从进度条 / 别的胶囊上来时画廊淡入多久 (同浮出面板换面板时的淡入). */
private const val TV_GALLERY_APPEAR_MILLIS = 150

/** 画廊排好最多等多久 (等不到也换上来, 免得一直摆着旧的). */
private const val TV_GALLERY_READY_WAIT_MILLIS = 600L

/** 过了这么多帧还没有「查看全部」内容就当没有内容 (推荐为空、名单为空), 换上来. */
private const val TV_GALLERY_EMPTY_FRAMES = 4

/** 左右跨画廊时等目标画廊排出格来最多等多久 (见 TvPlayerDetailLayersState.crossGallery). */
private const val TV_GALLERY_CROSS_WAIT_MILLIS = 1_000L

/**
 * 一层人物页 + (开着时) 盖在它上面的「查看全部」. 回到这一层时焦点落回离开时那一格: 「查看全部」开着就回画廊 (原生画廊记得停在哪一格);
 * 从人物页的横滑行点进去的回那一行 ([TvLayerReturnRows]); 「查看全部」关掉时回点开它的那颗按钮 (ViewAllPanelHost.origin).
 * 都没有时退回人物页的 focusRestorer (只落得回「上次在这一列」, 列里中间隔着几层焦点组, 落点是默认那一格).
 */
@Composable
private fun TvPeopleLayer(
    layers: TvPlayerDetailLayersState,
    index: Int,
    target: PeoplePreviewTarget,
    isTop: Boolean,
) {
    val host = remember { TvLayerViewAllHost() }
    DisposableEffect(host) {
        layers.peopleHosts[index] = host
        onDispose { if (layers.peopleHosts[index] === host) layers.peopleHosts.remove(index) }
    }
    val bodyFocus = remember { FocusRequester() }
    val viewAllFocus = remember { FocusRequester() }
    val baseRows = LocalPeoplePreviewRows.current
    val rows = remember(baseRows) { baseRows?.let { TvLayerReturnRows(it) } }
    // 「查看全部」关掉后焦点还给点开它的那颗按钮. 要等它真的收起 (shown 变 null、人物页不再拦焦点) 再送: 关的那一刻它还在,
    // 人物页的 onEnter 会把送进来的焦点挡掉, 焦点就丢了. 「要还」记在普通变量里、只按收起没有重启: 当成 effect 的键的话,
    // 在 effect 里清掉它会把正在等那一帧的自己取消掉, 焦点一直送不出去
    val restoreOrigin = remember { BooleanArray(1) }
    host.onDismissed = { restoreOrigin[0] = true }
    val viewAllOpen = host.shown != null
    val currentViewAllOpen by rememberUpdatedState(viewAllOpen)
    val exitSide = remember(layers) {
        { toRight: Boolean, windowY: Float ->
            layers.owner?.let { owner -> layers.launchCross(owner, toRight, windowY) }
            Unit
        }
    }
    LaunchedEffect(viewAllOpen) {
        if (viewAllOpen || !restoreOrigin[0]) return@LaunchedEffect
        // 盖着的那一层这一帧才离开组合
        withFrameNanos { }
        restoreOrigin[0] = false
        requestFocusOr(host.origin, bodyFocus)
    }
    TvLayerSlot(
        isTop,
        restoreFocus = {
            if (host.shown != null) runCatching { viewAllFocus.requestFocus() } else requestFocusOr(rows?.returnFocus, bodyFocus)
        },
    ) {
        if (isTop) {
            SideEffect {
                layers.enterTopPage = {
                    runCatching { (if (host.shown != null) viewAllFocus else bodyFocus).requestFocus(FocusDirection.Up) }
                }
            }
        }
        // 「查看全部」盖上来时人物页不画: 两层半透明的底叠着会透出下面的字
        TvPlayerLayerPanel(
            Modifier
                .fillMaxSize()
                .focusProperties { onEnter = { if (currentViewAllOpen) cancelFocusChange() } },
            // 上面只给一点: 人物页的头部 (名字 + 「打开完整页面」) 自带上边距, 按钮 48dp 高、名字在行里居中,
            // 名字落在离屏幕顶约 28dp, 与画廊第一行齐平; 再加渐隐那一截顶上就空出一大块
            contentPadding = PaddingValues(vertical = 8.dp),
            contentHidden = viewAllOpen,
        ) {
            CompositionLocalProvider(LocalViewAllPanelHost provides host) {
                Box(Modifier.fillMaxSize().focusRequester(bodyFocus).focusRestorer().focusGroup()) {
                    // 焦点滚动同其他弹窗里会滚的内容: 焦点项在看得见的范围里 (让开渐隐那一截) 就不滚. 默认那套 (TV 上把焦点项
                    // 摆到视口 30% 处) 在这里会停不下来: 从上面的原生行往下进「出演作品」, 改道到「查看全部」与那一块
                    // 自己的滚进视野叠在一起, 目标落在半像素上, 滚动按整像素来回追, 整列每帧上下抖 1px
                    // 长内容 (简介全文、基本信息表) 停上去按上下键在里面翻页, 不被焦点一下滚过去 (见 tvReadableBlock)
                    ProvideScrollFadeFocusMargin {
                        CompositionLocalProvider(LocalTvReadableBlocks provides true) {
                            PeoplePreviewBody(
                                target,
                                rememberImageViewerHandler(),
                                // 指针设备的关闭钮; 「打开完整页面」前也走这里 (下一帧就换页了)
                                onClose = { layers.clearPages() },
                                onDismissImmediately = { layers.clearPages() },
                                rows = rows,
                            )
                        }
                    }
                }
            }
        }
        host.shown?.let { shown ->
            // 「查看全部」的画廊左右出边同胶囊的画廊: 跨到这一摞人物页所属胶囊的旁边 (见 TvPlayerDetailLayersState.crossGallery)
            CompositionLocalProvider(LocalTvGalleryExitSide provides exitSide) {
                TvLayerViewAllPanel(shown, Modifier.fillMaxSize().focusRequester(viewAllFocus))
            }
        }
    }
}

/** 把焦点送到 [first], 没有或送不到 (不在组合里) 就送到 [fallback]. */
private fun requestFocusOr(first: FocusRequester?, fallback: FocusRequester) {
    if (first == null || runCatching { first.requestFocus() }.isFailure) runCatching { fallback.requestFocus() }
}

/**
 * 人物页的横滑行 (原生行) 外面包一层: 聚焦时记成这一层的回来点 [returnFocus]. 从行里点进的人退回来, 焦点直接送回这一行,
 * 行自己记得停在哪一格.
 */
private class TvLayerReturnRows(private val base: PeoplePreviewRows) : PeoplePreviewRows {
    var returnFocus: FocusRequester? = null
        private set

    @Composable
    private fun Modifier.trackReturn(): Modifier {
        val requester = remember { FocusRequester() }
        return focusRequester(requester).onFocusChanged { if (it.hasFocus) returnFocus = requester }
    }

    @Composable
    override fun PeopleRow(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        contentPadding: Dp,
        modifier: Modifier,
    ) = base.PeopleRow(items, onClick, onBind, contentPadding, modifier.trackReturn())

    @Composable
    override fun PosterRow(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        contentPadding: Dp,
        modifier: Modifier,
    ) = base.PosterRow(items, onClick, onBind, contentPadding, modifier.trackReturn())
}

/** 一次「查看全部」(见 [ViewAllPanelHost.Panel] 的参数; 叠层里不写标题, 只用得到这几样). */
internal class TvLayerViewAll(
    val imageZoom: TvImageZoomState?,
    val content: @Composable ColumnScope.(initialFocus: Modifier) -> Unit,
    private val onDismissRequest: () -> Unit,
    private val onDismissed: () -> Unit,
) {
    /** 里面自己的那一层 (评论列表点开的整楼) 登记在这, 返回先退它 (见 [TvPlayerDetailLayersState.back]). */
    val back = ViewAllBackHandlers()

    /** 关掉, 焦点回到点开它的那颗按钮 (见 [TvLayerViewAllHost.onDismissed]). */
    fun dismiss() {
        onDismissed()
        onDismissRequest()
    }
}

/**
 * 叠层里的「查看全部」宿主: 谁在里面调 ViewAllPanelDialog, 这里就记下它要摆的东西, 由那一层画在上面 (同宽同位置, 不弹窗).
 * 调用方不再调 (关掉了) 就收回.
 *
 * 一次打开只记一个 [TvLayerViewAll] (内容与回调经 rememberUpdatedState 取最新的), **不能每次重组都换一个**: 那一层读着 [shown],
 * 它变了那一层就重组, 连带把调 ViewAllPanelDialog 的组件也重组一遍 (参数里有现建的 lambda, 跳不过), 又记一个新的 ——
 * 每帧重组、画廊每帧重建, 叠层一闪一闪.
 */
@Stable
internal class TvLayerViewAllHost : ViewAllPanelHost {
    var shown: TvLayerViewAll? by mutableStateOf(null)
        private set

    override var origin: FocusRequester? = null

    /** 关掉后把焦点还回去 (由那一层设). */
    var onDismissed: () -> Unit = {}

    @Composable
    override fun Panel(
        title: String,
        onDismissRequest: () -> Unit,
        count: Int?,
        subtitle: String?,
        imageZoom: TvImageZoomState?,
        headerAction: @Composable () -> Unit,
        content: @Composable ColumnScope.(initialFocus: Modifier) -> Unit,
    ) {
        val currentContent = rememberUpdatedState(content)
        val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
        val entry = remember(imageZoom) {
            TvLayerViewAll(
                imageZoom,
                content = { initialFocus -> currentContent.value(this, initialFocus) },
                onDismissRequest = { currentOnDismissRequest.value() },
                onDismissed = { onDismissed() },
            )
        }
        // 组合完再记 (别在组合中途改别处已读过的状态); 那一层下一帧画出来
        SideEffect {
            if (shown !== entry) shown = entry
        }
        DisposableEffect(entry) {
            onDispose { if (shown === entry) shown = null }
        }
    }
}

/**
 * 叠层里的「查看全部」: 只有一排 [columns] 格的紧凑画廊 ([TvLayerGalleries]), 不写标题 —— 点开之前就知道是哪一块 (胶囊名 / 人物页那一节的标题).
 * 底是画廊那一档的淡烟灰 (见 [TV_PLAYER_GALLERY_BACKDROP_COLOR]).
 * 打开时焦点进第一格 ([takeInitialFocus] = false 时不抢: 胶囊聚焦就显示的画廊, 焦点留在胶囊上); 最下一行再按下回胶囊; 长按放大的大图画在这一层上面.
 */
@Composable
private fun TvLayerViewAllPanel(
    shown: TvLayerViewAll,
    modifier: Modifier = Modifier,
    takeInitialFocus: Boolean = true,
    columns: Int = TV_PLAYER_LAYER_GALLERY_COLUMNS,
) {
    val focus = rememberTvFocusScope()
    val touch = LocalTvTouchInputEnabled.current
    if (!touch && takeInitialFocus) focus.InitialFocus(TvLayerViewAllFocus)
    val galleries = LocalViewAllGalleries.current
    val exit = LocalTvPlayerLayerExit.current
    val layerGalleries = remember(galleries, exit, columns) { TvLayerGalleries(galleries, exit, columns) }
    TvPlayerLayerPanel(
        modifier
            .tvFocusNavSignal(focus)
            .ifThen(shown.imageZoom != null) { tvImageZoomKeys(shown.imageZoom!!) },
        gallery = true,
        // 上面留出叠层渐隐那一截, 评价网格这类不自己让的内容从这里排 (不然首行顶着屏幕上沿); 下面只留画廊的那一截 (底边渐隐短, 最后一排露得全).
        // 画廊自己上下已各让出一截 (滚动渐隐、首行放大画在里面), 往外出血抵掉 (见 tvLayerGalleryBleed), 不多空一块
        contentPadding = PaddingValues(
            start = TV_PLAYER_GALLERY_CONTENT_PADDING_H,
            top = TV_PLAYER_LAYER_EDGE_FADE,
            end = TV_PLAYER_GALLERY_CONTENT_PADDING_H,
            bottom = VIEW_ALL_GALLERY_COMPACT_VERTICAL_INSET,
        ),
        overlay = { shown.imageZoom?.let { TvZoomedImageOverlay(it) } },
    ) {
        CompositionLocalProvider(
            LocalViewAllGalleries provides layerGalleries,
            LocalViewAllBackHandlers provides shown.back,
            LocalViewAllImageZoom provides shown.imageZoom,
        ) {
            shown.content(this, if (touch) Modifier else Modifier.tvFocusAnchor(focus, TvLayerViewAllFocus))
        }
    }
}

private data object TvLayerViewAllFocus : TvFocusKey

/** 叠层里的画廊: 一排 [columns] 格的紧凑档, 往两侧多占一截与标题对齐; 原生画廊最下一行再按下交给 [onExitDown] (回胶囊). */
private class TvLayerGalleries(
    private val base: ViewAllGalleries,
    private val onExitDown: (() -> Unit)?,
    private val columns: Int,
) : ViewAllGalleries {
    @Composable
    override fun PeopleGallery(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onLongPress: ((index: Int) -> Unit)?,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
        columns: Int,
        initialFocusIndex: Int,
        onExitDown: (() -> Unit)?,
        compact: Boolean,
    ) = base.PeopleGallery(
        items, onClick, onLongPress, onBind, initialFocus, modifier.tvLayerGalleryBleed(),
        columns = this.columns, initialFocusIndex = initialFocusIndex, onExitDown = this.onExitDown, compact = true,
    )

    @Composable
    override fun PosterGallery(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
        columns: Int,
        initialFocusIndex: Int,
        onExitDown: (() -> Unit)?,
        compact: Boolean,
    ) = base.PosterGallery(
        items, onClick, onBind, initialFocus, modifier.tvLayerGalleryBleed(),
        columns = this.columns, initialFocusIndex = initialFocusIndex, onExitDown = this.onExitDown, compact = true,
    )
}

/** 人物页叠层里的「查看全部」画廊一行几格. */
private const val TV_PLAYER_LAYER_GALLERY_COLUMNS = 3

/** 胶囊的画廊一行几格 (格子同上, 叠层窄一截, 见 [galleryLayerWidth]). */
private const val TV_PLAYER_PILL_GALLERY_COLUMNS = 2

/** 画廊那一层的左右内边距 (画廊的格从这里起; 没有内容时那一句也从这里起, 见 TvPanelEmptyCard). */
internal val TV_PLAYER_GALLERY_CONTENT_PADDING_H = 20.dp

/**
 * 画廊往左右各多占 [VIEW_ALL_GALLERY_COMPACT_SIDE_INSET] (紧凑档网格自己两侧让出这么多给聚焦放大与投影): 第一列的左缘与内容对齐;
 * 往上下各多占 [VIEW_ALL_GALLERY_COMPACT_VERTICAL_INSET] (紧凑档网格自己上下让出的那一截), 不在叠层留的上下空里再空一截.
 */
private fun Modifier.tvLayerGalleryBleed(): Modifier = layout { measurable, constraints ->
    val bleed = VIEW_ALL_GALLERY_COMPACT_SIDE_INSET.roundToPx()
    val vertical = VIEW_ALL_GALLERY_COMPACT_VERTICAL_INSET.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = bleed * 2, vertical = vertical * 2))
    layout(
        constraints.constrainWidth(placeable.width - bleed * 2),
        constraints.constrainHeight(placeable.height - vertical * 2),
    ) { placeable.place(-bleed, -vertical) }
}

/** 被上面一层盖住的那层不接触摸 (平板触屏): 上面那层没接住的点按不能落到下面看不见的按钮上. */
private fun Modifier.swallowPointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
