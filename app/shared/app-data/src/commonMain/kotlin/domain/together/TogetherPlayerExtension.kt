/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.PlaybackLineageSelection
import me.him188.ani.app.domain.player.extension.EpisodePlayerExtensionFactory
import me.him188.ani.app.domain.player.extension.ExtensionBackgroundTaskScope
import me.him188.ani.app.domain.player.extension.PlayerExtension
import me.him188.ani.app.domain.player.extension.PlayerExtensionContext
import me.him188.ani.app.domain.player.extension.subscribeEvents
import me.him188.ani.utils.coroutines.sampleWithInitial
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.isMediaLoaded
import kotlin.math.abs

/**
 * 播放页这一侧的一起看 (docs/contributing/code/together.md): 把本机的播放状态写进 [TogetherPlaybackBridge],
 * 并执行它发来的指令 (对齐位置、播放 / 暂停、倍速、换集)。
 *
 * - **写状态**: 一进这一集就先写一份「加载中」(房主的话, 成员可以同时去加载), 资源装进播放器之后每秒采样一次。
 *   重新装资源的那一阵 (换源) 不写, 免得把瞬间的 0 秒发出去。主线程刚卡过时 (位置取样落后) 那一份也不写。
 * - **加载完先停住**: 在房间里同步着的人 (房主、跟随的成员) 这一集第一次加载完不自己开播, 停在开头等大家都加载完。
 *   房主等跟随的成员都在这一集上装好了 (或等满 [AUTO_START_TIMEOUT_MILLIS]) 就自己开播, 成员跟着房主开播; 等的途中房主自己按了播放就不再替他开。
 *   房间里没有别人跟随着时房主不停; 房主掉线期间成员不停。
 * - **房主跳了位置先停住**: 房主快进快退、拖进度条确认之后 (房间里有别人跟随着时), 停在新位置等跟随的成员在那里缓冲好再接着播,
 *   最多等 [SEEK_HOLD_TIMEOUT_MILLIS]; 不然房主当场接着播, 成员重新缓冲完已经落后一截。等过 [SEEK_HOLD_ANNOUNCE_MILLIS] 才提示, 跳一下就好的不弹。
 * - **同步的是播放意图**: 暂停要在缓冲中也立得住, 所以比的是 `playWhenReady` 而不是此刻画面动没动。
 * - **倍速** (房主的倍速菜单、长按确定键加速) 直接跟。播放器只在正在播时才真的下发倍速, 没在播时设回 1 倍那一下不生效,
 *   恢复播放时也不补 (mediamp 把 1 倍当成「不用管」; 播放页自己的长按加速也为此补发): 成员缓冲时房主松开长按, 不补的话成员一直以 2.5 倍在跑,
 *   报上去的却是 1 倍。所以那一刻没在播的, 播起来再设一次。
 * - **房主卡住时等他**: 房主缓冲 / 加载满 [BUFFERING_DEBOUNCE_MILLIS] 还没好, 成员停在房主的位置, 房主好了一起接着播。
 * - **差多少才跳**: 画面正连着播时差 3 秒以上才跳。房主按了暂停时成员只停下, 差 3 秒以上 (房主停着拖了进度) 才跳:
 *   停的那一下比房主晚一段网络延迟, 接着播时同样晚一段, 两段抵掉, 用不着在暂停时对一次、接着播时再对一次。
 *   房主卡在缓冲上、成员停着等他时差 0.5 秒就对齐 (反正在等, 跳的那点缓冲藏在等的时间里); 从停着接着播的那一下差 1 秒就对齐。
 * - **跳过去要重新缓冲**: 房主在播时成员跳到房主的位置, 等播起来房主已经又往前走了。所以落点比房主靠前一点
 *   (跳一次大约要多久 [TogetherPlaybackBridge.seekLeadMillis] × 房主的倍速), 播起来后量一下这次花了多久, 与之前的估计取平均记给下一次 ([nextSeekLead]);
 *   差得多 (超过 [ALIGN_OK_MILLIS]) 就再跳一次, 一轮最多 [MAX_ALIGN_JUMPS] 次, 补跳过了头 (从落后变成超前或反过来) 就停 ([shouldJumpAgain])。
 * - **起播位置**: 跟随中直接从房主的位置打开, 不先从头缓冲再跳。
 * - **一次只对一轮**: 进页时的那一次对齐与总管发来的指令走的是两个协程, 排着队做; 同时跳的话各自量到的是对方的落点。
 * - **播放页不在眼前时不动播放器**: 退出播放页后会话留在后台、应用退到后台时, 播放器被按着暂停, 这时照房主的状态去播会跟它较劲。
 * - **片源共享**: 片源装好时把正在播的地址与播放线索一起写出去; 自己的片源还没装好时用房间里别人交来的 (见 [TogetherSourceHints])。
 *
 * 没有注册一起看 (测试里的精简依赖) 时什么都不做。
 */
class TogetherPlayerExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("Together") {
    private val bridge: TogetherPlaybackBridge? = koin.getOrNull()
    private val manager: TogetherManager? = koin.getOrNull()
    private val owner = Any()
    private val syncLock = Mutex()

    /** 照房主设了倍速、而那一刻播放器没在播: 等播起来再设一次 (见 [onStart] 里的 TogetherRateResend) */
    private val pendingRate = MutableStateFlow<Float?>(null)
    private val sourceHints = TogetherSourceHints(context.subjectId, koin.getOrNull<PlaybackLineageSelection>(), koin.getOrNull<EpisodeContinuation>())

    override suspend fun startPositionMillis(episodeId: Int): Long? {
        val manager = manager ?: return null
        // 只有跟随中才有要对齐的房主状态
        val host = bridge?.targetPlayback?.value?.info ?: return null
        return if (host.subjectId == context.subjectId && host.episodeId == episodeId) host.positionAt(manager.roomNowMillis()) else 0L
    }

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        val bridge = bridge ?: return
        val manager = manager ?: return
        val mediaLoaded = CompletableDeferred<Unit>()
        // 「加载完先停住」有了结果 (停住了, 或用不着停) 之后才完成; 之前不把「在播」写出去, 免得加载完到停住之间那一下自动开播传到房间里
        val holdSettled = CompletableDeferred<Unit>()
        backgroundTaskScope.launch("TogetherMediaLoaded") {
            context.subscribeEvents<EpisodeFetchSelectPlayState.MediaLoadedEvent>().collectLatest { event ->
                if (event.episodeId == episodeSession.episodeId && mediaLoaded.isActive) mediaLoaded.complete(Unit)
            }
        }

        // 正在播的片源能交给房间的 (地址与线索, 见 TogetherSourceHints)
        val sharedSource = MutableStateFlow<TogetherSourceHint?>(null)
        backgroundTaskScope.launch("TogetherSharedSource") {
            sourceHints.shared(episodeSession, context.player, context.videoLoadingStateFlow).collect { sharedSource.value = it }
        }
        backgroundTaskScope.launch("TogetherSourceHints") {
            sourceHints.follow(episodeSession, manager, context.videoLoadingStateFlow)
        }

