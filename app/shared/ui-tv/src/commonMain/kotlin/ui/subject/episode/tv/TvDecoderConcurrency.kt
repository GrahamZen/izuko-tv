/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ResultReceiver
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/**
 * 硬件解码器能不能同时开两个 —— 决定播放时能不能用系统的 MediaMetadataRetriever 取进度条缩略图 (见 [TvFramePreviewSource]).
 *
 * MediaMetadataRetriever 在 mediaserver 进程里解码, 每取一帧新开一个硬件解码器. 主播放器占着一个时, 能同时开两个的机器相安无事;
 * 只能开一个的机器上, 系统按优先级把主播放器那个收回 (`ERROR_CODE_DECODING_RESOURCES_RECLAIMED`), 播放报错.
 * 厂商在 media_codecs.xml 里声明的 `concurrent-instances` 靠不住 (索尼 BRAVIA 的硬解写的是 16, 实测两路), 所以实测:
 * 在单独的检测进程 ([TvDecoderProbeService]) 里连开两个同类型、同分辨率的硬件解码器, 第二个开不出来就是只能开一个.
 * 两个都属于检测进程, 系统不会在它们之间收回; 厂商实现出了岔子也只带走检测进程.
 *
 * 检测要在没播放时做 (播放器占着解码器, 第二个必然开不出来), 所以只在应用启动时跑 ([probeIfNeeded]).
 * 结果按系统版本 ([Build.FINGERPRINT]) 缓存; 没通过的在之后启动时复查, 累计 [MAX_FAILED_PROBES] 次才认.
 * 播放时真的遇到主播放器的解码器被抢走 ([onDecoderPreempted]), 这个系统版本上就不再为缩略图另开任何解码器
 * (系统取帧与应用内取帧器都不用, 见 [allowsThumbnailDecoding]).
 */
object TvDecoderConcurrency {
    private const val PREFS = "tv_decoder_concurrency"
    private const val PROBE_VERSION = 1
    private const val KEY_SYSTEM = "system"
    private const val KEY_PREEMPTED = "preempted"
    private const val KEY_FAILED_PROBES = "failedProbes"

    /** 检测进程崩溃或卡住时收不到结果, 等这么久按没通过记. */
    private val PROBE_TIMEOUT = 30.seconds

    private var probing = false

    /**
     * 当前媒体 (视频 [videoWidth] x [videoHeight], 不知道时传 `null`) 能不能用系统取帧, 不会抢主播放器的解码器.
     * 没检测过、检测没通过、或者这台机器上出过解码器被抢走, 都是 `false`.
     */
    fun allowsSystemFrameExtraction(context: Context, videoWidth: Int?, videoHeight: Int?): Boolean {
        val prefs = prefs(context)
        return decideSystemFrameExtraction(
            tierResults = ProbeTier.entries.associateWith { prefs.getString(it.prefKey, null) },
            preempted = prefs.getBoolean(KEY_PREEMPTED, false),
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }

    /**
     * 需要时在检测进程里跑一次检测. 只在应用启动时、界面在前台 (才能起服务) 调用, 必须在主线程.
     */
    fun probeIfNeeded(context: Context) {
        if (probing) return
        val prefs = prefs(context)
        val needed = isProbeNeeded(
            tierResults = ProbeTier.entries.associateWith { prefs.getString(it.prefKey, null) },
            preempted = prefs.getBoolean(KEY_PREEMPTED, false),
            failedProbes = prefs.getInt(KEY_FAILED_PROBES, 0),
        )
        if (!needed) return

        probing = true
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(results: Map<ProbeTier, Boolean>, detail: String) {
            if (finished) return
            finished = true
            probing = false
            record(appContext, results, detail)
        }

        val timeout = Runnable {
            finish(ProbeTier.entries.associateWith { false }, "no result from the probe process within $PROBE_TIMEOUT")
        }
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                handler.removeCallbacks(timeout)
                val results = ProbeTier.entries.associateWith { resultData?.getBoolean(it.prefKey, false) == true }
                finish(results, resultData?.getString(RESULT_DETAIL).orEmpty())
            }
        }
        try {
            appContext.startService(
                Intent(appContext, TvDecoderProbeService::class.java).putExtra(EXTRA_RECEIVER, receiver.forIpc()),
            )
            handler.postDelayed(timeout, PROBE_TIMEOUT.inWholeMilliseconds)
            logger.info { "Decoder concurrency probe started" }
        } catch (e: Exception) {
            // 后台起不了服务之类: 这次不测, 下次启动再说
            probing = false
            logger.warn(e) { "Failed to start the decoder concurrency probe" }
        }
    }

    /**
     * 还能不能为缩略图另开解码器 (系统取帧与应用内取帧器都算). 这台机器上出过主播放器的解码器被抢走就是 `false`.
     */
    fun allowsThumbnailDecoding(context: Context): Boolean = !prefs(context).getBoolean(KEY_PREEMPTED, false)

    /**
     * 播放器报了解码器被抢走 (见 `isDecoderPreempted`): 这个系统版本上不再为缩略图另开解码器, 也不再检测.
     */
    fun onDecoderPreempted(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_PREEMPTED, false)) return
        prefs.edit().putBoolean(KEY_PREEMPTED, true).apply()
        logger.warn { "Player decoder preempted during playback, thumbnail decoding disabled on this device" }
    }

    private fun record(context: Context, results: Map<ProbeTier, Boolean>, detail: String) {
        val prefs = prefs(context)
        val failed = results.values.any { !it }
        prefs.edit().apply {
            for ((tier, ok) in results) putString(tier.prefKey, if (ok) RESULT_OK else RESULT_FAIL)
            putInt(KEY_FAILED_PROBES, if (failed) prefs.getInt(KEY_FAILED_PROBES, 0) + 1 else 0)
        }.apply()
        logger.info { "Decoder concurrency probe: ${results.entries.joinToString { "${it.key.prefKey}=${it.value}" }} ($detail)" }
    }

    /** 本系统版本的记录; 系统升级 (指纹变了) 后清空重测. */
    private fun prefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val system = "${Build.FINGERPRINT}#$PROBE_VERSION"
        if (prefs.getString(KEY_SYSTEM, null) != system) {
            prefs.edit().clear().putString(KEY_SYSTEM, system).apply()
        }
        return prefs
    }

    private fun ResultReceiver.forIpc(): ResultReceiver {
        // 按普通 ResultReceiver 传过去: 检测进程里只需要它的 Binder
        val parcel = Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}

