/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.platform.Context
import me.him188.ani.app.ui.foundation.playback.LocalPlaybackSessionEntry
import me.him188.ani.app.ui.foundation.playback.PlaybackProgress
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import me.him188.ani.app.ui.foundation.playback.RetainedPlaybackSessionInfo
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummary
import me.him188.ani.app.videoplayer.player.VideoSurfaceFrameSignal
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlayerState
import kotlin.time.Duration.Companion.seconds

/**
 * 后台会话里值得打断用户去提示一声的事 (见 [RetainedPlaybackSessionHolder.notices]).
 *
 * 只提示"用户不知道就会白等"的状态: 起播就绪 (可以回来看了), 以及各种再等也不会自己好的问题
 * (要用户回去换源/手选). 播放页在前台时一概不发 —— 那些状态界面上本来就写着.
 *
 * 文案见 `rememberRetainedPlaybackNoticeTexts`.
 */
@Immutable
sealed interface RetainedPlaybackNotice {
    /** 数据源解析完成且播放器已经开播 (缓冲出了画面), 回去就能看. */
    data object Ready : RetainedPlaybackNotice

    /** 解析数据源失败. [cause] 决定提示里的原因. */
    data class LoadFailed(val cause: VideoLoadingState.Failed) : RetainedPlaybackNotice

    /** 流解析出来了但播放器打不开 (常见于 web 源嗅探到的地址失效). */
    data object PlayerError : RetainedPlaybackNotice

    /** 所有数据源都查完了, 没有可播放的结果. */
    data object NoMediaFound : RetainedPlaybackNotice

    /** 查完了但没有自动选中 (偏好不是 WEB 时不自动选), 在等用户自己挑. */
    data object NeedsManualSelection : RetainedPlaybackNotice
}

/**
 * 保留播放会话的宿主 (见 `AniUiBehavior.retainPlaybackSession`).
 *
 * 播放页的 [EpisodeViewModel] 默认挂在播放页那个返回栈条目上, 按返回退出即被销毁 —— 播放器、
 * 已经搜出来的数据源、已经解析好的播放流全部作废, 再进去从头再来 (而"从头再来"在 web 源上意味着
 * 重搜一遍 + 重新嗅探视频地址, 是十几秒的量级). 本类把它挪到**自己的** [Session] 里,
 * 而本类挂在应用根部, 于是:
 *
 * - 退出播放页只销毁界面, 会话照旧活着, 再进来 [openSession] 按"这个会话在播哪一集"认回同一个
 *   [Session], `viewModel(...)` 也就拿回同一个 [EpisodeViewModel] —— 状态自然接上, 没有任何
 *   "恢复"逻辑;
 * - 数据源还在搜的时候退出去, 搜索继续跑 (见 [guard] 里对 `pageState` 的订阅), 起播就绪或者
 *   卡住了都从 [notices] 发一声让外面提示用户 —— 这正是这套机制的用处;
 * - 常态只保留一个会话: [openSession] 见到要播的不是保留着的那一集, 先销毁**界面已经不在场的**
 *   旧会话再让调用方建新的. **先销后建**是刻意的 —— 两个 ExoPlayer 同时在场会在低端电视盒子上
 *   抢硬件解码器. 例外是播放页跳播放页的那段退场动画: 旧页的组合还活着时销毁它的会话, 那个页面
 *   就会拿着一个已经 release 的播放器继续渲染, 所以只能等它的组合真正销毁 ([onPageDisposed]).
 *   新会话要先搜数据源 (秒级) 才会碰解码器, 这点重叠无害;
 * - 会话随应用界面销毁 ([onCleared]), 不跨进程存活.
 *
 * 用 [ViewModelStore] 而不是自己 new [EpisodeViewModel]: `ViewModel.onCleared` 是 protected 的,
 * 只有 store 能正确触发它 (播放器的释放、进度落库全挂在那条链上); 一个会话一个 store (而不是共用
 * 一个 store 按 key 区分) 则是因为 [ViewModelStore.clear] 是清全部, 要做到"销毁上一个会话而不动
 * 新的"就只能这样.
 *
 * 记账全部走**页面组合的生死** ([onPageComposed] / [onPageDisposed]), 不用"进页面时跑一次"的
 * 一次性副作用: 转场没走完就按返回时离场页是原地复用的 (组合不重建, 那次副作用不会再跑), 那种
 * 写法会把当前会话永远停在用户已经放弃的那一集上, 而且两个会话谁都不会被清.
 *
 * 侧边栏等入口只看 [PlaybackSessionEntry] 这一小片接口 (经 [LocalPlaybackSessionEntry] 拿到),
 * 不认识本类, 也就不可能自己造出第二个播放器.
 */
class RetainedPlaybackSessionHolder : ViewModel(), PlaybackSessionEntry {
    /**
     * 一个会话: 一个私有 [ViewModelStore] 加里面唯一那个 [EpisodeViewModel].
     *
     * 会话自己就是 [ViewModelStoreOwner], 且 [viewModelStore] **恒定不变** —— 播放页每次重组都会
     * 重新读一遍 owner 的 store (`viewModel(viewModelStoreOwner = …)` 内部没有 remember), owner
     * 若是"当前会话"这种会被换掉的东西, 退场动画期间重组一次的旧页面就会在新会话的空 store 里
     * 凭空建出第二个 [EpisodeViewModel] (第二个播放器). 每个页面在自己的 remember 里认准一个
     * [Session], 这类事就不可能发生.
     */
    @Stable
    class Session internal constructor(initialInfo: RetainedPlaybackSessionInfo) : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()

        /**
         * 这个会话此刻在播哪一集.
         *
         * **不是进页面时那一集**: 播放器内的选集条与详情层 (目标在选集列表里时) 以及播完自动连播
         * 都是就地 [EpisodeViewModel.switchEpisode], 根本不导航. 记着进页面那一集的话, 用户从入口
         * 回来 (或者去详情页点正在播的那一集) 都会与这个会话对不上, 于是把已经热好的会话销毁重建;
         * 反过来点原来那一集又会被认成"同一集", 打开的却是别的集. 由 [guard] 第 5 条跟着 VM 更新.
         */
        var info: RetainedPlaybackSessionInfo by mutableStateOf(initialInfo)
            internal set

