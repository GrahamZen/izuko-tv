/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.serialization.Serializable

/**
 * 这台设备上的一个用户: 家里几个人共用一台电视时各用各的, 进应用时选人 (像 Apple TV 那样).
 *
 * 每个用户有自己的数据库文件 ([databaseFileName]) 和几份按人的配置文件 ([scopedFileName]): 收藏、单集状态、播放记录、
 * 推荐、搜索记录、登录会话都跟着人走; 设置、数据源、缓存的视频是整机共用的.
 *
 * 1 号用户 ([PRIMARY_ID]) 用的就是有多用户之前的那些文件, 老用户什么都不用搬; 它的库同时是整机库 (缓存索引那几张表,
 * 见 `DeviceAniDatabase`), 所以 1 号用户不能删.
 *
 * 一个进程只属于一个用户, 换用户会重启进程, 见 [UserProfiles].
 */
@Serializable
data class UserProfile(
    val id: Int,
    /** 用户起的名字. 空 = 没起过, 界面上显示「用户 N」. */
    val name: String = "",
    val kind: UserProfileKind = UserProfileKind.BANGUMI,
    /** Bangumi 头像, 给选人页用. 由这个用户自己的进程在登录状态变化时写入. */
    val avatarUrl: String? = null,
    /** 新建的 Bangumi 用户还没走过登录那一步 (登录或跳过都算走过). 切进去时先弹登录. */
    val pendingLogin: Boolean = false,
) {
    val isPrimary: Boolean get() = id == PRIMARY_ID

    val databaseFileName: String
        get() = if (isPrimary) PRIMARY_DATABASE_FILE_NAME else "ani_room_database_user$id.db"

    /**
     * 按人的文件名: 1 号用户原样, 其他人在扩展名前加 `_user<id>`.
     */
    fun scopedFileName(fileName: String): String {
        if (isPrimary) return fileName
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) "${fileName}_user$id" else fileName.substring(0, dot) + "_user$id" + fileName.substring(dot)
    }

    companion object {
        const val PRIMARY_ID = 1
        const val PRIMARY_DATABASE_FILE_NAME = "ani_room_database_main.db"

        /** 按人的 DataStore (见 `PlatformDataStoreManager`), 删用户时一起删. */
        val SCOPED_DATASTORE_NAMES = listOf("authSession", "selfInfo", "episodeHistories")

        /** 推荐用的收藏快照 (缓存目录下, 见 `RecommendationRepository`). */
        const val RECOMMENDATION_COLLECTIONS_FILE_NAME = "recommendation-collections.json"
    }
}

@Serializable
enum class UserProfileKind {
    /** 登录 Bangumi (也可以不登录, 那就和匿名浏览一样). */
    BANGUMI,

    /** 不登录, 收藏与看过记在本机. */
    LOCAL,
}
