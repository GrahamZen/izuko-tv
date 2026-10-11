/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
actual fun DanmakuLayer(
    state: DanmakuHostState,
    modifier: Modifier,
    alpha: () -> Float,
    snapshots: DanmakuLayerSnapshots?,
) {
    val density = LocalDensity.current
    val baseStyle = MaterialTheme.typography.bodyMedium
    val trackStubMeasurer = rememberTextMeasurer(1)
    val danmakuTextMeasurer = rememberTextMeasurer(3000)

    SideEffect {
        state.setUIContext(baseStyle, danmakuTextMeasurer, density)
    }
    // 位置、轨道、过期与 DanmakuHost 同一套
    LaunchedEffect(trackStubMeasurer) { state.observeConfig(trackStubMeasurer) }
    LaunchedEffect(state.paused) { if (!state.paused) state.interpolateFrameLoop() }
    LaunchedEffect(state.paused) {
        if (!state.paused) {
            while (true) {
                state.tick()
                delay(1000)
            }
        }
    }

    BoxWithConstraints(modifier) {
        state.hostWidth = constraints.maxWidth
        state.hostHeight = constraints.maxHeight

        var view by remember { mutableStateOf<GlDanmakuLayerView?>(null) }
        AndroidView(
            factory = { context -> GlDanmakuLayerView(context).also { view = it } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.release() },
        )
        val layerView = view ?: return@BoxWithConstraints
        val scope = rememberCoroutineScope()
        val publisher = remember(state, scope) { DanmakuLayerPublisher(state, scope) }
        val currentAlpha by rememberUpdatedState(alpha)

        if (snapshots != null) {
            DisposableEffect(snapshots, layerView) {
                snapshots.source = { layerView.latestFrame?.let { renderToBitmap(it, System.nanoTime())?.asImageBitmap() } }
                onDispose { snapshots.source = null }
            }
        }
        LaunchedEffect(layerView, publisher, state.paused) {
            if (state.paused) {
                // 暂停时帧时间不走: 只在内容 (配置、重新铺弹幕、后台画好了图) 或整层不透明度变了时发一次
                snapshotFlow { Triple(state.danmakuUpdateSubscription, publisher.imagesReady, currentAlpha()) }.collect { (_, _, layerAlpha) ->
                    layerView.publish(publisher.frame(System.nanoTime(), paused = true, layerAlpha = layerAlpha))
                }
            } else {
                while (true) {
                    withFrameNanos { layerView.publish(publisher.frame(it, paused = false, layerAlpha = currentAlpha())) }
                }
            }
        }
    }
}

/** 弹幕层上的一条: 滚动的由渲染线程按时间算横坐标 ([placeNanos], [speedPxPerSecond]), 顶部 / 底部的固定在 [x]. */
internal class DanmakuLayerItem(
    @JvmField val bitmap: Bitmap,
    @JvmField val offsetX: Float,
    @JvmField val offsetY: Float,
    @JvmField val floating: Boolean,
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val placeNanos: Long,
    @JvmField val speedPxPerSecond: Float,
)

/**
 * 交给渲染线程的一份弹幕. [elapsedNanos] 是发布时弹幕的帧时间, [publishedAtNanos] 是同一刻的系统时间
 * (与 Choreographer 的帧时间同一基准): 渲染线程据此自己往后推, 界面线程忙的时候滚动也不停.
 */
internal class DanmakuLayerFrame(
    @JvmField val items: Array<DanmakuLayerItem>,
    @JvmField val elapsedNanos: Long,
    @JvmField val publishedAtNanos: Long,
    @JvmField val paused: Boolean,
    @JvmField val hostWidth: Int,
    @JvmField val hostHeight: Int,
    /** 弹幕的不透明度设置乘上整层的不透明度. */
    @JvmField val alpha: Float,
) {
    /** 渲染时刻 [nowNanos] 的弹幕帧时间. */
    fun elapsedAt(nowNanos: Long): Long =
        if (paused) elapsedNanos else elapsedNanos + (nowNanos - publishedAtNanos).coerceAtLeast(0L)

    /** 有滚动弹幕在走, 渲染线程要逐帧画. */
    val moving: Boolean get() = !paused && alpha > 0f && items.any { it.floating }

    /**
     * 两份都不动且画出来一样. 播放时界面线程每帧都发一份, 屏上没有滚动弹幕 (没弹幕、只有顶部 / 底部的、整层藏着) 时不必每帧重画.
     */
    fun stillSameAs(other: DanmakuLayerFrame): Boolean {
        if (moving || other.moving) return false
        if (alpha != other.alpha || hostWidth != other.hostWidth || hostHeight != other.hostHeight) return false
        if (alpha <= 0f) return true
        // 不动又有滚动弹幕的只能是暂停着, 位置由停住时的帧时间定
        return items === other.items &&
            (items.none { it.floating } || (paused && other.paused && elapsedNanos == other.elapsedNanos))
    }

    fun xOf(item: DanmakuLayerItem, elapsed: Long): Float =
        if (item.floating) floatingDanmakuX(hostWidth, elapsed, item.placeNanos, item.speedPxPerSecond) else item.x
}

