/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.pip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.videoplayer.ui.IosVideoLayerRegistry
import me.him188.ani.app.videoplayer.ui.findIosVideoLayer
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.avkit.AVKitMediampPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.Foundation.NSKeyValueObservingOptionInitial
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingOptions
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.QuartzCore.CALayer
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Composable
actual fun rememberPictureInPictureController(player: MediampPlayer): PictureInPictureController {
    // AVKit 在不支持 PiP 的设备上初始化控制器会返回 nil.
    if (!remember { AVPictureInPictureController.isPictureInPictureSupported().also { logger.info { "[PiP] 设备支持画中画: $it" } } }) {
        return NoOpPictureInPictureController
    }

    // layer 由 VideoPlayer 的 UIKitView factory 异步创建, 通过版本号感知
    val version by IosVideoLayerRegistry.version.collectAsState()
    val layer = remember(player, version) { player.findIosVideoLayer() }
    val avPlayer = player as? AVKitMediampPlayer
    // 别的控制器的系统小窗还没收完 (点「还原」后应用内小窗展开成播放页) 时先不建: 同一块视频图层上再建一个画中画控制器,
    // 正在收的系统小窗画面会被抢走 (先黑, 系统再淡出收尾); 等它收起再建
    val canBuild = remember(player, layer) { mutableStateOf(systemPictureInPictureShowing.value == 0) }
    LaunchedEffect(canBuild) {
        if (canBuild.value) return@LaunchedEffect
        val started = TimeSource.Monotonic.markNow()
        // 收起回调万一没来也别一直不建
        val stopped = withTimeoutOrNull(SYSTEM_PIP_STOP_WAIT) { systemPictureInPictureShowing.first { it == 0 } } != null
        logger.info { "[PiP] ${if (stopped) "系统小窗收起了" else "等系统小窗收起超时"}, 等了 ${started.elapsedNow().inWholeMilliseconds}ms 建控制器" }
        canBuild.value = true
    }
    val controller = remember(player, layer, canBuild.value) {
        when {
            layer == null || avPlayer == null -> {
                logger.info { "[PiP] 暂时建不了控制器: 有视频图层=${layer != null} 是 AVKit 播放器=${avPlayer != null}" }
                NoOpPictureInPictureController
            }

            !canBuild.value -> {
                logger.info { "[PiP] 系统小窗还没收完, 图层 ${layer.hashCode()} 先不建控制器, 等它收起" }
                NoOpPictureInPictureController
            }

            else -> IosPictureInPictureController(layer, avPlayer)
        }
    }
    DisposableEffect(controller) {
        onDispose { (controller as? IosPictureInPictureController)?.dispose() }
    }
    return controller
}

