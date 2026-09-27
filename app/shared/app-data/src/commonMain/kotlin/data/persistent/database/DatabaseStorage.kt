/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.room.RoomDatabase
import me.him188.ani.app.platform.Context
import me.him188.ani.utils.io.SystemPath

/**
 * @param fileName 数据库文件名. 每个用户一个文件, 见 `UserProfile.databaseFileName`
 */
expect fun Context.createDatabaseBuilder(fileName: String): RoomDatabase.Builder<AniDatabase>

/** [fileName] 这个数据库文件的位置 (删用户时用). SQLite 旁边还有同名加 `-wal` / `-shm` / `-journal` 的文件. */
expect fun Context.databaseFile(fileName: String): SystemPath

/**
 * 整机共享的那份库: 只从它取缓存索引那几张表 (种子缓存、HTTP 缓存下载状态). 缓存的视频文件是整机的,
 * 索引要是跟着用户走, 换个人启动时缓存引擎会把别人下好的文件当成没人用的删掉.
 *
 * 就是 1 号用户的库文件 (`UserProfile.PRIMARY_DATABASE_FILE_NAME`); 1 号用户在用时与 [AniDatabase] 是同一个实例.
 */
class DeviceAniDatabase(val database: AniDatabase)