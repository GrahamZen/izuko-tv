/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.asBitmapOrNull
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.resize.Precision
import com.github.panpf.sketch.util.asComposeImageBitmap
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.SubjectSeedColorCache
import me.him188.ani.app.ui.foundation.theme.subjectSeedColor
import com.github.panpf.sketch.util.Size as SketchSize

/**
 * 平板播放页的主题跟详情页一致: 设置「条目详情页使用动态主题」打开时, 整页 (视频下面、侧边、画面上的控件) 按这一部封面取的主色配色,
 * 同详情页 (MaterialThemeFromPaletteAndImage) —— 同一个取色函数 ([subjectSeedColor])、同一张图 (竖版封面 [coverUrl])、
 * 同一份按条目 id 记的主色 ([SubjectSeedColorCache]). 从详情页或首页卡片进来时那边已经算好, 进页第一帧就对;
 * 没算过 (直接从继续观看、通知进播放页) 时在后台取封面现算, 算出来之前用用户主题色 (同详情页第一次打开).
 * 设置关着时原样返回 [pageTheme].
 *
 * 换的是 [ThemeSettings.seedColorValue], 并关掉「使用系统强调色」: 骨架各块的 AniTheme 按它生成配色, 与详情页的配色同一套参数
 * (TonalSpot, 纯黑背景跟设置), 同一部番两边一致; 画面那一块照旧强制深色 (见 TabletPlayerFrame).
 */
@Composable
internal fun rememberTabletSubjectTheme(subjectId: Int, coverUrl: String?, pageTheme: ThemeSettings): ThemeSettings {
    if (!pageTheme.useDynamicSubjectPageTheme) return pageTheme
    var seed by remember(subjectId) { mutableStateOf(SubjectSeedColorCache[subjectId]) }
    if (seed == null && !coverUrl.isNullOrBlank()) {
        val sketch = LocalSketch.current
        val context = LocalPlatformContext.current
        LaunchedEffect(subjectId, coverUrl) {
            // 取色只看颜色分布 (调色板自己还会再缩), 不按原尺寸解码
            val result = sketch.execute(
                ImageRequest(context, coverUrl) {
                    size(SketchSize(TABLET_SEED_DECODE_PX, TABLET_SEED_DECODE_PX))
                    precision(Precision.LESS_PIXELS)
                },
            )
            val bitmap = (result as? ImageResult.Success)?.image?.asBitmapOrNull()?.asComposeImageBitmap() ?: return@LaunchedEffect
            val color = bitmap.subjectSeedColor()
            // 解码期间详情页或首页卡片可能已经写进去了: 以先写的为准, 两处不会差一点
            seed = SubjectSeedColorCache[subjectId] ?: color.also { SubjectSeedColorCache[subjectId] = it }
        }
    }
    val s = seed ?: return pageTheme
    return remember(pageTheme, s) { pageTheme.copy(seedColorValue = s.value, useDynamicTheme = false) }
}

/** 取主色时封面解码的边长上限 (按比例缩, 不裁). */
private const val TABLET_SEED_DECODE_PX = 400
