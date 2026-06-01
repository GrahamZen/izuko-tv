/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.TV_BACK_KEYS
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState

/**
 * **遥控器上的"长按放大看图"**: 一份状态 + 一个按键拦截 + 一层居中大图, 三件配套使用.
 *
 * 手机/桌面的放大查看是页面级 `ImageViewer` (缩放手势 + 单击关闭), 遥控器上那套没有对应输入 ——
 * 既没有捏合也没有拖拽, 接过来只剩"开一张图、返回关掉". 所以 TV 上不复用它, 换成本组件:
 * 卡片上**长按确认键**弹一张居中大图, 返回键关掉.
 *
 * ## 为什么不是"再开一个弹窗"
 *
 * 大图要能盖在「查看全部」那种弹窗**之上** (长按的正是弹窗里的卡片), 而遥控器上两层独立窗口的
 * 焦点归属没有好解法 (见 `CharactersViewAllDialog` 的 KDoc: 点卡片是"先关本弹窗再弹预览").
 * 本组件因此**不开窗口, 也完全不碰焦点**: 大图只是画在同一个窗口里的一层, 焦点始终留在刚才那张
 * 卡片上 —— 于是关掉时不需要"把焦点还回去"这一步 (那一步在真机上最容易失手), 长按那次按住的
 * 余波也照旧由卡片自己的 [me.him188.ani.app.ui.foundation.tvLongPressKey] 吞掉.
 *
 * 代价是焦点还在底下的卡片上, 方向键会把它挪走 (用户看不见焦点去哪了) —— 所以开着期间
 * [tvImageZoomKeys] **吞掉一切按键**, 只留返回键关闭.
 *
 * ## 用法 (三处都要挂)
 *
 * ```
 * val imageZoom = rememberTvImageZoomState()
 * Box(Modifier.tvImageZoomKeys(imageZoom)) {   // ① 焦点所在子树的祖先
 *     // ... 卡片: Modifier.tvLongPressKey(onLongPress = { imageZoom.open(url) }, onShortPress = ...)  ②
 *     TvZoomedImageOverlay(imageZoom)          // ③ 画在最上层 (必要时 zIndex 置顶)
 * }
 * ```
 */
@Stable
class TvImageZoomState {
    /** 正在放大的图 URL; null = 没开. */
    var url: String? by mutableStateOf(null)
        private set

    val zooming: Boolean get() = url != null

    /** 空 URL 不开层 (数据缺图时长按不该弹出一块空白). */
    fun open(url: String) {
        if (url.isNotBlank()) this.url = url
    }

    fun close() {
        url = null
    }
}

@Composable
fun rememberTvImageZoomState(): TvImageZoomState = remember { TvImageZoomState() }

/**
 * 大图开着期间**吞掉一切按键**, 返回键抬起时关掉大图.
 *
 * 挂在**焦点所在子树的祖先**上 (页面根 / 弹窗内容根): `onPreviewKeyEvent` 只在焦点路径上触发,
 * 挂在旁支节点上收不到任何事件. 祖先先行, 所以它也会吞掉卡片长按那次按住余下的连发与抬起 ——
 * 无害: `tvLongPressKey` 见到下一次"新按下"会无条件重来 (那条注释里写的就是这种情形).
 *
 * 全屏放大不吞方向键的话, 焦点会在看不见的底层卡片间移动、连列表一起滚 —— 关掉之后焦点已经
 * 不在原处了.
 *
 * 返回键长按 (根部的全局手势) 仍照旧生效: 它的跟踪器在应用根部, 比这里更外层.
 */
fun Modifier.tvImageZoomKeys(state: TvImageZoomState): Modifier = onPreviewKeyEvent { event ->
    if (!state.zooming) return@onPreviewKeyEvent false
    if (event.key in TV_BACK_KEYS && event.type == KeyEventType.KeyUp) state.close()
    true
}

/**
 * 居中大图层: 压一层 scrim, 中央按原比例摆一张图 (不裁切, 长边贴 [TV_ZOOM_IMAGE_FRACTION] 的框).
 *
 * 不放相框/圆角: 图的实际比例要等加载完才知道, 先按预设比例画一块底再换成真比例, 落地就是
 * 加载完那一帧尺寸跳一下.
 */
@Composable
fun TvZoomedImageOverlay(state: TvImageZoomState, modifier: Modifier = Modifier) {
    val url = state.url ?: return
    Box(
        modifier.fillMaxSize().background(TV_ZOOM_SCRIM_COLOR),
        contentAlignment = Alignment.Center,
    ) {
        // 放大用的是另一个尺寸档的 URL (imageLarge), 卡片头像那份缓存帮不上忙 —— 电视上
        // 这一等是看得见的, 给个转圈
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) {
            CircularProgressIndicator(Modifier.size(TV_ZOOM_SPINNER_SIZE))
        }
        // 失败重试同卡片列表 (TV 上并发请求多, 偶发超时会让 painter 永久停在 Error)
        val retry = rememberAsyncImageRetryState(url)
        AsyncImage(
            model = if (retry.suppressed) null else url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(TV_ZOOM_IMAGE_FRACTION),
            contentScale = ContentScale.Fit,
            onSuccess = { loaded = true },
            onError = { retry.onError() },
        )
    }
}

/** 大图背后的压暗层. 比面板那层 (0.38) 重: 这一层要的是"只看图", 底下的界面越淡越好. */
private val TV_ZOOM_SCRIM_COLOR = Color.Black.copy(alpha = 0.82f)

/** 图的长边占屏比 (TV 上 dp 视口约 960x540; 竖构图的人物图于是高约 460dp). */
private const val TV_ZOOM_IMAGE_FRACTION = 0.86f

private val TV_ZOOM_SPINNER_SIZE = 48.dp
