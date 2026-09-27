/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 换人重启时接力显示的那一帧 (电视上是选人页定格的「正在切换」): 旧进程截下来写进文件, 重启途中的中转页、新进程的落地页
 * 与主界面都原样显示它 (连同进度条, 见 [ProfileSwitchFrameDrawable]), 首页准备好了再淡出 —— 几个进程显示的是同一张图,
 * 看不出中间换了进程.
 *
 * 存原始像素, 不压缩: 1080p 一帧 8 MiB, 读写都是一次顺序 IO, 比编解码 PNG 快得多 (电视 CPU 慢); 刚写完就读, 多半还在页缓存里.
 */
object ProfileSwitchFrame {
    private const val FILE_NAME = "profile-switch-frame.bin"
    private const val MAGIC = 0x495A5347 // "IZSG"
    private const val HEADER_BYTES = 4 * 7 + 8
    private const val MAX_SIDE = 8192

    /**
     * @property track 进度条的槽在画面里的位置 (像素; 截下来的画面里已经有这条空槽, 填充由 [ProfileSwitchFrameDrawable] 现画), 没有为 null
     * @property startElapsedRealtime 开始换人的时刻 ([android.os.SystemClock.elapsedRealtime], 整机同一个时钟): 各进程的进度条按它算, 接得上
     */
    class Frame(val bitmap: Bitmap, val track: Rect?, val startElapsedRealtime: Long)

    private val landingState = MutableStateFlow<ProfileSwitchFrameDrawable?>(null)

    /**
     * 本进程主界面接着显示的那一帧: 换人重启进来时主界面创建时放进来 ([showLanding]), 界面上的过场淡出后放掉 ([release]).
     * 不为 null 期间过场盖在主界面上, 主界面不接按键与返回. 不是换人重启进来的为 null.
     */
    val landing: StateFlow<ProfileSwitchFrameDrawable?> get() = landingState

    fun showLanding(frame: ProfileSwitchFrameDrawable) {
        landingState.value = frame
    }

    private fun file(context: Context) = File(context.cacheDir, FILE_NAME)

    /** 写进文件: 先写临时文件再改名, 读的一方不会读到半截. 写不了返回 false (旧文件也删掉, 免得接着显示上一次的). */
    fun write(context: Context, frame: Frame): Boolean {
        val target = file(context)
        val temp = File(target.path + ".tmp")
        return try {
            val bitmap = frame.bitmap
            val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = ByteBuffer.allocateDirect(source.byteCount)
            source.copyPixelsToBuffer(pixels)
            pixels.flip()
            val track = frame.track ?: Rect()
            val header = ByteBuffer.allocate(HEADER_BYTES)
                .putInt(MAGIC).putInt(source.width).putInt(source.height)
                .putInt(track.left).putInt(track.top).putInt(track.right).putInt(track.bottom)
                .putLong(frame.startElapsedRealtime)
            header.flip()
            RandomAccessFile(temp, "rw").use { raf ->
                raf.setLength(0)
                val channel = raf.channel
                while (header.hasRemaining()) channel.write(header)
                while (pixels.hasRemaining()) channel.write(pixels)
            }
            if (!temp.renameTo(target)) error("Failed to rename ${temp.name}")
            true
        } catch (e: Exception) {
            temp.delete()
            target.delete()
            false
        }
    }

    /** 读出来; 没有或读不了返回 null. */
    fun read(context: Context): Frame? {
        val source = file(context)
        if (!source.exists()) return null
        return try {
            RandomAccessFile(source, "r").use { raf ->
                val channel = raf.channel
                val header = ByteBuffer.allocate(HEADER_BYTES)
                while (header.hasRemaining()) if (channel.read(header) < 0) return null
                header.flip()
                if (header.int != MAGIC) return null
                val width = header.int
                val height = header.int
                if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
                val track = Rect(header.int, header.int, header.int, header.int).takeUnless { it.isEmpty }
                val start = header.long
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val size = bitmap.byteCount.toLong()
                if (channel.size() != HEADER_BYTES + size) {
                    bitmap.recycle()
                    return null
                }
                bitmap.copyPixelsFromBuffer(channel.map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES.toLong(), size))
                Frame(bitmap, track, start)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context) {
        file(context).delete()
    }

    /** 过场放完: 放掉本进程拿着的那一帧, 删掉文件. */
    fun release(context: Context) {
        landingState.value = null
        delete(context)
    }
}
