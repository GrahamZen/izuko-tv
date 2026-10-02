/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class RssSourceKindTest {
    private fun config(searchConfig: RssSearchConfig) = MediaSourceConfig(
        serializedArguments = MediaSourceConfig.serializeArguments(
            RssMediaSourceArguments.serializer(),
            RssMediaSourceArguments(name = "test", description = "", iconUrl = "", searchConfig = searchConfig),
        ),
    )

    @Test
    fun `sources that rewrite titles into direct links are online sources`() {
        val config = config(
            RssSearchConfig(
                searchUrl = "https://example.com/rss?keyword={keyword}",
                titleToLink = RssTitleToLink(pattern = "^(?<file>.+)$", url = "https://files.example.com/{file}"),
            ),
        )
        assertEquals(MediaSourceKind.WEB, rssSourceKindOf(config))
        assertEquals(MediaSourceKind.WEB, RssMediaSource.Factory().create("ani", config, HttpClient(MockEngine { respondOk() }).asScopedHttpClient()).kind)
    }

    @Test
    fun `plain torrent feeds stay bit torrent sources`() {
        assertEquals(MediaSourceKind.BitTorrent, rssSourceKindOf(config(RssSearchConfig(searchUrl = "https://example.com/rss?keyword={keyword}"))))
        assertEquals(MediaSourceKind.BitTorrent, rssSourceKindOf(MediaSourceConfig()))
    }
}
