/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * **TV 示焦描边的唯一实现**: 几何 (宽/空隙/圆角) 与画法都在这里, 各页只传自己的圆角与颜色.
 *
 * ## 为什么必须共用
 *
 * 这套描边曾经在仓库里存在五份互相抄写的实现 (竖版卡自绘外圈、竖版卡锚位框、详情页选集卡锚位框、
 * 共享横滑卡的 `focusBorder`、弹窗里的两态边框), 宽度一度是 2dp / 2.5dp / 1.75dp / 2-1dp 四档
 * 并存. 各处 KDoc 只能靠"与 XX 同一档""改一边就改另一边"这种人工承诺同步 —— 而那正是缺一层
 * 共享原语的证据: 描边宽从 2.5dp 收到 1.75dp 那次, 就漏改了其中两处.
 *
 * 现在只有本文件定义宽度与空隙, 调一次全仓一致.
 *
 * ## 与 `Modifier.border` 的关系
 *
 * [tvFocusRing] 逐像素复刻 `Modifier.border(width, brush, RoundedCornerShape(r))` 的几何
 * (描边整条画在轮廓**内侧**: 内缩半个线宽, 圆角半径同步减半个线宽), 但**不在组合阶段读焦点态** ——
 * 见 [tvFocusRing] 的说明. 需要"常驻描边"(锚位框那种自己已经按可见性条件组合的) 用 [tvFocusRingBorder].
 */
object TvFocusRing {
    /**
     * 描边宽度.
     *
     * Prime Video 4K 截图实测 **1.75dp** (7px). 早先用过 2.5dp, 真机上比 Prime 明显粗一档.
     */
    val Width = 1.75.dp

    /**
     * 描边框盒相对卡片轮廓的**外扩量** (卡片内容常驻内缩此值, 聚焦时空隙处露出底色形成"色圈+留白").
     *
     * 描边从框盒边界向内画, 所以**肉眼看到的空隙** = 本值 − [Width] = 0.25dp —— Prime 的描边
     * 也几乎贴着卡片内容. 调它必须连 [Width] 一起算, 否则空隙变负 = 描边压到封面上.
     *
     * 它同时是**卡片外框与焦点目标之间的偏差**: 可聚焦节点是内缩后的封面, 而锚位框画在外框上,
     * 于是"把聚焦项滚到锚位"的 pivot 式 `BringIntoViewSpec` (拿到的是焦点目标矩形) 必须把锚
     * 加上本值, 否则框与卡片差这一点点对不齐 —— 真机肉眼可见 (2026-08-10 探索页踩到).
     */
    val Gap = 2.dp

    /**
     * 卡内文字避让描边的内缩量.
     *
     * 描边画在卡片轮廓内侧, 而横滑卡的标题/说明一般顶满卡宽、末行贴着卡底 —— 不留这一点内缩,
     * 聚焦时描边就压在字上 (真机: "文字被焦点框挡住"). 取略大于 [Width] 即可, 大了会显得文字
     * 与封面左缘对不齐.
     */
    val TextInset = 4.dp

    /**
     * 传给 [tvFocusRing] 的 `cornerRadius`, 表示**全圆角** (圆形头像 / 胶囊按钮): 半径取
     * `min(宽, 高) / 2`, 等价于 `CircleShape`.
     */
    val FullyRounded: Dp = Dp.Infinity

    /** 默认描边色: 主题主色. 与侧边栏选中、播放器面板条目同一个语义 ("这是焦点所在"). */
    val defaultBrush: Brush
        @Composable @ReadOnlyComposable
        get() = SolidColor(MaterialTheme.colorScheme.primary)

    /**
     * 详情页选集卡那套**主题动态色渐变** (左上 primary → 右下 secondary).
     *
     * 只用在"整块大卡"上: 卡越大渐变越读得出来, 小卡上两端色差不足一个色阶, 白费一次渐变着色.
     */
    val gradientBrush: Brush
        @Composable @ReadOnlyComposable
        get() = Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
        )

    /** 黑白态 (播放器面板 / 选集轮播的单色档) 的纯白描边. */
    val monochromeBrush: Brush = SolidColor(Color.White)
}

