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
 * 测试用的 mp4, 用盒子拼出来. 只放 [MediaStreamProbe] 读的那些盒子与字段, 其余字段填 0.
 */
internal object Mp4Samples {
    fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { acc, part -> acc + part }
        return u32(8L + body.size) + type.encodeToByteArray() + body
    }

    /** 视频的样本描述: 78 字节的定长字段之后是 [children]. */
    fun visualEntry(type: String, vararg children: ByteArray): ByteArray = box(type, ByteArray(78), *children)

    /** 音频的样本描述: 只有 28 字节的定长字段. */
    fun audioEntry(type: String): ByteArray = box(type, ByteArray(28))

    /** `nclx` 的 `colr`: BT.2020 原色与矩阵, 传输特性 [transfer]. */
    fun colr(transfer: Int): ByteArray = box("colr", "nclx".encodeToByteArray(), u16(9), u16(transfer), u16(9), byteArrayOf(0))

    /**
     * moov 在开头的 mp4: 一条 3840×2160 的视频轨 (样本描述 [videoEntry]), 每个 [audioEntries] 一条音轨, 时长 1440 秒.
     */
    fun file(videoEntry: ByteArray, vararg audioEntries: ByteArray): ByteArray {
        val moov = box(
            "moov",
            box("mvhd", ByteArray(12), u32(1000), u32(1_440_000)),
            trak("vide", videoEntry, 3840, 2160),
            *audioEntries.map { trak("soun", it, 0, 0) }.toTypedArray(),
        )
        return box("ftyp", "isom".encodeToByteArray(), u32(0x200), "isom".encodeToByteArray()) + moov + box("mdat", ByteArray(1000))
    }

    private fun trak(handler: String, entry: ByteArray, width: Int, height: Int): ByteArray = box(
        "trak",
        // version 0 的 tkhd: 宽高 (16.16 定点数) 在第 76 字节起
        box("tkhd", ByteArray(76), u32(width.toLong() shl 16), u32(height.toLong() shl 16)),
        box(
            "mdia",
            box("hdlr", ByteArray(8), handler.encodeToByteArray(), ByteArray(12)),
            box("minf", box("stbl", box("stsd", ByteArray(4), u32(1), entry))),
        ),
    )

    private fun u32(value: Long): ByteArray = ByteArray(4) { (value shr (8 * (3 - it))).toByte() }

    private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
}
