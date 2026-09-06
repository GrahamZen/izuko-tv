/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import me.him188.ani.app.ui.foundation.tv.tvPosterWallColorScheme

/**
 * 详情页的配色层: 浅色主题下把各档底色与文字色换成海报墙那套 Apple 浅色取值 ([tvPosterWallColorScheme]) —— 页面底 #BFC4C7,
 * 底板 / 卡片从它往白走, 正文纯黑、次要文字黑 60%; 主题色 (条目取色) 不动. 深色主题原样.
 *
 * Material 浅色配色的页面底 (surfaceContainerLowest) 是纯白, 条目取色后的 surface 也在 tone 98: 从浅灰的列表页放大进来
 * 整屏一下亮一截, 电视上大片白底又刺眼. Apple TV 的产品页不铺白 —— 首屏满版剧照, 往下是剧照模糊后压 20% 黑 (剧照本身
 * 很亮时压 55%), 按钮与底板用系统灰阶. 本页"剧照那一层"是条目取色的动态渐变, 它按页面底色调和, 底色换了它跟着降下来.
 *
 * 包在详情页本体 (连同评论 / 评分弹窗)、加载占位、放大与缩回那两层外面: 几处的底色必须是同一个值, 否则放大落位、
 * 占位换真页时底色会跳.
 */
@Composable
fun TvDetailsTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    // 配色对象按底色方案记住: 配色是静态的 CompositionLocal, 每次重组换新实例会让整页重组
    val scheme = remember(base) { if (base.surface.luminance() >= 0.5f) tvPosterWallColorScheme(base) else base }
    MaterialTheme(colorScheme = scheme, content = content)
}
