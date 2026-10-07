/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.data.models.preference.CloudDriveAddedShares
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.data.models.preference.DriveAddedShare
import me.him188.ani.app.data.models.preference.DriveAddedShares
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.resolver.CloudDriveMediaResolver
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.HttpStreamingMediaDataProvider
import me.him188.ani.app.domain.media.resolver.MediaResolutionException
import me.him188.ani.app.domain.media.resolver.ResolutionFailures
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 网盘列表: 从保存的「网盘」数据源读出协议、按网盘认资源地址, 以及认领这个网盘以前留下的数据.
 */
class CloudDriveRegistryTest {
    private val other = CloudDriveProtocol(
        id = "otherdrive",
        name = "另一个网盘",
        links = DriveLinkTemplates(file = "https://other.example/file/{fileId}"),
        share = DriveShareConfig(url = "https://other.example/s/{shareId}"),
    )

    private val withLegacy = TestDrive.protocol.copy(legacy = TestDrive.legacy)

    private fun save(instanceId: String, factoryId: String, arguments: JsonObject? = null, isEnabled: Boolean = true) =
        MediaSourceSave(instanceId, "$instanceId-source", FactoryId(factoryId), isEnabled, MediaSourceConfig(serializedArguments = arguments))

    // region 网盘列表与资源

    @Test
    fun `drives come from saved drive sources whether enabled or not`() = runTest {
        val test = testRegistry(
            saves = listOf(
                save("dmhy", "dmhy"),
                driveSave(TestDrive.protocol, isEnabled = false),
                driveSave(other),
                // 同一个网盘的第二个数据源不再建服务
                driveSave(TestDrive.protocol.copy(name = "重复"), instanceId = "duplicate"),
            ),
        )
        val drives = test.registry.drives.first { it != null }!!
        assertEquals(listOf(TestDrive.ID, "otherdrive"), drives.map { it.driveId })
        assertEquals("测试网盘", drives.first().protocol.name)
        assertSame(drives.first(), test.registry.service(TestDrive.ID))
        assertNull(test.registry.service("nodrive"))
        assertNull(test.registry.addedShareService("nodrive"))
        assertSame(drives.first(), test.registry.addedShareService(TestDrive.ID)?.drive)
    }

    /** 一个「网盘」数据源的参数坏了 (没有协议) 只跳过它, 别的网盘照常出来. */
    @Test
    fun `a drive source with broken arguments is skipped`() = runTest {
        val broken = save("broken", CloudDriveMediaSource.FactoryId.value, buildJsonObject { put("name", "坏的") })
        val test = testRegistry(saves = listOf(broken, driveSave()))
        val drives = test.registry.drives.first { it != null }!!
        assertEquals(listOf(TestDrive.ID), drives.map { it.driveId })
    }

    @Test
    fun `the same drive keeps one service and takes the newer protocol`() = runTest {
        val test = testRegistry()
        val service = assertNotNull(test.registry.awaitService(TestDrive.ID))
        val updated = TestDrive.protocol.copy(name = "新名字", tiers = emptyList())
        assertSame(service, test.registry.serviceFor(updated))
        assertEquals("新名字", service.protocol.name)
        assertEquals(TestDrive.DEFAULT_CONNECTIONS, service.parallelConnectionsFor("PRO"))
        assertFailsWith<IllegalArgumentException> { service.updateProtocol(other) }
    }

    @Test
    fun `drives are known by their placeholders`() = runTest {
        val test = testRegistry(saves = listOf(driveSave(TestDrive.protocol), driveSave(other)))
        val testDrive = assertNotNull(test.registry.awaitService(TestDrive.ID))
        val otherDrive = assertNotNull(test.registry.awaitService("otherdrive"))
        val ref = DriveShareFileRef("s1", "", "f1", "t1", "01.mkv", 1)

        assertSame(testDrive, test.registry.driveOf(testDrive.placeholders.fileUri("f1")))
        assertSame(testDrive, test.registry.driveOf(testDrive.placeholders.shareFileUri(ref)))
        assertSame(otherDrive, test.registry.driveOf(otherDrive.placeholders.fileUri("f1")))
        assertSame(otherDrive, test.registry.driveOf(otherDrive.placeholders.shareFileUri(ref)))
        // 普通的分享页与别的地址不是资源
        assertNull(test.registry.driveOf(testDrive.placeholders.shareUrl("s1")))
        assertNull(test.registry.driveOf("https://example.com/video.mp4"))
    }

