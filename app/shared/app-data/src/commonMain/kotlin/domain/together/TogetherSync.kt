/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin
import kotlin.math.abs

/** 本机现在在哪 */
sealed interface LocalPosition {
    data object NotInPlayer : LocalPosition
    data class InPlayer(val subjectId: Int, val episodeId: Int) : LocalPosition
}

/** 跟随的成员要做什么才能跟房主在同一集 */
sealed interface SyncAction {
    /** 不在播放页: 打开房主那一集 */
    data class PushEpisode(val subjectId: Int, val episodeId: Int, val initialPositionMillis: Long) : SyncAction

    /** 在别的番的播放页: 退出来再打开房主那一集 */
    data class PopThenPushEpisode(val subjectId: Int, val episodeId: Int, val initialPositionMillis: Long) : SyncAction

    /** 同一部番的另一集: 就地换集 */
    data class SwitchEpisodeInPlace(val episodeId: Int, val initialPositionMillis: Long) : SyncAction

    /** 已经在同一集: 对齐位置与播放 / 暂停 */
    data class SeekOnly(val positionMillis: Long, val paused: Boolean) : SyncAction
}

object SyncGuidanceEngine {
    /** @param nowMillis 房间时间 */
    fun compute(playback: TogetherPlayback?, local: LocalPosition, nowMillis: Long): SyncAction? {
        val info = playback?.info ?: return null
        val position = info.positionAt(nowMillis)
        return when (local) {
            LocalPosition.NotInPlayer -> SyncAction.PushEpisode(info.subjectId, info.episodeId, position)
            is LocalPosition.InPlayer -> when {
                local.subjectId != info.subjectId -> SyncAction.PopThenPushEpisode(info.subjectId, info.episodeId, position)
                local.episodeId != info.episodeId -> SyncAction.SwitchEpisodeInPlace(info.episodeId, position)
                else -> SyncAction.SeekOnly(position, info.paused)
            }
        }
    }
}

/** 交给播放页执行的同步指令 */
sealed interface PlaybackDirective {
    /** 对齐到 [playback]: 位置差得多才跳, 播放 / 暂停与倍速直接跟。[initial] 为真时不管差多少都跳 */
    data class Sync(val playback: TogetherPlayback, val initial: Boolean = false) : PlaybackDirective
    data class SwitchEpisode(val episodeId: Int, val playback: TogetherPlayback) : PlaybackDirective
}

/**
 * 一起看与播放页之间的接头: 播放页把本机的播放状态写进来 ([localWatching]), 一起看把要播放页做的事发出去 ([directives])。
 * 两边互不认识, 播放页不在时一起看照常运转。
 */
