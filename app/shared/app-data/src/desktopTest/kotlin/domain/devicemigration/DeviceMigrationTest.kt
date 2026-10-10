/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.models.user.SelfInfo
import me.him188.ani.app.data.persistent.create
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.AniDatabaseConstructor
import me.him188.ani.app.data.persistent.database.DeviceAniDatabase
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.app.data.persistent.database.dao.SearchHistoryEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.data.repository.media.ManualBrowseMemories
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionsSaveData
import me.him188.ani.app.data.repository.player.EpisodeHistories
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionsSaveData
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfileRegistry
import me.him188.ani.app.domain.profile.UserProfilesSave
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.DOING
import me.him188.ani.datasources.api.topic.UnifiedCollectionType.WISH
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import me.him188.ani.utils.io.writeBytes
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 换电视 (DeviceMigrationExporter → DeviceMigrationImporter): 两台「电视」各有自己的库文件与 DataStore 文件, 走一遍旧电视交出、新电视写进.
 */
class DeviceMigrationTest {
    private val dir = createTempDirectory("device-migration").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tvs = mutableListOf<Tv>()

    @AfterTest
    fun cleanup() {
        tvs.forEach { it.close() }
        scope.cancel()
        dir.deleteRecursively()
    }

    /** 一台电视: 库文件、按人配置与整机的 DataStore 都在自己的目录下. */
    private inner class Tv(name: String, profiles: List<UserProfile>, private val currentId: Int = UserProfile.PRIMARY_ID) {
        val root = File(dir, name).apply { mkdirs() }
        val registry: UserProfileRegistry = run {
            val file = File(root, "user-profiles.json")
            val save = UserProfilesSave(profiles, currentId, profiles.maxOf { it.id } + 1)
            file.writeText(DeviceMigrationJson.encodeToString(UserProfilesSave.serializer(), save))
            UserProfileRegistry.load(file.toKtPath().inSystem)
        }
        private val opened = mutableListOf<AniDatabase>()

        fun dbFile(fileName: String): SystemPath = File(root, "db/$fileName").also { it.parentFile.mkdirs() }.toKtPath().inSystem

        fun open(fileName: String): AniDatabase =
            Room.databaseBuilder<AniDatabase>(File(root, "db/$fileName").also { it.parentFile.mkdirs() }.path) { AniDatabaseConstructor.initialize() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
                .also { opened += it }

        val currentProfile get() = registry.find(currentId)!!
        val current = open(currentProfile.databaseFileName)
        val device = if (currentId == UserProfile.PRIMARY_ID) current else open(UserProfile.PRIMARY_DATABASE_FILE_NAME)

        fun scopedFile(profile: UserProfile, name: String): SystemPath =
            File(root, "ds/${profile.scopedFileName(name)}.json").also { it.parentFile.mkdirs() }.toKtPath().inSystem

        private fun <T> store(serializer: KSerializer<T>, default: T, file: () -> SystemPath) =
            DataStoreFactory.create(serializer, { default }, corruptionHandler = null, scope = scope, produceFile = file)

        private fun preferences(fileName: String) =
            PreferenceDataStoreFactory.create(scope = scope) { File(root, "ds/$fileName.preferences_pb").also { it.parentFile.mkdirs() } }

        val stores = DeviceMigrationStores(
            preferences = preferences("preferences"),
            subjectPreferences = preferences("preferredAlliances"),
            mediaSources = store(MediaSourceSaves.serializer(), MediaSourceSaves.Empty) { scopedFile(UserProfile(1), "mediaSourceSaves") },
            subscriptions = store(MediaSourceSubscriptionsSaveData.serializer(), MediaSourceSubscriptionsSaveData.Default.copy(list = emptyList())) {
                scopedFile(UserProfile(1), "mediaSourceSubscription")
            },
            peerFilterSubscriptions = store(PeerFilterSubscriptionsSaveData.serializer(), PeerFilterSubscriptionsSaveData(emptyList())) {
                scopedFile(UserProfile(1), "peerFilterSubscription")
            },
            danmakuFilters = store(ListSerializer(DanmakuRegexFilter.serializer()), emptyList()) { scopedFile(UserProfile(1), "danmakuFilter") },
            manualBrowseMemories = store(ManualBrowseMemories.serializer(), ManualBrowseMemories.Empty) { scopedFile(UserProfile(1), "manualBrowseMemories") },
            nsfwSubjects = store(ListSerializer(Int.serializer()), emptyList()) { scopedFile(UserProfile(1), "subjectNsfw") },
            tokens = store(TokenSave.serializer(), TokenSave.Initial) { scopedFile(currentProfile, "authSession") },
            selfInfo = store(SelfInfo.serializer().nullable, null) { scopedFile(currentProfile, "selfInfo") },
            episodeHistories = store(EpisodeHistories.serializer(), EpisodeHistories.Empty) { scopedFile(currentProfile, "episodeHistories") },
            scopedFile = ::scopedFile,
        )

        val exporter = DeviceMigrationExporter(
            registry = registry,
            currentProfileId = { currentId },
            currentDatabase = current,
            deviceDatabase = DeviceAniDatabase(device),
            openDatabase = ::open,
            stores = stores,
            appVersion = "1.0.6",
        )
        val importer = DeviceMigrationImporter(
            registry = registry,
            currentProfileId = { currentId },
            currentDatabase = current,
            openDatabase = ::open,
            databaseFile = ::dbFile,
            stores = stores,
        )

        fun close() {
            opened.forEach { runCatching { it.close() } }
        }

        init {
            tvs += this
        }
    }

    private fun source(instanceId: String, factory: String, subscriptionId: String? = null, arguments: Map<String, String?> = emptyMap()) =
        MediaSourceSave(
            instanceId = instanceId, mediaSourceId = factory, factoryId = FactoryId(factory), isEnabled = true,
            config = MediaSourceConfig(arguments = arguments, subscriptionId = subscriptionId),
        )

    private fun subscription(id: String, url: String) = MediaSourceSubscription(subscriptionId = id, url = url)

    private suspend fun Tv.setPreference(key: String, value: String) {
        stores.preferences.edit { it[stringPreferencesKey(key)] = value }
    }

    private suspend fun Tv.preference(key: String): String? = stores.preferences.data.first()[stringPreferencesKey(key)]

    /** 旧电视: 1 号「甲」登录着 Bangumi, 2 号「乙」是本地用户; 开着的是 1 号. */
    private suspend fun oldTv(): Tv {
        val tv = Tv(
            "old",
            listOf(
                UserProfile(1, name = "甲", kind = UserProfileKind.BANGUMI, avatarUrl = "https://lain.bgm.tv/a.jpg"),
                UserProfile(2, name = "乙", kind = UserProfileKind.LOCAL),
            ),
        )
        tv.current.subjectCollection().upsert(testSubjectCollectionEntity(101, DOING))
        tv.current.searchHistory().insert(SearchHistoryEntity(content = "芙莉莲"))
        tv.current.playbackHistoryDao().upsertRecord(record(episodeId = 1001, subjectId = 101))
        tv.current.torrentCacheInfoDao().upsert(TorrentCacheInfoEntity(mediaId = "old-cache", torrentData = byteArrayOf(1), relativeDir = "old"))
        tv.stores.tokens.updateData { TokenSave(refreshToken = "refresh-甲", accessTokens = TokenSave.AccessTokens("access-甲", "", 9_999), loginFlowVersion = 1) }
        val other = tv.open(UserProfile(2).databaseFileName)
        other.subjectCollection().upsert(testSubjectCollectionEntity(201, WISH))
        other.close()

        tv.setPreference("uiSettings", "old-ui")
        tv.setPreference("cachePreferences", "old-dir")
        tv.stores.mediaSources.updateData {
            MediaSourceSaves(listOf(source("a-dmhy", "dmhy"), source("a-rss", "rss", arguments = mapOf("url" to "x")), source("a-sub", "web", subscriptionId = "a-x")))
        }
        tv.stores.subscriptions.updateData { it.copy(list = listOf(subscription("a-x", "https://sub/x.json"))) }
        tv.stores.danmakuFilters.updateData { listOf(DanmakuRegexFilter(id = "a1", regex = "剧透")) }
        tv.stores.nsfwSubjects.updateData { listOf(7) }
        return tv
    }

    private fun record(episodeId: Int, subjectId: Int) = PlaybackHistoryRecordEntity(
        episodeId = episodeId, positionMillis = 60_000, subjectId = subjectId, subjectName = "甲", episodeName = "第 1 集",
        durationMillis = 1_440_000, updatedAtMillis = 100, deletedAtMillis = null,
    )

    /** 走一遍: 旧电视交出清单、整机数据、每个用户 (配置 + 库快照), 新电视写进. */
    private suspend fun migrate(from: Tv, to: Tv): List<UserProfile> {
        val manifest = from.exporter.manifest()
        assertTrue(manifest.databaseVersion <= to.importer.databaseVersion())
        val takeOver = to.importer.canTakeOverPrimary()
        val shared = DeviceMigrationJson.decodeFromString(
            DeviceMigrationShared.serializer(),
            DeviceMigrationJson.encodeToString(DeviceMigrationShared.serializer(), from.exporter.shared()),
        )
        to.importer.applyShared(shared)
        return manifest.profiles.map { summary ->
            val profile = DeviceMigrationJson.decodeFromString(
                DeviceMigrationProfile.serializer(),
                DeviceMigrationJson.encodeToString(DeviceMigrationProfile.serializer(), from.exporter.profile(summary.id)!!),
            )
            val snapshot = File(dir, "transfer-${to.root.name}-${summary.id}.db").toKtPath().inSystem
            assertTrue(from.exporter.snapshotDatabase(summary.id, snapshot))
            to.importer.applyProfile(profile, snapshot, takeOverPrimary = takeOver && profile.profile.isPrimary)
        }
    }

    @Test
    fun `清单 每个用户的种类 登录 收藏与播放记录`() = realTimeTest {
        val old = oldTv()
        val manifest = old.exporter.manifest()
        assertEquals(DeviceMigrationManifest.FORMAT, manifest.format)
        assertEquals(old.importer.databaseVersion(), manifest.databaseVersion)
        assertEquals(
            listOf(
                DeviceMigrationProfileSummary(1, "甲", UserProfileKind.BANGUMI, loggedIn = true, collections = 1, playbackRecords = 1),
                DeviceMigrationProfileSummary(2, "乙", UserProfileKind.LOCAL, loggedIn = false, collections = 1, playbackRecords = 0),
            ),
            manifest.profiles,
        )
        assertEquals(3, manifest.mediaSources)
        assertEquals(1, manifest.subscriptions)
    }

    @Test
    fun `快照 清掉缓存索引 保留版本号与用户的表`() = realTimeTest {
        val old = oldTv()
        val snapshot = File(dir, "snapshot.db").toKtPath().inSystem
        old.exporter.snapshotDatabase(1, snapshot)
        assertEquals(old.importer.databaseVersion(), DeviceMigrationDatabases.userVersion(snapshot))
        // 打开快照看内容
        File(snapshot.toString()).copyTo(File(old.root, "db/snapshot-copy.db"))
        val opened = old.open("snapshot-copy.db")
        assertEquals(DOING, opened.subjectCollection().getById(101)?.collectionType)
        assertEquals(listOf("芙莉莲"), opened.searchHistory().recent(10))
        assertEquals(emptyList(), opened.torrentCacheInfoDao().getAll().first())
        // 旧电视自己的不动
        assertEquals(1, old.current.torrentCacheInfoDao().getAll().first().size)
    }

    @Test
    fun `新电视刚装好 旧电视的 1 号放进这台的 1 号 其余作为新用户`() = realTimeTest {
        val old = oldTv()
        val new = Tv("new", listOf(UserProfile(1)))
        new.current.torrentCacheInfoDao().upsert(TorrentCacheInfoEntity(mediaId = "new-cache", torrentData = byteArrayOf(2), relativeDir = "new"))
        new.setPreference("cachePreferences", "new-dir")
        new.setPreference("onlyOnNew", "x")
        new.stores.mediaSources.updateData {
            MediaSourceSaves(
                listOf(
                    source("b-dmhy", "dmhy"), // 与旧电视的内置源同种类同配置: 去掉
                    source("b-own", "rss", arguments = mapOf("url" to "mine")), // 自己加的: 留下
                    source("b-sub-x", "web", subscriptionId = "b-x"), // 订阅地址旧电视也有: 换成旧电视那份
                    source("b-sub-y", "web2", subscriptionId = "b-y"), // 订阅地址只有这台有: 留下
                ),
            )
        }
        new.stores.subscriptions.updateData { it.copy(list = listOf(subscription("b-x", "https://sub/x.json"), subscription("b-y", "https://sub/y.json"))) }
        new.stores.danmakuFilters.updateData { listOf(DanmakuRegexFilter(id = "b1", regex = "剧透"), DanmakuRegexFilter(id = "b2", regex = "空降")) }
        assertTrue(new.importer.canTakeOverPrimary())

        val targets = migrate(old, new)

        assertEquals(listOf(1, 2), targets.map { it.id })
        assertEquals(
            listOf(
                UserProfile(1, name = "甲", kind = UserProfileKind.BANGUMI, avatarUrl = "https://lain.bgm.tv/a.jpg"),
                UserProfile(2, name = "乙", kind = UserProfileKind.LOCAL),
            ),
            new.registry.state.value.profiles,
        )
        // 1 号: 逐表换进开着的库, 缓存索引留这台自己的
        assertEquals(DOING, new.current.subjectCollection().getById(101)?.collectionType)
        assertEquals(listOf("芙莉莲"), new.current.searchHistory().recent(10))
        assertEquals(1, new.current.playbackHistoryDao().getActiveRecords().size)
        assertEquals(listOf("new-cache"), new.current.torrentCacheInfoDao().getAll().first().map { it.mediaId })
        assertEquals("refresh-甲", new.stores.tokens.data.first().refreshToken)
        assertFalse(new.dbFile("device_migration_incoming.db").exists())
        // 2 号: 新的库文件
        val second = new.open(UserProfile(2).databaseFileName)
        assertEquals(WISH, second.subjectCollection().getById(201)?.collectionType)
        // 设置换成旧电视的, 缓存目录留这台的
        assertEquals("old-ui", new.preference("uiSettings"))
        assertEquals("new-dir", new.preference("cachePreferences"))
        assertNull(new.preference("onlyOnNew"))
        assertEquals(
            listOf("a-dmhy", "a-rss", "a-sub", "b-own", "b-sub-y"),
            new.stores.mediaSources.data.first().instances.map { it.instanceId },
        )
        assertEquals(listOf("a-x", "b-y"), new.stores.subscriptions.data.first().list.map { it.subscriptionId })
        assertEquals(listOf("a1", "b2"), new.stores.danmakuFilters.data.first().map { it.id })
        assertEquals(listOf(7), new.stores.nsfwSubjects.data.first())
    }

    @Test
    fun `新电视有人用过 旧电视的用户都作为新用户加进来 原有的不动`() = realTimeTest {
        val old = oldTv()
        val new = Tv("new", listOf(UserProfile(1, name = "丙")))
        new.current.subjectCollection().upsert(testSubjectCollectionEntity(301, WISH))
        assertFalse(new.importer.canTakeOverPrimary())

        val targets = migrate(old, new)

        assertEquals(listOf(2, 3), targets.map { it.id })
        assertEquals(listOf("丙", "甲", "乙"), new.registry.state.value.profiles.map { it.name })
        assertEquals(WISH, new.current.subjectCollection().getById(301)?.collectionType)
        assertNull(new.current.subjectCollection().getById(101))
        assertEquals(TokenSave.Initial, new.stores.tokens.data.first())
        // 搬来的 1 号成了这台的 2 号: 库与登录凭据都跟着
        val moved = new.open(UserProfile(2).databaseFileName)
        assertEquals(DOING, moved.subjectCollection().getById(101)?.collectionType)
        assertEquals(emptyList(), moved.torrentCacheInfoDao().getAll().first())
        assertTrue(new.scopedFile(UserProfile(2), "authSession").exists())
        assertFalse(new.scopedFile(UserProfile(3), "authSession").exists())
    }

    @Test
    fun `库文件坏了 不留下半个用户`() = realTimeTest {
        val old = oldTv()
        val new = Tv("new", listOf(UserProfile(1, name = "丙")))
        val broken = File(dir, "broken.db").toKtPath().inSystem.also { it.writeBytes("not a database".encodeToByteArray()) }
        assertFailsWith<Exception> {
            new.importer.applyProfile(old.exporter.profile(2)!!, broken, takeOverPrimary = false)
        }
        assertEquals(listOf(1), new.registry.state.value.profiles.map { it.id })
        assertFalse(new.dbFile(UserProfile(2).databaseFileName).exists())
    }
}
