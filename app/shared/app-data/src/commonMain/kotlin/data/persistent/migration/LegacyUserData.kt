/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.migration

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
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryPendingOpEntity
import me.him188.ani.app.data.persistent.database.dao.PlaybackHistoryRecordEntity
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionsSaveData
import me.him188.ani.app.data.repository.player.EpisodeHistories
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionsSaveData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 换分发包名时, 旧包交给新包的用户数据 (设置备份与缓存之外的部分).
 *
 * 设置备份 (`SettingsBackupService`) 只挑了设置项与登录凭据; 这里补上用户自己攒下、丢了会明显感觉到的东西.
 * 番剧与剧集信息、弹幕、推荐、TMDB 图片这类缓存不搬, 新包自己会重新拉.
 *
 * - [preferences]: 设置所在的 DataStore 整份, 设置备份没列到的项 (如 PikPak 账号) 也一起带上;
 * - [mediaSources]: 数据源原样搬, **保留 instanceId** —— 缓存记录与每部番的偏好都按它认数据源,
 *   订阅刷新时也是按名字沿用已有的 id;
 * - [playbackHistoryPendingOps]: 还没同步出去的播放记录操作, 丢了就再也同步不上.
 */
@Serializable
data class LegacyUserData(
    val preferences: List<PreferenceEntry>,
    /** 每部番的资源偏好与改过的搜索名. */
    val episodePreferences: List<PreferenceEntry>,
    val mediaSources: MediaSourceSaves,
    val mediaSourceSubscriptions: MediaSourceSubscriptionsSaveData,
    val peerFilterSubscriptions: PeerFilterSubscriptionsSaveData,
    /** 每集看到哪. */
    val episodeHistories: EpisodeHistories,
    val playbackHistory: List<PlaybackHistoryRecordEntity>,
    val playbackHistoryPendingOps: List<PlaybackHistoryPendingOpEntity>,
    /** 从旧到新. */
    val searchHistory: List<String>,
    /** Web 控制台地址里的 token: 新正式包与旧正式包的端口相同, 接过 token 后手机上的书签与二维码照旧能用. */
    val remoteControlToken: String? = null,
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
    /** 不认识的值类型跳过 (目前 DataStore 只有这几种). */
    @OptIn(ExperimentalEncodingApi::class)
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

    @OptIn(ExperimentalEncodingApi::class)
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
