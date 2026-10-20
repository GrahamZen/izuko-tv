/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.tv.tvPosterWallToneGate

/**
 * hero 的一路展示内容 (由页面在 Compose 里按换挡规则算好; 探索页有热门轮播 / 聚焦卡两路): [backdrop] 停稳后的背景图, [dimming] = 目标已换、
 * 展示还没跟上 (按下即压暗要等它放开), [rawSubjectId] = 此刻真实的目标 (换了就压暗), [text] = 文字 (null = 滚动 / 连发中藏起来),
 * [autoAdvanced] = 这次换页是自动轮播推进的 (文字用放慢的过渡), [wall] = hero 态整屏模糊背景铺的图 (开了「hero 态铺模糊背景」才用,
 * 见 [TvNativeHeroStage.heroBlur]): 整部的横版背景图 —— 继续观看的条目 [backdrop] 是单集剧照时它也还是整部那张, 就是详情页的背景.
 */
@Immutable
data class TvNativeHeroSource(
    val backdrop: TvNativeBackdropTarget?,
    val dimming: Boolean,
    val rawSubjectId: Int?,
    val text: TvNativeHeroText?,
    val autoAdvanced: Boolean = false,
    val wall: TvNativeWallBackdropTarget? = null,
)

/**
 * hero 舞台: 原生海报墙 hero 态里「显示什么、透明到什么程度」的那一份, 网格页 ([TvNativeGridPageView]) 与行式海报墙 ([TvNativeRowWallView],
 * 探索页、追番页「追更」) 共用. 摆在哪里、卡片怎么挪由宿主管 —— 视图由宿主加进自己、量、排, 本类不碰布局.
 *
 * - [backdrop]: 背景图 (贴右上角那张); [heroText]: hero 文字 (标题、简介等, 宿主装进自己的文字块里);
 * - [wallBackdrop]: 整屏背景, 画在宿主最底下 ([enableWallBackdrop] 建). 新番时间表的卡片墙常铺; 开了 [heroBlur] 的 hero 态铺聚焦那部的模糊版;
 * - [timeline]: hero 态时间线 (进出 hero 态各层的先后与时长), 每一帧回调宿主 ([Host.onHeroFrame]) 重摆;
 * - [wallFocus]: 点开进详情页的对焦 (卡片淡没、整屏背景变清晰, 到位再进); 点开时 hero 文字里标题以外的几行跟着淡, 标题变成详情页的样子 ([openFade]);
 * - 压黑策略 ([dark] / [heroBlur] → [heroDarkens]): 深色主题且没铺模糊背景时 hero 态整屏压黑, 背景图等底色黑透才露面 ([contentAlpha]).
 *
 * 换内容 (按下即压暗、交叉淡入还是当场换) 各宿主规则不同 (网格页等页面的内容跟上聚焦卡, 探索页在轮播与聚焦卡两路之间换), 由宿主按自己的规则调
 * [backdrop] / [heroText] / [showWall]; 压在模糊背景上的字照 vibrancy 画 ([textOf]).
 */
