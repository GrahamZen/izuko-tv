/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 画面不在眼前时, 内存快不够了才释放视频解码器, 下好的数据留着.
 *
 * 播放器离开画面 (退出播放页而会话留着, 或应用退到后台) 后, 解码器挂在占位输出面上照样占着: 一个 4K 解码器连同输出缓冲
 * 一两百 MB, 记在系统的 media.codec 进程名下. 平时不动它, 回来马上有画面; 内存快不够时才让渲染器释放解码器
 * ([UndecodableProfileGuardingVideoRenderer] 收到 [MSG_PARK] 后不再用占位输出面, 没有真的输出面就不建解码器).
 * 媒体与缓冲不动: 不重新搜索、不重新解析地址、不重新请求网络.
 *
 * 「内存快不够」只有一个判据: 可用内存低于系统杀后台进程的门槛 ([ActivityManager.MemoryInfo.threshold]) 的两倍, 或系统已判低内存.
 * 画面不在眼前时每 [POLL_MILLIS] 看一次; 画面刚离开、系统发内存告警 ([MemoryPressureSignal]) 时当场看一次.
 * 告警本身不作数: 系统只在告警等级变化时回调, 有的设备长期停在最高一级 (见过 Shield 在可用 600 MB 时就是 RUNNING_CRITICAL),
 * 安卓 14 起前台与带前台服务的进程又收不到运行中的告警.
 *
 * 画面回来 (输出面建好) 时恢复. 新解码器要从关键帧解起, 而读位置停在两个关键帧之间, media3 会一直丢弃输入直到下一个关键帧
 * (片源关键帧间隔可达十秒, 画面就停那么久), 所以恢复时原地 seek 一次: 后向缓冲从关键帧起保留, 这次 seek 在内存里完成,
 * 解码器从前一个关键帧解到当前位置.
 *
 * 只在主线程上调用.
 */
@OptIn(UnstableApi::class)
internal class VideoDecoderParking(
    private val exoPlayer: ExoPlayer,
    /** 全屏视频画面的输出面还在 (播放页在眼前). */
    private val hasVisibleOutput: () -> Boolean,
    /** 在当前位置原地 seek, 让重建的解码器从前一个关键帧解起. */
    private val restartFromKeyframe: () -> Unit,
) : MemoryPressureSignal.Listener {
    private var parked = false
    private var activityManager: ActivityManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            checkAvailableMemory("periodic check")
            handler.postDelayed(this, POLL_MILLIS)
        }
    }

    fun start(context: Context) {
        activityManager = context.getSystemService(ActivityManager::class.java)
        MemoryPressureSignal.install(context)
        MemoryPressureSignal.addListener(this)
        handler.postDelayed(poll, POLL_MILLIS)
    }

    fun stop() {
        MemoryPressureSignal.removeListener(this)
        handler.removeCallbacks(poll)
    }

    private fun checkAvailableMemory(trigger: String) {
        if (parked || hasVisibleOutput()) return
        val info = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) ?: return }
        if (info.lowMemory || info.availMem < info.threshold * 2) {
            maybePark("$trigger: available memory ${info.availMem / MIB} MB, system kill threshold ${info.threshold / MIB} MB")
        }
    }

    override fun onMemoryPressure(level: Int) {
        checkAvailableMemory("trim level $level")
    }

    /** 全屏视频画面的输出面没了. */
    fun onOutputGone() {
        checkAvailableMemory("output gone")
    }

    /** 全屏视频画面的输出面建好了. */
    fun onOutputAvailable() {
        if (!parked) return
        parked = false
        videoRenderer()?.let { exoPlayer.createMessage(it).setType(MSG_PARK).setPayload(false).send() }
        logger.info { "[DecoderPark] Output is back, restarting the video decoder from the keyframe before ${exoPlayer.currentPosition} ms" }
        restartFromKeyframe()
    }

    private fun maybePark(reason: String) {
        if (parked || hasVisibleOutput()) return
        if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.currentMediaItem == null) return
        val renderer = videoRenderer() ?: return
        parked = true
        exoPlayer.createMessage(renderer).setType(MSG_PARK).setPayload(true).send()
        logger.info { "[DecoderPark] No visible output, releasing the video decoder ($reason); buffered media is kept" }
    }

    private fun videoRenderer(): Renderer? =
        (0 until exoPlayer.rendererCount).map { exoPlayer.getRenderer(it) }.firstOrNull { it is UndecodableProfileGuardingVideoRenderer }

    companion object {
        /** 发给 [UndecodableProfileGuardingVideoRenderer] 的消息, 附 `true` 释放并停用解码器, `false` 恢复. */
        const val MSG_PARK = Renderer.MSG_CUSTOM_BASE + 1

        /** 画面不在眼前时多久看一次可用内存. */
        private const val POLL_MILLIS = 5_000L
        private const val MIB = 1024 * 1024

        private val logger = logger<VideoDecoderParking>()
    }
}

/**
 * 系统的内存告警 ([ComponentCallbacks2.onTrimMemory]). 前台与带前台服务的进程收到 RUNNING_LOW / RUNNING_CRITICAL,
 * 后台缓存进程收到 BACKGROUND 及以上; UI_HIDDEN (界面刚隐藏) 与 RUNNING_MODERATE 不算.
 * 回调在主线程上.
 */
internal object MemoryPressureSignal {
    fun interface Listener {
        fun onMemoryPressure(level: Int)
    }

    private val installed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                @Suppress("DEPRECATION") // 安卓 14 起不再发 RUNNING_LOW 等, 之前的版本照常收
                override fun onTrimMemory(level: Int) {
                    if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
                    listeners.forEach { it.onMemoryPressure(level) }
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {}
            },
        )
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }
}
