/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.lan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * 二维码的模块矩阵 (`size × size`, true = 黑).
 */
class QrCodeMatrix(val size: Int, private val bits: BooleanArray) {
    init {
        require(bits.size == size * size) { "bits.size must be size*size" }
    }

    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]
}

/**
 * 把 [content] 编成二维码矩阵; 该平台没有编码器时返回 null (调用方退化为只显示文字).
 * JVM/Android 用 zxing; iOS 目前不做 (那边总有浏览器, 没有需要扫码的场景).
 */
expect fun encodeQrCode(content: String): QrCodeMatrix?

/**
 * 把 [content] 画成二维码, 模块用 [foreground] 直接画在宿主底色上, 不另起白卡 (用户 2026-09-10: 白卡在
 * 深色界面上太扎眼). 默认 [foreground] 是主题强调色 `primary`: 深色主题下它是浅色 —— 浅码压深底是
 * **反色码**, iOS 相机 / 微信 / 支付宝 / Google Lens 都认, 只有很老的扫码器不认; 浅色主题下 primary 是
 * 深色, 就是常规极性. 宿主要保证底色与 [foreground] 有足够明暗对比 (默认配色在 M3 的 surface 系上都够).
 * 该平台编不出码时什么都不画 (见 [encodeQrCode]).
 *
 * @param modifier 码本体的尺寸 (不含留白), 用 `size(...)` 给.
 * @param quietZone 四周留白 (透明, 只占位): 扫码器要靠它定位, 规范要求至少 4 个模块宽. 一个 36 字符的
 *   http 地址是 29 模块, 码 240dp 时一模块 ≈ 8dp, 默认 32dp 正好; 码画得更小时按比例给.
 * @param background 非 Unspecified 时在留白范围内铺这个底色 (圆角卡), 给宿主底色对比不够的场合用.
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    quietZone: Dp = 32.dp,
    foreground: Color = MaterialTheme.colorScheme.primary,
    background: Color = Color.Unspecified,
) {
    val matrix = remember(content) { encodeQrCode(content) } ?: return
    Box(
        Modifier
            .then(if (background.isSpecified) Modifier.background(background, RoundedCornerShape(12.dp)) else Modifier)
            .padding(quietZone),
    ) {
        Canvas(modifier) {
            val n = matrix.size
            // 模块边长取整像素: 小数边长会让相邻模块之间出现抗锯齿的灰缝, 码密一点时扫码器就开始犯难
            val cell = floor(size.minDimension / n)
            val offset = (size.minDimension - cell * n) / 2
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix[x, y]) {
                        drawRect(foreground, Offset(offset + x * cell, offset + y * cell), Size(cell, cell))
                    }
                }
            }
        }
    }
}