        backgroundTaskScope.launch("TogetherReporter") {
            val initialInfo = episodeSession.infoBundleFlow.filterNotNull().first()
            bridge.updateLocalWatching(
                owner,
                TogetherWatchingInfo(
                    subjectId = initialInfo.subjectId,
                    episodeId = initialInfo.episodeId,
                    subjectName = initialInfo.subjectInfo.displayName,
                    episodeSort = initialInfo.episodeInfo.sort.toString(),
                    episodeName = initialInfo.episodeInfo.displayName,
                    positionMillis = 0L,
                    positionAtMillis = manager.roomNowMillis(),
                    durationMillis = 0L,
                    paused = true,
                    loading = true,
                ),
            )

            mediaLoaded.await()
            // 倍速一变当场写 (长按确定键加速的开始与结束): 只靠每秒的节拍顺带的话, 两头各晚 0~1 秒, 成员多 (少) 快进的那段 × 倍速差就是偏差,
            // 长按一次成员能差出一秒多
            val rate = context.player.features[PlaybackSpeed]?.valueFlow ?: flowOf(1f)
            combine(
                episodeSession.infoBundleFlow.filterNotNull(),
                // 只管节奏; 位置在下面现读, 状态刚变的那一下 (比如重新装完接着播) 拿到的不会是旧采样
                combine(context.player.currentPositionMillis.sampleWithInitial(REPORT_SAMPLE_INTERVAL_MILLIS), rate) { _, r -> r },
                context.player.state,
                context.player.mediaProperties,
                sharedSource,
            ) { info, playbackRate, playerState, mediaProperties, source ->
                if (!playerState.isMediaLoaded) return@combine null
                if (!holdSettled.isCompleted && playerState.playWhenReady) return@combine null
                // 位置是播放器在主线程上取样的 (每 0.1 秒一次)。主线程卡住时取样停在卡住前, 读出来的会落后好几秒 (索尼上见过一帧 2.6 秒),
                // 照这个报出去, 别人会以为这边落后了: 到主线程上去读, 等了太久 (= 主线程刚卡过, 取样还没跟上) 这一份就不写, 下一份再说
                val askedAt = manager.roomNowMillis()
                val (positionMillis, sampledAt) = withContext(Dispatchers.Main.immediate) {
                    context.player.currentPositionMillis.value.coerceAtLeast(0L) to manager.roomNowMillis()
                }
                if (sampledAt - askedAt > MAIN_THREAD_STALL_MILLIS) return@combine null
                val durationMillis = mediaProperties?.durationMillis ?: 0L
                TogetherWatchingInfo(
                    subjectId = info.subjectId,
                    episodeId = info.episodeId,
                    subjectName = info.subjectInfo.displayName,
                    episodeSort = info.episodeInfo.sort.toString(),
                    episodeName = info.episodeInfo.displayName,
                    positionMillis = positionMillis,
                    positionAtMillis = sampledAt,
                    durationMillis = durationMillis,
                    paused = !playerState.playWhenReady,
                    buffering = playerState.isBuffering,
                    loading = durationMillis <= 0L,
                    playbackRate = playbackRate,
                    link = source?.link,
                    lineage = source?.lineage,
                )
            }.filterNotNull().collect { bridge.updateLocalWatching(owner, it) }
        }

        backgroundTaskScope.launch("TogetherHoldAfterLoad") {
            mediaLoaded.await()
            // 稳定下来 = 真的在播了, 或装好了且意图是暂停
            val firstStable = context.player.state
                .filter { it.isPlaying || (it.mediaStatus == MediaStatus.Ready && !it.playWhenReady) }
                .first()
            val held = firstStable.isPlaying && shouldHoldAfterLoad(manager)
            if (held) {
                withContext(Dispatchers.Main.immediate) { context.player.pause() }
                withTimeoutOrNull(HOLD_SETTLE_TIMEOUT_MILLIS) { context.player.state.filter { !it.isPlaying }.first() }
            }
            holdSettled.complete(Unit)
            if (held) startWhenFollowersReady(bridge, manager, episodeSession, holdAtMillis = null)
        }

