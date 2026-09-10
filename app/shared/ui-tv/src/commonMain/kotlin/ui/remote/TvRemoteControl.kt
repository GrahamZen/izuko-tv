/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.repository.subject.SubjectSearchHistoryRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.ui.foundation.lan.LanHttpServer
import me.him188.ani.app.ui.foundation.lan.TvRemoteSettingsBridge
import me.him188.ani.app.ui.foundation.lan.findLanAddress
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import me.him188.ani.app.ui.foundation.playback.PlayingCacheInfo
import me.him188.ani.app.ui.foundation.playback.RetainedPlaybackSessionInfo
import me.him188.ani.app.ui.main.TvUpNextStore
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 「Web 控制台」: 电视上常驻一个局域网 HTTP 小服务 ([LanHttpServer]), 手机扫码 (或打开书签) 进一个
 * 两个标签的网页 —— 「搜索」把关键词和筛选推给搜索页, 「播放器」列出当前这一集的全部数据源候选、点一下
 * 就让播放器换过去. 遥控器上打字、翻长列表都费劲, 这是给那些场景用的.
 *
 * **地址是固定的** (手机上能加书签): 端口按包名固定 ([fixedPortFor], 被占才退到随机端口), token 生成一次后存进
 * SharedPreferences, 进程重启不变; 只有搜索页面板上的「重置地址」会换一个新 token. 存储名与键沿用
 * 「搜索输入」时代的 (`tv_remote_search_input`), 升级后已加的书签照样能用.
 * 服务**应用级常驻** ([install] 起, 进程活着就监听), 不跟任何页面同生死.
 *
 * **IP 变了要提示**: 书签/已扫过的手机记的是当时的 IP. 记住「手机最后一次连上时的 IP」([knownHost], 持久化),
 * 与当前 IP 不一致时 [hostChanged] 为 true, 搜索页面板标红提示重新扫码; 手机用新地址连上来或按「重置」即更新.
 *
 * 两个标签各自的去向:
 * - 搜索: 搜索页在场就直接交给它 ([submissions]); 不在场则记为 pending 并把电视导航到搜索页, 页面进场
 *   [takePending]. 电视在播放页时同样直接把搜索页叠上去 (与播放器内嵌详情页点标签同一种方式), 按返回回到播放器.
 * - 结果: 电视搜索页已经加载的结果原样列到手机上, 点一条电视直接进播放页 (见 [RemoteSearchResults]).
 * - 缓存: 电视上的全部缓存按番列出 (状态 / 大小 / 暂停 / 删除) 与剩余空间, 见 [RemoteCacheList]; 给某部番挑几集缓存走 [RemoteCache].
 * - 设置: 账号与要打字的设置 (见 [RemoteSettings]), 以及「数据源」页 = 设置里数据源管理那一页的网页版
 *   (列表 / 启停 / 排序 / 新增 / 编辑 / 导入导出), 见 [RemoteSources].
 * - 播放器: 播放页在组合里时登记一个前台 [RemotePlayerHandle]; 保留播放会话在后台时由 TV 根组合登记一个后台的,
 *   前台优先 ([registerPlayer]). 网页的读 (轮询状态) 与写 (选源 / 改查询请求) 都经它. 后台会话照常搜源、选源、
 *   解析 (Web 解析器由保留会话挂在应用根部, 见 `RetainedPlaybackSessionHolder.ComposeRetainedContent`), 唯一的
 *   不同是它在播放页不可见时一直按住暂停 —— 所以后台只换源 (静音加载, 回到播放器接着播), 不给播放控制.
 */
