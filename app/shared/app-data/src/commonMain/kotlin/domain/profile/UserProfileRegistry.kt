/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * 用户列表文件的内容.
 *
 * @property currentId 下次启动进哪个用户 (选人页的默认焦点也落在他身上)
 * @property nextId 下一个新用户的编号. 编号不复用: 删掉的用户留下的文件万一没删干净, 也不会被新用户接手
 */
@Serializable
data class UserProfilesSave(
    val profiles: List<UserProfile>,
    val currentId: Int,
    val nextId: Int,
) {
    fun find(id: Int): UserProfile? = profiles.firstOrNull { it.id == id }

    /** 修正不合法的内容: 1 号用户必须在, [currentId] 必须指向存在的用户, [nextId] 比所有编号都大. */
    internal fun normalized(): UserProfilesSave {
        val withPrimary = if (profiles.any { it.isPrimary }) profiles else listOf(UserProfile(UserProfile.PRIMARY_ID)) + profiles
        val distinct = withPrimary.distinctBy { it.id }.sortedBy { it.id }
        return UserProfilesSave(
            profiles = distinct,
            currentId = currentId.takeIf { id -> distinct.any { it.id == id } } ?: UserProfile.PRIMARY_ID,
            nextId = maxOf(nextId, distinct.maxOf { it.id } + 1),
        )
    }

    companion object {
        val Default = UserProfilesSave(listOf(UserProfile(UserProfile.PRIMARY_ID)), UserProfile.PRIMARY_ID, 2)
    }
}

/**
 * 这台设备上的用户列表, 存在一个 JSON 小文件里 ([FILE_NAME], 应用数据目录下, 整机一份).
 *
 * 启动时要在建数据库和配置之前同步读出来 (见 [UserProfiles.install]), 所以不用 DataStore.
 * 文件不存在 = 只有 1 号用户 (有多用户之前的样子); 加第二个用户时才第一次写.
 * 读不出来 (坏了) 时同样当只有 1 号用户, 但不删文件: 其他用户的数据文件都还在, 下次写之前人工还能救.
 */
