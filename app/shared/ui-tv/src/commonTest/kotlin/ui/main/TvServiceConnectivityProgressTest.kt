/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals
import me.him188.ani.app.domain.settings.ServiceConnectionTesters

class TvServiceConnectivityProgressTest {
    private fun probe(id: String, result: TvServiceProbeResult) =
        TvServiceProbeState(TvServiceProbe(id, TvServiceTier.Required), result)

    @Test
    fun `fraction counts probes that have a result`() {
        val probes = listOf(
            probe(ServiceConnectionTesters.ID_BANGUMI_NEXT, TvServiceProbeResult.Ok),
            probe(ServiceConnectionTesters.ID_BANGUMI, TvServiceProbeResult.Failed),
            probe(ServiceConnectionTesters.ID_TMDB, TvServiceProbeResult.Pending),
            probe(ServiceConnectionTesters.ID_TMDB_IMAGE, TvServiceProbeResult.Pending),
        )
        assertEquals(0.5f, probes.completedFraction())
    }

    @Test
    fun `fraction is zero before any result and one when all are done`() {
        val ids = listOf(ServiceConnectionTesters.ID_BANGUMI, ServiceConnectionTesters.ID_TMDB)
        assertEquals(0f, ids.map { probe(it, TvServiceProbeResult.Pending) }.completedFraction())
        assertEquals(1f, ids.map { probe(it, TvServiceProbeResult.Ok) }.completedFraction())
        assertEquals(0f, emptyList<TvServiceProbeState>().completedFraction())
    }
}