/** 同时开两个硬件解码器要检测的分辨率档. */
internal enum class ProbeTier(val prefKey: String, val width: Int, val height: Int) {
    FHD("fhd", 1920, 1080),
    UHD("uhd", 3840, 2160),
}

internal const val MAX_FAILED_PROBES = 3
private const val RESULT_OK = "ok"
private const val RESULT_FAIL = "fail"
private const val RESULT_DETAIL = "detail"
private const val EXTRA_RECEIVER = "receiver"

/**
 * 分辨率档记录 ([tierResults], 值为 `"ok"` / `"fail"` / 没检测过 `null`) 下, 这个视频能不能用系统取帧.
 * 不知道视频尺寸时所有档都要通过.
 */
internal fun decideSystemFrameExtraction(
    tierResults: Map<ProbeTier, String?>,
    preempted: Boolean,
    videoWidth: Int?,
    videoHeight: Int?,
): Boolean {
    if (preempted) return false
    val tier = if (videoWidth == null || videoHeight == null || videoWidth <= 0 || videoHeight <= 0) {
        null
    } else if (max(videoWidth, videoHeight) > ProbeTier.FHD.width || min(videoWidth, videoHeight) > 1088) {
        ProbeTier.UHD
    } else {
        ProbeTier.FHD
    }
    val required = tier?.let { listOf(it) } ?: ProbeTier.entries
    return required.all { tierResults[it] == RESULT_OK }
}

/** 要不要检测: 出过解码器被抢走的不测; 全部通过的不测; 没通过的复查到 [MAX_FAILED_PROBES] 次为止. */
internal fun isProbeNeeded(tierResults: Map<ProbeTier, String?>, preempted: Boolean, failedProbes: Int): Boolean {
    if (preempted) return false
    if (ProbeTier.entries.all { tierResults[it] == RESULT_OK }) return false
    return failedProbes < MAX_FAILED_PROBES
}

/**
 * [TvDecoderConcurrency] 的检测进程 (`:codecprobe`): 收到一次请求就检测一次, 把结果交给主进程, 然后退出.
 */
class TvDecoderProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val receiver = intent?.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        thread(name = "DecoderConcurrencyProbe") {
            val detail = StringBuilder()
            val results = ProbeTier.entries.associateWith { tier -> probeTier(tier, detail) }
            probeLogger.info { "Decoder concurrency probe: $results ($detail)" }
            receiver?.send(0, Bundle().apply {
                for ((tier, ok) in results) putBoolean(tier.prefKey, ok)
                putString(RESULT_DETAIL, detail.toString())
            })
            stopSelf(startId)
            // 用完就退, 不在后台占着一个进程
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1000)
        }
        return START_NOT_STICKY
    }

    /** 这一档下 H.264 与 HEVC 的第一个硬件解码器都能同时开两个. 没有支持这一档的硬件解码器就不会争抢, 算通过. */
    private fun probeTier(tier: ProbeTier, detail: StringBuilder): Boolean {
        return listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC).all { mime ->
            val name = firstHardwareDecoder(mime, tier.width, tier.height) ?: return@all true
            openTwo(name, mime, tier.width, tier.height, detail)
        }
    }

    /** MediaMetadataRetriever 与主播放器都会先挑的那个: 列表里第一个支持这个尺寸的硬件解码器. */
    private fun firstHardwareDecoder(mime: String, width: Int, height: Int): String? {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    !info.name.endsWith(".secure") &&
                    isHardware(info.name, info) &&
                    runCatching {
                        info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
                    }.getOrDefault(false)
        }?.name
    }

    private fun isHardware(name: String, info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val lower = name.lowercase()
        return !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.") && !lower.startsWith("c2.google.")
    }

    /**
     * 连开两个 [name], 两个都能用才算通过. 第一个就开不起来 (比如不支持不接 Surface 输出) 的测不了, 不算失败:
     * 这种解码器 MediaMetadataRetriever 也用不上, 争抢无从谈起.
     */
    private fun openTwo(name: String, mime: String, width: Int, height: Int, detail: StringBuilder): Boolean {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val codecs = mutableListOf<MediaCodec>()
        try {
            for (index in 0..1) {
                try {
                    val codec = MediaCodec.createByCodecName(name)
                    codecs += codec
                    codec.configure(format, null, null, 0)
                    codec.start()
                } catch (e: Exception) {
                    detail.append("$name ${width}x$height: instance ${index + 1} failed ($e); ")
                    return index == 0
                }
            }
            // 有的实现开第二个时把第一个挤掉, 两个都要还能用
            for (codec in codecs) codec.dequeueInputBuffer(0)
            detail.append("$name ${width}x$height: 2 ok; ")
            return true
        } catch (e: Exception) {
            detail.append("$name ${width}x$height: not usable after opening 2 ($e); ")
            return false
        } finally {
            for (codec in codecs) runCatching { codec.release() }
        }
    }
}

private val logger = logger("TvDecoderConcurrency")
private val probeLogger = logger("TvDecoderProbe")
