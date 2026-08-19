/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package me.him188.ani.app.videoplayer.media

import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/*
 * 把 dataspace 直接写到视频 Surface 的 producer 端。
 *
 * 为什么需要: Shield (NVIDIA ROM) 的 h264 硬解管线不理会 MediaFormat 里的色彩信息 (h265 管线理,
 * 这是 2026-08-19 用完整三项实测出来的差别), 视频层 dataspace 没人设置, 留着的垃圾 transfer 位
 * 撞上 ST2084 就变假 HDR。ACodec 在 app 进程内经 MediaCodec 拿到的这同一个 Surface 对象
 * queueBuffer, 每帧都带上 Surface 的 sticky dataspace 字段 —— h265 修好时被 ACodec 写的正是它,
 * h264 管线没人写, 这里由 app 替它写。
 *
 * native 实现在 `app/shared/video-player/native/ani_dataspace.c` (预编译提交在
 * `src/androidMain/jniLibs/`, 重新构建见 native/build.ps1)。API < 28 没有对应符号,
 * native 侧 dlsym 拿不到会返回错误码, 功能静默关闭。
 */
internal object SurfaceDataSpace {
    private val logger = logger("SurfaceDataSpace")

    private val available: Boolean by lazy {
        try {
            System.loadLibrary("ani_dataspace")
            true
        } catch (e: UnsatisfiedLinkError) {
            logger.warn { "libani_dataspace unavailable, surface dataspace repair disabled: $e" }
            false
        }
    }

    /**
     * @return 0 成功; 负值为失败 (-101 = Surface 已失效, -102 = ANativeWindow 布局校验不过,
     * [Int.MIN_VALUE] = so 加载失败)
     */
    fun set(surface: Surface, dataSpace: Int): Int {
        if (!available) return Int.MIN_VALUE
        return nativeSetBuffersDataSpace(surface, dataSpace)
    }

    /**
     * 读回 producer 端当前的 dataspace。**负值 = 读不到** (读回口
     * `ANativeWindow_getBuffersDataSpace` 是 API 28, 实测 Shield Android 11 存在;
     * 符号缺失时为 -100), 调用方不能把负值当"未设置(0)"用。
     */
    fun get(surface: Surface): Int {
        if (!available) return Int.MIN_VALUE
        return nativeGetBuffersDataSpace(surface)
    }

    @JvmStatic
    private external fun nativeSetBuffersDataSpace(surface: Surface, dataSpace: Int): Int

    @JvmStatic
    private external fun nativeGetBuffersDataSpace(surface: Surface): Int
}

/**
 * SDR 视频轨 → android dataspace 位组合 (V0 编码: range<<27 | transfer<<22 | standard<<16)。
 *
 * 只处理 SDR: transfer 不是 [C.COLOR_TRANSFER_SDR] (HDR 或未知) 返回 null —— HDR 的
 * dataspace/tone-map 归平台管, 不越权; 未知则没有可写的值。
 *
 * BT601 按分辨率区分 525(NTSC)/625(PAL): media3 的 [C.COLOR_SPACE_BT601] 表达不了这个
 * 区别 (它对应 MediaFormat 的 BT601_PAL), 这里按 AOSP `setDefaultCodecColorAspectsIfNeeded`
 * 的同款分辨率规则拆开。两者矩阵系数相同, 只差原色坐标。
 */
internal fun Format.toSdrDataSpaceOrNull(): Int? {
    val colorInfo = colorInfo ?: return null
    if (colorInfo.colorTransfer != C.COLOR_TRANSFER_SDR) return null
    val standard = when (colorInfo.colorSpace) {
        C.COLOR_SPACE_BT709 -> 1 // STANDARD_BT709
        C.COLOR_SPACE_BT601 ->
            if ((width in 1..720 && height in 1..480) || (height in 1..720 && width in 1..480)) {
                4 // STANDARD_BT601_525 (NTSC 尺寸)
            } else {
                2 // STANDARD_BT601_625 (PAL 及未知尺寸)
            }

        C.COLOR_SPACE_BT2020 -> 6 // STANDARD_BT2020
        else -> return null
    }
    val range = if (colorInfo.colorRange == C.COLOR_RANGE_FULL) 1 else 2
    val transfer = 3 // TRANSFER_SMPTE_170M, SDR 视频曲线
    return (range shl 27) or (transfer shl 22) or (standard shl 16)
}