class TogetherPlaybackBridge(
    /**
     * 播放页此刻是不是导航栈顶的那一页。电视退出播放页后播放会话还留在后台 (被按成暂停), [localWatching] 仍有值,
     * 但人已经去了别的页面: 这时算不在播放页, 房主换集要把人带进那一集, 而不是让后台的会话自己换。
     */
    private val playerOnScreen: () -> Boolean = { true },
    /**
     * 应用整体在不在前台。应用退到后台时播放页还开着, 但播放器同样被按成暂停; 这时跟随的事一件都不做
     * (不动播放器、不换集、不改页面), 回到前台再跟上。
     */
    private val appForeground: () -> Boolean = { true },
    /** [playerOnScreen] 变了: 进出播放页时画面可能一点没变 (停着进出), 房主要为这个单独告诉房间 */
    val playerOnScreenChanges: Flow<Boolean> = emptyFlow(),
) {
    private val localOwner = atomic<Any?>(null)
    private val _localWatching = MutableStateFlow<TogetherWatchingInfo?>(null)

    /** 本机播放会话的状态 (留在后台的会话也算); 没有会话时为 `null` */
    val localWatching: StateFlow<TogetherWatchingInfo?> = _localWatching.asStateFlow()

    val isPlayerOnScreen: Boolean get() = playerOnScreen()
    val isAppForeground: Boolean get() = appForeground()

    /** 播放页此刻是不是真在用户眼前: 是栈顶那一页, 应用也在前台 */
    val isPlayerInFront: Boolean get() = playerOnScreen() && appForeground()

    private val _targetPlayback = MutableStateFlow<TogetherPlayback?>(null)

    /** 跟随中要对齐的房主状态; 不在跟随时为 `null` */
    val targetPlayback: StateFlow<TogetherPlayback?> = _targetPlayback.asStateFlow()

    private val _discontinuities = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 本机的播放状态出现了不连续: 进出播放页、换集、播放 / 暂停、缓冲、倍速变了、位置跳了 */
    val discontinuities: SharedFlow<Unit> = _discontinuities.asSharedFlow()

    private val _directives = MutableSharedFlow<PlaybackDirective>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val directives: SharedFlow<PlaybackDirective> = _directives.asSharedFlow()

    private val _corrections = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 播放页为对齐房主真的跳了一次位置时发出; 值是跳了多少毫秒 (正 = 往前) */
    val corrections: SharedFlow<Long> = _corrections.asSharedFlow()

    /**
     * 跟房主对齐时跳一次大约要多久 (毫秒, 按 1 倍速算): 跳过去要重新缓冲, 这段时间房主又往前走了, 所以落点要比房主靠前这么多 (房主开着倍速时再乘上倍速)。
     * 由播放页每次跳完量出来 (见 [nextSeekLead]), 换集、换播放页都沿用。
     */
    val seekLeadMillis = atomic(0L)

    /** 播放页正在一轮对齐里 (跳了位置, 还在等播起来量落点): 这期间位置差多少都不作数, 由播放页自己补跳 */
    val alignInProgress = atomic(false)

    private val _waitingForHost = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 房主卡在缓冲 / 加载上, 播放页把本来在播的画面停下来等他时发出 */
    val waitingForHost: SharedFlow<Unit> = _waitingForHost.asSharedFlow()

    fun notifyWaitingForHost() {
        _waitingForHost.tryEmit(Unit)
    }

    private val _waitingForMembers = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 房主停着等跟随的成员时发出: 这一集加载完停在开头 (`false`), 或自己跳了位置后停在新位置等成员在那里缓冲 (`true`) */
    val waitingForMembers: SharedFlow<Boolean> = _waitingForMembers.asSharedFlow()

    fun notifyWaitingForMembers(afterSeek: Boolean = false) {
        _waitingForMembers.tryEmit(afterSeek)
    }

    fun notifyCorrectionApplied(deltaMillis: Long) {
        _corrections.tryEmit(deltaMillis)
    }

    fun updateLocalWatching(value: TogetherWatchingInfo?) {
        localOwner.value = null
        updateLocalWatchingValue(value)
    }

    /** 播放页写自己的状态; [owner] 标明是哪一个播放页写的 (换集会重建播放页, 旧页面清理时不能把新页面写的清掉) */
    fun updateLocalWatching(owner: Any, value: TogetherWatchingInfo) {
        localOwner.value = owner
        updateLocalWatchingValue(value)
    }

    fun clearLocalWatching(owner: Any) {
        if (localOwner.compareAndSet(owner, null)) updateLocalWatchingValue(null)
    }

    private fun updateLocalWatchingValue(value: TogetherWatchingInfo?) {
        val old = _localWatching.value
        _localWatching.value = value
        if (isDiscontinuity(old, value)) _discontinuities.tryEmit(Unit)
    }

    fun emitDirective(directive: PlaybackDirective) {
        _directives.tryEmit(directive)
    }

    internal fun setTargetPlayback(playback: TogetherPlayback?) {
        _targetPlayback.value = playback
    }

    /** 栈顶的播放页开着哪一集; 播放页不是栈顶那一页 (见 [isPlayerOnScreen]) 算不在播放页 */
    fun localPosition(): LocalPosition =
        _localWatching.value?.takeIf { playerOnScreen() }?.let { LocalPosition.InPlayer(it.subjectId, it.episodeId) }
            ?: LocalPosition.NotInPlayer

    private fun isDiscontinuity(old: TogetherWatchingInfo?, new: TogetherWatchingInfo?): Boolean {
        if (old == null || new == null) return old != new
        return !old.predicts(new, POSITION_JUMP_MILLIS)
    }

    private companion object {
        const val POSITION_JUMP_MILLIS = 2_500L
    }
}

/**
 * 按这份状态往后推, 推得出 [later] 吗: 同一集、播放 / 暂停 / 缓冲 / 加载 / 倍速、在不在播放页与在播的资源都没变, 位置与推算的相差不超过 [toleranceMillis]。
 * 推得出就不用再告诉别人。
 */
internal fun TogetherWatchingInfo.predicts(later: TogetherWatchingInfo, toleranceMillis: Long): Boolean {
    if (subjectId != later.subjectId || episodeId != later.episodeId) return false
    if (paused != later.paused || buffering != later.buffering || loading != later.loading || playbackRate != later.playbackRate) return false
    if (onScreen != later.onScreen || link != later.link || lineage != later.lineage) return false
    return abs(later.positionMillis - positionAt(later.positionAtMillis)) <= toleranceMillis
}

/** 往前多跳的上限 */
internal const val MAX_SEEK_LEAD_MILLIS = 5_000L

/**
 * 这一次往前多跳了 [leadMillis] (画面时间), 播起来之后量到还落后房主 [behindMillis] (负数 = 超前了): 跳一次大约要多久的新估计 (按 1 倍速算)。
 *
 * 这次跳实际花的时间 = (多跳的 + 还落后的) ÷ 房主的倍速 [playbackRate]。同一台设备跳一次的耗时很飘 (跳到没缓冲的地方要两秒多,
 * 缓冲范围里的一秒左右), 照搬一次的会来回过冲, 所以与之前的估计 [previousMillis] 取平均; 之前没量过 (为 0) 时直接用这次的。
 */
