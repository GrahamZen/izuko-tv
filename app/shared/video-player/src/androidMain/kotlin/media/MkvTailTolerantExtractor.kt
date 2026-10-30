/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * mkv 文件最后 [TAIL_BYTES] 内解析出错时当作读完, 不报错. 有的 mkv 在最后一个元素后面还跟着几个字节的填充或残缺的尾巴,
 * media3 读到这里 (元素 ID 的第一个字节是 0 时报 `No valid varint length mask found`) 直接抛异常. 播放器往前预读一两分钟,
 * 读到文件末尾出错时画面还没播到那里, 整个播放就失败、换源了, 其实视频数据早已读完. 文件中间出错照常抛出.
 */
@AndroidxOptIn(UnstableApi::class)
internal class MkvTailTolerantExtractor(private val delegate: Extractor) : Extractor by delegate {
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        delegate.read(input, seekPosition)
    } catch (e: IllegalStateException) {
        endIfAtTail(input, e)
    } catch (e: ParserException) {
        endIfAtTail(input, e)
    }

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private fun endIfAtTail(input: ExtractorInput, e: Exception): Int {
        if (!isAtTail(input.position, input.length)) throw e
        logger.warn { "Treating the broken mkv tail at ${input.position} of ${input.length} as the end: $e" }
        return Extractor.RESULT_END_OF_INPUT
    }

    companion object {
        private val logger = logger<MkvTailTolerantExtractor>()

        /** 文件最后这么多字节里解析出错就当作读完 */
        const val TAIL_BYTES = 1L shl 20

        /** [position] 在长 [length] 的文件的最后 [TAIL_BYTES] 里; 不知道长度时为 false */
        fun isAtTail(position: Long, length: Long): Boolean =
            length != C.LENGTH_UNSET.toLong() && length - position in 0..TAIL_BYTES
    }
}

/** mkv 的提取器 (底层是 [MatroskaExtractor] 的) 包上 [MkvTailTolerantExtractor]. */
@AndroidxOptIn(UnstableApi::class)
internal fun ExtractorsFactory.withTolerantMkvTail(): ExtractorsFactory = ExtractorsFactory {
    createExtractors().map { extractor ->
        if (extractor.underlyingImplementation is MatroskaExtractor) MkvTailTolerantExtractor(extractor) else extractor
    }.toTypedArray()
}