/**
 * iOS 画中画控制器, 包装 AVKit 的 `AVPictureInPictureController`.
 *
 * - 自动进入: `canStartPictureInPictureAutomaticallyFromInline` (用户上滑回桌面时系统自动以小窗继续,
 *   动画由系统提供), 由 [updatePolicy] 开关
 * - 小窗播控由系统提供, 无需自定义 action
 * - 点了「还原」: 从播放页进的直接 completion(true) (收回原位); 从应用内小窗进的先换收回的目标, 见 [restore]
 *
 * 日志行首 `[PiP] #编号` (播放页与应用内小窗各有一个控制器, 编号区分): 建 / 拆、系统小窗开始 / 收起 / 起不来、点「还原」后等图层与换目标、回前台自动收.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private class IosPictureInPictureController(
    layer: AVPlayerLayer,
    private val player: AVKitMediampPlayer,
) : PictureInPictureController {
    override val isSupported: Boolean get() = true

    private val _isInPictureInPicture = MutableStateFlow(false)
    override val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

    private val _isPictureInPicturePossible = MutableStateFlow(false)
    override val isPictureInPicturePossible: StateFlow<Boolean> = _isPictureInPicturePossible.asStateFlow()

    private val id = nextId++
    private val stop = IosPictureInPictureStop(player, id)
    override val restoreRequests: Flow<Unit> get() = stop.requests

    override val restoreFinishedCount: StateFlow<Int> get() = restoreFinishedTimes

    // AVPictureInPictureController.delegate 是 weak 引用, 必须由本类强持有
    private val delegate = Delegate(
        onIsInChanged = ::onPictureInPictureChanged,
        onRestore = ::restore,
        onFailedToStart = { error -> logger.warn { "[PiP] #$id 系统小窗起不来: ${error.localizedDescription} (${error.code})" } },
        stop = stop,
    )

    private val pipController = AVPictureInPictureController(playerLayer = layer).apply {
        this.delegate = this@IosPictureInPictureController.delegate
        // 部署目标 iOS 16.0, 无需检查 14.2 可用性
        canStartPictureInPictureAutomaticallyFromInline = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        logger.info { "[PiP] #$id 建控制器, 图层 ${layer.hashCode()}" }
        // isPictureInPicturePossible 的 KVO 变更通知在部分 iOS 版本上不可靠: 初值投递后
        // 可能不再投递 (系统层 possible 已翻 true, 自动小窗都好使, 但 KVO 不再回调),
        // 而控制栏按钮的 enabled 依赖这个 flow — 随播放器状态重读真实值兜底.
        // StateFlow 收集会立即发射当前状态, 等价于再补一次初值.
        scope.launch {
            player.state.collect { refreshPossible() }
        }
    }

    // KVO 快速路径, 仅在变更通知真正投递时触发
    private val possibleObserver = pipController.observeKeyPathOnMain(
        KEY_IS_PICTURE_IN_PICTURE_POSSIBLE,
        options = NSKeyValueObservingOptionInitial or NSKeyValueObservingOptionNew,
    ) {
        refreshPossible()
    }

    private val foregroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidBecomeActiveNotification,
        null,
        null,
        usingBlock = {
            // 点「还原」回来的那一次由 restore 收 (要先换好收回的目标)
            if (pipController.isPictureInPictureActive()) {
                if (restoring) {
                    logger.info { "[PiP] #$id 回到前台: 正在按还原收, 不另外收" }
                } else {
                    logger.info { "[PiP] #$id 回到前台, 收起系统小窗" }
                    stop.onStopRequestedByApp()
                    pipController.stopPictureInPicture()
                }
            }
        },
    )

    private fun refreshPossible() {
        val possible = pipController.isPictureInPicturePossible()
        if (possible != _isPictureInPicturePossible.value) logger.info { "[PiP] #$id 能开小窗: $possible" }
        _isPictureInPicturePossible.value = possible
    }

    private var lastAutoEnter: Boolean? = null

    override fun updatePolicy(autoEnterEnabled: Boolean, aspectWidth: Int?, aspectHeight: Int?) {
        if (lastAutoEnter != autoEnterEnabled) {
            lastAutoEnter = autoEnterEnabled
            logger.info { "[PiP] #$id 回桌面自动进小窗: $autoEnterEnabled" }
        }
        // 宽高比由 AVKit 按 layer 内容自动处理, 无需声明 (与 Android 的 PictureInPictureParams 不同)
        pipController.canStartPictureInPictureAutomaticallyFromInline = autoEnterEnabled
        refreshPossible()
    }

    override fun enterPictureInPicture() {
        refreshPossible()
        logger.info {
            "[PiP] #$id 按钮进小窗: possible=${pipController.isPictureInPicturePossible()} active=${pipController.isPictureInPictureActive()}"
        }
        if (pipController.isPictureInPicturePossible() && !pipController.isPictureInPictureActive()) {
            pipController.startPictureInPicture()
        }
    }

    override fun exitPictureInPicture() {
        // 系统动画把 AVPlayerLayer 从小窗收回原位, 同一 AVPlayer 不中断播放
        if (pipController.isPictureInPictureActive()) {
            logger.info { "[PiP] #$id 页面要求收起系统小窗" }
            stop.onStopRequestedByApp()
            pipController.stopPictureInPicture()
        }
    }

    /** 正在按「还原」收 (等应用内小窗铺满, 见 [restore]): 这期间回到前台不另外收. */
    private var restoring = false

    /** 应用内小窗铺满全屏接住了这次还原: 系统小窗收起后等播放页的画面出来再给 [restoreFinishedCount] 加一. */
    private var takingOver = false

    /**
     * 点了「还原」. 有人接 (应用内小窗进的画中画) 时小窗当场铺满全屏 (同一块视频图层, 底下展开成播放页), 等它铺满再让系统收:
     * 系统收回到当初开小窗的那块图层上 —— 它一直留着最后一帧, 收回不黑 (收回到播放页新建的图层上, 那块一帧还没画过, 先黑再淡出).
     * 没人接 (从播放页进的, 收回原位) 时当场收.
     */
    private fun restore(completion: (Boolean) -> Unit) {
        if (!stop.onRestoreTapped()) {
            logger.info { "[PiP] #$id 点了还原, 没人接 (从播放页进的), 收回原位" }
            completion(true)
            return
        }
        restoring = true
        takingOver = true
        val layer = pipController.playerLayer
        logger.info { "[PiP] #$id 点了还原, 应用内小窗铺满全屏接住; 等图层 ${layer.hashCode()} 铺满 (现在 ${layer.sizeText()}, 窗口 ${layer.rootSizeText()})" }
        val started = TimeSource.Monotonic.markNow()
        scope.launch {
            try {
                val filled = withTimeoutOrNull(RESTORE_FILL_WAIT) {
                    while (!layer.fillsRoot()) delay(16)
                } != null
                val waited = started.elapsedNow().inWholeMilliseconds
                if (filled) {
                    logger.info { "[PiP] #$id 铺满了 (${layer.sizeText()}, 等了 ${waited}ms), 让系统收回" }
                } else {
                    logger.warn { "[PiP] #$id 等了 ${waited}ms 还没铺满 (${layer.sizeText()}, 窗口 ${layer.rootSizeText()}), 照样让系统收回" }
                }
            } finally {
                restoring = false
                completion(true)
            }
        }
    }

    /**
     * 系统小窗收起了, 这次还原是应用内小窗接住的: 等播放页自己的视频图层出画面 (最多 [TAKEOVER_HANDOFF_WAIT]), 再让小窗撤掉.
     * 在 [handoffScope] 里等: 本控制器收起后马上就拆 (应用内小窗已经换成跟着播放页新图层的控制器), 拆的时候 [scope] 会取消.
     */
    private fun finishTakeover() {
        takingOver = false
        val own = pipController.playerLayer
        val started = TimeSource.Monotonic.markNow()
        logger.info { "[PiP] #$id 接住的还原收起了, 等播放页的视频图层出画面" }
        handoffScope.launch {
            // 按 isEqual 比 (同一个 UIKit 对象每次桥过来的 Kotlin 引用不一定是同一个)
            val page = withTimeoutOrNull(TAKEOVER_HANDOFF_WAIT) {
                var layer = player.findIosVideoLayer()
                while (layer == null || layer == own || !layer.isReadyForDisplay()) {
                    delay(16)
                    layer = player.findIosVideoLayer()
                }
                layer
            }
            val waited = started.elapsedNow().inWholeMilliseconds
            if (page != null) {
                logger.info { "[PiP] #$id 播放页的图层 ${page.hashCode()} 出画面了 (等了 ${waited}ms), 撤掉铺满的小窗" }
            } else {
                val latest = player.findIosVideoLayer()
                logger.warn { "[PiP] #$id 等了 ${waited}ms 播放页的图层还没出画面 (最新图层 ${latest?.hashCode()} 是小窗自己的=${latest == own}), 照样撤掉" }
            }
            restoreFinishedTimes.value++
        }
    }

    /** 系统小窗还开着时页面不要它了 (点了「还原」, 应用内小窗当场换成播放页): 等系统收起再拆, 中途拆掉代理就收不到收起回调 (见 [IosPictureInPictureStop]). */
    private var disposeWhenStopped = false

    private fun onPictureInPictureChanged(value: Boolean) {
        logger.info { "[PiP] #$id 系统小窗${if (value) "开始" else "收起"}" }
        if (value != _isInPictureInPicture.value) systemPictureInPictureShowing.value += if (value) 1 else -1
        _isInPictureInPicture.value = value
        if (!value && takingOver) finishTakeover()
        if (!value && disposeWhenStopped) {
            disposeWhenStopped = false
            lingering -= this
            dispose()
        }
    }

    fun dispose() {
        if (pipController.isPictureInPictureActive()) {
            logger.info { "[PiP] #$id 页面不要了, 系统小窗还开着: 等它收起再拆" }
            disposeWhenStopped = true
            lingering += this
            return
        }
        logger.info { "[PiP] #$id 拆控制器" }
        scope.cancel()
        NSNotificationCenter.defaultCenter.removeObserver(foregroundObserver)
        pipController.removeObserver(possibleObserver, KEY_IS_PICTURE_IN_PICTURE_POSSIBLE)
        pipController.delegate = null
    }

    private companion object {
        const val KEY_IS_PICTURE_IN_PICTURE_POSSIBLE = "isPictureInPicturePossible"

        /** 点「还原」后等应用内小窗铺满全屏最多多久 (重组 + 布局, 一两帧). */
        val RESTORE_FILL_WAIT = 500.milliseconds

        /** 系统小窗收起后等播放页的视频图层出画面最多多久. */
        val TAKEOVER_HANDOFF_WAIT = 2000.milliseconds

        /** 等着系统收起小窗再拆的控制器 ([disposeWhenStopped]): 页面已经不持有它们, 在这里留个引用, 代理 (弱引用) 才收得到回调. */
        val lingering = mutableSetOf<IosPictureInPictureController>()

        /** 日志里区分各个控制器的编号. */
        var nextId = 1
    }
}

