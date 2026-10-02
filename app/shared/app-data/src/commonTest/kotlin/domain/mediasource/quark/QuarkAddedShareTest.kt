/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.QuarkAddedShares
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.datasources.api.EpisodeSort
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
 * 「我添加的分享」: 用户粘贴的夸克分享链接, 按条目记下, 查询时打开分享对集.
 */
class QuarkAddedShareTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class FakeShares(
        val folders: Map<String, Map<String, List<QuarkShareFile>>>,
        val titles: Map<String, String> = emptyMap(),
        val unavailable: Set<String> = emptySet(),
    ) : QuarkShareBrowser {
        override suspend fun open(shareId: String, passcode: String): String {
            if (shareId in unavailable) throw QuarkShareUnavailableException(41004, "文件不存在")
            return titles[shareId] ?: "分享 $shareId"
        }

        override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<QuarkShareFile> =
            folders[shareId]?.get(folderId).orEmpty()
    }

    private fun dir(fid: String, name: String) = QuarkShareFile(fid = fid, fileName = name, dir = true)

    private fun video(fid: String, name: String) =
        QuarkShareFile(fid = fid, fileName = name, size = 500L * 1024 * 1024, category = "video", shareFidToken = "t-$fid")

    /** 一季三集, 放在打乱了名字的文件夹里. */
    private val season = mapOf(
        "0" to listOf(dir("root", "葬丨送丨的丨芙莉莲")),
        "root" to listOf(video("e1", "01.mp4"), video("e2", "02.mp4"), video("e3", "03.mp4")),
    )

    /** 文件名认不出集号. */
    private val unnamed = mapOf("0" to listOf(video("x1", "芙莉莲上.mp4"), video("x2", "芙莉莲下.mp4")))

    private fun request(subjectId: Int = 400602, episode: Int = 2) = MediaFetchRequest(
        subjectId = subjectId.toString(),
        episodeId = "1",
        subjectNames = listOf("葬送的芙莉莲"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    private fun service(
        shares: FakeShares,
        settings: MemorySettings<QuarkAddedShares> = MemorySettings(QuarkAddedShares.Default),
        savedCopies: () -> List<QuarkFile> = { emptyList() },
    ) = QuarkAddedShareService(settings, shares) { savedCopies() }

    @Test
    fun `links and passcodes are picked out of pasted text`() {
        assertEquals(listOf(QuarkShareLink("abc123", "")), QuarkShareLinks.parse("https://pan.quark.cn/s/abc123"))
        assertEquals(listOf(QuarkShareLink("abc123", "Xy9z")), QuarkShareLinks.parse("链接：https://pan.quark.cn/s/abc123?pwd=Xy9z"))
        assertEquals(
            listOf(QuarkShareLink("abc123", "k7Qp")),
            QuarkShareLinks.parse("葬送的芙莉莲 4K\n链接：https://pan.quark.cn/s/abc123 提取码：k7Qp\n复制这段内容打开夸克"),
        )
        // 各自的提取码跟在各自的链接后面; 重复的只留第一个; 没写 https 的也认
        assertEquals(
            listOf(QuarkShareLink("one", "1111"), QuarkShareLink("two", "")),
            QuarkShareLinks.parse("pan.quark.cn/s/one 密码: 1111\nhttps://pan.quark.cn/s/two\nhttps://pan.quark.cn/s/one"),
        )
        // 提取码紧贴在链接后面
        assertEquals(listOf(QuarkShareLink("abc123", "k7Qp")), QuarkShareLinks.parse("https://pan.quark.cn/s/abc123提取码：k7Qp"))
        assertEquals(emptyList(), QuarkShareLinks.parse("https://pan.baidu.com/s/xyz 提取码：abcd"))
    }

    @Test
    fun `inspecting a share lists the episodes and the current one`() = runTest {
        val service = service(FakeShares(mapOf("s1" to season), titles = mapOf("s1" to "芙莉莲 全集")))
        val inspection = service.inspect(request(episode = 2), QuarkShareLink("s1", ""))

        assertEquals("芙莉莲 全集", inspection.title)
        assertEquals(listOf(EpisodeSort(1), EpisodeSort(2), EpisodeSort(3)), inspection.episodes)
        assertEquals(listOf("e2"), inspection.currentFiles.map { it.fid })
        assertEquals(listOf("葬丨送丨的丨芙莉莲"), inspection.currentFiles.single().folders)
    }

    @Test
    fun `a share without recognizable episodes still lists its videos`() = runTest {
        val inspection = service(FakeShares(mapOf("s1" to unnamed))).inspect(request(), QuarkShareLink("s1", ""))

        assertEquals(emptyList(), inspection.episodes)
        assertEquals(emptyList(), inspection.currentFiles)
        assertEquals(listOf("x1", "x2"), inspection.files.map { it.fid })
        assertTrue(inspection.files.all { it.episode == null })
    }

    @Test
    fun `an unavailable share fails the inspection`() = runTest {
        val service = service(FakeShares(emptyMap(), unavailable = setOf("gone")))
        assertFailsWith<QuarkShareUnavailableException> { service.inspect(request(), QuarkShareLink("gone", "")) }
    }

    @Test
    fun `shares are remembered per subject and adding again updates in place`() = runTest {
        val settings = MemorySettings(QuarkAddedShares.Default)
        val shares = FakeShares(mapOf("s1" to season, "s2" to season))
        val service = service(shares, settings)
        service.add(400602, service.inspect(request(), QuarkShareLink("s1", "")))
        service.add(400602, service.inspect(request(), QuarkShareLink("s2", "")))
        service.add(400602, service.inspect(request(), QuarkShareLink("s1", "pw12")))

        assertEquals(listOf("s1" to "pw12", "s2" to ""), service.sharesOf(400602).map { it.shareId to it.passcode })
        assertEquals(emptyList(), service.sharesOf(1))

        service.remove(400602, "s1")
        assertEquals(listOf("s2"), service.sharesOf(400602).map { it.shareId })
        service.remove(400602, "s2")
        assertEquals(emptyMap(), settings.state.value.subjects)
    }

    @Test
    fun `the source only answers for the subject the share was added to and skips broken shares`() = runTest {
        val shares = FakeShares(mapOf("s1" to season), unavailable = setOf("gone"))
        val service = service(shares)
        service.add(400602, service.inspect(request(), QuarkShareLink("s1", "")))
        // 记下之后分享失效了
        service.add(
            400602,
            QuarkShareInspection(QuarkShareLink("gone", ""), "", listOf(EpisodeSort(1)), emptyList(), emptyList(), emptyMap()),
        )
        val source = QuarkAddedShareMediaSource("added", service)

        val medias = source.fetch(request()).results.toList().map { it.media }
        assertEquals(listOf("added.s1.e1", "added.s1.e2", "added.s1.e3"), medias.map { it.mediaId })
        assertEquals(listOf("葬送的芙莉莲"), medias.map { it.properties.subjectName }.distinct())
        assertEquals(emptyList(), source.fetch(request(subjectId = 1)).results.toList())
    }

    @Test
    fun `a movie file without an episode number is the only episode`() = runTest {
        val movie = mapOf("0" to listOf(video("m1", "MOBILE.SUIT.GUNDAM.HATHAWAY.The.Sorcery.of.Nymph.Circe.2026.1080p官方中字.mp4")))
        val movieRequest = request(episode = 1).copy(
            subjectNames = listOf("机动战士高达 闪光的哈萨维 喀耳刻的魔女"),
            episodes = listOf(MediaFetchRequest.Episode("ep1", EpisodeSort(1))),
        )
        val inspection = service(FakeShares(mapOf("s1" to movie))).inspect(movieRequest, QuarkShareLink("s1", ""))
        assertEquals(listOf(EpisodeSort(1)), inspection.episodes)
        assertEquals(listOf("m1"), inspection.currentFiles.map { it.fid })

        // 不止一集的条目照旧认不出
        val series = request().copy(episodes = listOf(1, 2).map { MediaFetchRequest.Episode("ep$it", EpisodeSort(it)) })
        assertEquals(emptyList(), service(FakeShares(mapOf("s1" to movie))).inspect(series, QuarkShareLink("s1", "")).episodes)
    }

    @Test
    fun `picked files are remembered and override the recognized episode`() = runTest {
        val settings = MemorySettings(QuarkAddedShares.Default)
        // 默认标题「分享 s2」会被认成第二季
        val shares = FakeShares(mapOf("s1" to unnamed, "s2" to season), titles = mapOf("s2" to "芙莉莲 全集"))
        val service = service(shares, settings)
        // 认不出集号的分享: 手动指定 x2 是第 5 集, 分享随之记下
        service.pick(400602, service.inspect(request(episode = 5), QuarkShareLink("s1", "")), "x2", EpisodeSort(5))
        // 认得出的分享: 把 e2 改成第 7 集
        service.add(400602, service.inspect(request(), QuarkShareLink("s2", "")))
        service.pick(400602, service.inspect(request(), QuarkShareLink("s2", "")), "e2", EpisodeSort(7))
        // 再添加一次不丢手动指定
        service.add(400602, service.inspect(request(), QuarkShareLink("s2", "")))

        assertEquals(listOf(mapOf("x2" to "05"), mapOf("e2" to "07")), service.sharesOf(400602).map { it.picks })
        val source = QuarkAddedShareMediaSource("added", service)
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
        val settings = MemorySettings(QuarkAddedShares.Default)
        val folders = mutableMapOf("s1" to season)
        val unavailable = mutableSetOf<String>()
        val shares = FakeShares(folders, titles = mapOf("s1" to "芙莉莲 全集"), unavailable = unavailable)
        // 第 2 集播过, 转存到了自己网盘
        val copies = listOf(QuarkFile(fid = "copy2", fileName = "02.mp4", size = 500L * 1024 * 1024))
        val service = service(shares, settings) { copies }
        service.add(400602, service.inspect(request(), QuarkShareLink("s1", "")))
        val source = QuarkAddedShareMediaSource("added", service)

        assertEquals(3, source.fetch(request()).results.toList().size)
        assertEquals(listOf("e1", "e2", "e3"), settings.state.value.of(400602).single().files.map { it.fid })
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
        assertEquals(3, settings.state.value.of(400602).single().files.size)

        // 分享打不开也一样
        unavailable += "s1"
        assertEquals(listOf("added.s1.e2"), source.fetch(request()).results.toList().map { it.media.mediaId })
        val broken = assertNotNull(service.readStatusOf("s1"))
        assertNotNull(broken.error)
        assertEquals(1, broken.savedCopies)
    }

    @Test
    fun `a picked file plays as the current episode`() = runTest {
        val service = service(FakeShares(mapOf("s1" to unnamed)))
        val inspection = service.inspect(request(episode = 5), QuarkShareLink("s1", "pw12"))

        val media = assertNotNull(service.mediaOf(inspection, "x2", "added", request(episode = 5)))
        assertEquals("added.s1.x2", media.mediaId)
        assertEquals(EpisodeSort(5), media.episodeRange?.knownSorts?.single())
        val location = assertIs<ResourceLocation.HttpStreamingFile>(media.download)
        val ref = assertNotNull(QuarkShareFileRef.parse(location.uri))
        // 记下文件在分享里所在的文件夹 (这里是分享的根), 播放时到那里找外挂字幕
        assertEquals(QuarkShareFileRef("s1", "pw12", "x2", "t-x2", "芙莉莲下.mp4", 500L * 1024 * 1024, folderId = "0"), ref)
        assertNull(service.mediaOf(inspection, "missing", "added", request()))
    }
}
