/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.richtext

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.external.placeholder.placeholder
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.ifThen

/**
 * **评论正文里的外链图片**: 加载中 / 出图 / 失败三态, 全仓一份.
 *
 * 评论里贴的是外部图床, **挂掉或被删是常态** (实测有评论贴的是已经没有 A 记录的域名).
 * 不处理失败态的话骨架屏会一直闪, 看起来像永远在加载; 而失败后整块塌成 0 高度则是"骨架屏
 * 闪一下然后凭空消失", 可正文里明明写着 `[图片]`.
 *
 * 这套状态机原本在共享富文本与 TV 回复弹窗里各写了一遍, 上面那两句结论也就各修了一遍 ——
 * 两边最后给出的失败表现还不一样. 现在只有这一份, 差异收敛成 [errorContent] 一个参数.
 *
 * ## 高度不能跳
 *
 * 出图之前固有尺寸未知, 高度是 0; 图一到位整块正文往下弹一大截, 用户正在翻页的话会当场跳位.
 * 所以未出图时由 [unloadedModifier] 撑一个最小尺寸, 并全程 `animateContentSize` 把变化摊开.
 *
 * @param unloadedModifier 尚未出图时额外套上的尺寸约束 (`sizeIn` / `heightIn`), 出图后撤掉.
 * @param errorContent 失败时**替代整张图**的内容; null = 保留占位框, 交代"这里原本有张图".
 */
@Composable
fun CommentAsyncImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    cornerRadius: Dp = COMMENT_IMAGE_CORNER,
    unloadedModifier: Modifier = Modifier,
    errorContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // 0: 加载中, 1: 出图了, 2: 失败.
    // 按 url 键住: 同一个列表位置换了图 (翻页复用) 要从头来过, 不能继承上一张的结果
    var state by rememberSaveable(url) { mutableIntStateOf(0) }

    if (state == 2 && errorContent != null) {
        errorContent()
        return
    }

    AsyncImage(
        model = url,
        crossfade = false,
        contentDescription = null,
        modifier = modifier
            .ifThen(state != 1) { then(unloadedModifier) }
            .animateContentSize()
            .placeholder(state == 0)
            .clip(RoundedCornerShape(cornerRadius))
            .ifThen(onClick != null) { clickable { onClick?.invoke() } },
        contentScale = contentScale,
        onSuccess = { if (state != 1) state = 1 },
        onError = { if (state != 2) state = 2 },
    )
}

/** 评论图片的默认圆角. */
private val COMMENT_IMAGE_CORNER = 8.dp
