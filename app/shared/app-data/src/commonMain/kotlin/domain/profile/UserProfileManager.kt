/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * 切换 = 记下下次启动的用户再重启进程 ([AppRestarter]), 见 [UserProfiles]. 重启前界面上先放过场 ([transition]).
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

    /** 重启前界面上的过场, 界面在场时装上. 限时等, 出错或超时照样重启. */
    var transition: ProfileSwitchTransition? = null

    /** 换成 [id] 这个用户: 就是当前用户时什么都不做, 否则重启进程. 垫数据与过场同时做. */
    suspend fun switchTo(id: Int) {
        if (id == currentId) return
        val target = registry.find(id) ?: return
        coroutineScope {
            launch {
                try {
                    withTimeoutOrNull(BEFORE_SWITCH_TIMEOUT) { beforeSwitch(target) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to prepare user profile $id before switching, switching anyway" }
                }
            }
            launch { playTransition(target) }
        }
        registry.setCurrent(id)
        logger.info { "Switching user profile $currentId -> $id, restarting" }
        restarter.restart()
    }

    private suspend fun playTransition(target: UserProfile) {
        val transition = transition ?: return
        try {
            withTimeoutOrNull(TRANSITION_TIMEOUT) { transition.play(target) }
                ?: logger.warn { "Profile switch transition timed out, restarting anyway" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Profile switch transition failed, restarting anyway" }
        }
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

    /**
     * 把当前用户改成本地用户 (见 [UserProfileKind.LOCAL]) 再重启进程: 各仓库的本地模式在进程启动时按当前用户定下.
     * 登录与之前留下的收藏记录由调用方先处理, 见 [LocalProfileConversion].
     *
     * @param defaultName 没起过名字时存下的默认名 (本地用户的个人资料用名字当昵称, 同新建与改名时空名存默认名)
     */
    suspend fun convertCurrentToLocal(defaultName: String) {
        // Bangumi 头像是之前登录时记下的, 本地用户不显示
        registry.update(currentId) {
            it.copy(kind = UserProfileKind.LOCAL, name = it.name.ifBlank { defaultName }, pendingLogin = false, avatarUrl = null)
        }
        registry.find(currentId)?.let { playTransition(it) }
        logger.info { "Converted user profile $currentId to local, restarting" }
        restarter.restart()
    }

    /** 记下当前用户的 Bangumi 头像 (选人页用). */
    suspend fun updateAvatar(avatarUrl: String?) {
        registry.update(currentId) { it.copy(avatarUrl = avatarUrl) }
    }

    private companion object {
        private val logger = logger<UserProfileManager>()

        /** 垫数据最多等这么久: 选了人就该马上换, 垫不完的对方进来自己取. */
        private val BEFORE_SWITCH_TIMEOUT = 2.seconds

        /** 过场最多等这么久 (动画加截图写文件, 慢的电视上一秒左右). */
        private val TRANSITION_TIMEOUT = 3.seconds
    }
}

/**
 * 换人 / 改成本地用户重启前界面上的过场 (电视上: 选人页把要换过去的人摆到正中定格, 截下这一帧留给重启途中与新进程接着显示).
 * [play] 在这一帧截好 (或截不了) 时返回, 之后进程马上重启.
 */
fun interface ProfileSwitchTransition {
    /** @param target 重启后的用户 (改成本地用户时是改好的自己) */
    suspend fun play(target: UserProfile)
}
