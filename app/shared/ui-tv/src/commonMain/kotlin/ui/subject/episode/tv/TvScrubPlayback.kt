/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

/**
 * 拖拽预览期间的播放状态: 进来时按设置暂停, 确认时从圆点开始播, 取消时恢复进来之前的状态.
 *
 * 遥控器 (纯视频态长按 / 连按两次左右键, 进度条上按左右) 与触屏拖画面的拖拽预览都经过这里:
 *
 * | | 拖动时暂停 (默认) | 边播边选 |
 * |---|---|---|
 * | 进入 | 在播就暂停 | 不动 |
 * | 确认 | 播放 | 播放 |
 * | 取消 | 进来时被这里暂停的, 恢复播放 | 不动 |
 *
 * 确认一律播放: 这个态里按确认 / 播放键的意思只可能是「从这儿开始播」, 与进来之前播没播无关.
 * 取消只撤销这里自己做的暂停, 进来之前就停着的保持停着.
 *
 * 设置在进入那一刻读: 预览途中改了设置, 取消时仍按进来时做过什么来撤销.
 */
internal class TvScrubPlayback(
    private val pauseOnScrub: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val pause: () -> Unit,
    private val play: () -> Unit,
) {
    /** 这次预览开始时是否由这里暂停了播放. */
    private var pausedByScrub = false

    /** 进入拖拽预览时调用一次. 已经在预览中、只是再挪圆点时不要调. */
    fun onEnter() {
        pausedByScrub = pauseOnScrub() && isPlaying()
        if (pausedByScrub) pause()
    }

    /** 确认: 跳到圆点并播放. */
    fun onCommit() {
        pausedByScrub = false
        play()
    }

    /** 取消: 丢弃圆点, 进来时被这里暂停的恢复播放. */
    fun onCancel() {
        if (pausedByScrub) play()
        pausedByScrub = false
    }
}