object TvRemoteControl {
    private val logger = logger<TvRemoteControl>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("TvRemoteControl"))

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var navigator: AniNavigator? = null
    private var server: LanHttpServer? = null
    private var serveJob: Job? = null

    /** 本包的固定端口 ([fixedPortFor]); install 前为正式包那个. */
    private var fixedPort = RELEASE_PORT

    /** 搜索页在组合里的份数 (>0 = 有人收 [submissions]). */
    private var searchPageCount = 0

    /** 搜索页不在场时收到的提交, 导航过去后由页面 [takePending] 取走. */
    private var pendingSearch: RemoteSearchSubmission? = null

    /** 前台: 在组合里的播放页登记的. */
    @Volatile
    private var foregroundPlayer: RemotePlayerHandle? = null

    /** 后台: 保留播放会话由 TV 根组合登记的, 播放页不在前台时顶上. */
    @Volatile
    private var backgroundPlayer: RemotePlayerHandle? = null

    /** 网页读写的对象: 播放页在前台就是它, 否则是后台会话. */
    private val player: RemotePlayerHandle? get() = foregroundPlayer ?: backgroundPlayer

    /** 电视搜索页的结果面板 (在组合里时); 手机「结果」标签读它. */
    @Volatile
    private var searchResults: RemoteSearchResultsSource? = null

    /** 结果面板离场时留下的最后一份: 点了一条进播放页之后, 手机上仍看得到、点得了. */
    @Volatile
    private var lastSearchResults: RemoteSearchResultsSnapshot? = null

    /** 手机刚提交、电视还没换上的那次搜索及提交时刻; 期间「结果」标签显示「正在搜索」, 不让旧结果闪一下. */
    private var awaitingSearch: Pair<RemoteSearchSubmission, TimeMark>? = null

    private val _url = MutableStateFlow<String?>(null)

    /** 二维码里放的地址; 服务没起来 / 没连局域网时为 null. */
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _phoneConnected = MutableStateFlow(false)

    /** 有手机打开过网页 (本次服务存活期间); 面板上「等待手机连接」→「手机已连接」的依据. */
    val phoneConnected: StateFlow<Boolean> = _phoneConnected.asStateFlow()

    private val _knownHost = MutableStateFlow<String?>(null)

    /** 手机最后一次连上时电视的 IP (持久化); 书签 / 已扫的码记的就是它. */
    val knownHost: StateFlow<String?> = _knownHost.asStateFlow()

    private val _hostChanged = MutableStateFlow(false)

    /** 当前 IP 与 [knownHost] 不一致: 已扫过的手机连不上了, 面板标红提示重新扫码. */
    val hostChanged: StateFlow<Boolean> = _hostChanged.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)

    /**
     * 启动时那个二维码弹窗开着没有 (见 [showDialogOnLaunch]); 弹窗由 TV 根组合的 [TvRemoteControlDialogHost] 画.
     * 平时的入口是动作面板右侧常驻的那块码 (TvRemoteQrBlock), 不经这里.
     */
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    fun dismissDialog() {
        _dialogVisible.value = false
    }

    /**
     * 启动弹窗里「启动时不再显示」(同设置-界面里的开关). 写设置放在本对象的作用域里: 调用方随即关掉弹窗,
     * 用弹窗的组合作用域写会被一起取消.
     */
    fun setShowOnLaunch(enabled: Boolean) {
        scope.launch {
            KoinPlatform.getKoin().get<SettingsRepository>().themeSettings.update {
                copy(tvRemoteShowOnLaunch = enabled)
            }
        }
    }

    private val launchPromptDone = AtomicBoolean(false)

    /**
     * 应用启动时 (TV 根组合) 调: 设置里开着「启动时弹出」(`ThemeSettings.tvRemoteShowOnLaunch`, 默认开) 就弹一次二维码弹窗.
     *
     * - 一个进程只弹一次: Activity 重建 (切到别的应用再回来) 不再弹;
     * - 读的是**存下来的**设置, 不是组合里的 LocalThemeSettings —— 启动那一刻后者可能还是默认值, 关过的人会被弹一次;
     * - 等服务起来、拿到地址再弹; 一直拿不到 (没连局域网) 就不弹, 否则一开应用就是一个「无法使用」的弹窗;
     * - 拿到地址后再稍等一下, 让首页先画出来, 弹窗不跟启动画面抢.
     */
    fun showDialogOnLaunch() {
        if (!launchPromptDone.compareAndSet(false, true)) return
        scope.launch {
            val enabled = runCatching {
                KoinPlatform.getKoin().get<SettingsRepository>().themeSettings.flow.first().tvRemoteShowOnLaunch
            }.getOrDefault(false)
            if (!enabled) return@launch
            val address = withTimeoutOrNull(LAUNCH_PROMPT_WAIT) { url.first { it != null } }
            if (address == null) {
                logger.info { "Skip remote control prompt on launch: no LAN address" }
                return@launch
            }
            delay(LAUNCH_PROMPT_DELAY)
            _dialogVisible.value = true
        }
    }

    /**
     * 搜索页登记的「电视当前查询」, 打开网页时用它预填关键词与筛选项 (与电视上看到的一致).
     * 页面离开组合时置回 null; 为 null 时按空查询渲染.
     */
    @Volatile
    var currentQueryProvider: (() -> SubjectSearchQuery)? = null

    /** 保留的播放会话 (播放页不在前台时, 网页上给「在电视上打开播放器」用); TV 根组合登记. */
    @Volatile
    var playbackSessionProvider: (() -> RetainedPlaybackSessionInfo?)? = null

    /** 保留会话进行到哪一步了 (同电视侧边栏图标与动作面板顶行的判据); TV 根组合登记. 手机卡片上显示「已就绪」等. */
    @Volatile
    var playbackStatusProvider: (() -> PlaybackSessionStatus?)? = null

    /** 保留会话正在播的那条缓存 (同电视缓存页删除确认框的判据); TV 根组合登记. 手机「缓存」标签删它时多提示一句. */
    @Volatile
    var playingCacheProvider: (() -> PlayingCacheInfo?)? = null

    /** 日志目录 (与设置 → 日志同一个); TV 根组合登记. 手机「设置」标签底部据此列出日志、直接下载, 见 [RemoteLogs]. */
    @Volatile
    var logsDirProvider: (() -> File?)? = null

    /**
     * 电视上 Ani 的界面在不在前台 (由 [TrackTvRemoteForeground] 维护): 屏保、切到别的应用、息屏时为 false, 网页顶上一条
     * 提示 —— 这时手机上的操作照样生效 (导航、换源都在后台做了), 只是电视画面上看不到, 用户会以为没反应.
     */
    @Volatile
    private var tvForeground = true

    internal fun setTvForeground(foreground: Boolean) {
        if (tvForeground != foreground) logger.info { if (foreground) "TV app back in foreground" else "TV app went to background" }
        tvForeground = foreground
    }

    // 没人收集时不走这条 (见 deliverSearch 里的分支), replay = 0
    private val _submissions = MutableSharedFlow<RemoteSearchSubmission>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 手机提交上来的搜索 (关键词已 trim; 关键词与筛选项至少有一样非空). 搜索页在组合里时走这条. */
    val submissions: SharedFlow<RemoteSearchSubmission> = _submissions.asSharedFlow()

    private val _remoteNavigations = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 网页让电视换了内容 (搜索 / 播放 / 打开详情). 动作面板、Web 控制台二维码弹窗这类独立窗口收到就关掉自己:
     * 页面在底下换了, 它们还挡在上面, 用户回头看电视只看到一个跟刚才操作无关的面板.
     */
    internal val remoteNavigations: SharedFlow<Unit> = _remoteNavigations.asSharedFlow()

    internal fun notifyRemoteNavigation() {
        _remoteNavigations.tryEmit(Unit)
    }

    /**
     * 应用启动时 (TV 根组合) 调一次: 起常驻服务. 重复调用无副作用.
     * @param navigator 搜索页不在场时把电视导航到搜索页 / 把播放器调回前台用.
     */
    fun install(context: Context, navigator: AniNavigator) {
        // 设置-界面里的「重置 Web 控制台地址」(设置页够不到本对象, 经 ui-foundation 的桥)
        TvRemoteSettingsBridge.resetAddress = ::resetAddress
        synchronized(lock) {
            this.navigator = navigator
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            fixedPort = fixedPortFor(context.packageName)
            _knownHost.value = prefs?.getString(KEY_KNOWN_HOST, null)
        }
        scope.launch {
            synchronized(lock) { if (server == null) startLocked() }
            refreshUrl()
        }
    }

    /** 搜索页进入组合时调用; 与 [release] 配对. 顺带重算一次地址 (IP 可能变了). */
    fun acquire() {
        synchronized(lock) { searchPageCount++ }
        scope.launch { refreshUrl() }
    }

    fun release() {
        synchronized(lock) { searchPageCount-- }
    }

    /** 重算一次地址 (电视可能换过网络); 二维码弹窗打开时调. */
    fun refreshAddress() {
        scope.launch { refreshUrl() }
    }

    /** 搜索页进入组合时取走「它不在场时收到的提交」(最多一条), 取走即清. */
    fun takePending(): RemoteSearchSubmission? = synchronized(lock) {
        pendingSearch.also { pendingSearch = null }
    }

    internal fun registerPlayer(handle: RemotePlayerHandle) {
        synchronized(lock) {
            if (handle.background) backgroundPlayer = handle else foregroundPlayer = handle
        }
    }

    internal fun unregisterPlayer(handle: RemotePlayerHandle) {
        synchronized(lock) {
            if (foregroundPlayer === handle) foregroundPlayer = null
            if (backgroundPlayer === handle) backgroundPlayer = null
        }
    }

    /** 电视上保留播放会话的最新一条提示 (准备好了 / 出问题) 与它的序号 (只增); 网页轮询 [PATH_NOTICE] 取. */
    private var noticeSeq = 0L
    private var noticeText: String? = null

    /**
     * TV 根组合收到保留会话的提示时调用 (与电视上 toast 同一份文案). 手机上不管停在哪个标签都会弹出来 ——
     * 这些提示的前提就是电视不在播放页, 用户多半正拿着手机.
     */
    fun postNotice(text: String) {
        synchronized(lock) {
            noticeText = text
            noticeSeq++
        }
    }

    internal fun registerSearchResults(source: RemoteSearchResultsSource) {
        synchronized(lock) { searchResults = source }
    }

    internal fun unregisterSearchResults(source: RemoteSearchResultsSource) {
        synchronized(lock) {
            if (searchResults !== source) return
            lastSearchResults = runCatching { source.snapshot() }.getOrNull()
            searchResults = null
        }
    }

    /**
     * 「重置地址」: 换一个新 token 重启服务 (旧地址立刻失效), 并把当前 IP 记为已知 —— 用户重新扫码后
     * 一切从头开始. 也是「IP 变了」提示的手动确认途径.
     */
    fun resetAddress() {
        scope.launch {
            synchronized(lock) {
                prefs?.edit()?.remove(KEY_TOKEN)?.apply()
                stopLocked()
                startLocked()
            }
            val host = refreshUrl()
            rememberHost(host)
            logger.info { "Remote control address reset" }
        }
    }

    private fun startLocked() {
        val p = prefs ?: return
        val token = p.getString(KEY_TOKEN, null) ?: LanHttpServer.generateToken().also {
            p.edit().putString(KEY_TOKEN, it).apply()
        }
        val s = try {
            LanHttpServer(::handle, port = fixedPort, token = token)
        } catch (e: IOException) {
            logger.warn(e) { "Fixed port $fixedPort unavailable for remote control, falling back to a random port" }
            try {
                LanHttpServer(::handle, port = 0, token = token)
            } catch (e2: IOException) {
                logger.warn(e2) { "Failed to start remote control server" }
                return
            }
        }
        server = s
        serveJob = scope.launch { s.serve() }
        logger.info { "Remote control server started on port ${s.port}" }
    }

    private fun stopLocked() {
        server?.close()
        server = null
        serveJob = null
        _url.value = null
        _phoneConnected.value = false
    }

    /** 重算地址 (本机 IP 可能变了), 返回当前 host. */
    private fun refreshUrl(): String? {
        val s = synchronized(lock) { server } ?: run { _url.value = null; return null }
        val host = findLanAddress()
        val url = host?.let { s.rootUrl(it) }
        _url.value = url
        val known = _knownHost.value
        _hostChanged.value = host != null && known != null && known != host
        // 地址 (含 token) 写进日志: 排查/自动化测试从这里拿完整地址
        logger.info {
            if (url != null) "Remote control reachable at $url (known host: $known)"
            else "Remote control: no LAN address, QR code hidden"
        }
        return host
    }

    private fun rememberHost(host: String?) {
        host ?: return
        if (_knownHost.value != host) {
            _knownHost.value = host
            prefs?.edit()?.putString(KEY_KNOWN_HOST, host)?.apply()
        }
        _hostChanged.value = false
    }

    // ============================ 路由 ============================

    private fun handle(request: LanHttpRequest): LanHttpResponse {
        val path = request.path
        // 状态轮询每秒一次, 不在这里枚举网卡; 其余请求 (打开页面 / 各种提交) 顺带确认手机手里的地址就是当前 IP
        if (path != PATH_PLAYER_STATE && path != PATH_SEARCH_RESULTS && path != PATH_NOTICE && path != PATH_ACCOUNT &&
            !path.startsWith("api/cache") && path != "api/img" && path != "api/source-icon" // 转发的图一屏几十张, 同样不枚举
        ) {
            _phoneConnected.value = true
            rememberHost(findLanAddress())
        }
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return when {
            path.isEmpty() && get -> LanHttpResponse.html(renderPage())
            path == PATH_SEARCH && post -> json(deliverSearch(request))
            path == PATH_SEARCH_HISTORY && get -> json(searchHistory())
            path == PATH_SEARCH_HISTORY_DELETE && post -> json(deleteHistory(request))
            path == PATH_NOTICE && get -> json(noticeState(request))
            path == PATH_SEARCH_RESULTS && get -> searchResultsState(request)
            path == PATH_SEARCH_RESULTS_MORE && post -> json(loadMoreResults())
            path == PATH_SEARCH_RESULTS_RESUME && post -> json(resumeSearch())
            path == PATH_SEARCH_PLAY && post -> json(RemoteSearchResults.play(request, navigator, scope))
            path == PATH_SEARCH_OPEN && post -> json(
                RemoteSearchResults.open(
                    request, navigator, scope,
                    searchResults?.let { runCatching { it.snapshot() }.getOrNull() } ?: lastSearchResults,
                ),
            )
            path == PATH_PLAYER_STATE && get -> playerState(request)
            path == PATH_PLAYER_SELECT && post -> json(selectMedia(request))
            path == PATH_PLAYER_OPEN && post -> json(openPlayer())
            path == PATH_PLAYER_UPNEXT && post -> json(playUpNext())
            path == PATH_PLAYER_REQUEST && post -> json(updateRequest(request))
            path == PATH_PLAYER_CONTROL && post -> json(control(request))
            path == PATH_PLAYER_EPISODE && post -> json(switchEpisode(request))
            path == PATH_PLAYER_DETAILS && post -> json(openDetails(request))
            // 弹幕 (开关 / 偏移 / 手动匹配) 与音轨字幕轨, 见 RemotePlayerExtras
            (post && (path.startsWith("api/player/danmaku/") || path == "api/player/track" ||
                    path == "api/player/comment" || path.startsWith("api/player/review/"))) ||
                    (get && path == "api/player/review") ->
                json(player?.let { RemotePlayerExtras.handle(it, request) } ?: result(false, "电视当前不在播放页"))
            // 不依赖播放页的收藏状态 (手机搜索结果左滑「收藏」), 见 RemotePlayerExtras.subjectCollection
            path == "api/subject/collection" -> json(RemotePlayerExtras.subjectCollection(request))
            // 缓存 (选集 / 挑资源 / 自动批量), 见 RemoteCache
            path == "api/cache" || path.startsWith("api/cache/") ->
                RemoteCache.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 「缓存」标签: 电视上全部缓存一览 (暂停 / 继续 / 删除), 见 RemoteCacheList
            path == "api/caches" || path.startsWith("api/caches/") ->
                RemoteCacheList.handle(request, navigator, scope)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 设置标签「数据源」页里的订阅 (须排在 api/sources 通配之前), 见 RemoteSubscriptions
            path == "api/sources/subs" || path.startsWith("api/sources/subs/") ->
                RemoteSubscriptions.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 「设置」标签顶上的账号 (登录状态 / 用手机登录), 见 RemoteAccount
            path == PATH_ACCOUNT || path.startsWith("$PATH_ACCOUNT/") ->
                RemoteAccount.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 「设置」标签: 代理 / BT tracker 等要打字的设置, 见 RemoteSettings
            path == "api/settings" || path.startsWith("api/settings/") ->
                RemoteSettings.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 设置标签的「数据源」页: 设置里数据源管理那一页的网页版, 见 RemoteSources
            path == "api/sources" || path.startsWith("api/sources/") ->
                RemoteSources.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 「设置」标签底部的日志 (列表 / 下载), 见 RemoteLogs
            path == "api/logs" || path.startsWith("api/logs/") ->
                RemoteLogs.handle(request) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 「本集评论」表情面板的表情目录, 见 RemoteStickers
            path == "api/stickers" && get -> json(RemoteStickers.catalog)
            // 「设置」里的播放记录 (列表 / 打开详情 / 接着播), 见 RemoteHistory
            path == "api/history" || path.startsWith("api/history/") ->
                RemoteHistory.handle(request, navigator, scope)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 缓存标签「挑番缓存」: 在看 / 想看的番 (几集新的、几集没缓存), 见 RemoteCollections
            path == "api/collections" ->
                RemoteCollections.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            // 手机上的 TMDB 图经电视转发 (播放记录的剧照 / 横屏图), 见 RemoteImageProxy
            path == "api/img" && get -> RemoteImageProxy.handle(request)
            // 数据源名字前的图标 (内置源的打包图标 / 源自己配置的图标地址), 见 RemoteSourceIcons
            path == "api/source-icon" && get -> RemoteSourceIcons.handle(request)
            path.isEmpty() || path.startsWith("api/") -> LanHttpResponse.status(405, "Method Not Allowed")
            else -> LanHttpResponse.status(404, "Not Found")
        }
    }

    private fun renderPage(): String {
        val base = currentQueryProvider?.invoke() ?: SubjectSearchQuery("")
        val (searchForm, requestSection) = runBlocking {
            renderRemoteSearchForm(RemoteSearchFormValues.from(base)) to renderPlayerRequestSection()
        }
        // 电视在播放页时默认打开「播放器」, 否则「搜索」; 网页地址里的 #player / #search 优先
        val initialTab = if (player != null) "player" else "search"
        return renderRemoteControlPage(
            initialTab = initialTab,
            searchFormHtml = searchForm,
            requestSectionHtml = requestSection,
            themeCss = RemoteTheme.css(),
        )
    }

    // ---------------------------- 搜索 ----------------------------

    private fun deliverSearch(request: LanHttpRequest): JsonObject {
        val submission = RemoteSearchFormValues.parse(request.formFieldList()).toSubmission()
            ?: return result(false, "请输入关键词或选择筛选项")
        return deliver(submission)
    }

    /**
     * 「结果」标签里电视已离开搜索页时的「让电视回到搜索页」.
     *
     * 先找回原来那个: 从搜索结果点进详情 / 播放器时, 搜索页还在栈里 (按返回就回得去, 结果与翻到的位置都在), 那就弹到它为止
     * (等于替用户按返回, 上面叠着的播放器照常退到后台), 结果面板一回来就重新登记, 接着翻. 栈里没有了 (那个搜索页已经关掉)
     * 才拿最后一份结果的查询再交一次 (同手机提交搜索那条路), 从第一页重新加载.
     */
    private fun resumeSearch(): JsonObject {
        if (searchResults != null) return result(true, "电视已经在搜索页了")
        val nav = navigator
        val existing = nav?.backStack?.lastOrNull { it is NavRoutes.SubjectSearch }
        if (nav != null && existing != null) {
            logger.info { "Remote resume search: popping back to the search page in the back stack" }
            notifyRemoteNavigation()
            scope.launch(Dispatchers.Main) {
                runCatching { nav.popBackStack(existing, inclusive = false) }
                    .onFailure { logger.warn(it) { "Failed to pop back to search page for remote resume" } }
            }
            return result(true, "电视已回到刚才的搜索页")
        }
        val submission = lastSearchResults?.query?.let { RemoteSearchFormValues.from(it).toSubmission() }
            ?: return result(false, "没有可以恢复的搜索，重新搜一次吧")
        logger.info { "Remote resume search: no search page in the back stack, searching again" }
        return deliver(submission, okMessage = "刚才的搜索页已经关了，电视重新搜索")
    }

    /** 把一次搜索交给电视: 搜索页在场就直接换查询, 不在就把搜索页叠上去. @param okMessage 成功时的提示, 默认「已发送到电视」 */
    private fun deliver(submission: RemoteSearchSubmission, okMessage: String? = null): JsonObject {
        synchronized(lock) { awaitingSearch = submission to TimeSource.Monotonic.markNow() }
        // 搜索页已在场时也关: 面板盖在搜索页上, 结果换了也看不见
        notifyRemoteNavigation()
        val nav = navigator
        val inPage = synchronized(lock) { searchPageCount > 0 }
        logger.info {
            "Remote search received: ${submission.keywords.length} chars, sort=${submission.sort}, " +
                    "minRating=${submission.minRating}, tags=${submission.tags.size}, pageInComposition=$inPage"
        }
        val sent = okMessage ?: if (submission.keywords.isNotEmpty()) "已发送到电视：${submission.keywords}" else "已发送到电视"
        if (inPage) {
            _submissions.tryEmit(submission)
            return result(true, sent)
        }
        if (nav == null) return result(true, sent)
        val onPlayer = nav.backStack.lastOrNull() is NavRoutes.EpisodeDetail
        synchronized(lock) { pendingSearch = submission }
        scope.launch(Dispatchers.Main) {
            // 电视在播放页也直接把搜索页叠上去, 与播放器内嵌详情页点标签同一种方式 (navigateSubjectSearch):
            // 播放器留在栈里 (被盖住时照常暂停), 看完结果按返回就回到播放器
            runCatching { nav.navigateSubjectSearch() }
                .onFailure { logger.warn(it) { "Failed to navigate to search page for remote input" } }
        }
        return result(true, if (onPlayer) "已在电视上打开搜索，按返回可回到播放器" else sent)
    }

    /**
     * 搜索框下拉里的搜索记录: 与电视搜索页同一份 (手机搜过的也由搜索页照常记进去). 取最近 [HISTORY_LIMIT] 条,
     * 过滤交给网页 (按输入的字做包含匹配).
     */
    private fun searchHistory(): JsonObject {
        val items = runCatching { runBlocking { historyRepository().recentHistory(HISTORY_LIMIT) } }
            .onFailure { logger.warn(it) { "Failed to load search history for remote control" } }
            .getOrDefault(emptyList())
        return buildJsonObject { putJsonArray("items") { items.forEach { add(it) } } }
    }

    private fun deleteHistory(request: LanHttpRequest): JsonObject {
        val q = request.formFields()["q"].orEmpty()
        if (q.isEmpty()) return result(false, "无效的记录")
        runCatching { runBlocking { historyRepository().removeHistory(q) } }
            .onFailure {
                logger.warn(it) { "Failed to delete search history from remote control" }
                return result(false, "删除失败")
            }
        return result(true, "")
    }

    private fun historyRepository(): SubjectSearchHistoryRepository = KoinPlatform.getKoin().get()

    /**
     * 「结果」标签的状态: 电视搜索页结果面板的快照 (离场后用最后一份). 网页每秒轮询, 带版本号, 没变只回 same.
     */
    private fun searchResultsState(request: LanHttpRequest): LanHttpResponse {
        val source = searchResults
        val snapshot = source?.let { runCatching { it.snapshot() }.getOrNull() } ?: lastSearchResults
        val pending = synchronized(lock) {
            val (submission, submittedAt) = awaitingSearch ?: return@synchronized false
            // 查询换上之后, 新的分页要过一会儿才开始刷新 (这期间还是旧条目): 看到刷新开始、或换上已有一阵才放行
            val applied = snapshot != null && RemoteSearchResults.matches(submission, snapshot.query) &&
                    (snapshot.refreshing || submittedAt.elapsedNow() > AWAIT_REFRESH_GRACE)
            val done = applied || submittedAt.elapsedNow() > AWAIT_RESULTS_TIMEOUT
            if (done) awaitingSearch = null
            !done
        }
        val content = RemoteSearchResults.stateJson(snapshot, live = source != null, pending = pending)
        val version = content.toString().hashCode().toString()
        if (request.queryParam("v") == version) {
            return jsonResponse(
                buildJsonObject {
                    put("v", version)
                    put("same", true)
                },
            )
        }
        return jsonResponse(JsonObject(content + ("v" to JsonPrimitive(version))))
    }

    /**
     * 有没有比 `after` 更新的提示. 不带 `after` (网页刚打开) 只回当前序号当基线, 不回文案 —— 否则每次打开页面
     * 都会弹一条早就过去的旧提示. 顺带 `away`: 电视上 Ani 不在前台 (见 [tvForeground]), 网页顶上挂一条提示.
     */
    private fun noticeState(request: LanHttpRequest): JsonObject {
        val after = request.queryParam("after")?.toLongOrNull()
        val (seq, text) = synchronized(lock) { noticeSeq to noticeText }
        return buildJsonObject {
            put("seq", seq)
            if (after != null && after < seq && text != null) put("text", text)
            put("away", !tvForeground)
        }
    }

    /** 手机上翻到底 / 点「加载更多」: 让电视的结果列表加载下一页. */
    private fun loadMoreResults(): JsonObject {
        val source = searchResults ?: return result(false, "电视已离开搜索页，回到搜索页后可以继续加载")
        source.loadMore()
        return result(true, "")
    }

    // ---------------------------- 播放器 ----------------------------

    /**
     * 播放器状态. 网页每秒轮询一次, 带着上次拿到的版本号 `v`; 内容没变就只回 `{"same": true}` —— BT 源的
     * 候选可能有几百条, 每秒整份重发太浪费.
     */
    private fun playerState(request: LanHttpRequest): LanHttpResponse {
        val handle = player
        val content = if (handle != null) {
            // 网页上的下拉筛选与「显示被排除的」随轮询带过来 (空 = 不筛), 见 RemoteMediaFilter
            val filter = RemoteMediaFilter(
                resolution = request.queryParam("res")?.takeIf { it.isNotEmpty() },
                subtitle = request.queryParam("sub")?.takeIf { it.isNotEmpty() },
                alliance = request.queryParam("all")?.takeIf { it.isNotEmpty() },
                showExcluded = request.queryParam("ex") == "1",
                fullSource = request.queryParam("full")?.takeIf { it.isNotEmpty() },
            )
            val base = handle.stateJson(filter)
            // 后台会话: 附上它进行到哪一步了 (已就绪 / 准备中 / 出问题), 手机卡片上直接看得出来
            val session = if (handle.background) sessionStatusJson() else null
            if (session != null) JsonObject(base + ("session" to session)) else base
        } else {
            val session = playbackSessionProvider?.invoke()
            // 什么都没在播时给「接下来播放」(同动作面板那张卡, 见 TvUpNextStore), 手机上点一下直接进播放页
            val upNext = if (session == null) TvUpNextStore.target else null
            buildJsonObject {
                put("available", false)
                put("reason", if (session != null) "background" else "none")
                if (session != null) put("title", session.subjectTitle)
                if (session != null) sessionStatusJson()?.let { put("session", it) }
                if (upNext != null) putJsonObject("upNext") {
                    put("subjectId", upNext.subjectId)
                    put("title", upNext.subjectTitle)
                    put("episode", if (upNext.episodeSort.isNotEmpty()) "第 ${upNext.episodeSort} 话" else "")
                    put("continuing", upNext.continuing)
                    put("position", upNext.positionMillis)
                    put("duration", upNext.durationMillis)
                    putEpisodeArt(upNext.subjectId, upNext.episodeId)
                }
            }
        }
        val version = content.toString().hashCode().toString()
        // 播放状态每次都附上, 但不参与版本号 (位置每秒都变, 见 RemotePlayerHandle.playbackJson)
        // 手机上「播放信息」展开着才带 stats=1: 这时电视才开始采集 (见 RemotePlayerHandle.statsWanted)
        val wantStats = request.queryParam("stats") == "1"
        if (wantStats) handle?.requestStats()
        val playback = handle?.playbackJson(includeStats = wantStats)
        val body = if (request.queryParam("v") == version) {
            buildJsonObject {
                put("v", version)
                put("same", true)
                if (playback != null) put("playback", playback)
            }
        } else {
            val extra = buildMap {
                put("v", JsonPrimitive(version))
                if (playback != null) put("playback", playback)
            }
            JsonObject(content + extra)
        }
        return jsonResponse(body)
    }

    private fun selectMedia(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, "电视当前不在播放页")
        val id = request.formFields()["id"].orEmpty()
        handle.select(id)?.let { return result(false, it) }
        return result(
            true,
            if (handle.background) "已切换，电视在后台加载，回到播放器即可播放" else "已切换，电视正在加载",
        )
    }

    private fun updateRequest(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, "电视当前不在播放页")
        val fields = request.formFieldList()
        fun field(name: String) = fields.lastOrNull { it.first == name }?.second.orEmpty()
        if (field("reset") == "1") {
            return handle.resetRequest()?.let { result(false, it) }
                ?: result(true, "已恢复默认查询条件，正在重新搜索")
        }
        val error = handle.updateRequest(
            primary = field("primary"),
            others = field("others").lines(),
            sort = field("sort"),
            ep = field("ep"),
        )
        return if (error != null) result(false, error) else result(true, "已保存，正在重新搜索所有数据源")
    }

    private fun control(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, "电视当前不在播放页")
        // 后台会话被按住暂停 (见本类 KDoc), 这里放行只会被立刻按回去
        if (handle.background) return result(false, "电视未在播放页，播放控制不可用")
        val fields = request.formFields()
        val action = fields["action"].orEmpty()
        // 拖进度条 / 输入时间点: 跳到 ms (服务端夹在片长以内)
        if (action == "seek") {
            val ms = fields["ms"]?.toLongOrNull() ?: return result(false, "无效的时间")
            handle.seekTo(ms)
            return result(true, "")
        }
        // 拖音量条: v = 0~1 (播放器音量, 见 RemotePlayerHandle.setVolume)
        if (action == "volume") {
            val v = fields["v"]?.toFloatOrNull()?.takeIf { !it.isNaN() } ?: return result(false, "无效的音量")
            return if (handle.setVolume(v)) result(true, "") else result(false, "这个播放器不支持调音量")
        }
        // 成功不给提示文案: 按钮的效果电视上看得见, 手机上的播放状态也会马上刷新
        return if (handle.control(action)) result(true, "") else result(false, "未知操作")
    }

    private fun switchEpisode(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, "电视当前不在播放页")
        val id = request.formFields()["id"]?.toIntOrNull() ?: return result(false, "无效的剧集")
        handle.switchEpisode(id)?.let { return result(false, it) }
        return result(
            true,
            if (handle.background) "已切换，电视在后台加载，回到播放器即可播放" else "已切换，电视正在加载",
        )
    }

    /**
     * 手机上点「接下来播放」: 把电视带进播放页播那一集, 同动作面板上按卡片主体那一下. 位置由播放器自己按
     * episodeId 从播放进度表续, 这里只管导航. 以电视此刻的 [TvUpNextStore.target] 为准, 不信手机页面上那份.
     */
    private fun playUpNext(): JsonObject {
        if (player != null) return result(false, "电视已经在播放了")
        val target = TvUpNextStore.target ?: return result(false, "没有可播放的内容")
        val nav = navigator ?: return result(false, "电视还没准备好")
        notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            runCatching { nav.navigateEpisodeDetails(target.subjectId, target.episodeId, force = true) }
                .onFailure { logger.warn(it) { "Failed to start up-next playback for remote control" } }
        }
        return result(true, "已在电视上开始播放")
    }

    /**
     * 手机上点播放器卡片 (或「接下来播放」卡) 的剧名: 电视打开这部的详情页. 在播放页时叠在播放器上面 (同内嵌标签跳转),
     * 按返回回到播放器. 剧名当占位先画出来, 不必等详情加载.
     */
    private fun openDetails(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val id = fields["id"]?.toIntOrNull() ?: return result(false, "无效的条目")
        val nav = navigator ?: return result(false, "电视还没准备好")
        val placeholder = SubjectDetailPlaceholder(id = id, name = fields["title"].orEmpty())
        val onPlayer = foregroundPlayer != null
        notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            runCatching { nav.navigateSubjectDetails(id, placeholder) }
                .onFailure { logger.warn(it) { "Failed to open subject details from remote player card" } }
        }
        return result(true, if (onPlayer) "已在电视上打开详情页，按返回可回到播放器" else "已在电视上打开详情页")
    }

    /** [playEpisode] 的结果: 开了播放页 / 在当前播放页里换了集 / 本来就在播这一集. */
    internal enum class RemotePlayResult { Opened, Switched, AlreadyPlaying }

    /**
     * 手机上「播放某部番的某一集」(缓存标签点一集 / 播放记录点 ▶) 统一走这里: 电视前台的播放页就是这部番时直接换集
     * (同选集下拉框), 不再在上面叠一个新的播放页; 不是这部番 / 这一集不在它的选集列表里, 照旧打开播放页.
     */
    internal fun playEpisode(
        navigator: AniNavigator,
        uiScope: CoroutineScope,
        subjectId: Int,
        episodeId: Int,
        onNavigateFailure: (Throwable) -> Unit,
    ): RemotePlayResult {
        // 换集也算网页让电视换了内容: 挡在上面的动作面板 / 二维码弹窗照样收起
        notifyRemoteNavigation()
        val fg = foregroundPlayer
        if (fg != null && fg.vm.subjectId == subjectId) {
            if (fg.page?.episodePresentation?.episodeId == episodeId) return RemotePlayResult.AlreadyPlaying
            if (fg.switchEpisode(episodeId) == null) {
                logger.info { "Remote play: switching episode in the current player, subject $subjectId episode $episodeId" }
                return RemotePlayResult.Switched
            }
        }
        uiScope.launch(Dispatchers.Main) {
            runCatching { navigator.navigateEpisodeDetails(subjectId, episodeId) }.onFailure(onNavigateFailure)
        }
        return RemotePlayResult.Opened
    }

    private fun openPlayer(): JsonObject {
        if (foregroundPlayer != null) return result(true, "电视已在播放页")
        val session = playbackSessionProvider?.invoke() ?: return result(false, "电视上没有正在播放的内容")
        val nav = navigator ?: return result(false, "电视还没准备好")
        notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            // force: 回到已经在播的这一集, 跳过一起看跟随模式的导航守卫 (同动作面板的「回到正在播放」)
            runCatching { nav.navigateEpisodeDetails(session.subjectId, session.episodeId, force = true) }
                .onFailure { logger.warn(it) { "Failed to bring player to front for remote control" } }
        }
        return result(true, "已在电视上打开播放器")
    }

    /**
     * 保留会话的状态, 给手机卡片那一条: `kind` 决定颜色 (ready 绿 / busy 灰 / attention 黄 / error 红), `label` 是粗体短语,
     * `text` 是跟在后面的一句说明. 判据与电视侧边栏图标、动作面板顶行同一套 ([PlaybackSessionStatus]);
     * 失败的具体原因在电视画面上看, 这里只说下一步能做什么.
     */
    private fun sessionStatusJson(): JsonObject? {
        val status = playbackStatusProvider?.invoke() ?: return null
        val (kind, label, text) = when (status) {
            PlaybackSessionStatus.Ready -> Triple("ready", "已就绪", "回到播放器就能接着看")
            PlaybackSessionStatus.Preparing -> Triple("busy", "准备中", "正在查找数据源、解析播放地址")
            PlaybackSessionStatus.Buffering -> Triple("busy", "缓冲中", "马上就好")
            PlaybackSessionStatus.NeedsSelection -> Triple("attention", "等你选数据源", "在下面挑一个就会开始加载")
            PlaybackSessionStatus.NoMedia -> Triple("error", "没有可播放的资源", "可以试试修改查询条件")
            PlaybackSessionStatus.PlayerError -> Triple("error", "播放出错", "可以在下面换一个数据源")
            is PlaybackSessionStatus.LoadFailed -> Triple("error", "加载失败", "可以在下面换一个数据源")
        }
        return buildJsonObject {
            put("kind", kind)
            put("label", label)
            put("text", text)
        }
    }

    // ---------------------------- 工具 ----------------------------

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private fun json(obj: JsonObject): LanHttpResponse = jsonResponse(obj)

    private fun jsonResponse(obj: JsonObject): LanHttpResponse =
        LanHttpResponse.bytes(obj.toString().toByteArray(), "application/json; charset=utf-8")

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private const val PATH_SEARCH = "api/search"
    private const val PATH_SEARCH_HISTORY = "api/search/history"
    private const val PATH_SEARCH_HISTORY_DELETE = "api/search/history/delete"
    private const val PATH_SEARCH_RESULTS = "api/search/results"
    private const val PATH_SEARCH_RESULTS_MORE = "api/search/results/more"
    private const val PATH_SEARCH_RESULTS_RESUME = "api/search/results/resume"
    private const val PATH_SEARCH_PLAY = "api/search/play"
    private const val PATH_SEARCH_OPEN = "api/search/open"

    /** 保留会话的提示 (网页每 2 秒轮询, 不刷 knownHost). */
    private const val PATH_NOTICE = "api/notice"

    /** 账号状态 (等授权时网页每 2 秒轮询, 不刷 knownHost), 见 RemoteAccount. */
    private const val PATH_ACCOUNT = "api/account"

    /** 提交后电视换上新查询、新结果开始刷新一般在这之内; 超过就不再等「刷新开始」这个信号. */
    private val AWAIT_REFRESH_GRACE = 1.5.seconds

    /** 电视一直没换上手机提交的查询 (比如用户在电视上又改了): 不再显示「正在搜索」. */
    private val AWAIT_RESULTS_TIMEOUT = 10.seconds

    /** 下拉最多从这么多条记录里挑 (显示时再按输入过滤、取前 8 条). */
    private const val HISTORY_LIMIT = 50
    private const val PATH_PLAYER_STATE = "api/player"
    private const val PATH_PLAYER_SELECT = "api/player/select"
    private const val PATH_PLAYER_OPEN = "api/player/open"
    private const val PATH_PLAYER_UPNEXT = "api/player/upnext"
    private const val PATH_PLAYER_REQUEST = "api/player/request"
    private const val PATH_PLAYER_CONTROL = "api/player/control"
    private const val PATH_PLAYER_EPISODE = "api/player/episode"
    private const val PATH_PLAYER_DETAILS = "api/player/details"

    /**
     * 固定端口按包名分开: release 与 debug 装在同一台电视上时是两个进程, 抢同一个端口的话后启动的那个只能退到
     * 随机端口, 每次启动地址都变, 书签就废了. 正式包保持 [RELEASE_PORT] (升级前加的书签不受影响), 默认 debug 包
     * 用 [DEBUG_PORT], 其余自定义包名在其后几个里按包名稳定地取一个. 都在冷门段, 与 torrent 诊断口 (6890) /
     * OAuth 回环 (41890) 错开. 被占时仍退到随机端口, 面板会印实际地址.
     */
    private fun fixedPortFor(packageName: String): Int = when {
        packageName == RELEASE_PACKAGE -> RELEASE_PORT
        packageName.endsWith(".debug2") -> DEBUG_PORT
        else -> OTHER_PORT_BASE + Math.floorMod(packageName.hashCode(), OTHER_PORT_COUNT)
    }

    /** 启动时弹二维码: 最多等这么久拿地址 (服务刚起), 拿到后再等这么久让首页先画出来. 见 [showDialogOnLaunch]. */
    private val LAUNCH_PROMPT_WAIT = 10.seconds
    private val LAUNCH_PROMPT_DELAY = 1.seconds

    private const val RELEASE_PACKAGE = "me.him188.ani.tv"
    private const val RELEASE_PORT = 41892
    private const val DEBUG_PORT = 41893
    private const val OTHER_PORT_BASE = 41894
    private const val OTHER_PORT_COUNT = 5

    // 沿用「搜索输入」时代的存储名与键: 升级后 token 不变, 手机上已加的书签照样能用
    private const val PREFS_NAME = "tv_remote_search_input"
    private const val KEY_TOKEN = "token"
    private const val KEY_KNOWN_HOST = "known_host"
}

/**
 * TV 根组合里调一次: 把 Ani 的界面在不在前台 (Activity 在 onStart 与 onStop 之间) 告诉 Web 控制台. 屏保 (有的屏保是个普通
 * Activity, 直接盖在上面) / 切到别的应用 / 息屏时 Ani 退到后台, 手机上的操作照样生效, 只是电视画面上看不到 —— 网页据此
 * 顶上一条提示, 免得用户以为没反应. 组合离场 (Activity 销毁了, 进程和服务还在) 也算不在前台.
 */
@Composable
fun TrackTvRemoteForeground() {
    LifecycleStartEffect(Unit) {
        TvRemoteControl.setTvForeground(true)
        onStopOrDispose { TvRemoteControl.setTvForeground(false) }
    }
}