        /**
         * 会话的 VM; 它本来就在 [viewModelStore] 里, 这里只是留个取得到的引用 (由播放页上报).
         *
         * 是 snapshot state: [ComposeRetainedContent] 要跟着它变.
         */
        internal var vm: EpisodeViewModel? by mutableStateOf(null)
    }

    /**
     * 活着的会话. 常态只有一个 (保留着的那个).
     *
     * 播放页跳播放页的退场动画期间会多出一个 (连着跳几集就是几个): 旧页的组合还活着就还不能销毁
     * 它的会话, 否则那个页面会拿着已 release 的播放器继续渲染. 这段重叠由退场动画封顶 (几百毫秒),
     * 且新会话要先搜数据源 (秒级) 才会碰解码器, 见 [onPageDisposed].
     */
    private val sessions = mutableListOf<Session>()

    /** 此刻有播放页组合在场的会话, 按组合先后排列 (末尾是最近进来的那个). */
    private val composedSessions = mutableListOf<Session>()

    /** 当前会话. 是 snapshot state: [session] 与 [ComposeRetainedContent] 都要跟着它变. */
    private var currentSession: Session? by mutableStateOf(null)

    override val session: RetainedPlaybackSessionInfo? get() = currentSession?.info

    /**
     * 见 [PlaybackProgress]. 由 [guard] 每秒更新一次, 只有动作面板在读.
     */
    override var progress: PlaybackProgress? by mutableStateOf(null)
        private set

    /**
     * 见 [PlaybackSessionStatus]. 由 [guard] 第 7 条维护.
     */
    override var status: PlaybackSessionStatus? by mutableStateOf(null)
        private set

    /** 播放页此刻是否在前台. 由导航状态驱动 ([setPlayerPageVisible]). */
    private val playerPageVisible = MutableStateFlow(true)

    /** 应用整体是否在前台. 由根部的生命周期事件驱动 ([setAppForeground]). */
    private val appForeground = MutableStateFlow(true)

    /** 应用退到后台期间攒下的最后一条提示, 回到前台补发一次; 见 [notify]. */
    private var pendingNotice: RetainedPlaybackNotice? = null

    private val _notices = MutableSharedFlow<RetainedPlaybackNotice>(extraBufferCapacity = 4)

    /**
     * 会话在**后台**发生了用户该知道的事时发一次 (就绪 / 各种要用户处理的问题).
     *
     * 只在不看着播放页的时候发: 在播放页上画面自己就动起来了、错误也直接写在画面上, 再弹提示是噪音.
     */
    val notices: SharedFlow<RetainedPlaybackNotice> get() = _notices

    /** 监视当前会话的协程 (见 [guard]); 换会话时重启. */
    private var guardJob: Job? = null
    private var guardedVm: EpisodeViewModel? = null

    init {
        viewModelScope.launch {
            appForeground.collect { foreground ->
                if (foreground) flushPendingNotice()
            }
        }
    }

    /**
     * 进播放页时调用: 拿到这一页该用的会话.
     *
     * 要播的正是某个会话此刻在播的那一集 ([Session.info]) 就把它交回去 (这正是"回得去"的实现);
     * 否则先销毁界面已经不在场的旧会话腾出解码器, 再建一个新的空会话让调用方往里放 VM.
     *
     * 调用方必须把结果 remember 住: 同一个页面每次重组都要拿到同一个 [Session], 见 [Session].
     */
    fun openSession(subjectId: Int, episodeId: Int): Session {
        // 只比身份字段: info 上还挂着随条目信息补上的展示字段 (剧名/封面/集号), 拿刚构造的
        // 空壳整体 == 必然不等 -> 每次回播放页都会把热好的会话销毁重建. 见 RetainedPlaybackSessionInfo
        sessions.firstOrNull { it.info.isSameEpisodeAs(subjectId, episodeId) }?.let { existing ->
            makeCurrent(existing)
            return existing
        }
        // 先销后建: 界面还在场的会话不能动 (它的页面会拿着已 release 的播放器继续渲染),
        // 其余的一律清掉, 不让两个播放器同时占着解码器
        sessions.filter { it !in composedSessions }.forEach {
            // 认不出会话 = 热会话当场销毁重建 = 新播放器 + 整条流水线重跑, 而流水线末尾必然起播.
            // 这一步原先完全静默 —— "应用在后台却自己响起来"到底是这条 (会话被重建) 还是单纯的
            // loadMedia 重新起播 (换源重试等), 事后只能靠这行日志分辨
            logger.info {
                "Discarding retained session (ep${it.info.episodeId}) to open subject $subjectId episode $episodeId"
            }
            destroy(it)
        }
        return Session(RetainedPlaybackSessionInfo(subjectId, episodeId)).also {
            sessions += it
            makeCurrent(it)
        }
    }

    /**
     * 不进播放页, 直接在后台把某一集起起来 (动作面板上那颗「在后台准备」; 见
     * [PlaybackSessionEntry.startInBackground]).
     *
     * 与进播放页的差别**只有一处**: [EpisodeViewModel] 由这里建, 而不是由播放页的组合建. 其余
     * 完全同源 —— 建出来的 VM 放进会话自己的 store, 用的是与播放页**同一个 key** (两边都是
     * `ViewModelProvider` 的默认 key), 所以之后真进播放页时 `viewModel(viewModelStoreOwner = session)`
     * 拿回的就是这一个, 不会再建第二个播放器, 热好的进度也接得上.
     *
     * 起来之后的一切都由 [guard] 照旧管着: 它替界面当 `pageState` 的订阅者 (流水线因此会一直跑),
     * 后台一开播就按回暂停 (不出声), 就绪/出问题照常发提示. 也就是说这条路**没有引入任何新的
     * 后台行为**, 只是把"会话从哪来"多开了一个口子.
     *
     * **不进 [composedSessions]**: 那个列表的含义是"有播放页组合在场", 后台会话没有界面 ——
     * 混进去的话 [onPageDisposed] 会以为还有别的页面在场, 把该留下的会话当场销毁.
     */
    override fun startInBackground(subjectId: Int, episodeId: Int, context: Context): Boolean {
        val session = openSession(subjectId, episodeId)
        if (session.vm != null) return true // 已经在跑了 (回到同一集): 别碰它
        // 上游随 lifecycle 升级去掉了 ViewModelProvider(owner, factory) 构造, 改走 create()
        val vm = ViewModelProvider.create(
            session,
            viewModelFactory {
                initializer {
                    EpisodeViewModel(
                        subjectId = subjectId,
                        initialEpisodeId = episodeId,
                        initialIsFullscreen = false,
                        context = context,
                    )
                }
            },
        )[EpisodeViewModel::class]
        session.vm = vm
        updateGuard()
        // 会话作用域的后台任务 (自动选源、选中后 loadMedia、自动连播、进度记录……) 全都挂在
        // `EpisodeFetchSelectPlayState.onUIReady` 后面, 而调用它的只有播放页的组合
        // (EpisodePage / TvEpisodeScreen). 后台会话没有那一步, 于是**不补这一下就是半条流水线**:
        // 数据源照样查 (guard 第 1 条订阅着 pageState), 但 AutoSelectExtension 一直没上岗 ——
        // 面板上先是一直"正在查找数据源", 所有源查完后变成"等待手动选择"; 而真进播放页时它才启动,
        // 当场就选中并开始加载, 正是用户看到的"点进去立刻就开始加载, 返回后又是好的".
        //
        // 替播放页按下这一下是安全的: 那个名字说的是"有人看着了, 可以启动不受生命周期管的任务",
        // 而后台会话的"看着"正是本类干的 (guard 替界面订阅流水线、后台一开播就按回暂停、
        // 会话结束照常走 onCleared → onClose). 放在 updateGuard 之后是为了让订阅者先就位.
        vm.onUIReady()
        logger.info { "Started background session for subject $subjectId episode $episodeId" }
        return true
    }

    /** 播放页的组合建立: 上报它建出来的 VM, 本类据此监视这个会话 (见 [guard]). */
    fun onPageComposed(session: Session, vm: EpisodeViewModel) {
        session.vm = vm
        if (session !in composedSessions) composedSessions += session
        updateGuard()
    }

    /** 播放页的组合销毁: 决定这个会话是留下来 (保留会话) 还是就此销毁. */
    fun onPageDisposed(session: Session) {
        composedSessions.remove(session)
        val remaining = composedSessions.lastOrNull()
        if (remaining != null) {
            // 还有别的播放页在场: 本会话的界面已经没了, 不必再留 (常态只保留一个).
            // 转场没走完就按返回时走的正是这条 —— 被放弃的那一集在这里销毁, 当前会话交回还在场
            // 的那一页, 否则 current 会永远停在用户根本没看的那一集上, 两个播放器一起活着.
            if (currentSession === session) makeCurrent(remaining)
            destroy(session)
        } else if (currentSession !== session) {
            // 已经被新会话替换掉、界面又刚销毁的那个 (播放页跳播放页的退场动画走完)
            destroy(session)
        }
        // else: 只剩它自己且它就是当前会话 —— 这正是要保留下来的那个, 不动
    }

    private fun makeCurrent(session: Session?) {
        if (currentSession === session) return
        currentSession = session
        updateGuard()
    }

    private fun destroy(session: Session) {
        sessions.remove(session)
        composedSessions.remove(session)
        if (currentSession === session) currentSession = null
        session.vm = null
        session.viewModelStore.clear() // 触发 VM.onCleared: 释放播放器、进度落库
        updateGuard()
    }

    private fun updateGuard() {
        val vm = currentSession?.vm
        if (guardedVm === vm) return
        guardJob?.cancel()
        guardedVm = vm
        // 攒着的提示是上一个会话的, 换了会话就作废
        pendingNotice = null
        // 进度同理: 留着的话换会话那一瞬间面板会显示上一集的进度条
        progress = null
        // 新会话的初值就是"在准备": 第 7 条要等 debounce 才发第一个值, 那段时间面板不该还写着
        // 上一个会话的状态 (更不该是空的 —— 那一行会空掉半秒)
        status = vm?.let { PlaybackSessionStatus.Preparing }
        logger.info { "Guarded session changed: vm=${vm?.let { "ep${currentSession?.info?.episodeId}" } ?: "none"}" }
        guardJob = vm?.let { viewModelScope.launch { guard(it) } }
    }

    /** 播放页是否在前台; 由导航状态驱动, 与组合的存活无关. */
    fun setPlayerPageVisible(visible: Boolean) {
        playerPageVisible.value = visible
    }

    /** 应用整体是否在前台; 由根部的 `OnLifecycleEvent` 驱动. 见 [notify]. */
    fun setAppForeground(foreground: Boolean) {
        appForeground.value = foreground
    }

    override fun close() {
        pendingNotice = null
        sessions.toList().forEach { destroy(it) }
        currentSession = null
        updateGuard()
    }

    override fun onCleared() {
        super.onCleared()
        sessions.toList().forEach { destroy(it) }
        composedSessions.clear()
        currentSession = null
    }

    /**
     * 会话在后台期间仍要挂在组合里的东西. 挂在应用根部 (见 `AniAppContent`).
     *
     * Android 的 WEB 数据源解析器要靠组合挂载才拿得到 WebView 的宿主 Context
     * (`AndroidWebMediaResolver.ComposeContent`), 而原先挂载点在播放页的组合里 —— 退出播放页后
     * 后台还在跑的解析会一路抛 `WebVideoSourceResolver not attached`, 自动换源逐个试完, 最后
     * 界面上是"加载失败: 未知错误". 这里替播放页挂着, 它的挂载是引用计数的, 两处同时挂无妨.
     *
     * 注意用的是**会话自己那个** resolver: Koin 里 `MediaResolver` 是 factory, 每次注入都是新
     * 实例, 在根部另外注入一个挂上去等于挂了个没人用的.
     *
     * [key] 不能省: `ComposeContent` 里那个 `DisposableEffect` 的 key 是常量 (它本来只被播放页
     * 挂载, 而每个播放页都是新的组合, 所以够用). 换会话时本函数的调用点不变、effect 的 key 也不变,
     * effect 就不会重建 —— 于是它还挂着**上一个** resolver, 新会话那个从来没被挂上, 后台解析
     * 继续 "not attached". 按 VM 分组才能让旧的销毁、新的挂载.
     */
    @Composable
    fun ComposeRetainedContent() {
        val vm = currentSession?.vm ?: return
        key(vm) {
            vm.mediaResolver.ComposeContent()
        }
    }

    /** 只在不看着播放页、且应用还在前台时提示; 见 [notices]. */
    private fun notify(notice: RetainedPlaybackNotice) {
        if (playerPageVisible.value) {
            // 打日志而不是静默丢掉: "该提示的时候没提示"事后完全无法从日志还原,
            // 分不清是压根没走到这里, 还是走到了但被这条规则挡下 (2026-08-11 排查时吃过亏)
            logger.info { "Notice suppressed (player page visible): $notice" }
            return
        }
        if (!appForeground.value) {
            // 应用整个退到后台 (按 HOME 去别的应用) 也不能发: 这些提示在 Android 上是**系统 Toast**
            // 加一声满音量的按键音, 会空降在别人的应用/桌面上. 组合不随 Activity 停止而销毁, 后台
            // 解析完成时这里照样会被调到, 所以必须自己拦.
            // 只留最后一条回前台再补发, 不是丢掉: 用户回来仍然需要知道"可以看了", 而中间那些状态
            // (换源过程中一闪而过的失败) 补发出来只会误导.
            logger.info { "Notice deferred (app in background): $notice" }
            pendingNotice = notice
            return
        }
        logger.info { "Notice: $notice" }
        if (!_notices.tryEmit(notice)) {
            logger.warn { "Notice dropped, buffer full: $notice" }
        }
    }

    /** 回到前台: 把后台期间攒下的那条补发一次 (会话已经换掉/结束的话 [pendingNotice] 已被清空). */
    private fun flushPendingNotice() {
        val notice = pendingNotice ?: return
        pendingNotice = null
        notify(notice)
    }

    /**
     * 监视当前会话的五件事. 全部只读 [vm] 暴露的流, 不碰它的内部状态.
     */
    private suspend fun guard(vm: EpisodeViewModel) = coroutineScope {
        // 1. 替界面当订阅者, 让流水线在没有界面的时候继续跑.
        //    pageState 是 WhileSubscribed(5s) 的, 而"保证数据源会一直查询"的那个 collector 就挂在
        //    它的 scope 里 (见 EpisodeViewModel.createPageStateFlow) —— 没人订阅时数据源搜索会在
        //    5 秒后停下, 那样"退出去等它加载"就不成立了.
        launch { vm.pageState.collect { } }

        // 2. 不在眼前就不出声, 回来照原样接着播. 只在离开那一刻暂停是不够的: 数据源解析完成后
        //    流水线自己会 resume (loadMedia 末尾那句 `setMediaData(playWhenReady = true)`), 于是
        //    必须持续按住 —— 这也正是常见的"退出去之后忽然从后台传出声音".
        //
        //    "不在眼前"是**两个互不包含的维度**, 本条两个都按住:
        //
        //    - **播放页不在导航栈顶** ([playerPageVisible] = false): 退出了播放页, 或者导航去了
        //      更深的页面. 这一维**记账** ([EpisodeViewModel.autoPausedOffPage]), 回到播放页时
        //      把这次临时暂停原样还回去 (下面那一半);
        //    - **应用整个退到后台** ([appForeground] = false: 按 HOME 走开, 或者电视把信号源切去
        //      别的设备). 播放页仍然是栈顶, 上面那一维一次都不会触发.
        //
        //      两维都记在**本类这一本账**上 ([EpisodeViewModel.autoPausedOffPage]), 而
        //      `AutoPauseEffect` 在本类在场时**整个不生效** (它自己判 `LocalPlaybackSessionEntry`).
        //      不是分工问题而是必须如此: 记在这边才能走下面那条"等新的视频输出面出画再放声音"的
        //      恢复路径, 而它的 ON_START 是当场 play() —— 那正是 e5a0cda1a 修过的"先出声后出画".
        //      两边并存的话谁先落地不确定, 它先按下暂停的那一半会连着走偏: 本条就看不到
        //      isPlaying、不记账, 回前台由它当场恢复, 等首帧那一步整个被绕过去.
        //
        //      **这一维原先是漏的** (2026-08-30 修): 按 HOME 走开时全部防线只剩 `AutoPauseEffect`
        //      在 ON_STOP 那一下按的**一次**暂停, 而"只按一次"正是本条开头写的那个不成立的做法.
        //      后台自动换源重试 (SwitchMediaOnPlayerErrorExtension, 出错约 1 秒后换下一个源)、
        //      迟到的自动选源、任何一条走到 loadMedia 的路都会重新置起播放意图, 而那时没有任何
        //      东西再按它. 用户报的"暂停着按 HOME 走开 / 切走信号源, 过一会儿电视自己响起来"
        //      就是它.
        //
        //    **暂停与恢复是同一条规则的两半, 都收在这一个 collector 里** (2026-08-22 修):
        //    离开播放页是"临时暂停", 回到播放页就该把它原样还回去 —— 进去之前在播就接着播, 之前
        //    是用户自己按的暂停就保持暂停. 原先恢复那一半是搭播放页 `AutoPauseEffect` 的
        //    ON_START 便车的, 从播放器点人物卡片跳到人物全屏页再返回时它不成立:
        //      - 离开那一刻是本条规则先把播放器按成暂停 (导航状态一变就落地);
        //      - 之后播放页的组合才随退场动画销毁, 那时它的 per-entry LifecycleOwner
        //        (Navigation 3 的 `BackStackAwareLifecycleNavEntryDecorator` 造的) 一起被销毁并
        //        补发 ON_STOP —— `AutoPauseEffect` 看到的已经是"没在播", 于是按它自己的语义把
        //        标志清成 false, 顺手把本条记下的"回来要恢复"一起抹了;
        //      - 返回时组合重建, ON_START 读到的标志是 false, 一次 play() 都不发. 净效果就是
        //        用户报的"从人物全屏页返回后播放器停着不动".
        //    各记各的账 (本条用 `autoPausedOffPage`) 之后互不干扰; 恢复这一侧还刻意做成**状态**
        //    而不是事件 —— 哪怕别处在"不可见"期间又插一次暂停, 随后到达的 visible=true 也会把它
        //    收拾掉, 于是顺序不再重要.
        //
        //    唯一的例外是"一起看"跟随模式 (`playbackAutomationSuppressed`): 那时候播与不播由房主
        //    说了算, 本地任何自动暂停都是跟房间对着干 —— 房主在播, 房间的持续校正每秒发现本地是
        //    暂停就下发一次同步, 播放器 resume, 这里又按回去, 一秒一轮; 与此同时本地位置不动而
        //    房主在走, 偏差越拉越大, 于是每轮还多一次 seek + "已与房主同步"的提示, 状态翻转还都
        //    是"不连续", 每次都触发一次上报. 播放页在场时的自动暂停 (`AutoPauseEffect`) 本来就用
        //    同一个开关放过跟随模式, 这里跟着它, 前台后台一致.
        launch {
            // 在途的"等画面就位再恢复", 见 [resumeWhenVideoVisible]
            var resumeJob: Job? = null
            combine(
                playerPageVisible,
                appForeground,
                vm.player.state,
                vm.playbackAutomationSuppressed,
            ) { pageVisible, foreground, state, roomControlled ->
                AutoPauseInput(pageVisible, foreground, state, roomControlled)
            }
                .collect { (pageVisible, foreground, state, roomControlled) ->
                    if (roomControlled) return@collect
                    if (pageVisible && foreground) {
                        // 回到播放页: 把"离开时的临时暂停"原样还回去. 判据只有本类记下的那一笔账,
                        // 所以用户自己按的暂停不会被误恢复成播放 (那时下面根本没记账).
                        //
                        // 恢复不是当场 play(), 而是先等新的视频输出面出画 (见
                        // [resumeWhenVideoVisible]). 消账也跟着挪到真的 play() 那一刻, 于是这里
                        // 得自己防重入 —— 不消账的话每次 state 变化都会再起一个恢复任务.
                        if (vm.autoPausedOffPage && resumeJob?.isActive != true) {
                            resumeJob = launch { resumeWhenVideoVisible(vm) }
                        }
                        return@collect
                    }
                    // 又走了 (或者应用退到了后台): 在途的恢复作废 (账留着, 下次回来重新等)
                    resumeJob?.cancel()
                    // 这里必须是**严格** isPlaying (时钟真的在走), 不能图省事换成 playWhenReady ——
                    // 换过, 三个症状一起来 (2026-08-11 真机复现):
                    //
                    // loadMedia 的 setMediaData(playWhenReady = true) 一落地, playWhenReady 就是 true,
                    // 这条规则会在首帧之前就按下去, 于是播放器在后台**永远到不了** isPlaying:
                    // 1. 播放器一次都不播, 于是"播过一次"才做的事全都不做了 —— 当时表现为第 3 条的
                    //    就绪提示等不到 (那时它还在等 isPlaying). 第 3 条现已改成等
                    //    `Ready && !isBuffering`, 不再受这条影响, 但下面两条仍然成立;
                    // 2. 后台那段时间不再预热缓冲与解码器, 回页面要从头缓冲 (实测多等 3 秒),
                    //    保留会话的意义就没了;
                    // 3. 缩略图预热也等 isPlaying, 于是它恰好在主播放器做完整初始缓冲的峰值上开工,
                    //    两路抢带宽/解码器 → prewarm 失败 → framesAvailable 被永久置 false
                    //    (只有换媒体才重置) → 整集彻底没缩略图.
                    //
                    // 代价是时钟起走的那一瞬可能漏出一点声音, 这是原设计接受的取舍.
                    if (!state.isPlaying) return@collect
                    // 回到前台那一下是根部的 ON_START 先把 appForeground 置起来, 播放页
                    // `AutoPauseEffect` 的 ON_START 恢复播放随后才到 —— 本 collector 手上的那份值
                    // 可能还是上一轮的, 落地前再读一次现值, 否则刚恢复的播放会被当场按回去
                    if (pageVisible && appForeground.value) return@collect
                    // 记账: 回到播放页 (且应用在前台) 时由上面那一半原样还回去
                    vm.autoPausedOffPage = true
                    // 日志分两句: 后台自己播起来是"没人看着的时候发生的事", 事后只能从日志还原
                    // 到底是哪条路重新起的播 (换源重试? 迟到的选源? 会话被重建?) —— 前后那几行
                    // videoLoadingState 与 "Discarding retained session" 就是答案
                    logger.info {
                        if (pageVisible) {
                            "App in background, auto-pausing playback that started by itself " +
                                    "(mediaStatus=${state.mediaStatus})"
                        } else {
                            "Player page not visible, auto-pausing playback"
                        }
                    }
                    vm.player.pause()
                }
        }

        // 3. 后台起播就绪 → 通知外面提示用户. 这是用户等的那一下.
        launch {
            vm.videoStatisticsFlow
                .map { it.videoLoadingState }
                .distinctUntilChanged()
                .filterIsInstance<VideoLoadingState.Succeed>()
                .collectLatest {
                    // Succeed 只是"播放地址交给播放器了"(见 PlayerSession.loadMedia), 之后还有取容器头、
                    // 建解码器、缓冲首帧 —— 实测 1~18 秒, 在线源越慢越久. 按 Succeed 提示的话用户回来
                    // 还得对着黑屏干等 (进度条右侧还是 0:00), 那这声提示就没起到作用. 等真的开播再说.
                    //
                    // 后台一开播就会被第 2 条按回暂停, 但这里是常驻的收集者, 那一次开播收得到;
                    // 万一被合并掉或者一直卡在缓冲, 靠超时兜底照样提示 (地址确实有了, 让用户
                    // 自己决定要不要回去等).
                    //
                    // 判据是"媒体已打开且当前位置的数据够了", 而**不是** isPlaying ——
                    // `isPlaying = mediaStatus == Ready && playWhenReady && !isBuffering`, 它要求
                    // playWhenReady, 而退出播放页必然把播放器按成暂停 (AutoPauseEffect + 本类第 2 条),
                    // 于是后台**永远**到不了 isPlaying. 原先按 isPlaying 等, 每次都只能落到下面那个
                    // 25 秒超时兜底 —— 而用户在等它, 通常等不到 25 秒就自己走回播放页了, 那一刻
                    // notify 被 playerPageVisible 挡掉, 提示一次都不响
                    // (2026-08-11 真机: 本地文件 17:59:20 就绪, 播放器一直没动, 18:01:03 用户回到
                    //  页面的同一秒才首次开播; 换一集的另一次同样精确落在进页面那一秒).
                    //
                    // 不含 playWhenReady 的这两条正是"点进去就能看"的充要条件: 媒体开好了, 且当前
                    // 位置不缺数据. 时钟走不走取决于用户在不在看, 与"就绪"无关.
                    val state = withTimeoutOrNull(PLAYBACK_START_WAIT) {
                        vm.player.state.first {
                            (it.mediaStatus == MediaStatus.Ready && !it.isBuffering) ||
                                    it.mediaStatus is MediaStatus.Error
                        }
                    }
                    // 播放器直接报错: 交给第 4 条报"打不开这个源", 别再说一句"已就绪"
                    if (state?.mediaStatus is MediaStatus.Error) return@collectLatest

                    // 缓冲够了**还不等于**"点进去就能看": 还要恢复历史进度, 而 seek 会作废已经缓冲好的
                    // 数据, 在新位置重新缓冲一次 —— 实测 3 秒左右. 在那之前提示就绪, 用户点进去看到的
                    // 仍然是"正在缓冲"(2026-08-11 真机复现).
                    //
                    // 前提是那次 seek 得**在后台就发生**. RememberPlayProgressExtension 原先把它关在
                    // `isPlaying` 分支里 (后台永远进不去), 已改成媒体一 Ready 就恢复 —— 能不能 seek
                    // 的真正前提是时长已知, 不是时钟在走. 那两处必须一起看.
                    //
                    // 没有"还有没有待处理的 seek"这种信号可问, 就用稳定性代替: isBuffering 连续
                    // [PLAYBACK_SETTLE_DELAY] 保持 false 才算稳住. seek 起步比首帧晚一点也没关系 ——
                    // 它一把 isBuffering 顶起来, debounce 的计时就重置, 于是必然等到它缓冲完.
                    // 反过来若 seek 起步比这个窗口还晚 (或压根没有历史进度), 最坏也只是回到
                    // 改之前的行为: 提示早了一点, 不会更差.
                    withTimeoutOrNull(PLAYBACK_SETTLE_WAIT) {
                        vm.player.state.map { it.isBuffering }
                            .distinctUntilChanged()
                            .debounce(PLAYBACK_SETTLE_DELAY)
                            .first { !it }
                    }
                    notify(RetainedPlaybackNotice.Ready)
                }
        }

        // 4. 后台出了再等也不会自己好的问题 → 同样提示一声, 否则用户会一直等一个不会来的 Ready.
        launch {
            combine(
                vm.videoStatisticsFlow.map { it.videoLoadingState }.distinctUntilChanged(),
                vm.player.state,
                vm.pageState.map { selectionProblemOf(it) }.distinctUntilChanged(),
            ) { loading, playerState, selection -> problemOf(loading, playerState, selection) }
                .distinctUntilChanged()
                // 等状态稳定下来再提示: 播放失败常常是一闪而过的中间态 —— SwitchMediaOnPlayerErrorExtension
                // 会在出错约 1 秒后自动换下一个源重试, 每换一次都必然路过 Failed, 逐个弹提示就成了刷屏.
                // 稳定后仍是问题才提示; 万一之后自动换源又成功了, 用户紧接着会收到 Ready, 不会被误导太久.
                .debounce(PROBLEM_SETTLE_DELAY)
                .collect { problem -> problem?.let { notify(it) } }
        }

        // 5. 会话记着的"在播哪一集"要跟着 VM 走: 播放器内换集是就地 switchEpisode, 不导航,
        //    没有任何东西会来更新记账 (见 Session.info).
        //
        //    episodeId 取自 pageState 而不是 VM 内部的 episodeIdFlow (private + UnsafeEpisodeSessionApi).
        //    代价是换集后要等条目信息加载出来才更新 —— 在那之前 pageState 是 placeholder
        //    (episodeId = -1), 这里跳过, 会话暂时还记着上一集. 那段时间用户正在播放页上, 不会
        //    从入口回来, 影响有限.
        //
        //    展示字段 (剧名/封面/集号) 顺着同一条流取: 入口面板要显示"后台在播什么", 而这些值
        //    此刻就在内存里, 不产生任何额外请求 (见 RetainedPlaybackSessionInfo).
        launch {
            vm.pageState
                .map { state ->
                    val ep = state?.episodePresentation?.takeIf { it.episodeId > 0 }
                        ?: return@map null
                    // subjectId 不从这里取: 播放器内换集不会换条目, 而 subjectPresentation 在
                    // 条目信息到达前是 placeholder (info = SubjectInfo.Empty), 取到的会是 0
                    Triple(ep.episodeId, ep.sort, state.subjectPresentation)
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (episodeId, sort, subject) ->
                    onSessionInfoUpdated(vm, episodeId, sort, subject)
                }
        }

        // 6. 播放进度: 动作面板要显示"看到哪儿了". 读的是播放器自己维护的 StateFlow, 不是去调
        //    ExoPlayer 的方法 (那必须在主线程, 见 PlaybackSpeedExtension 的教训); 本作用域是
        //    viewModelScope, 本来就在主线程上.
        //
        //    取整到秒 + distinctUntilChanged: 位置每 100ms 变一次, 面板上只显示到秒, 没必要
        //    一秒失效十次. 会话在后台是暂停的, 所以实际几乎不发射.
        launch {
            combine(
                vm.player.currentPositionMillis.map { it / 1000 }.distinctUntilChanged(),
                vm.player.mediaProperties.map { it?.durationMillis ?: 0L }.distinctUntilChanged(),
            ) { seconds, duration -> PlaybackProgress(seconds * 1000, duration) }
                .collect { progress = it }
        }

        // 7. 面板要显示的**状态**. 判据与第 3/4 条同源 (见 statusOf), 区别在于:
        //    - 是状态不是事件: 前台后台一律更新, 不管 playerPageVisible;
        //    - Ready/Buffering/Preparing 这些"没出问题"的中间态也要有值 —— 用户打开面板正是想
        //      知道"进行到哪了", 只在出事时才有话说等于没做.
        //
        //    debounce 的理由与第 4 条一样 (自动换源必然路过 Failed), 只是短得多: 这里不打扰用户,
        //    晚两秒说实话比闪一下"加载失败"好, 但也不该像提示那样压六秒才更新.
        launch {
            combine(
                vm.videoStatisticsFlow.map { it.videoLoadingState }.distinctUntilChanged(),
                vm.player.state,
                vm.pageState.map { selectionProblemOf(it) }.distinctUntilChanged(),
            ) { loading, playerState, selection -> statusOf(loading, playerState, selection) }
                .distinctUntilChanged()
                .debounce(STATUS_SETTLE_DELAY)
                .collect {
                    // 每次变化打一行: 面板上"这行字与播放页里写的不是一回事"这类问题, 事后完全
                    // 无法从日志还原 —— 分不清是这条没跑、判据算错, 还是界面没读到 (与上面
                    // notify 里那条日志同一个理由). 状态变化很少, 不吵
                    logger.info { "Session status -> $it" }
                    status = it
                }
        }
    }

    private fun onSessionInfoUpdated(
        vm: EpisodeViewModel,
        episodeId: Int,
        episodeSort: String,
        subject: SubjectPresentation,
    ) {
        val session = sessions.firstOrNull { it.vm === vm } ?: return
        val updated = session.info.copy(
            episodeId = episodeId,
            episodeSort = episodeSort,
            // placeholder 期间别把已有的剧名/封面覆盖成空
            subjectTitle = subject.title.takeIf { !subject.isPlaceholder } ?: session.info.subjectTitle,
            coverUrl = subject.info.imageLarge.ifBlank { session.info.coverUrl },
        )
        if (session.info == updated) return
        session.info = updated
    }

    /**
     * 回到播放页时的恢复: **等视频面出画之后再放声音**.
     *
     * 视频输出面 (Android 上是播放页那块 SurfaceView) 跟着页面生死, 回来是新的一代; 而播放器
     * 本体一直活着, 所以立刻 `play()` 的结果是"声音已经在走, 画面还黑着" —— 解码器得先把输出
     * 重定向到新 Surface, 就地改不了的芯片还要释放重建、从关键帧重解 (实测索尼 BRAVIA BF1 上
     * 的联发科解码器就是这样, Shield 上不明显). 那几百毫秒的画面内容用户是真的没看到.
     *
     * 等一下再开, 声画一起起来, 一帧不漏. 三条约束:
     * - **有上限** ([RESUME_FRAME_WAIT]): 万一某设备在暂停状态下压根不渲染 (要播起来才出帧),
     *   等下去就是永远黑屏 —— 超时照旧 `play()`, 退化成老行为;
     * - 等的期间用户可能又走了, 或者"一起看"接管了播放, 落地前重新确认一遍;
     * - **消账挪到真的 `play()` 那一刻**: 提前清掉的话, 等待期间用户又退出去, 这笔"回来要恢复"
     *   的账就凭空消失了 (再进来播放器停着不动).
     *
     * 拿不到 [VideoSurfaceFrameSignal] 的播放器 (桌面/iOS) 不等, 语义与从前一致.
     */
    private suspend fun resumeWhenVideoVisible(vm: EpisodeViewModel) {
        val signal = vm.player as? VideoSurfaceFrameSignal
        var timedOut = false
        if (signal != null && !signal.hasFrameOnCurrentSurface.value) {
            timedOut = withTimeoutOrNull(RESUME_FRAME_WAIT) {
                signal.hasFrameOnCurrentSurface.first { it }
            } == null
        }
        // 等的这一秒里用户可能又走开了 (导航去别处, 或者干脆按 HOME 把应用切到后台), 也可能
        // "一起看"接管了播放: 三种情况都不能落地, 而且都**不消账** —— 下次真的回到播放页时重新等
        if (!playerPageVisible.value || !appForeground.value || vm.playbackAutomationSuppressed.value) return
        if (!vm.autoPausedOffPage) return
        vm.autoPausedOffPage = false
        logger.info {
            "Player page visible again, resuming auto-paused playback " +
                if (timedOut) "(no video frame within $RESUME_FRAME_WAIT, resuming anyway)" else "(video frame is up)"
        }
        vm.player.play()
    }

    private companion object {
        private val logger = logger<RetainedPlaybackSessionHolder>()

        /**
         * 恢复播放前最多等新输出面出第一帧多久, 见 [resumeWhenVideoVisible].
         *
         * 正常路径 (重定向输出面, 或释放重建解码器 + 从关键帧重解) 在这个量级以内. 到点仍然
         * 起播: 宁可退回"黑屏有声", 也不能把画面永远等在这儿.
         */
        private val RESUME_FRAME_WAIT = 1.seconds

        /** 问题状态要持续这么久才提示, 见 [guard] 第 4 条. */
        private val PROBLEM_SETTLE_DELAY = 6.seconds

        /** 面板上那行状态要稳定这么久才更新, 见 [guard] 第 7 条. */
        private val STATUS_SETTLE_DELAY = 2.seconds

        /** 解析成功后最多等这么久的"真的开播", 到点仍然提示就绪, 见 [guard] 第 3 条. */
        private val PLAYBACK_START_WAIT = 25.seconds

        /**
         * 开播之后, `isBuffering` 要连续这么久是 false 才算"稳住了", 见 [guard] 第 3 条.
         *
         * 取值要盖住"首帧出来"到"恢复历史进度的 seek 真正起步"之间的空档 (那之间要等
         * `mediaProperties` 报出时长, 通常紧随首帧). 太小会在 seek 起步前就放过去, 太大则纯粹
         * 推迟提示 —— 没有历史进度时这段是白等的.
         */
        private val PLAYBACK_SETTLE_DELAY = 2.seconds

        /** 等"稳住"的上限. 到点照样提示: 宁可早一点, 也别把这声提示彻底吞掉. */
        private val PLAYBACK_SETTLE_WAIT = 30.seconds
    }
}