internal fun nextSeekLead(previousMillis: Long, leadMillis: Long, behindMillis: Long, playbackRate: Float = 1f): Long {
    val cost = ((leadMillis + behindMillis) / playbackRate.coerceAtLeast(MIN_PLAYBACK_RATE)).toLong().coerceIn(0L, MAX_SEEK_LEAD_MILLIS)
    return if (previousMillis <= 0L) cost else (previousMillis + cost) / 2
}

private const val MIN_PLAYBACK_RATE = 0.25f

/** 跳完播起来后落在房主前后这么多以内就算对上了 */
internal const val ALIGN_OK_MILLIS = 1_000L

/** 一轮对齐最多跳几次 */
internal const val MAX_ALIGN_JUMPS = 3

/**
 * 跳了 [jumps] 次, 这一次落在房主后面 [behindMillis] (负数 = 超前; 上一次的是 [previousBehindMillis]), 要不要再补跳一次:
 * 差得不到 [ALIGN_OK_MILLIS] 不补, 满 [MAX_ALIGN_JUMPS] 次不补; 补跳过了头 (上一次落后、这一次超前, 或反过来) 也不补 ——
 * 这台设备跳一次的耗时比这点误差还飘, 再跳只会来回晃, 每跳一下画面还要停一下。
 */
internal fun shouldJumpAgain(behindMillis: Long, previousBehindMillis: Long?, jumps: Int): Boolean {
    if (abs(behindMillis) <= ALIGN_OK_MILLIS || jumps >= MAX_ALIGN_JUMPS) return false
    return previousBehindMillis == null || (previousBehindMillis > 0) == (behindMillis > 0)
}

/**
 * 跟随房主时本机的自动行为要不要让路 (自动播下一集、从上次进度接着播这类): 跟随中播什么、从哪播都听房主的。
 */
class PlaybackAutomationGate {
    private val _suppressed = MutableStateFlow(false)
    val suppressed: StateFlow<Boolean> = _suppressed.asStateFlow()

    internal fun setSuppressed(value: Boolean) {
        _suppressed.value = value
    }
}

private val neverSuppressed: StateFlow<Boolean> = MutableStateFlow(false)

/** 本机的自动行为现在要不要让路 (见 [PlaybackAutomationGate]); 没有注册一起看 (测试里的精简依赖) 时恒为假 */
fun Koin.playbackAutomationSuppressed(): StateFlow<Boolean> = getOrNull<PlaybackAutomationGate>()?.suppressed ?: neverSuppressed

/**
 * 房主停着等大家时, 跟随着的成员 (自由观看的、掉线的不算) 是不是都在 [subjectId] 的 [episodeId] 上装好了: 片源装进了播放器、不在缓冲;
 * 给了 [nearPositionMillis] 时还要停在它前后 [FOLLOWER_NEAR_HOST_MILLIS] 以内 (房主跳了位置, 等成员跟到那里)。
 * 一个跟随的成员都没有时为真。
 */
internal fun followersReady(snapshot: TogetherRoomSnapshot, subjectId: Int, episodeId: Int, nearPositionMillis: Long? = null): Boolean =
    followersNotReady(snapshot, subjectId, episodeId, nearPositionMillis).isEmpty()

/** 跟随着、在线、还没好的成员 (什么算好见 [followersReady]) */
internal fun followersNotReady(snapshot: TogetherRoomSnapshot, subjectId: Int, episodeId: Int, nearPositionMillis: Long? = null): List<TogetherMember> =
    snapshot.members
        .filter { !it.isHost && it.following && it.state != TogetherMemberState.DISCONNECTED }
        .filterNot { member ->
            val watching = member.watching
            watching != null && watching.subjectId == subjectId && watching.episodeId == episodeId && !watching.loading && !watching.buffering &&
                (nearPositionMillis == null || abs(watching.positionMillis - nearPositionMillis) <= FOLLOWER_NEAR_HOST_MILLIS)
        }

/** 日志里写成员在做什么: 只写成员编号的开头, 不写昵称 */
internal fun describeMembers(members: List<TogetherMember>): String = members.joinToString { member ->
    val watching = member.watching
    member.memberId.take(8) + ": " + when {
        watching == null -> "nothing open"
        else -> "episode ${watching.episodeId} at ${watching.positionMillis} ms" +
            (if (watching.loading) ", loading" else "") + (if (watching.buffering) ", buffering" else "") + (if (!watching.onScreen) ", off screen" else "")
    }
}

/** 房主停着时成员与他差这么多以内就不跳 (播放页那边同一档), 等成员跟到房主跳去的位置时也按这一档算到了 */
internal const val FOLLOWER_NEAR_HOST_MILLIS = 3_000L

