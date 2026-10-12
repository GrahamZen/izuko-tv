/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import io.github.grahamzen.izuko.installer.PackageChoice.Result.Packages
import io.github.grahamzen.izuko.installer.PackageChoice.Result.TooOld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageChoiceTest {
    private fun choose(sdk: Int, vararg abis: String) = PackageChoice.choose(sdk, abis.toList(), normalMinSdk = 27, legacyMinSdk = 25)

    @Test
    fun `64 位设备装正式包的 arm64 专包 universal 兜底`() {
        assertEquals(Packages(false, listOf("arm64-v8a", "universal")), choose(30, "arm64-v8a", "armeabi-v7a", "armeabi"))
    }

    @Test
    fun `芯片是 64 位而系统是 32 位时按系统的 ABI 列表装 v7a`() {
        assertEquals(Packages(false, listOf("armeabi-v7a", "universal")), choose(34, "armeabi-v7a", "armeabi"))
    }

    @Test
    fun `x86 电视不出 x86 包 跳到它能跑的 armeabi-v7a`() {
        assertEquals(Packages(false, listOf("armeabi-v7a", "universal")), choose(36, "x86", "armeabi-v7a"))
    }

    @Test
    fun `x86_64 设备装 x86_64 包`() {
        assertEquals(Packages(false, listOf("x86_64", "universal")), choose(30, "x86_64", "x86", "arm64-v8a"))
    }

    @Test
    fun `7_1 与 8_0 装兼容包`() {
        assertEquals(Packages(true, listOf("legacy-arm64-v8a", "legacy-universal")), choose(25, "arm64-v8a", "armeabi-v7a"))
        assertEquals(Packages(true, listOf("legacy-armeabi-v7a", "legacy-universal")), choose(26, "armeabi-v7a"))
    }

    @Test
    fun `兼容包没有 x86_64 专包 只能 universal`() {
        assertEquals(Packages(true, listOf("legacy-universal")), choose(26, "x86_64"))
    }

    @Test
    fun `正好是正式包的 minSdk 装正式包`() {
        assertEquals(Packages(false, listOf("armeabi-v7a", "universal")), choose(27, "armeabi-v7a"))
    }

    @Test
    fun `7_0 及以下哪个都装不了`() {
        assertEquals(TooOld(24, 25), choose(24, "arm64-v8a"))
        assertEquals(TooOld(21, 25), choose(21, "armeabi-v7a"))
    }

    @Test
    fun `纯 x86 设备哪个包都不带 不用下`() {
        assertEquals(PackageChoice.Result.UnsupportedAbi(listOf("x86")), choose(25, "x86"))
        assertEquals(PackageChoice.Result.UnsupportedAbi(listOf("x86", "armeabi")), choose(30, "x86", "armeabi"))
    }

    @Test
    fun `安装包文件名`() {
        assertEquals("izuko-1.0.5-legacy-armeabi-v7a.apk", PackageChoice.assetName("izuko", "1.0.5", "legacy-armeabi-v7a"))
    }

    @Test
    fun `版本线 只要主版本号小于 6 的`() {
        assertTrue(PackageChoice.isInVersionLine("1.0.5"))
        assertTrue(PackageChoice.isInVersionLine("1.0.6-alpha05"))
        assertFalse(PackageChoice.isInVersionLine("6.0.7"))
        assertFalse(PackageChoice.isInVersionLine("installer"))
    }
}
