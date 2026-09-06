/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import me.him188.ani.app.data.network.protocol.ReleaseClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 从 releases 列表里挑哪一个的逻辑.
 *
 * 只看最上面那个 release 会在两种情况下把"有更新"错判成"没更新": 老设备遇到不出兼容包的版本,
 * 以及排在最上面的是改分发包名之前那条版本线的 release (安装包叫 `ani-…`).
 */
class SelectUsableReleaseTest {
    private val abis = listOf("arm64-v8a", "armeabi-v7a")

    private fun release(
        tag: String,
        vararg assetNames: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = GitHubRelease(
        tagName = tag,
        assets = assetNames.map { GitHubAsset(it, "https://example.com/$it") },
        body = "",
        publishedAt = "",
        prerelease = prerelease,
        draft = draft,
    )

    /** 语义版本比较在别处测, 这里只要"数字大的更新". */
    private fun isNewer(candidate: String, current: String) =
        candidate.substringBefore('-').replace(".", "").toInt() > current.substringBefore('-').replace(".", "").toInt()

    private fun select(
        releases: List<GitHubRelease>,
        currentVersion: String = "1.0.0",
        releaseClass: ReleaseClass = ReleaseClass.STABLE,
        legacy: Boolean = false,
    ) = selectUsableRelease(
        releases = releases,
        currentVersion = currentVersion,
        releaseClass = releaseClass,
        assetPrefix = "izuko",
        abis = abis,
        legacy = legacy,
        isNewer = ::isNewer,
    )

    /** 改分发包名之前那条版本线的 release, 打在更晚的提交上时一直排在最上面, 只有 `ani-…`. */
    private val oldLineOnTop = release("v6.0.7", "ani-6.0.7-arm64-v8a.apk", "ani-6.0.7-universal.apk")

    @Test
    fun `取最新的可用版本`() {
        val releases = listOf(
            release("v1.0.2", "izuko-1.0.2-arm64-v8a.apk"),
            release("v1.0.1", "izuko-1.0.1-arm64-v8a.apk"),
        )
        assertEquals("v1.0.2", select(releases)?.first?.tagName)
    }

    /** 旧版本线的 release 排在最上面, 版本号还比本应用大 —— 必须越过它, 否则再也收不到更新. */
    @Test
    fun `越过排在最上面的旧版本线 release`() {
        val releases = listOf(
            oldLineOnTop,
            release("v1.0.1", "izuko-1.0.1-arm64-v8a.apk"),
        )
        val r = select(releases)
        assertEquals("v1.0.1", r?.first?.tagName)
        assertEquals(listOf("izuko-1.0.1-arm64-v8a.apk"), r?.second?.map { it.name })
    }

    /** 旧版本线 (6.x) 上的 release 就算带着本应用前缀的文件, 也不是本应用该装的. */
    @Test
    fun `只认本版本线上的 release`() {
        val releases = listOf(
            release("v6.0.8", "izuko-6.0.8-arm64-v8a.apk"),
            release("v1.0.0", "izuko-1.0.0-arm64-v8a.apk"),
        )
        assertNull(select(releases, currentVersion = "1.0.0"))
    }

    /**
     * Android 7.1 只能装 `-legacy-` 兼容包, 而不是每个 release 都出. 以前遇到不出的那一版就当没更新,
     * 于是这些设备再也收不到任何更新.
     */
    @Test
    fun `老设备越过不出兼容包的 release`() {
        val releases = listOf(
            release("v1.0.2", "izuko-1.0.2-arm64-v8a.apk"),
            release("v1.0.1", "izuko-1.0.1-legacy-arm64-v8a.apk", "izuko-1.0.1-arm64-v8a.apk"),
        )
        val r = select(releases, legacy = true)
        assertEquals("v1.0.1", r?.first?.tagName)
        assertEquals(listOf("izuko-1.0.1-legacy-arm64-v8a.apk"), r?.second?.map { it.name })
    }

    @Test
    fun `草稿与预发布按规则排除`() {
        val releases = listOf(
            release("v1.0.3", "izuko-1.0.3-arm64-v8a.apk", draft = true),
            release("v1.0.2-alpha01", "izuko-1.0.2-alpha01-arm64-v8a.apk", prerelease = true),
            release("v1.0.1", "izuko-1.0.1-arm64-v8a.apk"),
        )
        assertEquals("v1.0.1", select(releases)?.first?.tagName)
        // 预发布频道能看到 1.0.2-alpha01, 草稿谁都看不到
        assertEquals("v1.0.2-alpha01", select(releases, releaseClass = ReleaseClass.ALPHA)?.first?.tagName)
    }

    @Test
    fun `一个都不比当前新时为 null`() {
        val releases = listOf(release("v1.0.0", "izuko-1.0.0-arm64-v8a.apk"))
        assertNull(select(releases))
    }

    @Test
    fun `更新的版本里一个装得上的都没有时为 null`() {
        val releases = listOf(release("v1.0.1", "ani-1.0.1-arm64-v8a.apk"))
        assertNull(select(releases))
    }

    @Test
    fun `版本线按主版本号划分`() {
        assertTrue(isInUpdateLine("1.0.0"))
        assertTrue(isInUpdateLine("5.9.9-alpha01"))
        assertFalse(isInUpdateLine("6.0.7"))
        assertFalse(isInUpdateLine("6.0.7-alpha02"))
        assertFalse(isInUpdateLine("latest"))
    }
}
