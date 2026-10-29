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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `ads - webview - low resolution and failures each add levels up to T4`() {
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
    fun `searches give median time - failures and a reason when most fail`() {
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
    fun `a deferral marks the web view without counting as a play or failure`() {
        val deferral = SourceObservation(5, ok = true, webView = true, deferred = true)
        val s = SourceProfiles.summarize(listOf(deferral))!!
        assertEquals(true, s.webView)
        assertEquals(0, s.plays)
        assertEquals(0, s.failures)
        assertEquals(0, s.tests)
        // 播过一次直连、改选掉两次: 按最近几次的标记多数算要嗅探, 播放次数只算真播的
        val mixed = SourceProfiles.summarize(listOf(ok(1, startup = 3000, webView = false), deferral, deferral.copy(at = 6)))!!
        assertEquals(true, mixed.webView)
        assertEquals(1, mixed.plays)
        assertEquals(3000L, mixed.startupMillis)

        val id = "deferral-test"
        SourceProfiles.recordWebViewDeferred(id, now = 7)
        assertEquals(true, SourceProfiles.summary(id)!!.webView)
        assertEquals(0, SourceProfiles.summary(id)!!.plays)
    }

    @Test
    fun `a source failing most recent starts within three days is failing`() {
        val day = 24 * 3600 * 1000L
        val now = 10 * day
        // 两次失败一次播起来
        assertEquals(true, SourceProfiles.failsMostly(listOf(fail(now - 3), ok(now - 2, startup = 2000), fail(now - 1)), now))
        // 只失败一次不算
        assertEquals(false, SourceProfiles.failsMostly(listOf(fail(now - 1)), now))
        // 失败与播起来一样多不算
        assertEquals(false, SourceProfiles.failsMostly(listOf(fail(now - 4), fail(now - 3), ok(now - 2), ok(now - 1)), now))
        // 三天前的失败不算: 站点修好了自己升回来
        assertEquals(false, SourceProfiles.failsMostly(listOf(fail(now - 4 * day), fail(now - 4 * day + 1)), now))
        // 深度测试与改选掉的不算
        assertEquals(
            false,
            SourceProfiles.failsMostly(
                listOf(fail(now - 2).copy(test = true), fail(now - 1).copy(test = true), SourceObservation(now, ok = false, deferred = true)),
                now,
            ),
        )
    }

    @Test
    fun `failing sources drop two tiers`() {
        val tiers = MediaSelectorSourceTiers(mapOf("a" to MediaSourceTier(0u), "b" to MediaSourceTier(1u)))
        val demoted = SourceProfiles.demoteFailing(tiers, SourceProfiles.FailingSources(mostly = setOf("a"), never = emptySet()))
        assertEquals(MediaSourceTier(2u), demoted["a"])
        assertEquals(MediaSourceTier(1u), demoted["b"])
    }

    @Test
    fun `a source that failed every recent start never plays`() {
        val day = 24 * 3600 * 1000L
        val now = 10 * day
        assertTrue(SourceProfiles.neverPlays(listOf(fail(now - 3), fail(now - 2), fail(now - 1)), now))
        // 播起来过一次、失败不到三次、三天前的失败都不算
        assertFalse(SourceProfiles.neverPlays(listOf(fail(now - 4), fail(now - 3), ok(now - 2), fail(now - 1)), now))
        assertFalse(SourceProfiles.neverPlays(listOf(fail(now - 2), fail(now - 1)), now))
        assertFalse(SourceProfiles.neverPlays(listOf(fail(now - 4 * day), fail(now - 2), fail(now - 1)), now))
        // 深度测试与改选掉的不算
        assertFalse(SourceProfiles.neverPlays(listOf(fail(now - 3).copy(test = true), fail(now - 2), fail(now - 1)), now))
    }

    @Test
    fun `sources that never played go below every other source with their channels`() {
        val tiers = MediaSelectorSourceTiers(
            tiers = mapOf("dead" to MediaSourceTier(0u), "failing" to MediaSourceTier(0u), "fine" to MediaSourceTier(4u)),
            channelTiers = mapOf("dead" to mapOf("line" to MediaSourceTier(0u))),
        )
        val demoted = SourceProfiles.demoteFailing(
            tiers,
            SourceProfiles.FailingSources(mostly = setOf("dead", "failing"), never = setOf("dead", "unlisted")),
        )
        assertEquals(MediaSourceTier.MaximumValue, demoted["dead"])
        assertEquals(MediaSourceTier.MaximumValue, demoted.get("dead", "line"))
        assertEquals(MediaSourceTier.MaximumValue, demoted.getBestTier("dead"))
        assertEquals(MediaSourceTier.MaximumValue, demoted["unlisted"])
        assertEquals(MediaSourceTier(2u), demoted["failing"])
        assertEquals(MediaSourceTier(4u), demoted["fine"])
    }

    @Test
    fun `demote only raises the listed sources`() {
        val tiers = MediaSelectorSourceTiers(mapOf("a" to MediaSourceTier(0u), "b" to MediaSourceTier(2u)))
        val demoted = SourceProfiles.demote(tiers, setOf("a"))
        assertEquals(MediaSourceTier(1u), demoted["a"])
        assertEquals(MediaSourceTier(2u), demoted["b"])
        assertEquals(tiers, SourceProfiles.demote(tiers, emptySet()))
    }

    @Test
    fun `demoting a source near the lowest tier stops at the lowest`() {
        val lowest = MediaSourceTier.MaximumValue
        val tiers = MediaSelectorSourceTiers(mapOf("a" to lowest, "b" to MediaSourceTier(lowest.value - 1u)))
        val demoted = SourceProfiles.demote(tiers, setOf("a", "b"), SourceProfiles.FAILING_DEMOTION)
        assertEquals(lowest, demoted["a"])
        assertEquals(lowest, demoted["b"])
    }
}
