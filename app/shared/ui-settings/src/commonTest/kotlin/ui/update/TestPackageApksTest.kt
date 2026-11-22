/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 工单回复里的测试包挑这台设备装得上的那个. */
class TestPackageApksTest {
    private val base = "https://github.com/GrahamZen/izuko-tv/releases/download/test-fix1"
    private val urls = listOf(
        "$base/izuko-1.0.9-alpha05-fix1-arm64-v8a.apk",
        "$base/izuko-1.0.9-alpha05-fix1-armeabi-v7a.apk",
        "$base/izuko-1.0.9-alpha05-fix1-universal.apk",
        "$base/izuko-1.0.9-alpha05-fix1-legacy-armeabi-v7a.apk",
    )

    @Test
    fun `a 32 bit box gets the armeabi-v7a package`() {
        assertEquals(urls[1], pickTestPackageUrl(urls, listOf("armeabi-v7a", "armeabi"), legacy = false))
    }

    @Test
    fun `an x86 emulator falls back to universal`() {
        assertEquals(urls[2], pickTestPackageUrl(urls, listOf("x86_64", "x86"), legacy = false))
    }

    @Test
    fun `an Android 7 device only gets the legacy package`() {
        assertEquals(urls[3], pickTestPackageUrl(urls, listOf("armeabi-v7a", "armeabi"), legacy = true))
        assertNull(pickTestPackageUrl(urls.take(3), listOf("armeabi-v7a"), legacy = true))
    }

    @Test
    fun `a query string does not hide the architecture`() {
        val signed = listOf("$base/izuko-x-arm64-v8a.apk?token=1", "$base/izuko-x-universal.apk?token=1")
        assertEquals(signed[0], pickTestPackageUrl(signed, listOf("arm64-v8a"), legacy = false))
    }
}
