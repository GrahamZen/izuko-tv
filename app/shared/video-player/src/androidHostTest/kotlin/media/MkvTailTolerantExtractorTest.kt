/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MkvTailTolerantExtractorTest {
    /** 一读就抛 [error] 的提取器 */
    private class Failing(private val error: Exception) : Extractor {
        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) = Unit
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = throw error
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private val noData = DataReader { _, _, _ -> C.RESULT_END_OF_INPUT }

    /** 文件长 [length], 读到 [position] 处 */
    private fun inputAt(position: Long, length: Long): ExtractorInput = DefaultExtractorInput(noData, position, length)

    private val fileLength = 1_480_210_857L

    @Test
    fun `a parse error in the last bytes of the file ends the input`() {
        // 实测的文件: 最后 19 字节不是合法的元素, 读到这里报错
        val extractor = MkvTailTolerantExtractor(Failing(IllegalStateException("No valid varint length mask found")))
        assertEquals(Extractor.RESULT_END_OF_INPUT, extractor.read(inputAt(fileLength - 19, fileLength), PositionHolder()))
        val parser = MkvTailTolerantExtractor(Failing(ParserException.createForMalformedContainer("bad element", null)))
        assertEquals(Extractor.RESULT_END_OF_INPUT, parser.read(inputAt(fileLength - 4000, fileLength), PositionHolder()))
    }

    @Test
    fun `a parse error in the middle of the file is still thrown`() {
        val extractor = MkvTailTolerantExtractor(Failing(IllegalStateException("No valid varint length mask found")))
        assertFailsWith<IllegalStateException> { extractor.read(inputAt(fileLength / 2, fileLength), PositionHolder()) }
        // 不知道文件多长时也照常抛出
        assertFailsWith<IllegalStateException> { extractor.read(inputAt(100, C.LENGTH_UNSET.toLong()), PositionHolder()) }
    }

    @Test
    fun `other errors near the end are still thrown`() {
        val extractor = MkvTailTolerantExtractor(Failing(IOException("connection reset")))
        assertFailsWith<IOException> { extractor.read(inputAt(fileLength - 19, fileLength), PositionHolder()) }
    }

    @Test
    fun `the tail is the last megabyte of a file with a known length`() {
        assertTrue(MkvTailTolerantExtractor.isAtTail(fileLength - 19, fileLength))
        assertTrue(MkvTailTolerantExtractor.isAtTail(fileLength - MkvTailTolerantExtractor.TAIL_BYTES, fileLength))
        assertFalse(MkvTailTolerantExtractor.isAtTail(fileLength - MkvTailTolerantExtractor.TAIL_BYTES - 1, fileLength))
        assertFalse(MkvTailTolerantExtractor.isAtTail(19, C.LENGTH_UNSET.toLong()))
    }
}
