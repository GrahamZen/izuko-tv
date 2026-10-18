/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * 原生海报墙的一张卡的数据.
 *
 * @param subtitle 给了就番名只占一行、下面一行小字 (详情页关联条目的「续集」「前传」), 块高不变. 小字没聚焦时淡一档, 聚焦时全亮.
 * @param progress 继续观看的集数进度 (0..1), 贴封面底缘一条细进度条; null 不画.
 * @param obscure NSFW 打码: 封面只解一张很小的图, 放大成糊图.
 * @param subjectId 这张卡是哪个条目: 网格页进 hero 态时拿它核对页面给的 hero 内容是不是这一张的 (见 TvNativeGridPageView); null = 不核对.
 * @param subtitleColor [subtitle] 的颜色 (ARGB); null = 样式里的 ([TvNativeWallStyle.subtitle], 与番名同色).
 * @param badge 封面右上角画角标 (样式给了 [TvNativeWallStyle.badge] 才画; 新番时间表的「在追」).
 * @param count 封面右上角的数字角标 (样式给了 [TvNativeWallStyle.countBadge] 才画; 追番页「追更」的没看几集), 超过 99 写「99+」; null = 不画.
 */
@Immutable
data class TvNativeCard(
    val imageUrl: String?,
    val title: String,
    val subtitle: String? = null,
    val progress: Float? = null,
    val obscure: Boolean = false,
    val subjectId: Int? = null,
    val subtitleColor: Int? = null,
    val badge: Boolean = false,
    val count: Int? = null,
    /**
     * 行尾的「更多」卡 (不是条目): 封面位置是一块玻璃 (见 [TvNativeMoreGlassStyle]), 底下铺 [imageUrl] (一张竖版封面, 由页面挑) 的模糊小图,
     * 中间一枚圆钮托着加号, 加载中换成转着的圆弧; null = 普通海报.
     */
    val more: TvNativeMore? = null,
)

/** 「更多」卡的两种样子, 见 [TvNativeCard.more]. */
enum class TvNativeMore {
    Idle,
    Loading,
}

/**
 * 行尾「更多」卡 ([TvNativeCard.more]) 的玻璃外观, 照 tvOS 的玻璃材质 (配色同顶栏玻璃控件那一族, 见 TvGlassColors): 底下是一张竖版封面的
 * 模糊小图, 盖一层玻璃色 [tint], 左上角斜着一道高光 [sheen] 淡到透明, 一圈细亮边 [edge]; 中间一枚玻璃圆钮 ([buttonFill] / [buttonEdge])
 * 托着加号 ([icon]). 卡片聚焦时圆钮随聚焦程度换成浅色实底 ([buttonFocusedFill]) 配深色加号 ([iconFocused]), 同顶栏玻璃控件聚焦时的实底.
 * 颜色都是 ARGB.
 */
@Immutable
data class TvNativeMoreGlassStyle(
    val tint: Int,
    val sheen: Int,
    val edge: Int,
    val buttonFill: Int,
    val buttonEdge: Int,
    val icon: Int,
    val buttonFocusedFill: Int,
    val iconFocused: Int,
)

/** 深色主题: 中性深色玻璃, 白边白字; 聚焦的圆钮同顶栏玻璃的浅灰实底. */
internal val TV_NATIVE_MORE_GLASS_DARK = TvNativeMoreGlassStyle(
    tint = 0x6B1E1E1E,
    sheen = 0x29FFFFFF,
    edge = 0x38FFFFFF,
    buttonFill = 0x29FFFFFF,
    buttonEdge = 0x59FFFFFF,
    icon = 0xF2FFFFFF.toInt(),
    buttonFocusedFill = 0xFFD0D1D3.toInt(),
    iconFocused = 0xFF000000.toInt(),
)

/** 浅色主题: 发白的磨砂玻璃, 深色加号; 聚焦的圆钮是白色实底. */
internal val TV_NATIVE_MORE_GLASS_LIGHT = TvNativeMoreGlassStyle(
    tint = 0x59FFFFFF,
    sheen = 0x66FFFFFF,
    edge = 0x8CFFFFFF.toInt(),
    buttonFill = 0x73FFFFFF,
    buttonEdge = 0xB3FFFFFF.toInt(),
    icon = 0xBF000000.toInt(),
    buttonFocusedFill = 0xFFFFFFFF.toInt(),
    iconFocused = 0xFF000000.toInt(),
)

/**
 * 封面右上角的圆形角标: 直径 [sizePx]、离封面上缘与右缘 [insetPx], 底色 [backgroundColor], 中间一枚已着色的图标 [icon] (按它自己的尺寸画).
 */
@Immutable
data class TvNativeCardBadgeStyle(
    val icon: ImageBitmap,
    val sizePx: Float,
    val insetPx: Float,
    val backgroundColor: Int,
)

/** 把 [icon] 画成 [size] 见方、着 [tint] 色的位图 (原生视图与 Compose 版的卡片角标、按钮图标共用). */
@Composable
fun rememberTvIconImage(icon: ImageVector, size: Dp, tint: Color = Color.White): ImageBitmap {
    val painter = rememberVectorPainter(icon)
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(1)
    return remember(icon, px, tint, density) {
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) {
            with(painter) { draw(this@draw.size, colorFilter = ColorFilter.tint(tint)) }
        }
        image
    }
}

/** 演职人员圆头像行 ([TvNativeMonogramRowView]) 的一格. */
@Immutable
sealed interface TvNativeMonogram {
    /**
     * 一个人: 圆里是照片 ([imageUrl] 为空 = 写 [initials]), 圆下姓名 + 一行副标题 (角色是声优, 制作人员是职位).
     * 副标题空了也占一行 (同一排格子一样高).
     */
    data class Person(
        val imageUrl: String?,
        val name: String,
        val subtitle: String,
        val initials: String,
        /** 压在圆下缘的小标签 (角色的「主角」); 样式给了 TvNativeMonogramStyle.badge 才画. */
        val badge: String? = null,
    ) : TvNativeMonogram

    /** 行末的「查看全部」: 圆里一个箭头, 副标题写还有几个没露出来 (null = 数不出, 留空). */
    data class ViewAll(val remaining: Int?) : TvNativeMonogram
}
