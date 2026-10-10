/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.databaseFile
import me.him188.ani.app.domain.devicemigration.DeviceMigrationExporter
import me.him188.ani.app.domain.devicemigration.DeviceMigrationImporter
import me.him188.ani.app.domain.devicemigration.DeviceMigrationStores
import me.him188.ani.app.domain.profile.UserProfiles
import org.koin.core.module.Module

/**
 * 换电视 (Web 控制台「维护 → 换电视」) 的两侧: 旧电视交出数据 ([DeviceMigrationExporter]), 新电视写进来 ([DeviceMigrationImporter]).
 *
 * @param openDatabase 按文件名打开一个用户库 (与当前用户的库同样的建法)
 */
internal fun Module.deviceMigration(getContext: () -> Context, openDatabase: (fileName: String) -> AniDatabase) {
    single<DeviceMigrationExporter> {
        DeviceMigrationExporter(
            registry = get(),
            currentProfileId = { UserProfiles.currentId },
            currentDatabase = get(),
            deviceDatabase = get(),
            openDatabase = openDatabase,
            stores = DeviceMigrationStores.of(getContext().dataStores),
            appVersion = currentAniBuildConfig.versionName,
        )
    }
    single<DeviceMigrationImporter> {
        DeviceMigrationImporter(
            registry = get(),
            currentProfileId = { UserProfiles.currentId },
            currentDatabase = get(),
            openDatabase = openDatabase,
            databaseFile = { fileName -> getContext().databaseFile(fileName) },
            stores = DeviceMigrationStores.of(getContext().dataStores),
        )
    }
}
