/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.probe

/**
 * 测试用的 mkv, 用 EBML 元素拼出来. 元素大小一律写成 8 字节 (合法, 只是不是最短的写法).
 */
internal object MkvSamples {
    fun leaf(id: Long, data: ByteArray): ByteArray = idBytes(id) + sizeBytes(data.size.toLong()) + data

    fun master(id: Long, vararg children: ByteArray): ByteArray = leaf(id, children.fold(ByteArray(0)) { acc, child -> acc + child })

    fun uint(value: Long): ByteArray = ByteArray(8) { (value shr (8 * (7 - it))).toByte() }

    /**
     * 一个 mkv 的开头: [width]×[height] 的 HEVC 视频轨, 时长 [durationSeconds] 秒, 后面跟着第一个 Cluster.
     * [padding] 大于 0 时在 Info / Tracks 前面放这么大的 Void (像前面塞了大附件), 它们就只能按 SeekHead 记的位置找到.
     */
    fun video(width: Int, height: Int, durationSeconds: Int, padding: Int = 0): ByteArray {
        val ebml = master(Mkv.EBML, leaf(DOC_TYPE, "matroska".encodeToByteArray()))
        val info = master(
            Mkv.INFO,
            leaf(Mkv.TIMESTAMP_SCALE, uint(1_000_000)),
            leaf(Mkv.DURATION, uint((durationSeconds * 1000.0).toRawBits())),
        )
        val tracks = master(
            Mkv.TRACKS,
            master(
                Mkv.TRACK_ENTRY,
                leaf(Mkv.TRACK_TYPE, uint(1)),
                leaf(Mkv.CODEC_ID, "V_MPEGH/ISO/HEVC".encodeToByteArray()),
                master(Mkv.VIDEO, leaf(Mkv.PIXEL_WIDTH, uint(width.toLong())), leaf(Mkv.PIXEL_HEIGHT, uint(height.toLong()))),
            ),
        )

        fun seekHead(infoAt: Long, tracksAt: Long) = master(
            Mkv.SEEK_HEAD,
            master(Mkv.SEEK, leaf(Mkv.SEEK_ID, idBytes(Mkv.INFO)), leaf(Mkv.SEEK_POSITION, uint(infoAt))),
            master(Mkv.SEEK, leaf(Mkv.SEEK_ID, idBytes(Mkv.TRACKS)), leaf(Mkv.SEEK_POSITION, uint(tracksAt))),
        )

        val void = if (padding > 0) leaf(VOID, ByteArray(padding)) else ByteArray(0)
        // SeekHead 的位置都相对 Segment 内容的起点; 位置写成定长, SeekHead 本身多长与位置的值无关
        val infoAt = (seekHead(0, 0).size + void.size).toLong()
        val segment = seekHead(infoAt, infoAt + info.size) + void + info + tracks + leaf(Mkv.CLUSTER, ByteArray(16))
        return ebml + leaf(Mkv.SEGMENT, segment)
    }

    private const val DOC_TYPE = 0x4282L
    private const val VOID = 0xECL

    private fun idBytes(id: Long): ByteArray =
        generateSequence(id) { it shr 8 }.takeWhile { it != 0L }.map { it.toByte() }.toList().reversed().toByteArray()

    private fun sizeBytes(size: Long): ByteArray = byteArrayOf(0x01) + ByteArray(7) { (size shr (8 * (6 - it))).toByte() }
}