/**
 * [RetainedPlaybackSessionHolder.guard] 第 2 条的输入 (combine 没有四元组).
 *
 * [pageVisible] 与 [appForeground] 是"不在眼前"的两个维度, 不能先合成一个布尔值再传进去 ——
 * 两边的记账方式不同 (前者要记, 后者不记), 合了就分不出该记哪一笔.
 */
private data class AutoPauseInput(
    val pageVisible: Boolean,
    val appForeground: Boolean,
    val playerState: PlayerState,
    val roomControlled: Boolean,
)

/** 数据源搜索层面的"再等也没用". */
private enum class SelectionProblem {
    None,

    /** 有搜到结果, 但不会自动选 (偏好不是 WEB), 在等用户挑. */
    NeedsManualSelection,

    /** 全部源都查完了, 一个可播的结果都没有. */
    NoMedia,
}

private fun selectionProblemOf(state: EpisodePageState?): SelectionProblem {
    // 页面状态还没算出来 (刚进页面) 或还是占位数据: 什么都判断不了
    if (state == null || state.isPlaceholder) return SelectionProblem.None
    // 已经选中了就不是选择层面的问题 (解析/播放能不能成另说, 那是 problemOf 的前两条)
    if (state.mediaSelectorSummary is MediaSelectorSummary.Selected) return SelectionProblem.None
    val results = state.mediaSourceResultListPresentation
    // 源还没登记上来 (刚进页面) 或还有源在查 —— 等着就行, 这才是这套机制的正常用途
    if (results.list.isEmpty() || results.anyLoading) return SelectionProblem.None
    return if (results.list.any { it.totalCount > 0 }) SelectionProblem.NeedsManualSelection
    else SelectionProblem.NoMedia
}