private val logger = logger("PictureInPicture")

/** 铺满接住的还原收尾了几次 (见 [IosPictureInPictureController.finishTakeover]); 不分控制器, 应用内小窗换了控制器也看得到. */
private val restoreFinishedTimes = MutableStateFlow(0)

/** 收尾的等待放在这里: 发起的控制器收起后就拆, 不能用它自己的 scope. */
private val handoffScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** 系统小窗正开着的控制器有几个 (开始 +1, 收起 -1; 见 rememberPictureInPictureController 里等它收起再建). */
private val systemPictureInPictureShowing = MutableStateFlow(0)

/** 等别的控制器的系统小窗收起最多多久 (点「还原」到收起回调, iPad 上实测约 2 秒). */
private val SYSTEM_PIP_STOP_WAIT = 5000.milliseconds

/**
 * `AVPictureInPictureControllerDelegate` 的最小实现: 跟踪小窗启停, 收起时交给 [stop] (还原按钮、回到应用后接着放);
 * 用户关闭小窗时系统会暂停播放, 播放器状态经 mediamp 状态机自动同步 UI, 无需额外处理.
 */
private class Delegate(
    val onIsInChanged: (Boolean) -> Unit,
    val onRestore: (completion: (Boolean) -> Unit) -> Unit,
    val onFailedToStart: (NSError) -> Unit,
    val stop: IosPictureInPictureStop,
) : NSObject(), AVPictureInPictureControllerDelegateProtocol {
    override fun pictureInPictureControllerDidStartPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) {
        onIsInChanged(true)
    }

    override fun pictureInPictureControllerWillStopPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) {
        stop.onWillStop()
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) {
        onIsInChanged(false)
        stop.onDidStop()
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
    ) {
        onRestore(restoreUserInterfaceForPictureInPictureStopWithCompletionHandler)
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError,
    ) {
        onFailedToStart(failedToStartPictureInPictureWithError)
    }
}

