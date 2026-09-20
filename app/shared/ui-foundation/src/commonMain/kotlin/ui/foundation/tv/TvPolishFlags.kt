/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.concurrent.Volatile

/**
 * TV 动效精修项的运行时开关 (2026-09-10 用户要的: 每项留一个能在运行时切的开关, 便于 A/B 录像对比).
 * 默认全开. 改法: `adb shell am start -n <pkg>/.MainActivity --ez ani_polish_press_dim false` 等,
 * MainActivity.handleStartIntent 读 extra 写进来. 都是 @Volatile 普通变量, 读点在 lambda / 协程 / 过渡规格
 * 里, 下一次用到就生效, 不触发重组.
 */
object TvPolishFlags {
    /**
     * 返回缩回的旧顺序 (A/B 用, 默认关): 图上屏那一帧就出栈, 列表页的恢复 / 重建与缩回运动叠在一起. 默认 (新顺序) 缩到位、静止后才出栈,
     * 列表页就绪再撤层, 见 TvHeroZoomHandoff.Shrink. extra: `ani_polish_shrink_pop_first`.
     */
    @Volatile
    var shrinkPopFirst: Boolean = false

    /**
     * 放大完成后返回的快速路径 (来源列表页常驻时, 详情页只藏不销毁, 缩回不等它): 0 = 关 (图上屏那帧就出栈、详情页整页移出组合, 再起步);
     * 1 = 落地后出栈, 详情页销毁完再撤层; 2 = 落地先露列表页, 下一帧再出栈 (销毁落在列表页已上屏的静止画面上). extra: `ani_polish_shrink_keep`.
     */
    @Volatile
    var shrinkKeep: Int = 1

    /**
     * 缩回起步段的抬升 (A/B 用): 0 = iOS 关闭拟合的原曲线, 1/2/3 = 起步依次更快 (中后段与落地时刻基本不动).
     *
     * 原曲线前 60ms 几乎不动 (首帧 0.7%), 放在 iOS 上是"缩成图标"那种大位移, 读得出已经开始了; 我们这边全程只从
     * 全屏缩到 hero 框 (1.515 倍), 0.7% 折算到屏幕上**边缘只走 2.3px** —— 低于看得见的阈值, 于是像"按下去没反应"
     * (用户 2026-09-16: "返回好像有点慢或者说不跟手"). 只抬前 30~40ms, 不压总时长 —— 压短会变回"突然跳过去".
     * extra: `ani_polish_shrink_curve`.
     */
    @Volatile
    var shrinkCurve: Int = 1

    /**
     * 放大起跑时整屏底色渐入所占的**时长**比例: **默认 0 = 起跑那一帧直接铺满**, >0 = 在前这么多比例的时长里渐入.
     *
     * **默认关掉是有原因的**: 渐入要成立, 下面的列表页就得接着画 (否则是"底色盖在黑上", 与硬切没区别); 而放大层是个
     * **矩形** —— 图与渐变带都止于自己的边框, 压在卡片列表上就是一条清晰的硬边, 很难看 (用户 2026-09-16).
     * 羽化只处理左 / 下两边, 而且是往页面底色化开的, 底下换成列表内容就对不上. 也就是说"底色渐入 + 列表页留着"
     * 与现在这套放大 (假定四周是纯色底) 本质冲突, 要做得先把放大层四条边都做成软边.
     *
     * **试过的两条补救都不行** (2026-09-16, 别再试第三遍):
     * 1. 让羽化照常画 —— 它是"把周边底色不透明地涂在图边上再往内化开", 底下露着卡片时就是一条压在卡片上的深色条带;
     * 2. 那一段干脆不画羽化 —— 羽化带**比图的实际边缘宽**, 抽掉它本身就是一次突变, 起步更难看 (用户原话).
     *
     * 要真做, 只能把图的边缘**擦成透明** (DstOut) 让底下的列表内容透上来, 而那会强制本层每帧开离屏缓冲
     * (4K 实测每帧 20~30ms, `solidUnderlay` 那套就是专门为绕开它写的), 索尼又是 GPU 受限那台, 不能默认开.
     *
     * 而"按确认那一下背景提前变成详情页的样子"这个真正的问题, 是 backdrop 上那套遮罩两端各画各的造成的,
     * 已由 [TvBackdropTreatment] 的声明 + 插值解决, 与这一项无关. extra: `ani_polish_zoom_scrim_t`.
     */
    @Volatile
    var zoomScrimT: Float = 0.6f

    /**
     * 缩回时整屏底色化开所占的**时长**比例, **默认 0 = 撤层那一下直接交还**. 理由同 [zoomScrimT]:
     * 两个方向都试过, 只要列表页还画在下面, 放大 / 缩回层那个矩形的硬边就压在卡片上, 一样难看
     * (用户 2026-09-16: "返回的时候也一样"). extra: `ani_polish_shrink_scrim_t`.
     */
    @Volatile
    var shrinkScrimT: Float = 0.6f

    /**
     * **实验 (分支 tv/zoom-soft-edge)**: 放大 / 缩回途中整层走离屏, 四条边用 DstOut **擦掉 alpha** 做真透明软边,
     * 于是下面的列表页从边缘透上来, 而不是被一条不透明的深色带盖住.
     *
     * 前提是 [zoomScrimT] / [shrinkScrimT] > 0 (列表页要露着才有意义). 带宽窄、只在图越出源框的那段距离里生长、
     * 落地收没, 四条边四角连续. extra: `ani_polish_zoom_soft_edge`.
     */
    @Volatile
    var zoomSoftEdge: Boolean = true


    /** 按下方向键那一刻旧背景图先压暗、新图随后淡入 (Prime Video 式即时反馈). 两档都生效. */
    @Volatile
    var pressDim: Boolean = true

    /** hero 文字分行错落进场 (标题 → 元数据 → 简介各晚 40ms, Google TV 首页的味道). 只在完整视觉效果档. */
    @Volatile
    var textStagger: Boolean = true

    /** 点卡片进详情页时背景从列表页 hero 的框放大到全屏 (两页同一张图时才做, 见 TvHeroZoomHandoff). 两档都生效. */
    @Volatile
    var heroZoom: Boolean = true
}
