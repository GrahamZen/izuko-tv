/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * 「打开时先不播放」(平板模式的设置, 见 VideoScaffoldConfig.holdStartOnOpen): 新打开的一集装好后停在起播位置, 等用户点播放。
 *
 * 一个播放会话一份 (EpisodeViewModel 持有)。播放页第一次出现时 ([pageShown]) 开始读设置, 读到后 [decide] 一次, 之后回到这个会话
 * (保留会话、小窗放大) 不再判断: 停着的还停着, 播过的照离开时的状态。Web 控制台、一起看打开的那一集 (见 [openedRemotely]) 不停。
 * 读设置期间流水线就装片子的, 先停着装, 判断出不停时由调用方起播。
 * 播放页出现之前流水线就要装片子 (后台起的会话) 时不再判断, 回到它算回到保留的会话。
 *
 * 只拦流水线自己的「装好就播」, 排在 [PlaybackStartGate] 前面 (见 PlayerSession.loadMedia): 停着时选好的资源照常打开、缓冲,
 * 换源、打不开换下一条、原地重载也都停着装。用户要播 ([release]) 时, 片子已经交给播放器的由调用方当场起播, 还没交的等装上时照常起播。
 * 只停打开时的那一集: 会话换了集 (播放页里点选集、控制台换集) 就放开。
 *
 * 只在主线程上用。
 *
 * @param currentEpisodeId 会话此刻在放的那一集
 */
class PlaybackStartHold(private val currentEpisodeId: () -> Int) : PlaybackStartGate {
    private val _heldEpisodeId = MutableStateFlow<Int?>(null)

    /** 停着等点播放的那一集; null = 不停 */
    val heldEpisodeId: StateFlow<Int?> = _heldEpisodeId.asStateFlow()

    private var decided = false

    /** 播放页出现了、设置还没读到 ([pageShown] 之后、[decide] 之前) */
    private var deciding = false

    /** [deciding] 期间流水线装了片子: 先停着装的 */
    private var heldWhileDeciding = false

    /**
     * 播放页 (或它的占位) 第一次组合时当场调. 设置要异步读, 读到之前流水线就可能装片子 (记住的源一选就中、直链当场解析, 一秒内就到),
     * 这期间装的先停着, 由 [decide] 定下来.
     *
     * @return 这一次开始等设置 (调用方接着读设置、调 [decide]); 已经在等或已经判断过时为 false
     */
    fun pageShown(): Boolean {
        if (decided || deciding) return false
        deciding = true
        return true
    }

    /**
     * 读到设置后调: [enabled] 为设置开着且是平板模式. 已经判断过的会话不再起作用.
     *
     * @return 等设置期间片子已经停着装了、而这一集不停: 调用方要起播 (同流水线自己的「装好就播」, 还要问保留会话的门)
     */
    fun decide(subjectId: Int, enabled: Boolean): Boolean {
        if (decided) return false
        decided = true
        deciding = false
        val wasHeld = heldWhileDeciding
        heldWhileDeciding = false
        val episodeId = currentEpisodeId()
        if (enabled && !consumeRemoteOpen(subjectId, episodeId)) {
            _heldEpisodeId.value = episodeId
            return false
        }
        return wasHeld
    }

    /**
     * 会话在放的那一集变了 (播放页里点选集、控制台换集): 不再停。不能只等 [admit] 时才看: 换过去的那一集没轮到装片子
     * (资源解析失败) 再换回来的话, 打开时的那一集又会被停住。
     * 页面上还是占位的集 (集信息没到, 编号不大于 0) 不算换集。
     */
    fun onEpisodeChanged(episodeId: Int) {
        if (episodeId <= 0) return
        val held = _heldEpisodeId.value ?: return
        if (held != episodeId) _heldEpisodeId.value = null
    }

    /** 用户要播了 (点了播放键, 或别处已经让播放器播了). @return 之前是不是停着 */
    fun release(): Boolean {
        if (_heldEpisodeId.value == null) return false
        _heldEpisodeId.value = null
        return true
    }

    override fun admit(): Boolean {
        if (deciding) {
            heldWhileDeciding = true
            return false
        }
        decided = true
        val held = _heldEpisodeId.value ?: return true
        if (held == currentEpisodeId()) return false
        _heldEpisodeId.value = null
        return true
    }

    companion object {
        /** 登记之后多久内打开的播放页算这一次远程打开: 慢的源要十几秒才轮到装片子, 但判断在播放页一出现时就做 */
        private val REMOTE_OPEN_VALIDITY = 2.minutes

        private val remoteOpens = mutableMapOf<Pair<Int, Int>, TimeSource.Monotonic.ValueTimeMark>()

        /** Web 控制台、一起看要打开这一集的播放页: 它打开时直接播, 不停。在导航之前调 */
        fun openedRemotely(subjectId: Int, episodeId: Int) {
            remoteOpens[subjectId to episodeId] = TimeSource.Monotonic.markNow()
        }

        private fun consumeRemoteOpen(subjectId: Int, episodeId: Int): Boolean {
            val mark = remoteOpens.remove(subjectId to episodeId) ?: return false
            return mark.elapsedNow() < REMOTE_OPEN_VALIDITY
        }
    }
}
