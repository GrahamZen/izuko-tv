/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.repository.media.ManualBrowseMemories
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.torrent.peer.PeerFilterSubscription
import kotlin.io.encoding.Base64

/*
 * 换电视: 新电视经局域网从旧电视取数据, 一次搬完用户、设置、数据源与登录 (Web 控制台「设置 → 维护 → 换电视」).
 *
 * 旧电视由 [DeviceMigrationExporter] 交出四样: 清单 [DeviceMigrationManifest] (手机上确认用)、整机共用的 [DeviceMigrationShared]、
 * 每个用户的 [DeviceMigrationProfile] 与他的库文件快照 (见 [DeviceMigrationDatabases]); 新电视由 [DeviceMigrationImporter] 写进自己.
 * 除库文件外都是 JSON, 字段按名读、认不得的跳过, 两台电视的版本可以不同; 库文件要新电视打得开 (见 [DeviceMigrationManifest.databaseVersion]).
 *
 * 不搬: 缓存的视频与缓存索引、弹幕 / 推荐 / 图片这类缓存、内置浏览器的 Cookie、控制台地址、跟着设备走的设置 (缓存目录).
 */

/** 新旧电视之间传的 JSON. */
val DeviceMigrationJson = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
}

/**
 * 旧电视上有什么, 新电视先取这一份给手机上看, 用户确认了才开始搬.
 */
@Serializable
data class DeviceMigrationManifest(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** 旧电视上 Izuko 的版本号. */
    val appVersion: String,
    /** 库的版本 (Room schema 版本). 新电视的库比它旧时打不开搬过来的库, 要先更新新电视. */
    val databaseVersion: Int,
    val profiles: List<DeviceMigrationProfileSummary>,
    val mediaSources: Int,
    val subscriptions: Int,
) {
    companion object {
        const val FORMAT = "izuko-tv-device"
        const val VERSION = 1
    }
}

@Serializable
data class DeviceMigrationProfileSummary(
    val id: Int,
    /** 用户起的名字, 空 = 没起过 (界面上显示「用户 N」). */
    val name: String,
    val kind: UserProfileKind,
    /** 登录着 Bangumi (存着登录凭据). */
    val loggedIn: Boolean,
    /** 收藏了的条目数. */
    val collections: Int,
    /** 播放记录条数. */
    val playbackRecords: Int,
)

/**
 * 整机共用的数据.
 */
@Serializable
data class DeviceMigrationShared(
    /** 设置所在的 DataStore 整份: 各页的设置、网盘与 PikPak 账号、各处的一次性标记都在里面. */
    val preferences: List<PreferenceEntry>,
    /** 每部番的偏好: 字幕组、改过的搜索名、选过的音轨与字幕. */
    val subjectPreferences: List<PreferenceEntry>,
    val mediaSources: MediaSourceSaves,
    val subscriptions: List<MediaSourceSubscription>,
    val peerFilterSubscriptions: List<PeerFilterSubscription>,
    val danmakuFilters: List<DanmakuRegexFilter>,
    /** 每部番手动查找时选过的资源. */
    val manualBrowseMemories: ManualBrowseMemories,
    /** 登记过是 NSFW 的条目. */
    val nsfwSubjects: List<Int>,
    /** 应用内语言 (语言标签, 空 = 跟随系统). 安卓上才有, 由平台那一侧填与写. */
    val appLanguage: String = "",
)

/**
 * 一个用户: 列表里的那一项与他按人的配置. 他的库文件另外传 (见 [DeviceMigrationDatabases]).
 */
@Serializable
data class DeviceMigrationProfile(
    /** 旧电视上的这一项 (编号是旧电视的, 新电视另给). */
    val profile: UserProfile,
    /**
     * 按人的配置文件原文 (登录凭据、个人资料、播放记录的同步时间), 键是不带用户后缀的名字 ([UserProfile.SCOPED_DATASTORE_NAMES]).
     * 旧电视上没有的文件不在里面.
     */
    val scopedStores: Map<String, String>,
    /** Web 控制台里删掉的播放记录 (库里的记录还在, 按这份藏起来). 由控制台那一侧填与写. */
    val hiddenHistory: List<String> = emptyList(),
)

/** [Preferences] 里的一项, 带上类型以便原样还原. */
@Serializable
data class PreferenceEntry(
    val key: String,
    val type: Type,
    val value: String? = null,
    val values: List<String>? = null,
) {
    enum class Type { STRING, BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING_SET, BYTES }
}

object PreferenceEntries {
    /** 不认识的值类型跳过 (DataStore 只有这几种). */
    fun of(preferences: Preferences): List<PreferenceEntry> = preferences.asMap().mapNotNull { (key, value) ->
        val name = key.name
        when (value) {
            is String -> PreferenceEntry(name, PreferenceEntry.Type.STRING, value)
            is Boolean -> PreferenceEntry(name, PreferenceEntry.Type.BOOLEAN, value.toString())
            is Int -> PreferenceEntry(name, PreferenceEntry.Type.INT, value.toString())
            is Long -> PreferenceEntry(name, PreferenceEntry.Type.LONG, value.toString())
            is Float -> PreferenceEntry(name, PreferenceEntry.Type.FLOAT, value.toString())
            is Double -> PreferenceEntry(name, PreferenceEntry.Type.DOUBLE, value.toString())
            is Set<*> -> PreferenceEntry(name, PreferenceEntry.Type.STRING_SET, values = value.map { it.toString() })
            is ByteArray -> PreferenceEntry(name, PreferenceEntry.Type.BYTES, Base64.encode(value))
            else -> null
        }
    }

    fun toPreferences(entries: List<PreferenceEntry>): Preferences = mutablePreferencesOf().apply {
        for (e in entries) {
            val v = e.value
            when (e.type) {
                PreferenceEntry.Type.STRING -> v?.let { set(stringPreferencesKey(e.key), it) }
                PreferenceEntry.Type.BOOLEAN -> v?.toBooleanStrictOrNull()?.let { set(booleanPreferencesKey(e.key), it) }
                PreferenceEntry.Type.INT -> v?.toIntOrNull()?.let { set(intPreferencesKey(e.key), it) }
                PreferenceEntry.Type.LONG -> v?.toLongOrNull()?.let { set(longPreferencesKey(e.key), it) }
                PreferenceEntry.Type.FLOAT -> v?.toFloatOrNull()?.let { set(floatPreferencesKey(e.key), it) }
                PreferenceEntry.Type.DOUBLE -> v?.toDoubleOrNull()?.let { set(doublePreferencesKey(e.key), it) }
                PreferenceEntry.Type.STRING_SET -> e.values?.let { set(stringSetPreferencesKey(e.key), it.toSet()) }
                PreferenceEntry.Type.BYTES -> v?.let { set(byteArrayPreferencesKey(e.key), Base64.decode(it)) }
            }
        }
    }
}