/**
 * 在界面线程上从 [DanmakuHostState] 取出当前在屏的弹幕. 集合没变时复用上一份数组, 只换时间基准.
 *
 * 弹幕的图不在界面线程上画: 放进轨道时已在后台画好 ([DanmakuHostState.trySend]); 没画好的 (如用户自己发的) 交给 [scope] 在后台画,
 * 画好之前先不显示, 画好后下一帧带上.
 */
internal class DanmakuLayerPublisher(
    private val state: DanmakuHostState,
    private val scope: CoroutineScope,
) {
    private var lastSignature = 0L
    private var lastItems: Array<DanmakuLayerItem> = emptyArray()
    // 只在界面线程上读写
    private val rasterizing = HashSet<StyledDanmaku>()

    /** 后台画好过几张图; 暂停时 (不逐帧发布) 靠它知道要重发. */
    var imagesReady by mutableIntStateOf(0)
        private set

    fun frame(frameTimeNanos: Long, paused: Boolean, layerAlpha: Float): DanmakuLayerFrame {
        var signature = 17L
        state.forEachFloatingDanmaku {
            if (it.y.isNaN()) return@forEachFloatingDanmaku
            signature = signature * 31 + System.identityHashCode(it)
            signature = signature * 31 + it.placeFrameTimeNanos
            signature = signature * 31 + it.speedPxPerSecond.toRawBits()
            signature = signature * 31 + it.y.toRawBits()
        }
        state.forEachFixedDanmaku {
            if (it.y.isNaN()) return@forEachFixedDanmaku
            signature = signature * 31 + System.identityHashCode(it)
            signature = signature * 31 + it.y.toRawBits()
        }
        signature = signature * 31 + state.hostWidth
        signature = signature * 31 + imagesReady
        if (signature != lastSignature) {
            lastSignature = signature
            lastItems = buildItems()
        }
        return DanmakuLayerFrame(
            lastItems, state.elapsedFrameTimeNanos, frameTimeNanos, paused,
            state.hostWidth, state.hostHeight, state.canvasAlpha * layerAlpha.coerceIn(0f, 1f),
        )
    }

    private fun buildItems(): Array<DanmakuLayerItem> {
        val items = ArrayList<DanmakuLayerItem>()
        state.forEachFloatingDanmaku {
            if (it.y.isNaN()) return@forEachFloatingDanmaku
            val image = imageOf(it.danmaku) ?: return@forEachFloatingDanmaku
            items += DanmakuLayerItem(
                image.bitmap.asAndroidBitmap(), image.offset.x, image.offset.y,
                floating = true, x = 0f, y = it.y, placeNanos = it.placeFrameTimeNanos, speedPxPerSecond = it.speedPxPerSecond,
            )
        }
        val hostWidth = state.hostWidth
        state.forEachFixedDanmaku {
            if (it.y.isNaN()) return@forEachFixedDanmaku
            val image = imageOf(it.danmaku) ?: return@forEachFixedDanmaku
            items += DanmakuLayerItem(
                image.bitmap.asAndroidBitmap(), image.offset.x, image.offset.y,
                floating = false, x = (hostWidth - it.danmaku.danmakuWidth) / 2f, y = it.y, placeNanos = 0L, speedPxPerSecond = 0f,
            )
        }
        return items.toTypedArray()
    }

    private fun imageOf(danmaku: StyledDanmaku): ImageBitmapWithOffset? {
        danmaku.cachedImageOrNull()?.let { return it }
        if (rasterizing.add(danmaku)) {
            scope.launch(Dispatchers.Default) {
                danmaku.cachedImage()
                withContext(Dispatchers.Main) {
                    rasterizing.remove(danmaku)
                    imagesReady++
                }
            }
        }
        return null
    }
}

/** 把 [frame] 在 [nowNanos] 时的样子画到一张透明底的位图上 (截图用). 屏上没有弹幕时为 null. */
internal fun renderToBitmap(frame: DanmakuLayerFrame, nowNanos: Long): Bitmap? {
    if (frame.alpha <= 0f || frame.items.isEmpty() || frame.hostWidth <= 0 || frame.hostHeight <= 0) return null
    val bitmap = Bitmap.createBitmap(frame.hostWidth, frame.hostHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (frame.alpha * 255).roundToInt() }
    val elapsed = frame.elapsedAt(nowNanos)
    for (item in frame.items) {
        canvas.drawBitmap(item.bitmap, frame.xOf(item, elapsed) + item.offsetX, item.y + item.offsetY, paint)
    }
    return bitmap
}