        backgroundTaskScope.launch("TogetherHoldAfterSeek") {
            holdSettled.await()
            // 正停着等成员 (是这里按的暂停); 等的途中房主又跳了一次, 从新位置重新等
            var holding = false
            context.player.events.filterIsInstance<PlaybackEvent.SeekCompleted>().collectLatest { seek ->
                // 停着跳、跳过去还停着 (拖进度条取消): 房主没要接着播, 成员照暂停时的规则跟
                if (!holding && !context.player.state.value.playWhenReady) return@collectLatest
                val session = (manager.state.value as? TogetherState.InRoom)?.session
                // 跟随的成员已经在落点附近 (换源重装、跳得很近): 用不着等
                val needWait = session != null && isHostWithFollowers(manager) && bridge.isPlayerInFront &&
                    !followersReady(session.snapshot.value, context.subjectId, episodeSession.episodeId, nearPositionMillis = seek.positionMillis)
                if (!needWait) {
                    if (holding) {
                        holding = false
                        resumeForRoom(bridge, manager, session)
                    }
                    return@collectLatest
                }
                if (!holding) {
                    logger.info { "Together: the host jumped to ${seek.positionMillis} ms, waiting for followers to buffer there" }
                    withContext(Dispatchers.Main.immediate) { context.player.pause() }
                    withTimeoutOrNull(HOLD_SETTLE_TIMEOUT_MILLIS) { context.player.state.filter { !it.playWhenReady }.first() }
                    holding = true
                }
                startWhenFollowersReady(bridge, manager, episodeSession, holdAtMillis = seek.positionMillis)
                holding = false
            }
        }

        backgroundTaskScope.launch("TogetherInitialSync") {
            mediaLoaded.await()
            // 排在「先停住」后面, 两边不抢播放 / 暂停
            holdSettled.await()
            context.player.state
                .filter { it.isPlaying || (it.mediaStatus == MediaStatus.Ready && !it.playWhenReady) }
                .first()
            applyPlayback(bridge, manager, episodeSession, forceSeek = true)
        }

        backgroundTaskScope.launch("TogetherRateResend") {
            pendingRate.collectLatest { rate ->
                if (rate == null) return@collectLatest
                context.player.state.first { it.isPlaying }
                withContext(Dispatchers.Main.immediate) {
                    if (pendingRate.value != rate) return@withContext
                    pendingRate.value = null
                    context.player.features[PlaybackSpeed]?.set(rate)
                }
            }
        }

