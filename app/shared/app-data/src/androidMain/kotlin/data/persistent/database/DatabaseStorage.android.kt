/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.room.Room
import androidx.room.RoomDatabase
import me.him188.ani.app.platform.Context

actual fun Context.createDatabaseBuilder(): RoomDatabase.Builder<AniDatabase> {
    return Room.databaseBuilder<AniDatabase>(
        context = applicationContext,
        name = applicationContext.getDatabasePath("ani_room_database_main.db").absolutePath,
    )
        // Room 默认 (AUTOMATIC) 在低内存设备上用 TRUNCATE: 全库只有一个连接, 读要排在写后面.
        // 在线源一次写上万行搜索缓存时, 切集只读几行也要等十几秒. WAL 下读写互不阻塞.
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
}