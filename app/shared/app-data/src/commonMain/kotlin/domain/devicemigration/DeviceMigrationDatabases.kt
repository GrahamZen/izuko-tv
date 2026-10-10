/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.devicemigration

import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.delete

/**
 * 换电视时用户库文件的搬法: 旧电视拍一份快照整个传过去 (所有表, 以后加的表也自动跟着), 新电视
 * - 新建的用户: 快照直接当他的库文件;
 * - 接管自己的 1 号用户: 1 号的库开着, 而且兼作整机库 (缓存索引那几张表), 不能换文件, 改成逐表拷进去 ([copyInto]).
 */
internal object DeviceMigrationDatabases {
    /**
     * 跟着设备走的表: 缓存索引 (只在 1 号用户的库里有用, 见 `DeviceAniDatabase`). 快照里清空, 接管时也不动新电视自己的.
     */
    val DEVICE_TABLES = setOf("torrent_cache", "torrent_cache_episode", "http_cache_download_state")

    /** 缓存: 弹幕、网页搜索结果、推荐. 快照里清空 (省流量), 新电视自己再取. */
    val CACHE_TABLES = setOf("danmaku", "web_search_session_cache", "recommendation_feed")

    /** SQLite 与 Room 自己的表, 拷表时跳过 (Room 的表里记着库的身份, 两边相同). */
    private fun isInternal(table: String) =
        table.startsWith("sqlite_") || table == "room_master_table" || table == "android_metadata"

    /**
     * 把 [database] 拍一份快照写到 [target] (库开着也能拍, 是一致的一刻), 再清掉 [DEVICE_TABLES] 与 [CACHE_TABLES] 并压紧.
     * 快照保留库的版本号 (`user_version`).
     */
    suspend fun snapshot(database: AniDatabase, target: SystemPath) {
        withContext(Dispatchers.IO_) { target.delete() }
        database.useWriterConnection { connection ->
            connection.usePrepared("VACUUM INTO ?") { statement ->
                statement.bindText(1, target.absolutePath)
                statement.step()
            }
        }
        withContext(Dispatchers.IO_) {
            val connection = BundledSQLiteDriver().open(target.absolutePath)
            try {
                for (table in connection.tableNames()) {
                    if (table in DEVICE_TABLES || table in CACHE_TABLES) connection.execSQL("DELETE FROM `$table`")
                }
                connection.execSQL("VACUUM")
            } finally {
                connection.close()
            }
        }
    }

    /** 库文件的版本号 (Room schema 版本). 读完关掉, 不改文件. */
    suspend fun userVersion(file: SystemPath): Int = withContext(Dispatchers.IO_) {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            connection.prepare("PRAGMA user_version").use { it.step(); it.getInt(0) }
        } finally {
            connection.close()
        }
    }

    /** 开着的库的版本号. */
    suspend fun userVersion(database: AniDatabase): Int = database.useReaderConnection { connection ->
        connection.usePrepared("PRAGMA user_version") { it.step(); it.getInt(0) }
    }

    /**
     * 把 [source] (版本已与 [database] 相同, 见调用方) 里的各表整表换进 [database]: 先清空再拷, 一个事务里做完.
     * [DEVICE_TABLES] 不动. Room 的观察者收不到这次变化, 之后要重启进程.
     */
    suspend fun copyInto(database: AniDatabase, source: SystemPath) {
        database.useWriterConnection { connection ->
            // 外键要关掉: 逐表清空再拷, 中途父表空着
            val foreignKeys = connection.usePrepared("PRAGMA foreign_keys") { it.step(); it.getLong(0) != 0L }
            connection.execSQL("PRAGMA foreign_keys = OFF")
            connection.usePrepared("ATTACH DATABASE ? AS incoming") { it.bindText(1, source.absolutePath); it.step() }
            try {
                val tables = connection.usePrepared("SELECT name FROM incoming.sqlite_master WHERE type = 'table'") { statement ->
                    buildList { while (statement.step()) add(statement.getText(0)) }
                }.filter { !isInternal(it) && it !in DEVICE_TABLES }
                connection.immediateTransaction {
                    for (table in tables) {
                        val columns = usePrepared("PRAGMA main.table_info(`$table`)") { statement ->
                            buildList { while (statement.step()) add(statement.getText(1)) }
                        }
                        if (columns.isEmpty()) continue
                        val list = columns.joinToString { "`$it`" }
                        execSQL("DELETE FROM main.`$table`")
                        execSQL("INSERT INTO main.`$table` ($list) SELECT $list FROM incoming.`$table`")
                    }
                }
            } finally {
                connection.execSQL("DETACH DATABASE incoming")
                if (foreignKeys) connection.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }

    private fun SQLiteConnection.tableNames(): List<String> =
        prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
}
