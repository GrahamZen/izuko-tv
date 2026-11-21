/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package me.him188.ani.app.videoplayer.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.avkit.AVKitMediampPlayer
import platform.AVFoundation.AVPlayerLayer
import kotlin.native.ref.WeakReference

/**
 * iOS 视频 surface 创建的 `AVPlayerLayer` 注册表.
 *
 * `AVPictureInPictureController` 必须持有渲染视频的 `AVPlayerLayer`, 而 layer 由 [VideoPlayer] 的
 * UIKitView factory 创建. 通过注册表把 layer 暴露给画中画控制器, 模式同 Android 侧的
 * `AndroidVideoSurface.kt`.
 *
 * 所有调用都发生在主线程 (UIKitView factory/update/onRelease 与 Compose 均在主线程), 不做线程同步.
 */
object IosVideoLayerRegistry {
    private val layers = HashMap<WeakReference<MediampPlayer>, WeakReference<AVPlayerLayer>>()

    /**
     * 注册表变化计数. 观察者通过收集它来感知新 layer 的出现 (layer 的创建晚于组合期 remember).
     */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * 一个播放器同一时刻只认一个 layer, 以后建的为准: 播放页与小窗交接时 (见 TouchMiniPlayer) 两个视图会短暂同时在,
     * 新视图先建、旧视图后拆.
     */
    fun register(player: MediampPlayer, layer: AVPlayerLayer) {
        logger.info { "[PiP] 播放器 ${player.hashCode()} 登记视频图层 ${layer.hashCode()}" }
        sweep()
        removeIf { it.key.get() === player }
        layers[WeakReference(player)] = WeakReference(layer)
        _version.value += 1
        (player as? AVKitMediampPlayer)?.let { IosVideoLayerWake.watch(player, it.impl, layer) { find(player) == layer } }
    }

    /** 拆掉 [layer] 所在的视图时调用; 播放器已经登记到别的 layer 上 (交接时新视图先建) 就不动那一条. */
    fun unregister(player: MediampPlayer, layer: AVPlayerLayer) {
        logger.info { "[PiP] 播放器 ${player.hashCode()} 撤下视频图层 ${layer.hashCode()}" }
        removeIf { it.key.get() === player && it.value.get() === layer }
        _version.value += 1
    }

    private inline fun removeIf(predicate: (Map.Entry<WeakReference<MediampPlayer>, WeakReference<AVPlayerLayer>>) -> Boolean) {
        val iterator = layers.entries.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) iterator.remove()
        }
    }

    fun find(player: MediampPlayer): AVPlayerLayer? {
        for ((key, value) in layers) {
            if (key.get() === player) return value.get()
        }
        return null
    }

    private fun sweep() {
        val iterator = layers.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.get() == null) iterator.remove()
        }
    }
}

fun MediampPlayer.findIosVideoLayer(): AVPlayerLayer? = IosVideoLayerRegistry.find(this)

private val logger = logger("PictureInPicture")
