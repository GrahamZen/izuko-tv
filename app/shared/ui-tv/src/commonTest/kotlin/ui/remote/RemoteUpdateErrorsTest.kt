/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.SerializationException
import me.him188.ani.app.tools.update.ChecksumMismatchException
import me.him188.ani.app.tools.update.SourceOutcome
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 控制台「应用更新」出错时给网页的一句话 ([updateErrorReason] / [downloadFailureText]): 按类型说原因, 线路只写域名,
 * 异常原文 (英文, 带地址) 不出现. 测试里 [tr] 用简体原文.
 */
class RemoteUpdateErrorsTest {
    @Test
    fun `network errors read as short reasons`() {
        assertEquals("连接超时", updateErrorReason(SocketTimeoutException("Read timed out")))
        assertEquals("解析不了域名", updateErrorReason(UnknownHostException("Unable to resolve host \"api.github.com\"")))
        assertEquals("连不上", updateErrorReason(ConnectException("Failed to connect to /20.205.243.166:443")))
        assertEquals("安全连接失败", updateErrorReason(SSLHandshakeException("Chain validation failed")))
        assertEquals("连接断了", updateErrorReason(SocketException("Connection reset")))
        assertEquals("返回的内容不对", updateErrorReason(SerializationException("Expected start of the array '['")))
    }

    @Test
    fun `the reason is found along the cause chain`() {
        assertEquals("连接超时", updateErrorReason(IOException("wrapped", SocketTimeoutException("timeout"))))
    }

    @Test
    fun `checksum and storage failures`() {
        assertEquals("下载的文件校验不对", updateErrorReason(ChecksumMismatchException("mismatch (from https://ghfast.top/…)")))
        assertEquals("电视存储空间不够", updateErrorReason(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `anything else shows only the type and never the message with the address`() {
        val reason = updateErrorReason(IllegalArgumentException("bad url https://api.github.com/repos/x/y/releases?per_page=20"))
        assertEquals("出错了（IllegalArgumentException）", reason)
    }

    @Test
    fun `download failures are listed per line by domain`() {
        val outcomes = listOf(
            SourceOutcome("https://github.com/o/r/releases/download/v1/a.apk", null, ConnectException("x")),
            SourceOutcome("https://gh-proxy.com/https://github.com/o/r/releases/download/v1/a.apk", 5L * MIB, SocketTimeoutException("x")),
        )
        assertEquals("github.com 连不上；gh-proxy.com 连接超时", downloadFailureText(IOException("last"), outcomes))
    }

    @Test
    fun `the same reason on every line is said once`() {
        val outcomes = listOf(
            SourceOutcome("https://github.com/o/r/a.apk", null, SocketTimeoutException("x")),
            SourceOutcome("https://gh-proxy.com/https://github.com/o/r/a.apk", null, SocketTimeoutException("y")),
            SourceOutcome("https://ghfast.top/https://github.com/o/r/a.apk", null, SocketTimeoutException("z")),
        )
        assertEquals("3 条线路都连接超时", downloadFailureText(IOException("last"), outcomes))
    }

    @Test
    fun `one line says its domain and reason`() {
        val outcomes = listOf(SourceOutcome("https://gh-proxy.com/https://github.com/o/r/a.apk", null, SocketTimeoutException("x")))
        assertEquals("gh-proxy.com 连接超时", downloadFailureText(IOException("last"), outcomes))
    }

    @Test
    fun `without per-line results the own reason of the error is used`() {
        assertEquals("电视存储空间不够", downloadFailureText(IOException("No space left on device"), emptyList()))
    }

    @Test
    fun `lines are named by domain and speeds are short`() {
        assertEquals("gh-proxy.com", hostOf("https://gh-proxy.com/https://github.com/o/r/a.apk"))
        assertEquals("github.com", hostOf("https://github.com/o/r/a.apk"))
        assertEquals("5.5 MB/s", formatSpeed(5L * MIB + MIB / 2))
        assertEquals("12 MB/s", formatSpeed(12L * MIB))
        assertEquals("380 KB/s", formatSpeed(380L * 1024))
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
