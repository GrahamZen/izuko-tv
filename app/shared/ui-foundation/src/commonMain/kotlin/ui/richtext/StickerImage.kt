/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.richtext

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.AsyncImage
import org.jetbrains.compose.resources.painterResource

/**
 * 行内表情的显示尺寸.
 *
 * 行内表情要在**排版前**就给出占位框的大小, 但表情图是按地址现拉的 (见
 * [me.him188.ani.app.ui.comment.BangumiStickers]), 拉到之前并不知道原图多大 —— 而各个表情包差得很远:
 * 最早那 125 张是 20x20 的方图, 颜文字是扁的横条 (最扁 23x9), Bangumi 娘那套是宽的动图.
 *
 * 所以: 先按方的占位, 图一到手就把原图尺寸记下来并触发重排. 同一张图在一次运行里最多"跳"一次,
 * 之后 (含 Coil 缓存命中) 直接按正确尺寸排.
 */
@Stable
object StickerSizes {
    /**
     * 原图尺寸 (px), 键是表情代码.
     *
     * **按 token 一格 `MutableState`, 不用 `SnapshotStateMap`**: SnapshotStateMap 的 `get` 只把
     * "整个 map 对象"报给快照系统, 没有按键的订阅粒度 —— 任何一枚表情图加载完成的那次写入,
     * 都会让**所有**读过表的作用域整体失效. 实测场景: 在回复弹窗里滚 Bangumi 娘那包 (116 枚),
     * 每张图 onSuccess 一次写入 = 弹窗正文 + 背后所有带表情的评论行全部重排一遍, 正好发生在
     * 网格滚动最需要流畅的时刻. 外层普通 map + 内层 MutableState 让读方只订阅自己那一枚.
     *
     * 外层 map 只增不改 (每个 token 一个恒定的 state 格子), 容量 = 表情目录大小 (几百枚, 有界).
     * 读写都在主线程 (组合 / Coil 主线程回调), 不需要锁.
     */
    private val intrinsicSizes = mutableMapOf<String, MutableState<IntPair?>>()

    private fun sizeStateOf(token: String): MutableState<IntPair?> =
        intrinsicSizes.getOrPut(token) { mutableStateOf(null) }

    /** 上限: 相对基准尺寸的倍率. 动图那套原图很大, 不压一下会在文字里鹤立鸡群. */
    private const val MAX_HEIGHT_FACTOR = 1.4f
    private const val MAX_WIDTH_FACTOR = 2.4f

    fun record(token: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val size = IntPair(width, height)
        val state = sizeStateOf(token)
        // 先比后写: 同值写入也会让订阅方失效, 而 Coil 缓存命中时每次组合都会回调一次
        if (state.value != size) state.value = size
    }

    /**
     * [token] 对应表情的显示尺寸.
     *
     * 原图按 1px : 1dp 摆 (随包那 125 张的既有观感就是 20px 图摆 20dp), 超过上限再等比压;
     * 还没拿到原图时按 [base] 见方 —— 最常见的方图一次也不会跳.
     */
    fun displaySize(token: String, base: Dp): DpSize {
        // 在组合里读 = 只订阅这一枚表情的尺寸
        val intrinsic = sizeStateOf(token).value ?: return DpSize(base, base)
        val width = intrinsic.first.toFloat()
        val height = intrinsic.second.toFloat()
        val scale = minOf(
            1f,
            base.value * MAX_HEIGHT_FACTOR / height,
            base.value * MAX_WIDTH_FACTOR / width,
        )
        return DpSize((width * scale).dp, (height * scale).dp)
    }

    private data class IntPair(val first: Int, val second: Int)
}

/**
 * 一枚表情.
 *
 * **优先走 Coil 拉** ([UIRichElement.Annotated.Sticker.imageUrl]): Bangumi 的表情包有不少是动图
 * (`musume_61` 这类), 而随包的图是 compose resource, `painterResource` 只会出第一帧 —— 不会动.
 * Coil 有动图解码器 (见 `configurePlatformDecoders`), 且走内存/磁盘缓存, 命中就不会再下载.
 *
 * 随包的那 125 张 ([UIRichElement.Annotated.Sticker.resource]) 当占位与兜底: 拉之前先摆上去,
 * 所以最常见的表情立刻可见; 离线/拉失败也就停在这张静态图上, 不会变成一块空白.
 */
@Composable
fun StickerImage(
    sticker: UIRichElement.Annotated.Sticker,
    modifier: Modifier = Modifier,
) {
    val bundled = sticker.resource?.let { painterResource(it) }
    val imageUrl = sticker.imageUrl
    if (imageUrl == null) {
        if (bundled != null) {
            Image(painter = bundled, contentDescription = null, modifier = modifier)
        }
        return
    }
    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        modifier = modifier,
        placeholder = bundled,
        error = bundled,
        fallback = bundled,
        contentScale = ContentScale.Fit,
        // 拿到图才知道原图多大, 记下来给下一次排版用 (会触发一次重排, 见 StickerSizes)
        onSuccess = { result ->
            StickerSizes.record(sticker.id, result.width, result.height)
        },
    )
}