/**
 * 聚焦时沿轮廓画一圈示焦描边 —— **焦点态只在绘制阶段读, 不触发重组**.
 *
 * ## 为什么不是 `Modifier.border`
 *
 * 上一版是 `focusBorder`: 一个返回 `Modifier` 的 `@Composable` 扩展, 内部 `var focused by remember`
 * 再 `ifThen(focused) { border(...) }`. 返回 Modifier 的 composable **拿不到自己的 restart scope**
 * (Compose 只给返回 `Unit` 的做 restartable), 于是那次 `focused` 读被记到**调用方**头上 ——
 * 描边挂在哪张卡上, 那张卡的整个 composable body 就在每次焦点进出时重跑一遍, 只为在链上加/去一圈线.
 *
 * 遥控器长按横移时限流后仍有 8 格/秒, 每格牵动两张卡 (让出焦点的 + 拿到焦点的) ≈ 16 次整卡重组/秒,
 * 而真正需要变的只有一圈描边的**绘制**.
 *
 * 现在焦点态存进一个普通 `MutableState`, 只在 `drawWithCache` 的绘制 lambda 里读: 快照系统把它
 * 记成**绘制阶段**的读, 焦点变化只触发重绘, 组合与布局都不动.
 *
 * ## 几何与 `border` 的关系
 *
 * 描边整条画在轮廓内侧: 内缩半个线宽、圆角半径同步减半个线宽、线宽向上取整到整像素
 * (见 [ringStrokeWidthPx]) —— 这三条与 `Modifier.border` 逐句相同, 所以**当前各调用点**
 * 从 `border` 换过来没有位移或粗细变化.
 *
 * **没有**复刻 border 的两个退化分支, 当前调用点都碰不到, 但新增调用点前要确认:
 *  - 线宽粗到 `2×线宽 > min(宽,高)` 时 border 改画实心填充, 这里仍画描边 (会自重叠);
 *  - 圆角半径小于半个线宽时 border 保留外圆角并用差集填充, 这里会把外角削成直角.
 *
 * ## 用法约束
 *
 *  - 必须挂在**焦点目标之前** (同一条链上, `clickable`/`focusable` 之前), 否则观察不到焦点;
 *  - 必须挂在**定尺寸之后** (`width`/`size` 之后), 否则按父约束的尺寸画;
 *  - 链上若还有 `clip`, 挂在 `clip` **之前**, 免得描边被卡片自己的圆角裁掉一半.
 *
 * @param cornerRadius 与卡片自身圆角一致; [TvFocusRing.FullyRounded] = 圆形/胶囊.
 * @param enabled false 时整条链原样返回 (不挂焦点监听也不绘制). 用于按形态门控 ——
 *   见 [tvFocusableCard], 一般不必自己传.
 */
@Composable
fun Modifier.tvFocusRing(
    cornerRadius: Dp,
    width: Dp = TvFocusRing.Width,
    brush: Brush = TvFocusRing.defaultBrush,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    // hasFocus 而非 isFocused: 焦点目标有时在下面的子树里 (卡容器包着可聚焦的内容).
    // 普通 MutableState (不是 by remember 的委托读): 写在这里、只在下面的绘制 lambda 里读
    val focused = remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused.value = it.hasFocus }
        .drawWithCache {
            val widthPx = ringStrokeWidthPx(width, size)
            val radiusPx = cornerRadius.resolveRingRadiusPx(this, size)
            onDrawWithContent {
                drawContent()
                if (focused.value) drawTvFocusRing(brush, widthPx, radiusPx)
            }
        }
}

/**
 * 常驻描边版本: 供**锚位聚焦框**用 —— 那种框自己已经由调用方按可见性条件组合 (框钉着不动,
 * 卡片在框下滑动, Prime Video 式), 不需要再自己监听焦点.
 *
 * 与 [tvFocusRing] 同几何同默认值, 区别只是"永远画".
 */
fun Modifier.tvFocusRingBorder(
    cornerRadius: Dp,
    brush: Brush,
    width: Dp = TvFocusRing.Width,
): Modifier = drawWithCache {
    val widthPx = ringStrokeWidthPx(width, size)
    val radiusPx = cornerRadius.resolveRingRadiusPx(this, size)
    onDrawWithContent {
        drawContent()
        drawTvFocusRing(brush, widthPx, radiusPx)
    }
}

