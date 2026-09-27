/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 界面上管理用户的入口: 切换、新建、改名、删除.
 *
 * 切换 = 记下下次启动的用户再重启进程 ([AppRestarter]), 见 [UserProfiles].
 */
class UserProfileManager(
    private val registry: UserProfileRegistry,
    private val restarter: AppRestarter,
    /** 删掉这个用户的数据文件 (数据库、按人的配置、推荐快照). 在列表里去掉之后调用. */
    private val deleteFiles: suspend (UserProfile) -> Unit,
    /** 换过去之前给对方的库垫数据, 见 [UserProfileSeeder]. 限时做, 出错或超时照样换. */
    private val beforeSwitch: suspend (UserProfile) -> Unit = {},
) {
    val state: StateFlow<UserProfilesSave> get() = registry.state

    /** 本进程的用户. */
    val currentId: Int get() = UserProfiles.currentId

    /** 平台支持换用户 (要能重启进程). 不支持时界面上不出现用户相关的入口. */
    val isSupported: Boolean get() = restarter.isSupported

    /** 换成 [id] 这个用户: 就是当前用户时什么都不做, 否则重启进程. */
    suspend fun switchTo(id: Int) {
        if (id == currentId) return
        val target = registry.find(id) ?: return
        try {
            withTimeoutOrNull(BEFORE_SWITCH_TIMEOUT) { beforeSwitch(target) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to prepare user profile $id before switching, switching anyway" }
        }
        registry.setCurrent(id)
        logger.info { "Switching user profile $currentId -> $id, restarting" }
        restarter.restart()
    }

    /** 新建一个用户, 不切过去. */
    suspend fun add(name: String, kind: UserProfileKind): UserProfile {
        val profile = registry.add(name, kind)
        logger.info { "Added user profile ${profile.id} (${profile.kind})" }
        return profile
    }

    /** 新建一个用户并切过去. */
    suspend fun addAndSwitch(name: String, kind: UserProfileKind) {
        switchTo(add(name, kind).id)
    }

    suspend fun rename(id: Int, name: String) = registry.rename(id, name)

    /**
     * 删掉 [id] 这个用户和他的数据文件. 1 号用户与当前用户不能删 (前者的库兼作整机库, 后者的文件正开着).
     * @return 删掉了
     */
    suspend fun delete(id: Int): Boolean {
        if (id == currentId) return false
        val removed = registry.remove(id) ?: return false
        deleteFiles(removed)
        logger.info { "Deleted user profile $id" }
        return true
    }

    /** 当前用户走过了登录那一步 (登录了或跳过了), 以后切进来不再弹. */
    suspend fun finishPendingLogin() {
        registry.update(currentId) { it.copy(pendingLogin = false) }
    }

    /** 记下当前用户的 Bangumi 头像 (选人页用). */
    suspend fun updateAvatar(avatarUrl: String?) {
        registry.update(currentId) { it.copy(avatarUrl = avatarUrl) }
    }

    private companion object {
        private val logger = logger<UserProfileManager>()

        /** 垫数据最多等这么久: 选了人就该马上换, 垫不完的对方进来自己取. */
        private val BEFORE_SWITCH_TIMEOUT = 2.seconds
    }
}
