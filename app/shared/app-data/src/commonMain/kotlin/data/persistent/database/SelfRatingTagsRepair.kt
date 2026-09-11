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
import androidx.sqlite.SQLiteConnection
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 打开数据库时修复 `subject_collection.self_rating_tags` 被写成纯文本的行.
 *
 * 旧的 `SubjectCollectionDao.updateRating` 把标签列表直接当查询参数, Room 把它展开成 `COALESCE(?, ?, …)`, 列里写进的是
 * 第一个标签的原文而不是 protobuf. 这样的行一读就抛 SerializationException ("Varint too long"): 播放器信息包、详情页、
 * 接下来播放、收藏列表全跟着坏, 而且不会自己好 —— 读都读不出来, 走不到"过期重取". 这里把坏行的标签清空并把
 * lastFetched 置 0, 下次用到时从服务端取回整行 (服务端的标签一直是对的, 写坏的只是本地这一份).
 *
 * 每次打开数据库都跑一次: 条件只命中坏行, 正常时不改任何东西.
 */
object SelfRatingTagsRepair : RoomDatabase.Callback() {
    private val logger = logger<SelfRatingTagsRepair>()

    override fun onOpen(connection: SQLiteConnection) {
        val repaired = repair(connection)
        if (repaired > 0) {
            logger.warn { "Repaired $repaired subject_collection rows with malformed self_rating_tags" }
        }
    }

    /** @return 修了几行 */
    fun repair(connection: SQLiteConnection): Int {
        connection.prepare(
            "UPDATE subject_collection SET self_rating_tags = ?, lastFetched = 0 WHERE typeof(self_rating_tags) != 'blob'",
        ).use { statement ->
            statement.bindBlob(1, ProtoConverters.StringList().fromList(emptyList()))
            statement.step()
        }
        return connection.prepare("SELECT changes()").use { statement ->
            if (statement.step()) statement.getLong(0).toInt() else 0
        }
    }
}
