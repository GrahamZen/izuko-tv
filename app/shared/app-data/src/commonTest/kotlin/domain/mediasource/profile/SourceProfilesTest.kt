/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.profile

import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.datasources.api.source.MediaSourceTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceProfilesTest {
    private fun ok(
        at: Long, width: Int? = null, height: Int? = null, kbps: Int? = null, startup: Long? = null,
        webView: Boolean? = null, ads: Int? = null, test: Boolean = false,
    ) = SourceObservation(at, ok = true, startupMillis = startup, webView = webView, width = width, height = height, kbps = kbps, adSegments = ads, test = test)

    private fun fail(at: Long, webView: Boolean? = null) = SourceObservation(at, ok = false, webView = webView, reason = "NETWORK")

    @Test
    fun `clean 1080p direct source suggests T0`() {
        val s = SourceProfiles.summarize(listOf(ok(1, 1920, 1080, 1700, 4000, webView = false, ads = 0), ok(2, 1920, 1080, 1600, 6000)))!!
        assertEquals(0, s.suggestedTier)
        assertEquals(emptyList(), s.reasons)
        assertEquals(2, s.plays)
        assertEquals(1700, s.kbps)
        assertEquals(6000L, s.startupMillis)
        assertEquals(1, s.hlsPlays)
        assertEquals(0, s.adPlays)
    }

    @Test
    fun `ads, webview, low resolution and failures each add levels up to T4`() {
        val s = SourceProfiles.summarize(
            listOf(ok(1, 1280, 720, 900, webView = true, ads = 3), fail(2, webView = true), fail(3, webView = true), fail(4)),
        )!!
        assertEquals(listOf(SuggestReason.ADS, SuggestReason.WEB_VIEW, SuggestReason.BELOW_1080P, SuggestReason.FAILURES), s.reasons)
        assertEquals(4, s.suggestedTier)
    }

    @Test
    fun `cropped 1080p by width is not below 1080p`() {
        assertEquals(emptyList(), SourceProfiles.summarize(listOf(ok(1, 1920, 800, 1500)))!!.reasons)
    }

    @Test
    fun `no suggestion before any video is measured`() {
        assertNull(SourceProfiles.summarize(listOf(ok(1, startup = 3000)))!!.suggestedTier)
        assertNull(SourceProfiles.summarize(listOf(fail(1)))!!.suggestedTier)
        assertNull(SourceProfiles.summarize(emptyList()))
    }

    @Test
    fun `webview is decided by the majority of the latest marks`() {
        assertEquals(true, SourceProfiles.usesWebView(listOf(ok(1, webView = false), ok(2, webView = true), ok(3, webView = true))))
        assertEquals(false, SourceProfiles.usesWebView(listOf(ok(1, webView = true), ok(2, webView = false))))
        assertNull(SourceProfiles.usesWebView(listOf(ok(1))))
    }

    @Test
    fun `searches give median time, failures and a reason when most fail`() {
        fun search(at: Long, millis: Long, outcome: SearchObservation.Outcome = SearchObservation.Outcome.OK) = SearchObservation(at, millis, outcome)
        val ok = SourceProfiles.summarize(listOf(ok(1, 1920, 1080, 1500)), listOf(search(1, 1000), search(2, 3000), search(3, 2000)))!!
        assertEquals(3, ok.searches)
        assertEquals(2000L, ok.searchMillis)
        assertEquals(0, ok.searchFailures)
        assertEquals(0, ok.suggestedTier)

        val bad = SourceProfiles.summarize(
            listOf(ok(1, 1920, 1080, 1500)),
            listOf(search(1, 1000), search(2, 9000, SearchObservation.Outcome.FAILED), search(3, 9000, SearchObservation.Outcome.CAPTCHA)),
        )!!
        assertEquals(2, bad.searchFailures)
        assertEquals(listOf(SuggestReason.SEARCH_FAILS), bad.reasons)
        assertEquals(1, bad.suggestedTier)

        // 只有搜索记录时也有画像, 但还给不出建议
        assertNull(SourceProfiles.summarize(emptyList(), listOf(search(1, 1000)))!!.suggestedTier)
    }

    @Test
    fun `video is probed at most once a day per source`() {
        val id = "probe-interval-test"
        val day = 24 * 3600 * 1000L
        assertEquals(true, SourceProfiles.shouldProbe(id, now = 10 * day))
        SourceProfiles.record(id, ok(10 * day, startup = 3000)) // 没测到视频信息的不算
        assertEquals(true, SourceProfiles.shouldProbe(id, now = 10 * day + 1))
        SourceProfiles.record(id, ok(10 * day, 1920, 1080, 1500))
        assertEquals(false, SourceProfiles.shouldProbe(id, now = 10 * day + day - 1))
        assertEquals(true, SourceProfiles.shouldProbe(id, now = 11 * day))
    }

    @Test
    fun `demote only raises the listed sources`() {
        val tiers = MediaSelectorSourceTiers(mapOf("a" to MediaSourceTier(0u), "b" to MediaSourceTier(2u)))
        val demoted = SourceProfiles.demote(tiers, setOf("a"))
        assertEquals(MediaSourceTier(1u), demoted["a"])
        assertEquals(MediaSourceTier(2u), demoted["b"])
        assertEquals(tiers, SourceProfiles.demote(tiers, emptySet()))
    }
}