    private fun mediaAt(uri: String): Media = DefaultMedia(
        mediaId = "m",
        mediaSourceId = "s",
        originalUrl = uri,
        download = ResourceLocation.HttpStreamingFile(uri),
        originalTitle = "Show - 01.mkv",
        publishedTime = 0,
        properties = MediaProperties(subjectName = null, episodeName = null, subtitleLanguageIds = emptyList(), resolution = "1080P", alliance = "", size = FileSize.Unspecified, subtitleKind = null),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    private val episode = EpisodeMetadata("第一集", EpisodeSort(1), EpisodeSort(1))

    @Test
    fun `resolver plays files of configured drives`() = runTest {
        val test = testRegistry(
            accounts = accountsOf(TestDrive.loggedIn),
            client = {
                mockClient { request ->
                    when (request.url.encodedPath) {
                        "/api/files/download" -> reply(downloadJson(request.jsonBody().strings("ids")))
                        else -> error("unexpected request ${request.url}")
                    }
                }
            },
        )
        val drive = assertNotNull(test.registry.awaitService(TestDrive.ID))
        val resolver = CloudDriveMediaResolver(test.registry)

        val media = CloudDriveMediaSource.mediaFor(drive, DriveFile("f1", fileName = "Show - 01.mkv", isVideo = true), EpisodeSort(1), null)
        assertTrue(resolver.supports(media))
        assertTrue(resolver.supports(mediaAt(drive.placeholders.shareFileUri(DriveShareFileRef("s1", "", "f1", "t1", "01.mkv", 1)))))
        assertTrue(!resolver.supports(mediaAt("https://other.example/file/f1")))
        assertTrue(!resolver.supports(mediaAt("https://example.com/video.mp4")))

        val provider = assertIs<HttpStreamingMediaDataProvider>(resolver.resolve(media, episode))
        assertEquals("https://dl.drive.test/f1", provider.uri)
    }

    @Test
    fun `resolver reports login and share failures`() = runTest {
        val accounts = accountsOf(CloudDriveAccount.Default)
        val test = testRegistry(
            accounts = accounts,
            client = {
                mockClient { request ->
                    when (request.url.encodedPath) {
                        "/api/files/list" -> reply(listJson())
                        "/api/share/open" -> reply("""{"code":404,"message":"分享已取消"}""")
                        else -> error("unexpected request ${request.url}")
                    }
                }
            },
        )
        val drive = assertNotNull(test.registry.awaitService(TestDrive.ID))
        val resolver = CloudDriveMediaResolver(test.registry)
        val fileMedia = mediaAt(drive.placeholders.fileUri("f1"))
        val shareMedia = mediaAt(drive.placeholders.shareFileUri(DriveShareFileRef("s1", "", "f1", "t1", "01.mkv", 1)))

        val notLoggedIn = assertFailsWith<MediaResolutionException> { resolver.resolve(fileMedia, episode) }
        assertEquals(ResolutionFailures.ENGINE_ERROR, notLoggedIn.reason)
        assertIs<CloudDriveAuthException>(notLoggedIn.cause)

        accounts.state.value = CloudDriveAccounts.Default.with(TestDrive.ID, TestDrive.loggedIn.copy(shareSaveFolderId = "save1"))
        val gone = assertFailsWith<MediaResolutionException> { resolver.resolve(shareMedia, episode) }
        assertEquals(ResolutionFailures.NO_MATCHING_RESOURCE, gone.reason)
    }

    // endregion

    // region 认领旧数据

    private val oldAccount = CloudDriveAccount(
        cookie = "sid=old; sig=s0",
        nickname = "旧账号",
        tier = "PRO",
        playbackMode = CloudDrivePlaybackMode.TRANSCODED,
        shareSaveFolderId = "save9",
    )

    private val oldShares = DriveAddedShares(
        subjects = mapOf(
            1 to listOf(DriveAddedShare("s1", title = "旧分享 1")),
            2 to listOf(DriveAddedShare("s2", title = "旧分享 2")),
        ),
    )

    private val oldSearchArguments = Json.parseToJsonElement(
        """{"name":"站点","description":"旧的分享搜索","config":{"searchUrl":"https://site.test/search?wd={keyword}","itemsPath":"list","maxShares":2}}""",
    ) as JsonObject

    private fun legacySaves() = listOf(
        driveSave(withLegacy),
        save("old-drive", TestDrive.legacy.driveFactoryId),
        save("old-added", TestDrive.legacy.addedSharesFactoryId, isEnabled = false),
        save("old-search", TestDrive.legacy.shareSearchFactoryId, oldSearchArguments),
        save("dmhy", "dmhy"),
    )

    private fun legacyPreferences() = FakeLegacyPreferences(
        mapOf(
            TestDrive.legacy.accountKey to DataStoreJson.encodeToString(CloudDriveAccount.serializer(), oldAccount),
            TestDrive.legacy.addedSharesKey to DataStoreJson.encodeToString(DriveAddedShares.serializer(), oldShares),
            "unrelated" to "{}",
        ),
    )

    @Test
    fun `legacy account and added shares are moved into the drive settings`() = runTest {
        val legacy = legacyPreferences()
        // 新设置里已经有条目 2 的分享: 以新的为准
        val current = DriveAddedShares(subjects = mapOf(2 to listOf(DriveAddedShare("s3"))))
        val test = testRegistry(
            saves = legacySaves(),
            legacy = legacy,
            addedShares = MemorySettings(CloudDriveAddedShares.Default.with(TestDrive.ID, current)),
        )
        test.accounts.state.first { it.of(TestDrive.ID).isLoggedIn }
        test.addedShares.state.first { 1 in it.of(TestDrive.ID).subjects }

        assertEquals(oldAccount, test.accounts.account)
        assertEquals(
            mapOf(1 to listOf("s1"), 2 to listOf("s3")),
            test.addedShares.state.value.of(TestDrive.ID).subjects.mapValues { (_, shares) -> shares.map { it.shareId } },
        )
        // 认领完删掉旧的键, 别的不动
        assertEquals(setOf("unrelated"), legacy.values.keys)
    }

    @Test
    fun `legacy sources are converted to the drive keeping their ids and enabled state`() = runTest {
        val test = testRegistry(saves = legacySaves(), legacy = legacyPreferences())
        val legacyFactories = with(TestDrive.legacy) { setOf(driveFactoryId, addedSharesFactoryId, shareSearchFactoryId) }

        val saves = test.instances.flow.first { saves -> saves.none { it.factoryId.value in legacyFactories } }.associateBy { it.instanceId }
        // 旧的「自己网盘」数据源由带协议的那个代替
        assertEquals(setOf("drive-testdrive", "old-added", "old-search", "dmhy"), saves.keys)

        val added = saves.getValue("old-added")
        assertEquals(CloudDriveAddedShareMediaSource.FactoryId, added.factoryId)
        assertEquals("old-added-source", added.mediaSourceId)
        assertEquals(false, added.isEnabled)
        assertEquals(CloudDriveAddedShareArguments(TestDrive.ID), added.config.deserializeArgumentsOrNull(CloudDriveAddedShareArguments.serializer()))

        val search = saves.getValue("old-search")
        assertEquals(CloudDriveShareSearchMediaSource.FactoryId, search.factoryId)
        assertEquals("old-search-source", search.mediaSourceId)
        assertEquals(true, search.isEnabled)
        // 参数原样搬过来, 加上网盘 id
        assertEquals(JsonObject(oldSearchArguments + ("drive" to JsonPrimitive(TestDrive.ID))), search.config.serializedArguments)
        val arguments = assertNotNull(search.config.deserializeArgumentsOrNull(CloudDriveShareSearchArguments.serializer()))
        assertEquals("站点", arguments.name)
        assertEquals(TestDrive.ID, arguments.drive)
        assertEquals("https://site.test/search?wd={keyword}", arguments.config.searchUrl)
        assertEquals(2, arguments.config.maxShares)

        assertEquals(FactoryId("dmhy"), saves.getValue("dmhy").factoryId)

        // 之后再出现的旧数据源 (例如导入以前的备份) 照样转换
        test.instances.add(save("late", TestDrive.legacy.shareSearchFactoryId, oldSearchArguments))
        val late = test.instances.flow.first { saves -> saves.none { it.factoryId.value in legacyFactories } }.single { it.instanceId == "late" }
        assertEquals(CloudDriveShareSearchMediaSource.FactoryId, late.factoryId)
    }

    @Test
    fun `a drive that is already logged in keeps its account`() = runTest {
        val legacy = legacyPreferences()
        val current = CloudDriveAccount(cookie = "sid=new")
        val test = testRegistry(saves = legacySaves(), legacy = legacy, accounts = accountsOf(current))
        test.awaitDrives()

        assertEquals(current, test.accounts.account)
        assertNull(legacy.values[TestDrive.legacy.accountKey])
    }

    @Test
    fun `broken legacy data is dropped`() = runTest {
        val legacy = FakeLegacyPreferences(mapOf(TestDrive.legacy.accountKey to "not json", TestDrive.legacy.addedSharesKey to "[]"))
        val test = testRegistry(saves = listOf(driveSave(withLegacy)), legacy = legacy)
        test.awaitDrives()

        assertEquals(CloudDriveAccounts.Default, test.accounts.state.value)
        assertEquals(CloudDriveAddedShares.Default, test.addedShares.state.value)
        assertEquals(emptySet(), legacy.values.keys)
    }

    @Test
    fun `drives without legacy data leave old settings and sources alone`() = runTest {
        val legacy = legacyPreferences()
        val saves = legacySaves().drop(1) + driveSave(TestDrive.protocol)
        val test = testRegistry(saves = saves, legacy = legacy)
        test.awaitDrives()

        assertEquals(CloudDriveAccounts.Default, test.accounts.state.value)
        assertEquals(3, legacy.values.size)
        assertEquals(saves, test.instances.flow.first())
    }

    // endregion
}
