/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.room.RoomDatabase

/**
 * 还能升级上来的最老的库版本: Izuko TV (现在的包名) 的第一个版本 1.0.1 建库就是这一版, 之后的版本都由
 * [AniDatabase] 的 AutoMigration 接着升.
 *
 * 更老的库只在旧包名的 6.x 里, 那边的数据由 1.0.1 的接管逐行导进新库, 不带库文件; 换电视导入的快照也只来自
 * 装着 Izuko TV 的电视. 所以这个包里遇不到更老的库, 万一遇到 (比如内部测试包) 就清空重建, 不为它们留迁移.
 */
const val OLDEST_UPGRADABLE_DATABASE_VERSION = 26

/**
 * 库版本的处理: 比 [OLDEST_UPGRADABLE_DATABASE_VERSION] 旧的、以及比当前版本新的 (装回了旧版) 都清空重建,
 * 其余按 [AniDatabase] 的 AutoMigration 升级.
 */
fun <T : RoomDatabase> RoomDatabase.Builder<T>.withAniUpgradePolicy(): RoomDatabase.Builder<T> =
    fallbackToDestructiveMigrationOnDowngrade(true)
        .fallbackToDestructiveMigrationFrom(
            dropAllTables = true,
            startVersions = (1 until OLDEST_UPGRADABLE_DATABASE_VERSION).toList().toIntArray(),
        )