class UserProfileRegistry private constructor(
    /** `null` = 只在内存里 (不支持多用户的平台与测试). */
    private val file: SystemPath?,
    initial: UserProfilesSave,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<UserProfilesSave> = _state.asStateFlow()

    private val mutex = Mutex()

    fun find(id: Int): UserProfile? = state.value.find(id)

    /**
     * 新建一个用户 (不切过去, 切换见 `UserProfileManager`). Bangumi 用户记上 [UserProfile.pendingLogin].
     */
    suspend fun add(name: String, kind: UserProfileKind): UserProfile = mutate { save ->
        val profile = UserProfile(
            id = save.nextId,
            name = name.trim(),
            kind = kind,
            pendingLogin = kind == UserProfileKind.BANGUMI,
        )
        save.copy(profiles = save.profiles + profile, nextId = save.nextId + 1) to profile
    }

    suspend fun rename(id: Int, name: String) = update(id) { it.copy(name = name.trim()) }

    suspend fun update(id: Int, transform: (UserProfile) -> UserProfile) {
        mutate { save ->
            if (save.find(id) == null) return@mutate save to Unit
            save.copy(profiles = save.profiles.map { if (it.id == id) transform(it) else it }) to Unit
        }
    }

    /**
     * 从列表里去掉 [id] (不删文件, 见 `UserProfileManager.delete`). 1 号用户与 [UserProfilesSave.currentId] 不能去掉.
     * @return 去掉的那个用户; 不存在或不能去掉时为 `null`
     */
    suspend fun remove(id: Int): UserProfile? = mutate { save ->
        val profile = save.find(id)
        if (profile == null || profile.isPrimary || id == save.currentId) return@mutate save to null
        save.copy(profiles = save.profiles - profile) to profile
    }

    /** 下次启动进 [id] 这个用户. 返回时已经写进文件. */
    suspend fun setCurrent(id: Int) {
        mutate { save ->
            if (save.find(id) == null) return@mutate save to Unit
            save.copy(currentId = id) to Unit
        }
    }

    private suspend fun <R> mutate(block: (UserProfilesSave) -> Pair<UserProfilesSave, R>): R = mutex.withLock {
        val (newSave, result) = block(_state.value)
        if (newSave != _state.value) {
            val normalized = newSave.normalized()
            write(normalized)
            _state.value = normalized
        }
        result
    }

    private suspend fun write(save: UserProfilesSave) {
        val file = file ?: return
        withContext(Dispatchers.IO_) {
            // 先写临时文件再原子挪过去: 写到一半进程被杀不会留下半截, 用户列表丢了就找不回别人的数据
            val temp = file.resolveSibling(file.name + ".tmp")
            temp.writeText(json.encodeToString(UserProfilesSave.serializer(), save))
            // Windows 上刚写过的文件可能正被别的进程 (杀毒、索引) 开着, 替换会被拒一下 (桌面单测里撞到过); 稍等再试
            var attempt = 1
            while (true) {
                try {
                    temp.moveTo(file)
                    break
                } catch (e: IOException) {
                    if (attempt >= MOVE_ATTEMPTS) throw e
                    attempt++
                    delay(MOVE_RETRY_DELAY)
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "user-profiles.json"

        private const val MOVE_ATTEMPTS = 5
        private val MOVE_RETRY_DELAY = 50.milliseconds

        private val logger = logger<UserProfileRegistry>()
        private val json = Json { ignoreUnknownKeys = true }

        /** 同步读 (启动时在建数据库之前调用). */
        fun load(file: SystemPath): UserProfileRegistry {
            val save = if (!file.exists()) {
                UserProfilesSave.Default
            } else {
                try {
                    json.decodeFromString(UserProfilesSave.serializer(), file.readText()).normalized()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to read ${file.name}, using the primary profile only" }
                    UserProfilesSave.Default
                }
            }
            logger.info { "User profiles: ${save.profiles.map { it.id }}, current=${save.currentId}" }
            return UserProfileRegistry(file, save)
        }

        fun inMemory(initial: UserProfilesSave = UserProfilesSave.Default): UserProfileRegistry =
            UserProfileRegistry(null, initial.normalized())
    }
}

/**
 * 本进程属于哪个用户.
 *
 * 平台入口在建数据库与配置之前 [install] (Android: `AniApplication.onCreate` 里 `startKoin` 之前), 之后 [currentId] 不变:
 * 换用户是改下次启动的用户再重启进程, 不在进程里热切换 —— 数据库、配置、各种单例和内存缓存都绑着一个用户, 逐个重建容易漏.
 * 没装 (桌面、iOS、测试) 时只有 1 号用户, 行为与多用户之前一样.
 */
object UserProfiles {
    @Volatile
    private var installedRegistry: UserProfileRegistry? = null

    @Volatile
    private var installedCurrentId: Int? = null

    private val fallbackRegistry by lazy { UserProfileRegistry.inMemory() }

    val registry: UserProfileRegistry get() = installedRegistry ?: fallbackRegistry

    /** 本进程的用户编号, 启动后不变. */
    val currentId: Int get() = installedCurrentId ?: UserProfile.PRIMARY_ID

    /** 本进程的用户 (名字、头像这些会变, 所以每次从列表里取). */
    val current: UserProfile get() = registry.find(currentId) ?: UserProfile(currentId)

    /**
     * 本进程是换人 / 改成本地用户时应用自己重启出来的 (不是用户打开的应用): 打开应用才弹的提示 (有新版本、Web 控制台二维码) 这次都不弹.
     * 平台入口在主界面创建时定下 (Android: 主界面带着 `ProfileRestartActivity.EXTRA_PROFILE_CHOSEN` 打开).
     */
    @Volatile
    var launchedBySwitch: Boolean = false

    fun install(registry: UserProfileRegistry) {
        installedRegistry = registry
        installedCurrentId = registry.state.value.currentId
    }
}
