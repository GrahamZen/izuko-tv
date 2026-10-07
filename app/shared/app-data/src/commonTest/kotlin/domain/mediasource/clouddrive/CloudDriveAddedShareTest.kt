/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAddedShares
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「我添加的分享」: 用户粘贴的分享链接, 按条目记下, 查询时打开分享对集.
 */
class CloudDriveAddedShareTest {
    private val links = DriveShareLinks(TestDrive.protocol)

    /** 一季三集, 放在打乱了名字的文件夹里. */
    private val season = mapOf(
        TestDrive.SHARE_ROOT to listOf(dir("root", "葬丨送丨的丨芙莉莲")),
        "root" to listOf(video("e1", "01.mp4"), video("e2", "02.mp4"), video("e3", "03.mp4")),
    )

    /** 文件名认不出集号. */
    private val unnamed = mapOf(TestDrive.SHARE_ROOT to listOf(video("x1", "芙莉莲上.mp4"), video("x2", "芙莉莲下.mp4")))

    private fun request(subjectId: Int = 400602, episode: Int = 2) = MediaFetchRequest(
        subjectId = subjectId.toString(),
        episodeId = "1",
        subjectNames = listOf("葬送的芙莉莲"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    /** 不发请求的网盘服务: 分享由 [FakeShares] 直接给出. */
    private val drive = testDriveService(accountsOf(CloudDriveAccount.Default)) { error("unexpected request ${it.url}") }

    private fun service(
        shares: FakeShares,
        settings: MemorySettings<CloudDriveAddedShares> = MemorySettings(CloudDriveAddedShares.Default),
    ) = CloudDriveAddedShareService(settings, drive, browser = shares, savedCopies = { emptyList() })

    private val MemorySettings<CloudDriveAddedShares>.shares get() = state.value.of(TestDrive.ID)

    @Test
    fun `links and passcodes are picked out of pasted text`() {
        assertEquals(listOf(DriveShareLink("abc123", "")), links.parse("https://share.drive.test/s/abc123"))
        assertEquals(listOf(DriveShareLink("abc123", "Xy9z")), links.parse("链接：https://share.drive.test/s/abc123?pwd=Xy9z"))
        assertEquals(
            listOf(DriveShareLink("abc123", "k7Qp")),
            links.parse("葬送的芙莉莲 4K\n链接：https://share.drive.test/s/abc123 提取码：k7Qp\n复制这段内容打开网盘 App"),
        )
        // 各自的提取码跟在各自的链接后面; 重复的只留第一个; 没写 https 的也认
        assertEquals(
            listOf(DriveShareLink("one", "1111"), DriveShareLink("two", "")),
            links.parse("share.drive.test/s/one 密码: 1111\nhttps://share.drive.test/s/two\nhttps://share.drive.test/s/one"),
        )
        // 提取码紧贴在链接后面
        assertEquals(listOf(DriveShareLink("abc123", "k7Qp")), links.parse("https://share.drive.test/s/abc123提取码：k7Qp"))
        // 别的网盘的链接不认
        assertEquals(emptyList(), links.parse("https://other.example/s/xyz 提取码：abcd"))
    }

    @Test
    fun `inspecting a share lists the episodes and the current one`() = runTest {
        val service = service(FakeShares(mutableMapOf("s1" to season), titles = mapOf("s1" to "芙莉莲 全集")))
        val inspection = service.inspect(request(episode = 2), DriveShareLink("s1", ""))

        assertEquals("芙莉莲 全集", inspection.title)
        assertEquals(listOf(EpisodeSort(1), EpisodeSort(2), EpisodeSort(3)), inspection.episodes)
        assertEquals(listOf("e2"), inspection.currentFiles.map { it.fid })
        assertEquals(listOf("葬丨送丨的丨芙莉莲"), inspection.currentFiles.single().folders)
        assertEquals(3, service.readStatusOf("s1")?.videos)
    }

    @Test
    fun `a share without recognizable episodes still lists its videos`() = runTest {
        val inspection = service(FakeShares(mutableMapOf("s1" to unnamed))).inspect(request(), DriveShareLink("s1", ""))

        assertEquals(emptyList(), inspection.episodes)
        assertEquals(emptyList(), inspection.currentFiles)
        assertEquals(listOf("x1", "x2"), inspection.files.map { it.fid })
        assertTrue(inspection.files.all { it.episode == null })
    }

    @Test
    fun `an unavailable share fails the inspection`() = runTest {
        val service = service(FakeShares(unavailable = mutableSetOf("gone")))
        assertFailsWith<CloudDriveShareUnavailableException> { service.inspect(request(), DriveShareLink("gone", "")) }
        assertNotNull(service.readStatusOf("gone")?.error)
    }

    @Test
    fun `shares are remembered per subject and adding again updates in place`() = runTest {
        val settings = MemorySettings(CloudDriveAddedShares.Default)
        val shares = FakeShares(mutableMapOf("s1" to season, "s2" to season))
        val service = service(shares, settings)
        service.add(400602, service.inspect(request(), DriveShareLink("s1", "")))
        service.add(400602, service.inspect(request(), DriveShareLink("s2", "")))
        service.add(400602, service.inspect(request(), DriveShareLink("s1", "pw12")))

        assertEquals(listOf("s1" to "pw12", "s2" to ""), service.sharesOf(400602).map { it.shareId to it.passcode })
        assertEquals(emptyList(), service.sharesOf(1))
        // 按网盘分开记
        assertEquals(setOf(TestDrive.ID), settings.state.value.drives.keys)

        service.remove(400602, "s1")
        assertEquals(listOf("s2"), service.sharesOf(400602).map { it.shareId })
        service.remove(400602, "s2")
        assertEquals(emptyMap(), settings.state.value.drives)
    }

    @Test
    fun `a movie file without an episode number is the only episode`() = runTest {
        val movie = mapOf(
            TestDrive.SHARE_ROOT to listOf(video("m1", "MOBILE.SUIT.GUNDAM.HATHAWAY.The.Sorcery.of.Nymph.Circe.2026.1080p官方中字.mp4")),
        )
        val movieRequest = request(episode = 1).copy(
            subjectNames = listOf("机动战士高达 闪光的哈萨维 喀耳刻的魔女"),
            episodes = listOf(MediaFetchRequest.Episode("ep1", EpisodeSort(1))),
        )
        val inspection = service(FakeShares(mutableMapOf("s1" to movie))).inspect(movieRequest, DriveShareLink("s1", ""))
        assertEquals(listOf(EpisodeSort(1)), inspection.episodes)
        assertEquals(listOf("m1"), inspection.currentFiles.map { it.fid })

        // 不止一集的条目照旧认不出
        val series = request().copy(episodes = listOf(1, 2).map { MediaFetchRequest.Episode("ep$it", EpisodeSort(it)) })
        assertEquals(emptyList(), service(FakeShares(mutableMapOf("s1" to movie))).inspect(series, DriveShareLink("s1", "")).episodes)
    }

    @Test
    fun `a picked file plays as the current episode`() = runTest {
        val service = service(FakeShares(mutableMapOf("s1" to unnamed)))
        val inspection = service.inspect(request(episode = 5), DriveShareLink("s1", "pw12"))

        val media = assertNotNull(service.mediaOf(inspection, "x2", "added", request(episode = 5)))
        assertEquals("added.s1.x2", media.mediaId)
        assertEquals(CloudDriveAddedShareMediaSource.DISPLAY_NAME, media.properties.alliance)
        assertEquals(EpisodeSort(5), media.episodeRange?.knownSorts?.single())
        assertEquals("https://share.drive.test/s/s1", media.originalUrl)
        val location = assertIs<ResourceLocation.HttpStreamingFile>(media.download)
        val ref = assertNotNull(drive.placeholders.parseShareFile(location.uri))
        // 记下文件在分享里所在的文件夹 (这里是分享的根), 播放时到那里找外挂字幕
        assertEquals(DriveShareFileRef("s1", "pw12", "x2", "t-x2", "芙莉莲下.mp4", 500L * 1024 * 1024, folderId = TestDrive.SHARE_ROOT), ref)
        assertNull(service.mediaOf(inspection, "missing", "added", request()))
    }

    // region 数据源: 走网盘服务的分享接口

    /**
     * 配置了测试网盘的网盘列表, 分享按 [shares] 的内容回. 账号登录着, 转存文件夹 `save1` 里放着 [savedCopies].
     */
    private fun TestScope.sourceOf(shares: FakeShares, savedCopies: List<DriveFile> = emptyList()): Pair<CloudDriveAddedShareMediaSource, TestRegistry> {
        val test = testRegistry(
            accounts = accountsOf(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1")),
            client = {
                mockClient { request ->
                    shares.respond(this, request) ?: when {
                        request.url.encodedPath == "/api/files/list" && request.url.parameters["parent"] == "save1" -> reply(listJson(savedCopies))
                        else -> error("unexpected request ${request.url}")
                    }
                }
            },
        )
        return CloudDriveAddedShareMediaSource("added", TestDrive.ID, test.registry) to test
    }

    private suspend fun TestRegistry.addedShareService() = assertNotNull(registry.awaitAddedShareService(TestDrive.ID))

    @Test
    fun `the source only answers for the subject the share was added to and skips broken shares`() = runTest {
        val shares = FakeShares(mutableMapOf("s1" to season), unavailable = mutableSetOf("gone"))
        val (source, test) = sourceOf(shares)
        val service = test.addedShareService()
        service.add(400602, service.inspect(request(), DriveShareLink("s1", "")))
        // 记下之后分享失效了
        service.add(
            400602,
            DriveShareInspection(DriveShareLink("gone", ""), "", listOf(EpisodeSort(1)), emptyList(), emptyList(), emptyMap()),
        )

        val matches = source.fetch(request()).results.toList()
        assertEquals(listOf("added.s1.e1", "added.s1.e2", "added.s1.e3"), matches.map { it.media.mediaId })
        assertTrue(matches.all { it.kind == MatchKind.EXACT })
        assertEquals(listOf("葬送的芙莉莲"), matches.map { it.media.properties.subjectName }.distinct())
        assertEquals(emptyList(), source.fetch(request(subjectId = 1)).results.toList())
    }

    @Test
    fun `the source gives nothing for a drive that is not configured`() = runTest {
        val (_, test) = sourceOf(FakeShares())
        val source = CloudDriveAddedShareMediaSource("added", "otherdrive", test.registry)
        assertEquals(emptyList(), source.fetch(request()).results.toList())
    }

    @Test
    fun `picked files are remembered and override the recognized episode`() = runTest {
        // 默认标题「分享 s2」会被认成第二季
        val shares = FakeShares(mutableMapOf("s1" to unnamed, "s2" to season), titles = mapOf("s2" to "芙莉莲 全集"))
        val (source, test) = sourceOf(shares)
        val service = test.addedShareService()
        // 认不出集号的分享: 手动指定 x2 是第 5 集, 分享随之记下
        service.pick(400602, service.inspect(request(episode = 5), DriveShareLink("s1", "")), "x2", EpisodeSort(5))
        // 认得出的分享: 把 e2 改成第 7 集
        service.add(400602, service.inspect(request(), DriveShareLink("s2", "")))
        service.pick(400602, service.inspect(request(), DriveShareLink("s2", "")), "e2", EpisodeSort(7))
        // 再添加一次不丢手动指定
        service.add(400602, service.inspect(request(), DriveShareLink("s2", "")))

        assertEquals(listOf(mapOf("x2" to "05"), mapOf("e2" to "07")), service.sharesOf(400602).map { it.picks })
        val episodes = source.fetch(request()).results.toList().associate { it.media.mediaId to it.media.episodeRange?.knownSorts?.single() }
        assertEquals(
            mapOf(
                "added.s1.x2" to EpisodeSort(5),
                "added.s2.e1" to EpisodeSort(1),
                "added.s2.e3" to EpisodeSort(3),
                "added.s2.e2" to EpisodeSort(7),
            ),
            episodes,
        )
    }

    @Test
    fun `an emptied or broken share still offers the episodes saved to the drive`() = runTest {
        val folders = mutableMapOf("s1" to season)
        val unavailable = mutableSetOf<String>()
        val shares = FakeShares(folders, titles = mapOf("s1" to "芙莉莲 全集"), unavailable = unavailable)
        // 第 2 集播过, 转存到了自己网盘
        val (source, test) = sourceOf(shares, savedCopies = listOf(DriveFile("copy2", fileName = "02.mp4", size = 500L * 1024 * 1024)))
        val service = test.addedShareService()
        service.add(400602, service.inspect(request(), DriveShareLink("s1", "")))

        assertEquals(3, source.fetch(request()).results.toList().size)
        assertEquals(listOf("e1", "e2", "e3"), test.addedShares.shares.of(400602).single().files.map { it.fid })
        assertEquals(3, service.readStatusOf("s1")?.videos)

        // 分享被清空: 只给自己网盘里有副本的那一集, 资源 id 不变; 记下的剧集不被冲掉
        folders["s1"] = emptyMap()
        val medias = source.fetch(request()).results.toList().map { it.media }
        assertEquals(listOf("added.s1.e2"), medias.map { it.mediaId })
        assertEquals(EpisodeSort(2), medias.single().episodeRange?.knownSorts?.single())
        val emptied = assertNotNull(service.readStatusOf("s1"))
        assertEquals(0, emptied.videos)
        assertNull(emptied.error)
        assertEquals(1, emptied.savedCopies)
        assertEquals(3, test.addedShares.shares.of(400602).single().files.size)

        // 分享打不开也一样
        unavailable += "s1"
        assertEquals(listOf("added.s1.e2"), source.fetch(request()).results.toList().map { it.media.mediaId })
        val broken = assertNotNull(service.readStatusOf("s1"))
        assertNotNull(broken.error)
        assertEquals(1, broken.savedCopies)
    }

    // endregion
}
