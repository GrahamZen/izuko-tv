/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.datasources.api.Media

/**
 * 选中的一个资源这一路的结果, 给数据源画像记 (见 [SourceProfileExtension]): 开播成功或失败只记一次, 播起来之后出错再记一次.
 *
 * 开播失败有两种: 打开失败 (解析地址、建连、读容器出错, [onLoadFailed]); 打开成功、还没播起来就出错 (缓冲时链接断了、解码不了,
 * [onPlayerError]). 后一种自动换源照常, 也算这个源的一次开播失败: 同一档里开查的先后按真实播放的成败排 (见 SourceStartOrder).
 */
internal class SourceAttempt(val media: Media, val selectedAt: Long) {
    /** 打开成功: 交给了播放器, 读到了音视频轨 */
    var loaded = false
        private set

    /** 播起来了为 true, 开播失败为 false, 还没定为 null */
    var outcome: Boolean? = null
        private set

    private var playbackFailed = false

    fun onLoaded() {
        loaded = true
    }

    /** 打开失败. @return 这次要记一条开播失败 */
    fun onLoadFailed(): Boolean {
        if (outcome != null) return false
        outcome = false
        return true
    }

    /** 开始播放. @return 这次要记一条播起来了 (每一路只记第一次) */
    fun onPlaying(): Boolean {
        if (!loaded || outcome != null) return false
        outcome = true
        return true
    }

    /**
     * 播放器报错. @return 这次要记一条失败: 播起来之后第一次出错 (播放中出错), 或打开成功、还没播起来就出错 (开播失败).
     * 还没打开成功时不记, 那是打开失败, 由 [onLoadFailed] 记.
     */
    fun onPlayerError(): Boolean = when {
        outcome == true && !playbackFailed -> {
            playbackFailed = true
            true
        }

        outcome == null && loaded -> {
            outcome = false
            true
        }

        else -> false
    }
}
