/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.DriveRememberedShare
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 「分享搜索」记住播过的那一集所在的分享文件夹: 之后先只列它, 有要的那一集就不再去站点搜索.
 */
class CloudDriveShareRememberTest {
    private val episode1 = video("f1", "Smoking.S01E01.mp4", parent = "d1", size = 300_000_000, token = "t1")
    private val episode3 = video("f3", "Smoking.S01E03.mp4", parent = "d1", size = 300_000_000, token = "t3")

    private var siteSearches = 0
    private var shareGone = false
    private val folderListings = mutableListOf<String>()

    private val client = mockClient { request ->
        val path = request.url.encodedPath
        when {
            request.url.host == "site.test" -> {
                siteSearches++
                reply("""{"list":[{"vod_name":"在超市后门吸烟的二人","vod_down_url":"https://share.drive.test/s/s1"}]}""")
            }

            path == "/api/share/open" -> if (shareGone) {
                reply("""{"code":404,"message":"分享已取消"}""")
            } else {
                reply("""{"code":0,"data":{"token":"st1","title":"超市后门"}}""")
            }

            path == "/api/share/list" -> {
                val folder = request.url.parameters["parent"].orEmpty()
                folderListings += folder
                when {
                    shareGone -> reply("""{"code":404,"message":"分享已取消"}""")
                    folder == TestDrive.SHARE_ROOT -> reply(listJson(dir("d1", "S01")))
                    folder == "d1" -> reply(listJson(episode1, episode3))
                    else -> reply(listJson())
                }
            }

            // 转存文件夹里已经有这一集的副本 (同名同大小), 播放时不再转存
            path == "/api/files/list" && request.url.parameters["parent"] == "save1" ->
                reply(listJson(DriveFile("saved1", fileName = "Smoking.S01E01.mp4", size = 300_000_000)))

            path == "/api/files/download" -> reply(downloadJson(request.jsonBody().strings("ids")))
            else -> error("unexpected request ${request.method.value} ${request.url}")
        }
    }

    private fun request(episode: Int) = MediaFetchRequest(
        subjectId = "571784",
        episodeId = "$episode",
        subjectNames = listOf("在超市后门吸烟的二人"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    @Test
    fun `remembers the share folder of a played episode and lists it instead of searching`() = runTest {
        val test = testRegistry(
            accounts = accountsOf(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1")),
            client = { client },
        )
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
        val service = assertNotNull(test.registry.awaitService(TestDrive.ID))
        val remembered = { test.accounts.account.rememberedShares }

        // 找字幕有超时上限, 模拟的 HTTP 跑在真线程上: 这一段用真实时间
        withContext(Dispatchers.Default) {
            suspend fun fetch(episode: Int) = source.fetch(request(episode)).results.toList().map { it.media }

            // 第一集: 照常搜; 只是搜到还不记, 播了才记
            val first = fetch(1)
            assertEquals(listOf("share-site.s1.f1", "share-site.s1.f3"), first.map { it.mediaId })
            assertEquals(1, siteSearches)
            assertEquals(emptyMap(), remembered())
            val ref = assertNotNull(service.placeholders.parseShareFile(first.first().download.uri))
            assertEquals("d1", ref.folderId)
            val playback = service.resolveSharePlayback(ref)
            assertEquals("https://dl.drive.test/saved1", playback.url)
            // 本机数据按分享里的文件存: 转存的副本被删了重新转存, 内容还是同一个
            assertEquals("testdrive-share:s1/f1", playback.headers[PlaybackRequestHints.CACHE_KEY_HEADER])
            assertEquals(
                DriveRememberedShare("s1", "", "在超市后门吸烟的二人", "d1", listOf("S01")),
                remembered()["share-site:571784"],
            )

            // 第三集在记下的文件夹里: 不去站点搜索, 也只列那一个文件夹
            folderListings.clear()
            assertEquals(listOf("share-site.s1.f1", "share-site.s1.f3"), fetch(3).map { it.mediaId })
            assertEquals(1, siteSearches)
            assertEquals(listOf("d1"), folderListings)

            // 第二集文件夹里没有: 照常搜
            fetch(2)
            assertEquals(2, siteSearches)

            // 分享被取消: 忘掉, 照常搜
            shareGone = true
            assertEquals(emptyList(), fetch(3))
            assertEquals(3, siteSearches)
            assertEquals(emptyMap(), remembered())
        }
    }

    @Test
    fun `gives nothing without login or when the drive is not configured`() = runTest {
        val test = testRegistry(client = { client })
        fun source(drive: String) = CloudDriveShareSearchMediaSource(
            "share-site",
            CloudDriveShareSearchArguments(
                name = "站点",
                drive = drive,
                config = DriveShareSearchConfig(searchUrl = "https://site.test/search?wd={keyword}", itemsPath = "list", userAgent = "test"),
            ),
            client.asScopedHttpClient(),
            test.registry,
        )
        assertEquals(emptyList(), source(TestDrive.ID).fetch(request(1)).results.toList())
        assertEquals(emptyList(), source("otherdrive").fetch(request(1)).results.toList())
        assertEquals(0, siteSearches)
    }
}