/**
 * 线宽换算, **与 `Modifier.border` 同一套**: 向上取整到整像素, 再按尺寸钳制.
 *
 * `ceil` 不能省 —— border 就是这么做的 (`Border.kt` 的 `BorderModifierNode`), 少了它在
 * **density 非整除**的设备上会与 border 画得不一样: 1.75dp 在 density 2.0 (1080p 电视) 上
 * 裸算是 3.5px, border 取 4px; 3.5px 还会让描边压在半像素上糊一档.
 *
 * 只在 density 4.0 (Shield 强制 4K, 1.75×4=7.0) 上两者恰好相等 —— 当初就是在这个密度上
 * 验证的, 所以没看出差别.
 */
private fun Density.ringStrokeWidthPx(width: Dp, size: Size): Float =
    min(ceil(width.toPx()), ceil(size.minDimension / 2f))

/**
 * 画一圈描边, 几何与 `Modifier.border(width, brush, RoundedCornerShape(radius))` 逐像素一致:
 * 内缩半个线宽把整条线放进轮廓内, 圆角半径同步减半个线宽保持与轮廓同心.
 */
private fun DrawScope.drawTvFocusRing(brush: Brush, widthPx: Float, radiusPx: Float) {
    val half = widthPx / 2f
    drawRoundRect(
        brush = brush,
        topLeft = Offset(half, half),
        size = Size(size.width - widthPx, size.height - widthPx),
        cornerRadius = CornerRadius((radiusPx - half).coerceAtLeast(0f)),
        style = Stroke(widthPx),
    )
}

/** [TvFocusRing.FullyRounded] 按当前尺寸解析成 `min(宽, 高) / 2`; 其余按 dp 换算. */
private fun Dp.resolveRingRadiusPx(density: Density, size: Size): Float =
    if (this == TvFocusRing.FullyRounded) min(size.width, size.height) / 2f else with(density) { toPx() }

/**
 * 两态边框 (未聚焦细灰线 / 聚焦粗主题色): 弹窗里那些**整块一个焦点**的区域用 —— 引用区、
 * 更新正文块、输入框. 它们与卡片不同, 未聚焦时也要有个框划出边界, 所以不能直接用 [tvFocusRing].
 *
 * 这里仍用 `Modifier.border`: 调用方本来就在组合里读了聚焦态 (还要拿它决定内容), 再避开无益.
 */
@Composable
fun Modifier.tvFieldBorder(
    focused: Boolean,
    idleColor: Color,
    cornerRadius: Dp,
    focusedWidth: Dp = TV_FIELD_BORDER_FOCUSED_WIDTH,
    idleWidth: Dp = TV_FIELD_BORDER_IDLE_WIDTH,
): Modifier = border(
    // 委托给 stroke 版, 宽/色只有那一份真相 —— 同一个弹窗里两种写法必须画出同一圈线
    tvFieldBorderStroke(focused, idleColor, focusedWidth, idleWidth),
    RoundedCornerShape(cornerRadius),
)

/**
 * [tvFieldBorder] 的 `BorderStroke` 形态, 给 `Surface(border = ...)` 这种只收 stroke 的组件用
 * (输入框、预览块). 与 modifier 版同宽同色 —— 同一个弹窗里两种写法必须画出同一圈线.
 */
@Composable
@ReadOnlyComposable
fun tvFieldBorderStroke(
    focused: Boolean,
    idleColor: Color,
    focusedWidth: Dp = TV_FIELD_BORDER_FOCUSED_WIDTH,
    idleWidth: Dp = TV_FIELD_BORDER_IDLE_WIDTH,
): BorderStroke = BorderStroke(
    width = if (focused) focusedWidth else idleWidth,
    color = if (focused) MaterialTheme.colorScheme.primary else idleColor,
)

/**
 * 弹窗内**整块可聚焦区域**聚焦时的边框宽度.
 *
 * 比卡片的 [TvFocusRing.Width] 粗: 卡片有封面色块衬着, 一圈细线就够显眼; 这些区域是纯文字
 * 铺在面板底色上, 细线在 10-foot 距离读不出来.
 */
val TV_FIELD_BORDER_FOCUSED_WIDTH = 2.dp

/** 未聚焦时的边框宽度: 只用来划出区域边界, 尽量弱. */
val TV_FIELD_BORDER_IDLE_WIDTH = 1.dp