internal class TvNativeHeroStage(
    private val hostView: ViewGroup,
    private val sketch: Sketch,
    private val scope: CoroutineScope,
    heroTextStyle: TvNativeHeroTextStyle,
    private val host: Host,
) {
    /** 宿主 (原生海报墙页视图). */
    interface Host {
        /** 视觉效果的过渡 (长按窥视与倒放要不要走过渡). */
        val transitions: Boolean

        /** hero 时间线走了一帧, 或压黑策略变了 (换主题 / 开关模糊背景): 按 [timeline] 与 [contentAlpha] 重摆, 黑度 ([tone]) 报给页面. */
        fun onHeroFrame()

        /** 开关了 [heroBlur] (整屏背景已建好 / 已撤): 宿主按自己的规则补 (背景图边缘擦不擦、放大转场从哪起、内容重新套一遍). */
        fun onHeroBlurChanged(enabled: Boolean) {}

        /** 点开 / 窥视时卡片淡没的程度 (见 [TvNativeWallFocus.Host.applyFade]); 本类已改好 hero 文字与 [openFade], 宿主淡自己的卡片等. */
        fun applyOpenFade(fade: Float, keep: Int, chrome: Boolean)

        /** 点开途中 (对焦还没到位、还没进详情页): 这期间返回键取消点开. */
        fun onOpeningChanged(opening: Boolean)

        /** 对焦到位, 这就进第 [index] 张的详情页. */
        fun onOpened(index: Int)
    }

    val backdrop = TvNativeBackdropView(hostView.context, sketch, scope)
    val heroText = TvNativeHeroTextView(hostView.context, heroTextStyle).also { it.sketch = sketch }
    val timeline = TvNativeHeroTimeline { host.onHeroFrame() }

    /** 整屏背景 (见类说明); null = 没建. */
    var wallBackdrop: TvNativeWallBackdropView? = null
        private set

    /** 点开时背景图与 hero 文字里标题以外的几行跟着卡片淡没的程度 (0..1); 长按窥视不算 (只淡卡片). */
    var openFade = 0f
        private set

    val wallFocus = TvNativeWallFocus(
        hostView,
        object : TvNativeWallFocus.Host {
            override val wall: TvNativeWallBackdropView? get() = wallBackdrop
            override val transitions: Boolean get() = host.transitions

            override fun applyFade(fade: Float, keep: Int, chrome: Boolean) {
                openFade = if (chrome) fade else 0f
                heroText.detailAlpha = 1f - openFade
                // 标题跟着变成详情页的样子 (浅色主题: 黑字 → 白字压黑影), 背景对上焦时已经是白字
                heroText.titleLook = openFade
                host.applyOpenFade(fade, keep, chrome)
            }

            override fun onOpeningChanged(opening: Boolean) = host.onOpeningChanged(opening)

            override fun onOpened(index: Int) = host.onOpened(index)
        },
    )

    /** Compose 根视图 (放大转场的框按它的坐标登记). */
    var composeRoot: View? = null
        set(value) {
            field = value
            backdrop.composeRoot = value
            heroText.composeRoot = value
            wallBackdrop?.composeRoot = value
        }

    /** 此刻在不在导航: 背景图的剧照升档等它为 false 才去取原图 (见 [TvNativeBackdropView.navigating]). */
    var backdropNavigating: () -> Boolean
        get() = backdrop.navigating
        set(value) {
            backdrop.navigating = value
        }

    /** 背景图遮罩色 (hero 的底). */
    var fadeColor: Int
        get() = backdrop.fadeColor
        set(value) {
            backdrop.fadeColor = value
        }

    /** 深色主题: hero 态整屏压黑, 背景图等黑透才露面 (铺着模糊背景时不压黑, 见 [heroDarkens]). */
    var dark: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyTone()
        }

    /**
     * hero 态在整页底下铺模糊背景 (设置里的「hero 态铺模糊背景」): 进 hero 态时整屏背景随 hero 时间线淡入, 铺停稳后聚焦那部的横版背景图的模糊版
     * ([TvNativeHeroSource.wall]: 整部的那张, 不是单集剧照); 背景图与 hero 文字照旧画在它上面; 整屏底色不压黑. hero 态里按确定: 卡片、背景图、
     * 简介淡没, 模糊背景对焦变清晰 (就是详情页的背景), 到位再进详情页, 回来倒放. hero 标题的 logo 按正下方的模糊背景判看不看得清.
     */
    var heroBlur: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                // 连着换卡时模糊层同时只有一张在淡入 (单次按键当场换)
                enableWallBackdrop().coalesceSwaps = true
                heroText.logoBackdrop = TvNativeLogoBackdrop { subjectId, rect -> wallBackdrop?.sampler(subjectId, rect) }
            } else {
                wallFocus.reset()
                wallBackdrop?.let { hostView.removeView(it) }
                wallBackdrop = null
                heroText.logoBackdrop = null
            }
            host.onHeroBlurChanged(value)
            applyTone()
        }

    /**
     * hero 态把整屏底色压黑: 深色主题、且没铺模糊背景 —— 模糊背景盖满整屏, 压黑只会在进出 hero 态时先闪一下黑; 浅色主题 hero 底与卡片墙同色.
     * 不压黑时 hero 时间线按不压黑的时长走, 背景图不等底色, 报给页面的黑度恒为 0.
     */
    val heroDarkens: Boolean get() = dark && !heroBlur

    /** 报给页面画整屏底色的黑度 (不压黑时恒 0). */
    val tone: Float get() = if (heroDarkens) timeline.tone else 0f

    /** hero 态内容 (背景图、模糊背景) 此刻的不透明度: 跟着 hero 时间线, 压黑时等底色黑透才淡入. */
    val contentAlpha: Float get() = timeline.content * (if (heroDarkens) tvPosterWallToneGate(timeline.tone) else 1f)

    init {
        timeline.darkens = heroDarkens
    }

    private fun applyTone() {
        timeline.darkens = heroDarkens
        host.onHeroFrame()
    }

    /** hero 文字: 铺着模糊背景时照 vibrancy 画 (深色主题). */
    fun textOf(src: TvNativeHeroSource): TvNativeHeroText? = src.text?.copy(vibrant = heroBlur && dark)

    /** 建出整屏背景 (页面要了才建, 画在宿主最底下). */
    fun enableWallBackdrop(): TvNativeWallBackdropView =
        wallBackdrop ?: TvNativeWallBackdropView(hostView.context, sketch, scope).also { view ->
            view.composeRoot = composeRoot
            view.onSharpReady = { wallFocus.onSharpReady() }
            view.onShownChanged = { heroText.logoBackdropChanged() }
            hostView.addView(view, 0)
            wallBackdrop = view
        }

    /**
     * 整屏背景换图. [replace] = 当场换掉旧的那张, 不交叉淡入 (换来源的重建). [keepMatches] = 换完之后背景是不是对焦留着的那张卡的、能变清晰
     * (长按时背景还没换到那张卡, 换到了就接着变清晰, 见 [TvNativeWallFocus.onTargetChanged]).
     */
    fun showWall(target: TvNativeWallBackdropTarget?, replace: Boolean = false, keepMatches: () -> Boolean = { false }) {
        val wall = wallBackdrop ?: return
        wall.show(target, replace = replace)
        wallFocus.onTargetChanged(keepMatches = keepMatches())
    }

    /** 宿主离开窗口. */
    fun detach() {
        wallFocus.detach()
    }
}