/**
 * 注册 KVO 并把回调蹦床到主队列 (KVO 可能在工作线程触发).
 * 写法参照 mediamp-avkit 内部的 `observeKeyPathOnMain`.
 */
@OptIn(ExperimentalForeignApi::class)
private class MainQueueKvoObserver(
    private val onChange: () -> Unit,
) : NSObject(), NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        dispatch_async(dispatch_get_main_queue()) { onChange() }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSObject.observeKeyPathOnMain(
    keyPath: String,
    options: NSKeyValueObservingOptions = NSKeyValueObservingOptionNew,
    onChange: () -> Unit,
): NSObject {
    val observer = MainQueueKvoObserver(onChange)
    addObserver(observer, forKeyPath = keyPath, options = options, context = null)
    return observer
}

/** 图层铺满了它所在的窗口 (一路往上找到根图层, 比宽高; 留几个点的余量给取整). */
@OptIn(ExperimentalForeignApi::class)
private fun AVPlayerLayer.fillsRoot(): Boolean {
    val (w, h) = sizeOf(this)
    val (rw, rh) = sizeOf(rootLayer())
    return rw > 0 && w >= rw - 4 && h >= rh - 4
}

@OptIn(ExperimentalForeignApi::class)
private fun CALayer.rootLayer(): CALayer {
    var layer = this
    while (true) layer = layer.superlayer ?: return layer
}

@OptIn(ExperimentalForeignApi::class)
private fun sizeOf(layer: CALayer): Pair<Double, Double> = layer.bounds.useContents { size.width to size.height }

private fun AVPlayerLayer.sizeText(): String = sizeOf(this).let { (w, h) -> "${w.toInt()}x${h.toInt()}" }

private fun AVPlayerLayer.rootSizeText(): String = sizeOf(rootLayer()).let { (w, h) -> "${w.toInt()}x${h.toInt()}" }
