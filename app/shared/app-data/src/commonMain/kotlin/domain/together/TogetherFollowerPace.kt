/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

/**
 * 跟随的成员自己的片源跟不跟得上房主。
 *
 * 位置差得多时成员要跳到房主那里, 而每跳一次都要重新缓冲、扔掉已经缓冲好的部分。片源比码率慢 (限速的网盘、种子少的 BT) 时,
 * 跳过去等播起来又落后了、再跳, 画面一直播不起来。所以:
 * - 成员自己在缓冲、加载, 或播放页正在一轮对齐里 (跳了还在量落点) 时, 不按位置纠正 —— 由 [TogetherManager] 跳过这几种时刻;
 * - 自己落后需要纠正, 而上一次自己落后的纠正还不到 [WINDOW_MILLIS]: 认定跟不上, 不再按位置往前跳, 落后着连续播 ([lagging]);
 *   跑到房主前面的 (自己拖了进度、房主停着自己却在播) 总是跳回去, 不算「自己落后」;
 * - 落后着播期间房主自己变了 (跳了位置、暂停 / 继续……) 照样跟一次, 这种纠正不算「自己落后」;
 * - 连续顺畅播满 [RETRY_AFTER_SMOOTH_MILLIS], 或重新装了片源 (换源、换集) 时再试着跟上。
 *
 * 只记状态、不发指令, 由 [TogetherManager] 每秒喂一次本机与房主的状态。时刻都是本机时间。
 */
internal class TogetherFollowerPace {
    /** 认定跟不上, 落后着播 */
    var lagging: Boolean = false
        private set

    /** 上一次因为自己落后而纠正的时刻 */
    private var lastOwnCorrectionAt: Long? = null
    private var smoothSince: Long? = null
    private var lastHost: TogetherWatchingInfo? = null

    /** 房主自己变过还没跟上: 成员那一刻在缓冲也不能漏掉, 等能跳了再跳 */
    private var hostChangePending = false

    /** 跟随停了、换了集: 从头算 */
    fun reset() {
        lagging = false
        lastOwnCorrectionAt = null
        smoothSince = null
        lastHost = null
        hostChangePending = false
    }

    /** 每秒一次: 本机这一刻的状态 */
    fun observeLocal(local: TogetherWatchingInfo, nowMillis: Long) {
        when {
            // 重新装片源 (换源、换集): 新的片源可能跟得上, 重新来过
            local.loading -> {
                lagging = false
                lastOwnCorrectionAt = null
                smoothSince = null
            }

            local.buffering -> smoothSince = null

            !local.paused -> {
                val since = smoothSince ?: nowMillis.also { smoothSince = it }
                if (lagging && nowMillis - since >= RETRY_AFTER_SMOOTH_MILLIS) {
                    lagging = false
                    lastOwnCorrectionAt = null
                }
            }
        }
    }

    /** 房主这一刻的状态。房主自己变了 (跳了位置、暂停 / 继续、缓冲、换了倍速) 时, 落后着播的成员也要跟一次 */
    fun observeHost(host: TogetherWatchingInfo) {
        val last = lastHost
        lastHost = host
        if (last != null && !last.predicts(host, HOST_JUMP_TOLERANCE_MILLIS)) hostChangePending = true
    }

    /** 位置没差多少: 房主那一下变化已经跟上了 */
    fun inStep() {
        hostChangePending = false
    }

    /**
     * 位置差得多了, 该不该跳过去。房主自己变过而还没跟上时总是跳。
     * 返回 [Decision.FallBehind] 的那一次是刚认定跟不上 (只这一次, 用来告诉用户)。
     *
     * @param behind 成员落在房主后面。跑到房主前面 (自己拖了进度、房主停着自己却在播) 的总是跳回去, 也不算「自己落后」:
     *   那与片源快慢无关, 往回跳多半落在已经缓冲好的地方
     */
    fun decide(nowMillis: Long, behind: Boolean = true): Decision {
        if (hostChangePending) {
            hostChangePending = false
            return Decision.Correct
        }
        if (!behind) return Decision.Correct
        if (lagging) return Decision.StayBehind
        val last = lastOwnCorrectionAt
        if (last != null && nowMillis - last <= WINDOW_MILLIS) {
            lagging = true
            return Decision.FallBehind
        }
        lastOwnCorrectionAt = nowMillis
        return Decision.Correct
    }

    enum class Decision {
        /** 跳到房主那里 */
        Correct,

        /** 刚认定跟不上: 不跳, 告诉用户一次 */
        FallBehind,

        /** 跟不上, 继续落后着播 */
        StayBehind,
    }

    companion object {
        /** 自己落后的纠正, 两次隔不到这么久就算跟不上 */
        const val WINDOW_MILLIS = 60_000L

        /** 落后着播的成员连续顺畅播了这么久就再试着跟上 */
        const val RETRY_AFTER_SMOOTH_MILLIS = 60_000L

        /** 房主的状态与按上一次推算的差多少算他自己跳了 */
        const val HOST_JUMP_TOLERANCE_MILLIS = 2_500L
    }
}
