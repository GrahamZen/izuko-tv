/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

/**
 * 流水线要「装好就播」时 ([PlayerSession.loadMedia] 的 `playWhenReady = true`: 选好资源后装上、出错换源、原地重载),
 * 装片子前问一次现在能不能播。
 *
 * 播放画面不在眼前 (电视的保留会话在后台准备) 时回答 `false`: 片子照样装上、缓冲、建解码器, 只是不播;
 * 回答 `false` 的一方负责记下「回来要播」, 画面回到眼前时由它起播。先播再按停的话, 慢电视上从开播到暂停生效
 * 有将近一秒, 这一秒的声音会从后台漏出来。
 *
 * [admit] 在主线程上调用, 同一轮里紧接着把片子交给播放器 (播放器从那一刻起处于打开中, 打开中按播放会在打开后生效),
 * 所以回答之后画面回到眼前、立刻要求播放的, 不会落在「还没交给播放器」的空档里被丢掉。
 * 本来就要停着装的 (`playWhenReady = false`) 不问。
 */
fun interface PlaybackStartGate {
    /** @return `true` 装好就播; `false` 装上但不播, 由回答方负责之后起播 */
    fun admit(): Boolean

    companion object {
        /** 没有人管画面在不在眼前时: 照常装好就播。 */
        val Always: PlaybackStartGate = PlaybackStartGate { true }
    }
}
