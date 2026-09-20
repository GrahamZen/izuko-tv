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
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import me.him188.ani.app.ui.foundation.lan.lanInterfacesSummary
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import me.him188.ani.app.ui.foundation.playback.PlayingCacheInfo
import me.him188.ani.app.ui.foundation.playback.RetainedPlaybackSessionInfo
import me.him188.ani.app.ui.main.TvUpNextStore
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.app.domain.episode.GetAnimeSeasonIdsFlowUseCase
import me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh
import org.koin.mp.KoinPlatform
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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

    /** 切到电视前台 / 查授权 / 开授权页用, 见 [frontGranted]. */
    @Volatile
    private var appContext: Context? = null
    /** 当前界面的导航; 界面销毁时清掉 ([detachNavigator]), 之后要界面的操作先拉起 Ani (见 [awaitUi]). */
    @Volatile
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
     * 网页上关掉启动弹窗 —— 扫完码人拿着手机, 不用再去找遥控器按「关闭». 弹窗开着时网页顶上有一条「电视上的二维码弹窗还开着
     * [关闭]」(开没开见 [noticeState] 的 `launchDialog`), 用户点了才关; 勾了「以后这台手机打开就自动关闭」(记在手机上) 的,
     * 网页一打开就带 `auto=1` 来关, 这时先留一小会儿 (这次请求已经把状态点成「手机已连接」), 让电视上看得到连上了再关.
     * 用户已经自己关了 (或没弹) 就什么都不做. 回 `closed`: 网页据此提示一句以后去哪找码 (弹窗里那句随它关掉就看不到了).
     */
    private fun closeLaunchDialog(request: LanHttpRequest): JsonObject {
        if (!_dialogVisible.value) return buildJsonObject { put("closed", false) }
        if (request.formFields()["auto"] == "1") {
            scope.launch {
                delay(LAUNCH_DIALOG_CLOSE_DELAY)
                _dialogVisible.value = false
            }
        } else {
            _dialogVisible.value = false
        }
        return buildJsonObject { put("closed", true) }
    }

    // ---------------------------- 切到电视前台 ----------------------------

    /**
     * 电视上 Ani 不在前台 (屏保 / 别的应用) 时, 手机上的操作把它切到前台. Android 10 起后台应用不能自己起界面 (前台服务也不算),
     * 唯一的通用豁免是「显示在其他应用的上层」(SYSTEM_ALERT_WINDOW, 电视设置 → 应用 → 特殊应用权限 里授权); Android 9 及以下不用.
     * 只对用 Web 控制台的人有意义, 所以是网页设置里的开关, 默认关, 记在电视上 (几台手机共用). 开着且授了权时, 每次网页让电视
     * 换页面 ([notifyRemoteNavigation]) 顺带切过来; 网页顶上「不在前台」那一条也给一个手动的「切到 Ani」([manualFront]).
     * 息屏 / 待机时点亮屏幕、切 HDMI 输入这些第三方应用做不到.
     */
    private fun frontEnabled(): Boolean = prefs?.getBoolean(KEY_BRING_TO_FRONT, false) == true

    private fun frontGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val ctx = appContext ?: return false
        return Settings.canDrawOverlays(ctx)
    }

    /**
     * 电视上开着 VPN 没有 (任一网络带 VPN 传输). 只用来在二维码下面给更具体的排障提示、写日志: 电视的 VPN 一般只接管电视
     * 自己往外发的流量, 手机连进来的连接照常从 Wi-Fi 回, 连不上多半是手机的 VPN 没开「绕过局域网」.
     */
    internal fun tvVpnActive(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        @Suppress("DEPRECATION") // allNetworks: 要看的是有没有 VPN 在, 不只是默认网络
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(false)
    }

    private fun bringToFront(): Boolean {
        val ctx = appContext ?: return false
        // 休眠 / 屏保中先点亮屏幕: 不然界面在黑屏后面起来又立刻被系统停掉 (实测 Shield 休眠时 resume 后 30ms 就 stop)
        val woke = wakeScreen(ctx)
        val ok = launchSelf(ctx)
        if (ok && woke) wakeFrontUntil = System.currentTimeMillis() + WAKE_FRONT_GUARD.inWholeMilliseconds
        return ok
    }

    private fun launchSelf(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(ctx.packageName) ?: pm.getLaunchIntentForPackage(ctx.packageName) ?: return false
        // 同点桌面图标: 已有的任务整个挪到前台, 不新建页面
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }
            .onSuccess { logger.info { "Brought TV app to front for remote control" } }
            .onFailure { logger.warn(it) { "Failed to bring TV app to front" } }
            .getOrDefault(false)
    }

    /**
     * 刚把电视从休眠里叫醒并拉起 Ani 的这几秒. Google TV 桌面在休眠时先塞一个 BootHelperEmptyActivity, 亮屏后它再把桌面主页
     * 拉到最前面; 它比我们晚到就把 Ani 压下去 (实测 Shield: 我们拉起后 0.2 秒桌面 MainActivity 顶上来), 网页上的搜索一直等,
     * 再发一次才好. 这段时间里 Ani 被切到后台就再拉一次, 只补一次 (见 setTvForeground), 免得和按遥控器的人抢.
     */
    @Volatile
    private var wakeFrontUntil = 0L
    private val WAKE_FRONT_GUARD = 6.seconds
    private val WAKE_FRONT_RETRY_DELAY = 300.milliseconds

    /**
     * 点亮屏幕 (同按遥控器): 休眠时亮屏, 屏保中退出屏保, 本来就亮着没影响. 用带 ACQUIRE_CAUSES_WAKEUP 的短唤醒锁 (应用能用的
     * 唤醒办法; WAKE_LOCK 权限本来就有), 3 秒后放掉, 之后照常按系统的息屏时间走. Shield 醒来时会经 HDMI-CEC 把电视也切过来
     * (取决于两台的 CEC 设置, 不归这里管).
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK / ACQUIRE_CAUSES_WAKEUP: 普通应用唤醒屏幕仍只有这条路
    /** @return 这次是不是我们把屏幕叫醒的 (原本就亮着 = false) */
    private fun wakeScreen(ctx: Context): Boolean {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        val wasOn = power.isInteractive
        runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "ani:remote-wake",
            ).acquire(REMOTE_WAKE_HOLD)
        }.onFailure { logger.warn(it) { "Failed to wake screen for remote control" } }
        logger.info { "Remote wake: screen was ${if (wasOn) "on" else "off"}, now interactive=${power.isInteractive}" }
        return !wasOn
    }

    /** 网页设置里「切到电视前台」那张卡片的状态, 见 RemoteSettings.state. */
    internal fun frontState(): JsonObject = buildJsonObject {
        put("enabled", frontEnabled())
        put("granted", frontGranted())
        put("needsPermission", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    internal fun setBringToFront(on: Boolean): JsonObject {
        prefs?.edit()?.putBoolean(KEY_BRING_TO_FRONT, on)?.apply()
        val granted = frontGranted()
        // 开的时候还没授权: Ani 正在前台就直接打开授权页 (在后台时系统同样拦着打不开)
        val opened = on && !granted && tvForeground && openOverlaySettings()
        // 没能当场打开 (Ani 在后台): 记下时间, [FRONT_AUTH_WINDOW] 内 Ani 回到前台就直接打开授权页 (只一次), 见 scheduleFrontAuth
        val editor = prefs?.edit()
        if (on && !granted && !opened) editor?.putLong(KEY_FRONT_AUTH_AT, System.currentTimeMillis()) else editor?.remove(KEY_FRONT_AUTH_AT)
        editor?.apply()
        logger.info { "Remote bring-to-front ${if (on) "enabled" else "disabled"} (granted=$granted, settingsOpened=$opened)" }
        val message = when {
            !on -> tr("已关闭")
            granted -> tr("已开启")
            opened -> tr("授权页已打开。请允许 Animeko「显示在其他应用的上层」，然后返回 Ani。")
            else -> tr("请在 30 分钟内回到电视上的 Ani，并完成授权。")
        }
        return JsonObject(frontState() + ("ok" to JsonPrimitive(true)) + ("message" to JsonPrimitive(message)))
    }

    /** 网页顶上「不在前台」那一条里的「切到 Ani」. */
    private fun manualFront(): JsonObject {
        if (tvForeground) return result(true, tr("Ani 已在电视上显示"))
        if (!frontGranted()) return result(false, tr("尚未授权。请在电视设置中为 Animeko 开启「显示在其他应用的上层」。"))
        return if (bringToFront()) result(true, tr("已打开 Ani")) else result(false, tr("无法打开 Ani，请在电视上手动打开。"))
    }

    /** 打开系统的「显示在其他应用的上层」授权页 (本应用那一项; 有的电视是整张应用列表). Ani 在后台时系统会拦. */
    private fun openOverlaySettings(): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.onFailure { logger.warn(it) { "Failed to open overlay permission settings" } }.getOrDefault(false)
    }

    /**
     * 手机上开「切到电视前台」时 Ani 在后台、授权页没能当场打开: Ani 回到前台 (或启动时的二维码弹窗关掉) 时直接打开授权页.
     * 用户是主动开的、手机上也说了回来会弹, 不想开按返回就回到 Ani, 不再夹一个自己的「去授权 / 以后再说」弹窗.
     * 只跳一次 (跳没跳成都清掉); 在手机上打开开关之后超过 [FRONT_AUTH_WINDOW] 才回来的不跳 —— 隔了几个小时 / 换了个人,
     * 一开 Ani 就进系统设置页会莫名其妙, 这时网页顶上那一条里的「怎么授权」照样在. 稍等一下再开: 让回来的页面先画出来,
     * 也看看启动时的二维码弹窗会不会紧接着弹 (它开着时不打开, 等它关).
     */
    private fun scheduleFrontAuth() {
        if ((prefs?.getLong(KEY_FRONT_AUTH_AT, 0L) ?: 0L) == 0L) return
        scope.launch {
            delay(FRONT_AUTH_DELAY)
            if (!tvForeground || _dialogVisible.value) return@launch
            val p = prefs ?: return@launch
            // 取出即清: 回到前台与弹窗关掉可能同时触发, 只让一个去开
            val requestedAt = synchronized(lock) {
                p.getLong(KEY_FRONT_AUTH_AT, 0L).also { if (it != 0L) p.edit().remove(KEY_FRONT_AUTH_AT).apply() }
            }
            if (requestedAt == 0L) return@launch
            val age = System.currentTimeMillis() - requestedAt
            if (!frontEnabled() || frontGranted() || age > FRONT_AUTH_WINDOW.inWholeMilliseconds) {
                logger.info { "Skip overlay permission page requested ${age}ms ago (enabled=${frontEnabled()}, granted=${frontGranted()})" }
                return@launch
            }
            logger.info { "Opening overlay permission page requested from remote control ${age}ms ago" }
            openOverlaySettings()
        }
    }

    // ---------------------------- 退出 Ani 后保留 Web 控制台 ----------------------------

    /**
     * 网页设置里的「退出 Ani 后保留 Web 控制台」(默认关, 记在电视上): 开着时按返回退出 Ani 只收界面与 BT 服务, 进程与网页服务留着
     * (见 app 模块的 exitTvApp; 以前退出一律 System.exit, 手机随即连不上); 并起一个前台服务把进程优先级抬高 —— 只剩网页服务的
     * 后台进程在别的应用要内存时最先被回收. 前台服务在 app 模块 (RemoteKeepAliveService), 经本回调起停.
     */
    @Volatile
    var keepAliveService: ((Boolean) -> Unit)? = null

    fun keepAliveOnExit(): Boolean = prefs?.getBoolean(KEY_KEEP_ALIVE, false) == true

    /** 常驻服务被系统重启时 (还没 [ensureStarted]) 用: 直接读存下来的开关. */
    fun keepAliveEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ALIVE, false)

    /** 进程起来的时刻: 启动风暴期间不碰常驻服务, 见 [syncKeepAlive]. */
    private val processStart = System.currentTimeMillis()

    /**
     * 开着就趁 Ani 在前台起常驻服务 (Android 12 起后台起不了前台服务); 重复起无副作用.
     *
     * **刚启动的一段时间内要等**: 前台服务必须在 `startForegroundService` 之后 10 秒内 `startForeground`,
     * 而它的 `onStartCommand` 是在主线程排队的 —— 冷启动 (尤其刚装完、还没 AOT 的包) 主线程能忙十几秒, 排不上
     * 就被系统按 `RemoteServiceException` 杀掉, 而 START_STICKY 又会把它拉起来接着崩
     * (2026-09-15 真机: 装完包第一次启动连崩三次). 用户在网页上主动开这个开关时进程早就起来了, 不受影响.
     */
    private fun syncKeepAlive() {
        if (!keepAliveOnExit() || !tvForeground) return
        val since = System.currentTimeMillis() - processStart
        if (since < KEEP_ALIVE_START_DELAY.inWholeMilliseconds) {
            scope.launch {
                delay(KEEP_ALIVE_START_DELAY.inWholeMilliseconds - since)
                if (keepAliveOnExit() && tvForeground) keepAliveService?.invoke(true)
            }
            return
        }
        keepAliveService?.invoke(true)
    }

    /** 网页设置里那张卡片的状态, 见 RemoteSettings.state. */
    internal fun keepState(): JsonObject = buildJsonObject { put("enabled", keepAliveOnExit()) }

    internal fun setKeepAlive(on: Boolean): JsonObject {
        prefs?.edit()?.putBoolean(KEY_KEEP_ALIVE, on)?.apply()
        if (on) syncKeepAlive() else keepAliveService?.invoke(false)
        logger.info { "Remote keep-alive ${if (on) "enabled" else "disabled"} (tvForeground=$tvForeground)" }
        // Ani 在后台时开: 常驻服务起不来 (Android 12 起), 要等它回到前台 —— 说清楚, 免得以为这时休眠已经不会断
        val message = when {
            !on -> tr("已关闭")
            tvForeground -> tr("已开启")
            else -> tr("已开启。下次打开 Ani 后，电视休眠时也能保持连接；在此之前，电视休眠仍会断开。")
        }
        return JsonObject(keepState() + ("ok" to JsonPrimitive(true)) + ("message" to JsonPrimitive(message)))
    }

    /**
     * 要界面的网页操作 ([UI_PATHS]) 进来时界面不在 (退出后进程留着 / 被回收后常驻服务把监听重启了): 开了「切到电视前台」且授了权
     * 就把 Ani 拉起来, 等新界面装上 ([install] 换上新的 navigator) 再往下走; 拉不起来返回 false, 调用方明说.
     */
    private fun awaitUi(): Boolean {
        if (navigator != null) return true
        if (!(frontEnabled() && frontGranted())) return false
        logger.info { "Remote control needs the TV UI but it is gone, bringing the app to front" }
        if (!bringToFront()) return false
        val deadline = System.nanoTime() + UI_WAIT.inWholeNanoseconds
        while (navigator == null && System.nanoTime() < deadline) Thread.sleep(100)
        return navigator != null
    }

    /**
     * 播放器上的操作进来时 Ani 不在前台 (休眠 / 屏保 / 切到别的应用): 开了「切到电视前台」且授了权就叫回来, 在前台站稳了再往下走.
     * 刚从休眠叫醒的要多等一下: 桌面还会顶上来一次再被 [wakeFrontUntil] 那边拉回 (见 bringToFront), 在那之前按下的暂停 / 播放
     * 会被随后的 stop 盖掉. 没开 / 没授权 / 等不到就原样往下走, 由调用方看 [tvForeground] 决定怎么回.
     */
    private fun awaitFront() {
        if (tvForeground || !(frontEnabled() && frontGranted())) return
        logger.info { "Remote player action while TV app is in background, bringing it to front" }
        if (!bringToFront()) return
        val woke = System.currentTimeMillis() < wakeFrontUntil
        val deadline = System.nanoTime() + FRONT_WAIT.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (tvForeground && (!woke || System.currentTimeMillis() - foregroundSince >= FRONT_STABLE.inWholeMilliseconds)) break
            Thread.sleep(100)
        }
        logger.info { "Remote player action: TV app foreground=$tvForeground after bringing to front (woke=$woke)" }
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
     * - **不在首页就不弹** (见 [onHomePage]);
     * - 读的是**存下来的**设置, 不是组合里的 LocalThemeSettings —— 启动那一刻后者可能还是默认值, 关过的人会被弹一次;
     * - 等服务起来、拿到地址再弹; 一直拿不到 (没连局域网) 就不弹, 否则一开应用就是一个「无法使用」的弹窗;
     * - 拿到地址后再稍等一下, 让首页先画出来, 弹窗不跟启动画面抢.
     */
    /**
     * @param onHomePage 真要弹的那一刻再问一次"现在还在首页吗"。传进来而不是自己读:
     * 本类在 ui-tv, 拿不到导航栈。
     */
    fun showDialogOnLaunch(onHomePage: () -> Boolean = { true }) {
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
            // 等地址与这一下延迟的工夫里可能已经进了播放页 (上次休眠前在播的会自动接着播). 弹窗是模态的,
            // 盖上去连遥控器的播放 / 暂停都按不动了 —— 这时不弹, 码随时能长按播放键调出来.
            if (foregroundPlayer != null) {
                logger.info { "Skip remote control prompt on launch: the player page is already in foreground" }
                return@launch
            }
            // **不在首页也不弹** (2026-09-20 用户报): 电视休眠后进程常被系统收掉, 再进来时
            // [launchPromptDone] 是新的一份, 而 Activity 会恢复到离开时那个页面 —— 弹窗于是落在详情页 /
            // 缓存页之类的地方。它是模态的, 盖上去遥控器就按不动了 (同上面跳过播放页的理由)。
            // 等地址与那一下延迟的工夫里用户自己导航走了也算。
            // 不补弹: “启动时弹一次”的机会用掉就算, 码随时能从侧边栏或长按播放键调出来。
            if (!onHomePage()) {
                logger.info { "Skip remote control prompt on launch: not on the home page" }
                return@launch
            }
            _dialogVisible.value = true
        }
    }

    /**
     * 搜索页登记的「电视当前查询」, 打开网页时用它预填关键词与筛选项 (与电视上看到的一致).
     * 页面离开组合时置回 null; 为 null 时按空查询渲染.
     */
    @Volatile
    var currentQueryProvider: (() -> SubjectSearchQuery)? = null

    /**
     * 搜索筛选里可选的年份, 由电视搜索页在场时注册 (同 [currentQueryProvider]).
     *
     * 年份表来自上游的番剧索引 (SearchPageState.seasons), 是异步取来的运行时数据, 手机这边
     * 自己造一串年份没法保证跟电视一致 —— 取不到就不显示这一节.
     */
    var currentYearsProvider: (() -> List<Int>)? = null

    /** 自己拉来的年份表; 季度一年才变四次, 拉到就整个进程复用. */
    private var cachedSearchYears: List<Int>? = null

    /**
     * 搜索表单里可选的年份.
     *
     * 电视正在搜索页时直接用它那份 (ViewModel 已经拉好); 否则自己拉一次 —— 年份表原本**只有**搜索页的
     * ViewModel 会在 init 里异步取, 而手机通常先于电视进搜索页打开控制台, 那一节就空着, 要等用户提交
     * 一次搜索、电视进了搜索页、请求回来、再刷新网页才突然冒出来 (2026-09-17 用户实测约一分钟).
     */
    private suspend fun searchYears(): List<Int> {
        currentYearsProvider?.invoke()?.takeIf { it.isNotEmpty() }?.let { return it }
        cachedSearchYears?.let { return it }
        val loaded = try {
            // 首屏不能为它干等: 接口本身只要三四百毫秒, 超时就这次不显示, 下次请求再拿
            withTimeoutOrNull(SEARCH_YEARS_TIMEOUT) {
                KoinPlatform.getKoin().get<GetAnimeSeasonIdsFlowUseCase>()()
                    .first().map { it.year }.distinct().sortedDescending()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load season ids for the web search form" }
            null
        }
        return loaded?.also { cachedSearchYears = it }.orEmpty()
    }

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

    /**
     * 电视上 Ani 在不在前台. 缓存那边要用: BT 服务只有在前台才会启动 (上游的省电策略), 所以后台点缓存只会排队等着,
     * 缓存面板与缓存列表据此说明白是"在启动服务"还是"要等打开 Ani" (见 [RemoteCacheList]).
     */
    internal fun isTvForeground(): Boolean = tvForeground

    /** 最近一次回到前台的时刻, 见 [awaitFront] */
    @Volatile
    private var foregroundSince = 0L

    internal fun setTvForeground(foreground: Boolean) {
        if (tvForeground != foreground) logger.info { if (foreground) "TV app back in foreground" else "TV app went to background" }
        if (foreground && !tvForeground) foregroundSince = System.currentTimeMillis()
        tvForeground = foreground
        // 刚从休眠叫醒拉起来就被桌面压下去 (见 wakeFrontUntil): 等桌面那一下落定再拉一次
        if (!foreground && System.currentTimeMillis() < wakeFrontUntil) {
            wakeFrontUntil = 0L
            scope.launch {
                delay(WAKE_FRONT_RETRY_DELAY)
                if (tvForeground) return@launch
                logger.info { "Launcher took over right after remote wake, bringing TV app to front again" }
                appContext?.let { launchSelf(it) }
            }
        }
        // 手机上开「切到电视前台」时 Ani 在后台、没能当场打开授权页: 回来了就打开 (见 scheduleFrontAuth)
        if (foreground) scheduleFrontAuth()
        // 「退出 Ani 后保留」开着: 常驻服务只能在前台起, 回来了就补上 (见 syncKeepAlive)
        if (foreground) syncKeepAlive()
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
        // 电视上 Ani 不在前台、用户在网页设置里开了「切到电视前台」且授了权: 顺带切过来 (见 frontEnabled)
        if (!tvForeground && frontEnabled() && frontGranted()) bringToFront()
    }

    /**
     * 应用启动时 (TV 根组合) 调一次: 起常驻服务. 重复调用无副作用.
     * @param navigator 搜索页不在场时把电视导航到搜索页 / 把播放器调回前台用.
     */
    fun install(context: Context, navigator: AniNavigator) {
        synchronized(lock) { this.navigator = navigator }
        ensureStarted(context)
        // 「退出 Ani 后保留 Web 控制台」开着: 趁在前台把常驻服务起起来 (后台起不了, 见 syncKeepAlive)
        syncKeepAlive()
    }

    /**
     * 界面没了 (Activity 销毁 —— 比如开了「退出 Ani 后保留」时按返回退出, 进程留着): 清掉旧的导航入口. 之后要界面的网页操作
     * 先把 Ani 拉起来再做 (见 [awaitUi]), 拉不起来就明说 —— 不再往已经不在的界面上导航 (那样什么都不会发生).
     */
    fun detachNavigator(navigator: AniNavigator) {
        synchronized(lock) { if (this.navigator === navigator) this.navigator = null }
    }

    /**
     * 起监听, 不要界面: 界面装上时 ([install]), 以及常驻服务被系统重启时 (那时没有界面, 网页照样能连, 能「切到前台」拉起 Ani).
     * 重复调用无副作用.
     */
    fun ensureStarted(context: Context) {
        // 设置-界面里的「重置 Web 控制台地址」(设置页够不到本对象, 经 ui-foundation 的桥)
        TvRemoteSettingsBridge.resetAddress = ::resetAddress
        synchronized(lock) {
            appContext = context.applicationContext
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            fixedPort = fixedPortFor(context.packageName)
            _knownHost.value = prefs?.getString(KEY_KNOWN_HOST, null)
        }
        // 启动时的二维码弹窗关掉时, 看看有没有欠着的授权页要打开 (它开着时先不打开, 免得叠在一起; 见 scheduleFrontAuth)
        scope.launch { _dialogVisible.collect { if (!it) scheduleFrontAuth() } }
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
        // 弹窗已经开着、播放页随后才起来 (恢复播放比弹窗慢的那半拍): 同上, 模态弹窗会挡掉遥控器的播放控制,
        // 让位给播放页. 后台会话不算 —— 那时用户人在首页, 弹窗不碍事.
        if (!handle.background && _dialogVisible.value) {
            logger.info { "Player page entered while the launch prompt is open, closing the prompt" }
            _dialogVisible.value = false
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
            (if (url != null) "Remote control reachable at $url (known host: $known)"
            else "Remote control: no LAN address, QR code hidden") +
                "; interfaces: ${lanInterfacesSummary()}; TV VPN: ${tvVpnActive()}"
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
        // 回给网页的文案跟着 app 当前语言 (见 RemoteI18n)
        RemoteI18n.refresh()
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        // 要电视界面的操作进来时界面不在 (退出后保留了进程 / 被回收后常驻服务重启了监听): 先把 Ani 拉起来, 拉不起来就明说
        if (post && path in UI_PATHS && !awaitUi()) return json(result(false, tr(UI_GONE_MESSAGE)))
        // 播放器上的按钮 (控制 / 换源 / 换集 / 音轨弹幕…): 电视休眠或切走时界面停着, 按了没反应 —— 先把 Ani 叫回前台
        if (post && (path in PLAYER_FRONT_PATHS || path.startsWith("api/player/danmaku/"))) awaitFront()
        return when {
            path.isEmpty() && get -> LanHttpResponse.html(renderPage())
            path == PATH_SEARCH && post -> json(deliverSearch(request))
            path == PATH_SEARCH_HISTORY && get -> json(searchHistory())
            path == PATH_SEARCH_HISTORY_DELETE && post -> json(deleteHistory(request))
            path == PATH_NOTICE && get -> json(noticeState(request))
            path == PATH_LAUNCH_DIALOG_CLOSE && post -> json(closeLaunchDialog(request))
            path == PATH_TV_FRONT && post -> json(manualFront())
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
            path == PATH_PLAYER_REFETCH && post -> json(refetchSources())
            path == PATH_PLAYER_CONTROL && post -> json(control(request))
            path == PATH_PLAYER_EPISODE && post -> json(switchEpisode(request))
            path == PATH_PLAYER_DETAILS && post -> json(openDetails(request))
            // 弹幕 (开关 / 偏移 / 手动匹配) 与音轨字幕轨, 见 RemotePlayerExtras
            (post && (path.startsWith("api/player/danmaku/") || path == "api/player/track" ||
                    path == "api/player/comment" || path.startsWith("api/player/review/"))) ||
                    (get && path == "api/player/review") ->
                json(player?.let { RemotePlayerExtras.handle(it, request) } ?: result(false, tr("电视当前不在播放页")))
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
            // 手机那侧的诊断回传 (载体建了多长 / 元数据到没到 / 加载失败码), 见 RemoteClientLog.
            // 网页里出的事在电视日志里本来没有任何痕迹, 这条是唯一的通路.
            path == "api/client-log" ->
                RemoteClientLog.handle(request)?.let(::json) ?: LanHttpResponse.status(405, "Method Not Allowed")
            path.isEmpty() || path.startsWith("api/") -> LanHttpResponse.status(405, "Method Not Allowed")
            else -> LanHttpResponse.status(404, "Not Found")
        }
    }

    private fun renderPage(): String {
        val base = currentQueryProvider?.invoke() ?: SubjectSearchQuery("")
        val (searchForm, requestSection) = runBlocking {
            renderRemoteSearchForm(
                RemoteSearchFormValues.from(base),
                searchYears(),
            ) to renderPlayerRequestSection()
        }
        // 电视在播放页时默认打开「播放器」, 否则「搜索」; 网页地址里的 #player / #search 优先
        val initialTab = if (player != null) "player" else "search"
        return renderRemoteControlPage(
            initialTab = initialTab,
            searchFormHtml = searchForm,
            requestSectionHtml = requestSection,
            themeCss = RemoteTheme.css(),
            i18nScript = RemoteI18n.pageScript(),
            // 同一次启动内不变, 重启 / 重装就变 —— 手机上那份脚本是不是新的, 看这个号就知道 (Safari 会把
            // 页面缓存下来, 装了新包不等于手机上换了脚本; 2026-09-18 有好几轮反馈其实测的是旧脚本)
            pageVersion = (processStart % 100000).toString(),
        )
    }

    // ---------------------------- 搜索 ----------------------------

    private fun deliverSearch(request: LanHttpRequest): JsonObject {
        val submission = RemoteSearchFormValues.parse(request.formFieldList()).toSubmission()
            ?: return result(false, tr("请输入关键词或选择筛选项"))
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
        if (searchResults != null) return result(true, tr("电视已经在搜索页了"))
        val nav = navigator
        val existing = nav?.backStack?.lastOrNull { it is NavRoutes.SubjectSearch }
        if (nav != null && existing != null) {
            logger.info { "Remote resume search: popping back to the search page in the back stack" }
            notifyRemoteNavigation()
            scope.launch(Dispatchers.Main) {
                runCatching { nav.popBackStack(existing, inclusive = false) }
                    .onFailure { logger.warn(it) { "Failed to pop back to search page for remote resume" } }
            }
            return result(true, tr("电视已回到刚才的搜索页"))
        }
        val submission = lastSearchResults?.query?.let { RemoteSearchFormValues.from(it).toSubmission() }
            ?: return result(false, tr("没有可以恢复的搜索，重新搜一次吧"))
        logger.info { "Remote resume search: no search page in the back stack, searching again" }
        return deliver(submission, okMessage = tr("刚才的搜索页已经关了，电视重新搜索"))
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
        val sent = okMessage ?: if (submission.keywords.isNotEmpty()) tr("已发送到电视：{0}", submission.keywords) else tr("已发送到电视")
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
        return result(true, if (onPlayer) tr("已在电视上打开搜索，按返回可回到播放器") else sent)
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
        if (q.isEmpty()) return result(false, tr("无效的记录"))
        runCatching { runBlocking { historyRepository().removeHistory(q) } }
            .onFailure {
                logger.warn(it) { "Failed to delete search history from remote control" }
                return result(false, tr("删除失败"))
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
            // 有没下完的 BT 缓存时, 上面那条要说清楚缓存也停着: BT 服务只在 Ani 前台时才起 (见 RemoteCacheList),
            // 不说的话用户只当是"电视没显示 Ani"而已, 不会想到下载也不动了
            if (!tvForeground && RemoteCacheList.hasPendingTorrentCache()) put("cachePending", true)
            // 启动时的二维码弹窗还开着: 网页顶上给一条「还开着 [关闭]」, 遥控器关掉后下一轮就收起, 见 closeLaunchDialog
            put("launchDialog", _dialogVisible.value)
            // 「不在前台」那一条里的入口按这两个分三态: 没开 → 「切到 Ani」(先确认开启) / 开了没授权 → 「怎么授权」/
            // 开了且授了权 → 「切到 Ani」直接切 (见 frontEnabled)
            put("frontOn", frontEnabled())
            put("frontGranted", frontGranted())
            // 「退出 Ani 后保留」开没开: 网页记着, 断连时没开的话提示去设置里开 (见 keepAliveOnExit)
            put("keep", keepAliveOnExit())
            // app 里换了语言: 网页发现和自己加载时的不一样就整页重载 (见 RemoteI18n)
            put("lang", RemoteI18n.lang.tag)
        }
    }

    /** 手机上翻到底 / 点「加载更多」: 让电视的结果列表加载下一页. */
    private fun loadMoreResults(): JsonObject {
        val source = searchResults ?: return result(false, tr("电视已离开搜索页，回到搜索页后可以继续加载"))
        source.loadMore()
        return result(true, "")
    }

    // ---------------------------- 播放器 ----------------------------

    /**
     * 播放器状态. 网页每秒轮询一次, 带着上次拿到的版本号 `v`; 内容没变就只回 `{"same": true}` —— BT 源的
     * 候选可能有几百条, 每秒整份重发太浪费.
     */
    /** 上一次回报给手机的进度, 以及它出自哪个 handle; 见 [logPositionJump]. */
    private var lastReportedPosition = -1L
    private var lastReportedHandle = 0
    private var lastReportedBackground = false

    /**
     * 诊断 (2026-09-18 用户报「正常播放时手机上的进度在 0 附近与正确时间之间来回跳」, 真机抓到同一次轮询里
     * position 在三组数值间交替、每组各自正常递增).
     *
     * 进度只该往前走, 倒退超过 [POSITION_JUMP_THRESHOLD] 记一行, 写明这次与上次分别出自哪个 handle —— 一眼
     * 分出两种真因: **换了会话回报** ([player] 取的是 `foregroundPlayer ?: backgroundPlayer`, 前台注销与新注册
     * 之间会回退到后台保留会话, 它的进度停在自己那一集), 还是**同一个会话自己跳** (那就是播放器侧的事).
     */
    private fun logPositionJump(handle: RemotePlayerHandle, playback: JsonObject) {
        val position = (playback["position"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        val prev = lastReportedPosition
        val prevHandle = lastReportedHandle
        val prevBackground = lastReportedBackground
        lastReportedPosition = position
        lastReportedHandle = handle.hashCode()
        lastReportedBackground = handle.background
        if (prev < 0 || position + POSITION_JUMP_THRESHOLD >= prev) return
        logger.warn {
            "Playback position jumped back: " + prev + "ms -> " + position + "ms; handle " +
                    prevHandle.toString(16) + "(bg=" + prevBackground + ") -> " +
                    handle.hashCode().toString(16) + "(bg=" + handle.background + "), sameHandle=" +
                    (prevHandle == handle.hashCode())
        }
    }

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
                    put("episode", if (upNext.episodeSort.isNotEmpty()) tr("第 {0} 话", upNext.episodeSort) else "")
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
        if (handle != null && playback != null) logPositionJump(handle, playback)
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
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val id = request.formFields()["id"].orEmpty()
        handle.select(id)?.let { return result(false, it) }
        return result(
            true,
            if (handle.background) tr("已切换，电视在后台加载，回到播放器即可播放") else tr("已切换，电视正在加载"),
        )
    }

    /**
     * 「重新搜索（含新数据源）」: 搜索会话建立时对数据源列表取了快照, 之后改数据源 / 更新订阅都不会让新源参与
     * 这一次搜索 (见 MediaFetchSessionRefresh 的说明) —— 按一下让播放页用当前的数据源列表重建会话。
     *
     * 不进 [PLAYER_FRONT_PATHS]: 不需要把 Ani 叫到前台, 电视在后台时刷了也算数 (下次进播放页就是新的)。
     */
    private fun refetchSources(): JsonObject {
        KoinPlatform.getKoin().get<MediaFetchSessionRefresh>().request()
        logger.info { "Remote requested a media fetch session rebuild, newly added sources will join the search" }
        return result(true, tr("正在用最新的数据源重新搜索"))
    }

    private fun updateRequest(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val fields = request.formFieldList()
        fun field(name: String) = fields.lastOrNull { it.first == name }?.second.orEmpty()
        if (field("reset") == "1") {
            return handle.resetRequest()?.let { result(false, it) }
                ?: result(true, tr("已恢复默认查询条件，正在重新搜索"))
        }
        val error = handle.updateRequest(
            primary = field("primary"),
            others = field("others").lines(),
            sort = field("sort"),
            ep = field("ep"),
        )
        return if (error != null) result(false, error) else result(true, tr("已保存，正在重新搜索所有数据源"))
    }

    private fun control(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        // 后台会话被按住暂停 (见本类 KDoc), 这里放行只会被立刻按回去
        if (handle.background) return result(false, tr("电视未在播放页，播放控制不可用"))
        if (!tvForeground) return result(false, tr("电视当前没有显示 Ani，播放控制不可用。在「设置」中开启「从手机打开 Ani」后，使用播放控制时会自动打开 Ani。"))
        val fields = request.formFields()
        val action = fields["action"].orEmpty()
        // 拖进度条 / 输入时间点: 跳到 ms (服务端夹在片长以内)
        if (action == "seek") {
            val ms = fields["ms"]?.toLongOrNull() ?: return result(false, tr("无效的时间"))
            handle.seekTo(ms)
            return result(true, "")
        }
        // 拖倍速条: v = 倍速 (服务端量化到电视倍速条的档位并夹在其范围内, 见 RemotePlayerHandle.setSpeed)
        if (action == "speed") {
            val v = fields["v"]?.toFloatOrNull()?.takeIf { !it.isNaN() && it > 0f } ?: return result(false, tr("无效的倍速"))
            return if (handle.setSpeed(v)) result(true, "") else result(false, tr("这个播放器不支持倍速"))
        }
        // 拖音量条: v = 0~1 (播放器音量, 见 RemotePlayerHandle.setVolume)
        if (action == "volume") {
            val v = fields["v"]?.toFloatOrNull()?.takeIf { !it.isNaN() } ?: return result(false, tr("无效的音量"))
            return if (handle.setVolume(v)) result(true, "") else result(false, tr("这个播放器不支持调音量"))
        }
        // 成功不给提示文案: 按钮的效果电视上看得见, 手机上的播放状态也会马上刷新
        return if (handle.control(action)) result(true, "") else result(false, tr("未知操作"))
    }

    private fun switchEpisode(request: LanHttpRequest): JsonObject {
        val handle = player ?: return result(false, tr("电视当前不在播放页"))
        val id = request.formFields()["id"]?.toIntOrNull() ?: return result(false, tr("无效的剧集"))
        handle.switchEpisode(id)?.let { return result(false, it) }
        return result(
            true,
            if (handle.background) tr("已切换，电视在后台加载，回到播放器即可播放") else tr("已切换，电视正在加载"),
        )
    }

    /**
     * 手机上点「接下来播放」: 把电视带进播放页播那一集, 同动作面板上按卡片主体那一下. 位置由播放器自己按
     * episodeId 从播放进度表续, 这里只管导航. 以电视此刻的 [TvUpNextStore.target] 为准, 不信手机页面上那份.
     */
    private fun playUpNext(): JsonObject {
        if (player != null) return result(false, tr("电视已经在播放了"))
        val target = TvUpNextStore.target ?: return result(false, tr("没有可播放的内容"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            runCatching { nav.navigateEpisodeDetails(target.subjectId, target.episodeId, force = true) }
                .onFailure { logger.warn(it) { "Failed to start up-next playback for remote control" } }
        }
        return result(true, tr("已在电视上开始播放"))
    }

    /**
     * 手机上点播放器卡片 (或「接下来播放」卡) 的剧名: 电视打开这部的详情页. 在播放页时叠在播放器上面 (同内嵌标签跳转),
     * 按返回回到播放器. 剧名当占位先画出来, 不必等详情加载.
     */
    private fun openDetails(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val id = fields["id"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        val placeholder = SubjectDetailPlaceholder(id = id, name = fields["title"].orEmpty())
        val onPlayer = foregroundPlayer != null
        notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            runCatching { nav.navigateSubjectDetails(id, placeholder) }
                .onFailure { logger.warn(it) { "Failed to open subject details from remote player card" } }
        }
        return result(true, if (onPlayer) tr("已在电视上打开详情页，按返回可回到播放器") else tr("已在电视上打开详情页"))
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
        if (foregroundPlayer != null) return result(true, tr("电视已在播放页"))
        val session = playbackSessionProvider?.invoke() ?: return result(false, tr("电视上没有正在播放的内容"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            // force: 回到已经在播的这一集, 跳过一起看跟随模式的导航守卫 (同动作面板的「回到正在播放」)
            runCatching { nav.navigateEpisodeDetails(session.subjectId, session.episodeId, force = true) }
                .onFailure { logger.warn(it) { "Failed to bring player to front for remote control" } }
        }
        return result(true, tr("已在电视上打开播放器"))
    }

    /**
     * 保留会话的状态, 给手机卡片那一条: `kind` 决定颜色 (ready 绿 / busy 灰 / attention 黄 / error 红), `label` 是粗体短语,
     * `text` 是跟在后面的一句说明. 判据与电视侧边栏图标、动作面板顶行同一套 ([PlaybackSessionStatus]);
     * 失败的具体原因在电视画面上看, 这里只说下一步能做什么.
     */
    private fun sessionStatusJson(): JsonObject? {
        val status = playbackStatusProvider?.invoke() ?: return null
        val (kind, label, text) = when (status) {
            PlaybackSessionStatus.Ready -> Triple("ready", tr("已就绪"), tr("回到播放器就能接着看"))
            PlaybackSessionStatus.Preparing -> Triple("busy", tr("准备中"), tr("正在查找数据源、解析播放地址"))
            PlaybackSessionStatus.Buffering -> Triple("busy", tr("缓冲中"), tr("马上就好"))
            PlaybackSessionStatus.NeedsSelection -> Triple("attention", tr("等你选数据源"), tr("在下面挑一个就会开始加载"))
            PlaybackSessionStatus.NoMedia -> Triple("error", tr("没有可播放的资源"), tr("可以试试修改查询条件"))
            PlaybackSessionStatus.PlayerError -> Triple("error", tr("播放出错"), tr("可以在下面换一个数据源"))
            is PlaybackSessionStatus.LoadFailed -> Triple("error", tr("加载失败"), tr("可以在下面换一个数据源"))
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

    /** 网页打开时替用户关掉启动时的二维码弹窗, 见 [closeLaunchDialog]. */
    private const val PATH_LAUNCH_DIALOG_CLOSE = "api/launch-dialog/close"

    /** 网页顶上「不在前台」那一条里的「切到 Ani」, 见 [manualFront]. */
    private const val PATH_TV_FRONT = "api/tv/front"

    /** 网页设置里「切到电视前台」开没开 (默认关), 见 [frontEnabled]. */
    private const val KEY_BRING_TO_FRONT = "bring_to_front"

    /** 手机上开「切到电视前台」时没能当场打开授权页的那一刻 (毫秒), 等 Ani 回到前台再打开, 见 [scheduleFrontAuth]. */
    private const val KEY_FRONT_AUTH_AT = "front_auth_requested_at"

    /** 开了之后多久内回到 Ani 才直接打开授权页; 再晚就不打扰了. */
    private val FRONT_AUTH_WINDOW = 30.minutes

    /** 回到前台后等多久再打开授权页: 让页面先画出来, 也等启动时的二维码弹窗有没有要弹. */
    private val FRONT_AUTH_DELAY = 1500.milliseconds

    /** 网页设置里「退出 Ani 后保留 Web 控制台」开没开 (默认关), 见 [keepAliveOnExit]. */
    private const val KEY_KEEP_ALIVE = "keep_alive_on_exit"

    /** 界面不在时拉起 Ani 后, 等新界面装上多久. */
    private val UI_WAIT = 10.seconds

    /** 要电视界面才能做的网页操作 (让电视换页面): 界面不在时先拉起 Ani, 见 [awaitUi]. */
    private val UI_PATHS = setOf(
        PATH_SEARCH, PATH_SEARCH_PLAY, PATH_SEARCH_OPEN, PATH_PLAYER_OPEN, PATH_PLAYER_UPNEXT, PATH_PLAYER_DETAILS,
        "api/history/open", "api/history/play", "api/caches/play", "api/caches/open",
    )

    /** 播放器上的操作: Ani 不在前台时先叫回来, 见 [awaitFront]. 评论 / 评分 / 收藏不算 (不用看电视). 弹幕另按前缀 api/player/danmaku/ */
    private val PLAYER_FRONT_PATHS = setOf(
        PATH_PLAYER_CONTROL, PATH_PLAYER_SELECT, PATH_PLAYER_EPISODE, PATH_PLAYER_REQUEST, "api/player/track",
    )

    /**
     * 从手机把电视叫醒后屏幕保持多久. 这段时间是留给**交接**的: 刚亮屏那会儿页面还在恢复, 播放页自己的
     * FLAG_KEEP_SCREEN_ON (ScreenOnEffect) 还没挂上. 原来只持 3 秒, 一放手屏幕就按系统超时走, 二十多秒后
     * 息屏 → app 又退到后台 (2026-09-18 实测). 页面接管之后这把锁到不到期都无所谓.
     */
    private const val REMOTE_WAKE_HOLD = 45_000L

    /** 播放器操作叫 Ani 回前台时最多等多久; 刚从休眠叫醒的要在前台连续待这么久才算站稳 */
    private val FRONT_WAIT = 5.seconds
    private val FRONT_STABLE = 1.seconds

    /** 进程起来后多久才允许起常驻服务 (避开冷启动的主线程排队, 见 syncKeepAlive). */
    /** 首屏渲染最多为年份表等这么久, 见 searchYears; 接口本身只要三四百毫秒. */
    private val SEARCH_YEARS_TIMEOUT = 3.seconds

    private val KEEP_ALIVE_START_DELAY = 20.seconds

    private const val UI_GONE_MESSAGE = "Ani 已退出，无法从手机打开。请先在电视上重新打开 Ani。开启「从手机打开 Ani」并完成授权后，下次可直接从手机打开。"

    /** 账号状态 (等授权时网页每 2 秒轮询, 不刷 knownHost), 见 RemoteAccount. */
    private const val PATH_ACCOUNT = "api/account"

    /** 提交后电视换上新查询、新结果开始刷新一般在这之内; 超过就不再等「刷新开始」这个信号. */
    private val AWAIT_REFRESH_GRACE = 1.5.seconds

    /** 电视一直没换上手机提交的查询 (比如用户在电视上又改了): 不再显示「正在搜索」. */
    private val AWAIT_RESULTS_TIMEOUT = 10.seconds

    /** 下拉最多从这么多条记录里挑 (显示时再按输入过滤、取前 8 条). */
    private const val HISTORY_LIMIT = 50
    /** 进度倒退超过这个值才记一行 (见 logPositionJump): 正常轮询间隔里的抖动不该刷屏. */
    private const val POSITION_JUMP_THRESHOLD = 5_000L

    private const val PATH_PLAYER_STATE = "api/player"
    private const val PATH_PLAYER_SELECT = "api/player/select"
    private const val PATH_PLAYER_OPEN = "api/player/open"
    private const val PATH_PLAYER_UPNEXT = "api/player/upnext"
    private const val PATH_PLAYER_REQUEST = "api/player/request"
    private const val PATH_PLAYER_REFETCH = "api/player/refetch"
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

    /** 手机打开网页后, 启动弹窗留着显示「手机已连接」多久再自动关. */
    private val LAUNCH_DIALOG_CLOSE_DELAY = 1500.milliseconds

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
