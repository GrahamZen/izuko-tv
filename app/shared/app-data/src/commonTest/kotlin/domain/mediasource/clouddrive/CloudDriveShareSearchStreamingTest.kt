/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * 分享搜索源每读完一个分享就把认出的剧集交给选源, 不等最慢的那个分享.
 */
class CloudDriveShareSearchStreamingTest {
    /** 第二个分享的目录要等它放行才回 */
    private val secondShareGate = CompletableDeferred<Unit>()

    private val client = mockClient { request ->
        val path = request.url.encodedPath
        when {
            request.url.host == "site.test" ->
                reply("""{"list":[{"vod_name":"在超市后门吸烟的二人","vod_down_url":"https://share.drive.test/s/s1 https://share.drive.test/s/s2"}]}""")

            path == "/api/share/open" -> reply("""{"code":0,"data":{"token":"st","title":"超市后门"}}""")

            path == "/api/share/list" -> when (request.url.parameters["share"]) {
                "s1" -> reply(listJson(video("a1", "Smoking.S01E01.mp4", size = 300_000_000, token = "t")))
                else -> {
                    secondShareGate.await()
                    reply(listJson(video("b1", "Smoking.S01E01.1080p.mp4", size = 300_000_000, token = "t")))
                }
            }

            else -> error("unexpected request ${request.method.value} ${request.url}")
        }
    }

    @Test
    fun `episodes of the first share arrive before the slow share is read`() = runTest {
        val test = testRegistry(accounts = accountsOf(CloudDriveAccount(cookie = "sid=p1")), client = { client })
        val source = CloudDriveShareSearchMediaSource(
            "share-site",
            CloudDriveShareSearchArguments(
                name = "站点",
                drive = TestDrive.ID,
                config = DriveShareSearchConfig(
                    searchUrl = "https://site.test/search?wd={keyword}",
                    itemsPath = "list",
                    titlePath = "vod_name",
                    linkPaths = listOf("vod_down_url"),
                    maxKeywords = 1,
                    userAgent = "test",
                ),
            ),
            client.asScopedHttpClient(),
            test.registry,
        )
        val request = MediaFetchRequest(
            subjectId = "571784", episodeId = "1", subjectNames = listOf("在超市后门吸烟的二人"), episodeSort = EpisodeSort(1), episodeName = "",
        )

        // 模拟的 HTTP 跑在真线程上: 这一段用真实时间
        withContext(Dispatchers.Default) {
            val arrived = Channel<MediaMatch>(Channel.UNLIMITED)
            val collecting = launch { source.fetch(request).results.collect { arrived.send(it) } }
            assertEquals("share-site.s1.a1", withTimeout(10.seconds) { arrived.receive() }.media.mediaId)
            secondShareGate.complete(Unit)
            collecting.join()
            assertEquals("share-site.s2.b1", arrived.receive().media.mediaId)
        }
    }
}