        backgroundTaskScope.launch("TogetherDirectives") {
            bridge.directives.collect { directive ->
                when (directive) {
                    is PlaybackDirective.Sync -> applyPlayback(bridge, manager, episodeSession, forceSeek = directive.initial)
                    is PlaybackDirective.SwitchEpisode -> {
                        if (directive.playback.info.subjectId != context.subjectId) return@collect
                        switchEpisodeIfNeeded(directive.episodeId)
                    }
                }
            }
        }
    }

    /**
     * 照房主此刻的状态 ([TogetherPlaybackBridge.targetPlayback]) 对齐。排着队做, 排到时已经不跟随了
     * (关了跟随、离开了房间、房主掉线) 就不做。
     */
    private suspend fun applyPlayback(
        bridge: TogetherPlaybackBridge,
        manager: TogetherManager,
        episodeSession: EpisodeSession,
        forceSeek: Boolean,
    ) {
        syncLock.withLock {
            val playback = bridge.targetPlayback.value ?: return
            alignTo(bridge, manager, episodeSession, playback, forceSeek)
        }
    }

    private suspend fun alignTo(
        bridge: TogetherPlaybackBridge,
        manager: TogetherManager,
        episodeSession: EpisodeSession,
        playback: TogetherPlayback,
        forceSeek: Boolean,
    ) {
        if (!bridge.isPlayerInFront) return
        var host = playback.info
        if (host.subjectId != context.subjectId || host.episodeId != episodeSession.episodeId) return
        if (host.buffering || host.loading) {
            // 一闪而过的缓冲不管; 等满了还卡着才让成员停下来等
            delay(BUFFERING_DEBOUNCE_MILLIS)
            val latest = bridge.targetPlayback.value?.info
            if (latest?.subjectId != host.subjectId || latest.episodeId != host.episodeId || !(latest.buffering || latest.loading)) return
            host = latest
        }
        val wantPlaying = !host.holding

        var forced = forceSeek
        var jumps = 0
        var previousBehind: Long? = null
        var announced = false
        bridge.alignInProgress.value = true
        try {
            while (true) {
                // 跳的这段时间里房主照他的倍速往前走
                val lead = if (wantPlaying) (bridge.seekLeadMillis.value * host.playbackRate).toLong() else 0L
                val appliedDelta = withContext(Dispatchers.Main.immediate) {
                    val player = context.player
                    if (!player.state.value.isMediaLoaded || !bridge.isPlayerInFront) return@withContext null
                    var delta: Long? = null
                    val targetPosition = host.positionAt(manager.roomNowMillis())
                    val localPosition = player.currentPositionMillis.value
                    // 画面正连着播时差得多才跳 (跳一次要重新缓冲); 停着时、从停着接着播的那一下对得紧一些, 反正画面本来就要断一下
                    // 房主刚回到原速 (松开长按加速): 改倍速时播放器本来就要停一下, 顺带对齐, 不留下加速时卡出来的一两秒
                    val backToNormalSpeed = host.playbackRate == 1f && abs((player.features[PlaybackSpeed]?.value ?: 1f) - 1f) > 0.01f
                    val threshold = when {
                        jumps > 0 -> 0L
                        host.paused -> FOLLOWER_NEAR_HOST_MILLIS
                        !wantPlaying -> HOLD_ALIGN_THRESHOLD_MILLIS
                        !player.state.value.playWhenReady || backToNormalSpeed -> ALIGN_OK_MILLIS
                        else -> POSITION_CORRECTION_THRESHOLD_MILLIS
                    }
                    if (forced || abs(localPosition - targetPosition) > threshold) {
                        val destination = (targetPosition + lead).let { if (host.durationMillis > 0L) it.coerceAtMost(host.durationMillis) else it }
                        player.seekTo(destination)
                        delta = destination - localPosition
                    }
                    player.features[PlaybackSpeed]?.set(host.playbackRate)
                    // 没在播 (缓冲、停着) 时这一下只记下数、不下发, 恢复播放时又因为是 1 倍而不补 (见 TogetherPlayerExtension 类说明「倍速」):
                    // 播起来再补一次
                    pendingRate.value = if (player.state.value.isPlaying) null else host.playbackRate
                    if (!wantPlaying) {
                        if (player.state.value.playWhenReady) {
                            player.pause()
                            // 房主自己按的暂停不用说; 卡住了才告诉用户画面为什么停了
                            if (!host.paused) bridge.notifyWaitingForHost()
                        }
                    } else if (!player.state.value.playWhenReady) {
                        player.play()
                    }
                    delta
                } ?: return
                jumps++
                if (!forceSeek && !announced) {
                    announced = true
                    bridge.notifyCorrectionApplied(appliedDelta)
                }
                if (!wantPlaying) return

                // 量落点: 等播起来, 看还落后房主多少
                val landing = measureBehind(bridge, host) ?: return
                val behind = landing.behindMillis
                logger.info { "Together: jumped to the host with a lead of $lead ms, landed $behind ms behind (slow resume: ${landing.slowResume})" }
                // 跳过去迟迟播不起来 = 片源慢: 这一轮不再补跳 (每跳一次都扔掉缓冲, 越跳越播不起来), 这次的落点也不拿来学往前多跳多少
                if (landing.slowResume) return
                bridge.seekLeadMillis.value = nextSeekLead(bridge.seekLeadMillis.value, lead, behind, host.playbackRate)
                if (!shouldJumpAgain(behind, previousBehind, jumps)) return
                previousBehind = behind
                forced = true
            }
        } finally {
            bridge.alignInProgress.value = false
        }
    }

    /**
     * 一次跳转的落点。
     *
     * @param behindMillis 播起来时还落后房主多少毫秒 (负数 = 超前)
     * @param slowResume 跳过去之后过了 [SLOW_RESUME_MILLIS] 才播起来
     */
    private class Landing(val behindMillis: Long, val slowResume: Boolean)

    /**
     * 刚跳完, 等播放器重新播起来, 量还落后房主多少。期间房主的状态变了 (暂停、换集、又拖了) 或等不到播起来时为 `null`。
     */
    private suspend fun measureBehind(bridge: TogetherPlaybackBridge, host: TogetherWatchingInfo): Landing? {
        val manager = manager ?: return null
        var slowResume = false
        val waitResume: suspend () -> Unit? = {
            withTimeoutOrNull(ALIGN_RESUME_TIMEOUT_MILLIS) {
                if (withTimeoutOrNull(SLOW_RESUME_MILLIS) { context.player.state.filter { it.isPlaying }.first() } == null) slowResume = true
                // 播起来之后再等一下让位置走起来; 这一下里又缓冲了就重新等
                while (true) {
                    context.player.state.filter { it.isPlaying }.first()
                    delay(ALIGN_SETTLE_MILLIS)
                    if (context.player.state.value.isPlaying) break
                }
            }
        }
        // 等的途中房主又变了 (松开长按加速、暂停、又跳了): 不量了, 让出这一轮, 新的指令马上照新状态做 ——
        // 不然它要排在后面等这边播起来 (缓冲可能要好几秒), 成员这几秒里还照旧的倍速、旧的播放 / 暂停走
        val hostChanged = bridge.targetPlayback.filter { latest -> latest == null || !host.predicts(latest.info, ALIGN_OK_MILLIS) }
        coroutineScope {
            val resumed = async { waitResume() }
            val changed = async { hostChanged.first() }
            select<Unit?> {
                resumed.onAwait { changed.cancel(); it }
                changed.onAwait { resumed.cancel(); null }
            }
        } ?: return null
        // 量的途中人离开了播放页 (播放器被按了暂停): 这次量的不作数
        if (!bridge.isPlayerInFront) return null
        val latest = bridge.targetPlayback.value?.info ?: return null
        if (latest != host && !host.predicts(latest, ALIGN_OK_MILLIS)) return null
        val behind = withContext(Dispatchers.Main.immediate) {
            latest.positionAt(manager.roomNowMillis()) - context.player.currentPositionMillis.value
        }
        return Landing(behind, slowResume)
    }

    /**
     * 房主停着等大家: 跟随的成员都在这一集上装好了 (见 [followersReady]; 给了 [holdAtMillis] = 房主跳了位置, 还要跟到那里) 就开播,
     * 最多等 [AUTO_START_TIMEOUT_MILLIS] / [SEEK_HOLD_TIMEOUT_MILLIS], 到时还没好的照常开播, 由跟随的规则追上。
     * 等的途中房主自己按了播放、离开了播放页或房间, 就不再替他开播。
     */
    private suspend fun startWhenFollowersReady(
        bridge: TogetherPlaybackBridge,
        manager: TogetherManager,
        episodeSession: EpisodeSession,
        holdAtMillis: Long?,
    ) {
        val session = (manager.state.value as? TogetherState.InRoom)?.session ?: return
        if (!session.isHost) return
        val afterSeek = holdAtMillis != null
        val subjectId = context.subjectId
        val episodeId = episodeSession.episodeId
        fun ready(snapshot: TogetherRoomSnapshot): Boolean = followersReady(snapshot, subjectId, episodeId, nearPositionMillis = holdAtMillis)
        // 真 = 房主自己按了播放; 假 = 成员都好了
        val outcome = combine(session.snapshot, context.player.state) { snapshot, playerState ->
            when {
                playerState.playWhenReady -> true
                ready(snapshot) -> false
                else -> null
            }
        }.filterNotNull()
        val pressedPlay = withTimeoutOrNull(if (afterSeek) SEEK_HOLD_TIMEOUT_MILLIS else AUTO_START_TIMEOUT_MILLIS) {
            if (afterSeek) {
                withTimeoutOrNull(SEEK_HOLD_ANNOUNCE_MILLIS) { outcome.first() } ?: run {
                    bridge.notifyWaitingForMembers(afterSeek = true)
                    outcome.first()
                }
            } else {
                if (!ready(session.snapshot.value)) bridge.notifyWaitingForMembers(afterSeek = false)
                outcome.first()
            }
        }
        when (pressedPlay) {
            true -> return
            false -> logger.info { "Together: the following members are ready" }
            null -> logger.info {
                val notReady = followersNotReady(session.snapshot.value, subjectId, episodeId, nearPositionMillis = holdAtMillis)
                "Together: the wait for the following members ran out, not ready: ${describeMembers(notReady)}"
            }
        }
        resumeForRoom(bridge, manager, session)
    }

    /** 等完了替房主开播: 还在这个房间、播放页在眼前、还停着 */
    private suspend fun resumeForRoom(bridge: TogetherPlaybackBridge, manager: TogetherManager, session: TogetherSession?) {
        withContext(Dispatchers.Main.immediate) {
            if (session == null || (manager.state.value as? TogetherState.InRoom)?.session !== session || !bridge.isPlayerInFront) return@withContext
            val player = context.player
            if (player.state.value.isMediaLoaded && !player.state.value.playWhenReady) {
                logger.info { "Together: starting playback for the room" }
                player.play()
            }
        }
    }

    /** 自己是房主, 房间里有别人在线并且跟随着 */
    private fun isHostWithFollowers(manager: TogetherManager): Boolean {
        val session = (manager.state.value as? TogetherState.InRoom)?.session ?: return false
        return session.isHost && shouldHoldAfterLoad(manager)
    }

    /** 房主 (房间里有别人在线并且跟随着时) 与跟随的成员参加同步; 自由观看的不参加, 房主也不用等他们 */
    private fun shouldHoldAfterLoad(manager: TogetherManager): Boolean {
        val session = (manager.state.value as? TogetherState.InRoom)?.session ?: return false
        if (!session.isHost) return manager.isFollowingHost()
        return session.snapshot.value.members.any { !it.isHost && it.following && it.state != TogetherMemberState.DISCONNECTED }
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    private suspend fun switchEpisodeIfNeeded(episodeId: Int) {
        if (context.getCurrentEpisodeId() != episodeId) context.switchEpisode(episodeId)
    }

    override suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {
        bridge?.clearLocalWatching(owner)
    }

    override suspend fun onClose() {
        bridge?.clearLocalWatching(owner)
    }

    companion object : EpisodePlayerExtensionFactory<TogetherPlayerExtension> {
        private val logger = logger<TogetherPlayerExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): TogetherPlayerExtension = TogetherPlayerExtension(context, koin)

        private const val REPORT_SAMPLE_INTERVAL_MILLIS = 1_000L

        /** 到主线程上读位置等了这么久, 算主线程刚卡过、取样不新鲜 */
        private const val MAIN_THREAD_STALL_MILLIS = 300L
        private const val POSITION_CORRECTION_THRESHOLD_MILLIS = 3_000L
        private const val BUFFERING_DEBOUNCE_MILLIS = 2_000L
        private const val HOLD_SETTLE_TIMEOUT_MILLIS = 2_000L

        /** 房主停在开头等跟随的成员加载, 最多等这么久就开播 */
        internal const val AUTO_START_TIMEOUT_MILLIS = 15_000L

        /** 房主跳了位置后停着等跟随的成员在那里缓冲, 最多等这么久就接着播 */
        internal const val SEEK_HOLD_TIMEOUT_MILLIS = 10_000L

        /** 房主跳了位置后等了这么久成员还没好才提示 */
        internal const val SEEK_HOLD_ANNOUNCE_MILLIS = 1_000L


        /** 房主卡在缓冲 / 加载上、成员停着等他时, 位置差多少以上才对一次 */
        private const val HOLD_ALIGN_THRESHOLD_MILLIS = 500L

        private const val ALIGN_RESUME_TIMEOUT_MILLIS = 10_000L

        /** 跳过去之后超过这么久才播起来, 算片源慢 */
        private const val SLOW_RESUME_MILLIS = 4_000L
        private const val ALIGN_SETTLE_MILLIS = 500L
    }
}
