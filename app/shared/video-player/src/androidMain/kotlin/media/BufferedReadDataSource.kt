/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * 给 [upstream] 加一层读缓冲: 小块读从缓冲里拷, 缓冲空了一次向上游读 [bufferSize] 字节; 不小于缓冲的读直接交给上游.
 *
 * 用于本机文件 ([FileDataSource] 没有缓冲, 每次读都是一次系统调用). 提取器解析 mkv 元素头时一次只读一两个字节,
 * 字幕组 BD 合集的 Cues 常给每条 ASS 字幕事件都建索引点 (几万个、几十万个元素), 不加缓冲要上百万次系统调用,
 * 电视上要十几到几十秒才出轨道. 网络与 BT 的数据源自己有缓冲, 不用套.
 */
@AndroidxOptIn(UnstableApi::class)
internal class BufferedReadDataSource(
    private val upstream: DataSource,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
) : DataSource {
    private val buffer = ByteArray(bufferSize)

    /** 缓冲里下一个要交出去的字节 */
    private var position = 0

    /** 缓冲里有效字节的末尾 */
    private var limit = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        clear()
        return upstream.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == limit) {
            if (length >= buffer.size) return upstream.read(target, offset, length)
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return read
            position = 0
            limit = read
        }
        val n = min(length, limit - position)
        buffer.copyInto(target, offset, position, position + n)
        position += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        clear()
        upstream.close()
    }

    private fun clear() {
        position = 0
        limit = 0
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = BufferedReadDataSource(upstream.createDataSource())
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