/**
 * 面板那行状态. 与 [problemOf] 同一套判据 (出了问题先说问题), 只是把"没出问题"的那几步也分了
 * 出来 —— 那正是这行字的用处.
 *
 * **播放器状态 ([playerState]) 只在 [VideoLoadingState.Succeed] 之后才作数**, 这是本函数唯一的
 * 讲究处: 播放器实例是**跨换集/换源复用**的 (保留会话的整个前提), 所以在新的播放地址交给它之前,
 * `mediaStatus` 报的还是**上一集**那次的 `Ready`. 按它判断的话, "换完集正在解析下一集"会被写成
 * "正在播放" —— 面板上说在播, 点进去播放页写着"正在解析资源链接", 正是 2026-08-16 实测到的那个
 * 不一致. 第 3 条等就绪时先 `filterIsInstance<Succeed>` 再看播放器, 是同一个道理.
 */
private fun statusOf(
    loading: VideoLoadingState,
    playerState: PlayerState,
    selection: SelectionProblem,
): PlaybackSessionStatus = when {
    // Cancelled 不是问题: 它是"换源"的中间态, 紧接着就会重新开始解析 (与 problemOf 同)
    loading is VideoLoadingState.Failed && loading != VideoLoadingState.Cancelled ->
        PlaybackSessionStatus.LoadFailed(loading)

    selection == SelectionProblem.NeedsManualSelection -> PlaybackSessionStatus.NeedsSelection
    selection == SelectionProblem.NoMedia -> PlaybackSessionStatus.NoMedia

    // 地址已经交给播放器: 这时候播放器说的才是这一集的事
    loading is VideoLoadingState.Succeed -> when {
        playerState.mediaStatus is MediaStatus.Error -> PlaybackSessionStatus.PlayerError
        // 与第 3 条的"就绪"同一个判据: 媒体开好了且当前位置不缺数据. 不含 playWhenReady ——
        // 后台会话是被按住暂停的, 要求时钟在走的话永远到不了这一档
        playerState.mediaStatus == MediaStatus.Ready && !playerState.isBuffering ->
            PlaybackSessionStatus.Ready
        // 取容器头 / 建解码器 / 缓冲首帧, 以及播放中途的重新缓冲
        else -> PlaybackSessionStatus.Buffering
    }

    else -> PlaybackSessionStatus.Preparing
}

private fun problemOf(
    loading: VideoLoadingState,
    playerState: PlayerState,
    selection: SelectionProblem,
): RetainedPlaybackNotice? = when {
    // Cancelled 不是问题: 它是"换源"的中间态 (loadMedia 被取消), 紧接着就会重新开始解析
    loading is VideoLoadingState.Failed && loading != VideoLoadingState.Cancelled ->
        RetainedPlaybackNotice.LoadFailed(loading)

    playerState.mediaStatus is MediaStatus.Error -> RetainedPlaybackNotice.PlayerError
    selection == SelectionProblem.NeedsManualSelection -> RetainedPlaybackNotice.NeedsManualSelection
    selection == SelectionProblem.NoMedia -> RetainedPlaybackNotice.NoMediaFound
    else -> null
}
