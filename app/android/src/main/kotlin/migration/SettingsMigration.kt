/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.migration.LegacyUserData
import me.him188.ani.app.data.repository.user.SettingsBackupService
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.platform.DistributionApplicationIds
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * 换分发包名之后, 新包第一次启动时把旧包的设置接过来.
 *
 * 旧包 (跳板包) 通过 [SettingsMigrationProvider] 提供备份, 这里读一次就完成迁移 —— 设置、数据源、
 * 订阅、弹幕规则, 以及**登录凭据**, 用户不用重新配一遍也不用重新登录.
 *
 * 只尝试一次: 成功与否都记一笔, 免得每次冷启动都去敲旧包 (旧包已卸载时那是一次必然失败的 binder 调用).
 * 例外是旧包还没有迁移通道 (还是跳板包之前的老版本), 那次不记, 等它升级后再来. 想重来可以清掉本应用数据.
 *
 * 导入成功后接着搬缓存 ([CacheMigrationImport]), 都完成后由 [LegacyAppUninstallPrompt] 提示卸载旧包.
 */
object SettingsMigration {
    private val logger = logger<SettingsMigration>()

    /** 与本包对应的旧包名, 见 [DistributionApplicationIds.legacyOf]. */
    fun legacyPackageOf(context: Context): String = DistributionApplicationIds.legacyOf(context.packageName)

    private const val PREFS = "settings_migration"
    private const val KEY_ATTEMPTED = "attempted"
    private const val KEY_IMPORTED = "imported"
    private const val KEY_UNINSTALL_PROMPT_DISMISSED = "uninstall_prompt_dismissed"

    private val _importSucceededThisProcess = MutableStateFlow(false)

    /**
     * 本进程里的这次导入成功了. 首次启动时导入在后台跑, 界面先出来 —— 卸载提示要靠它在导入完成的那一刻出现,
     * 之前导入过的看 [wasImported].
     */
    val importSucceededThisProcess: StateFlow<Boolean> = _importSucceededThisProcess.asStateFlow()

    /**
     * 旧包还在, 而且本包还没试过迁移.
     *
     * 用它决定要不要在界面上提示用户 —— 迁移本身要读旧包的私有数据, 不该悄悄做.
     */
    fun isAvailable(context: Context): Boolean =
        !hasAttempted(context) && isLegacyInstalled(context)

    /**
     * 迁移的界面此刻开着, 或者马上要出来: 新包的缓存搬运进度 ([CacheMigrationOverlay])、卸载旧版提示
     * ([LegacyAppUninstallPrompt]); 跳板包的迁移提示卡.
     *
     * 启动时的其它弹窗 (Web 控制台二维码) 给它们让路: 那些弹窗会整个盖在上面 (2026-09-22 真机: 搬运进度被
     * 二维码挡住一半, 重启后卸载提示也压在二维码下面; 跳板包的提示卡同样被盖住).
     */
    fun isMigrationUiPending(context: Context): Boolean {
        // 跳板包的更新检查一找到新包就弹提示卡, 它存在就是为了这张卡
        if (currentAniBuildConfig.isMigrationBridge) return true
        if (CacheMigrationImport.uiState.value !is CacheMigrationImport.UiState.Idle) return true
        if (!isLegacyInstalled(context)) return false
        // 读的顺序与 importFromLegacy 写的顺序配套 (置导入中 → 标记已试 → 导入成功 → 清导入中), 导入跑到一半来读也不会误判
        if (!hasAttempted(context)) {
            // 还没开始: 旧包有迁移通道的话, 首次启动的导入马上就跑
            return context.packageManager.resolveContentProvider(legacyAuthority(context), 0) != null
        }
        if (importing) return true
        if (!(importSucceededThisProcess.value || wasImported(context))) return false // 没接管成, 后面没有迁移界面
        return when (CacheMigrationImport.phase(context)) {
            CacheMigrationImport.Phase.DONE -> !isUninstallPromptDismissed(context)
            else -> true // 缓存还没搬完, 搬运进度马上会出来
        }
    }

    private fun legacyAuthority(context: Context) = legacyPackageOf(context) + SettingsMigrationProvider.AUTHORITY_SUFFIX

    /**
     * 从旧包取设置并导入.
     *
     * does not throw (取消除外)
     *
     * @return 成功导入为 `true`; 旧包不在、拒绝、或内容读不动都是 `false`
     */
    suspend fun importFromLegacy(context: Context, koin: Koin): Boolean {
        if (!isLegacyInstalled(context)) {
            markAttempted(context)
            logger.info { "migration: 旧包 ${legacyPackageOf(context)} 不在, 跳过" }
            return false
        }
        val authority = legacyAuthority(context)
        // 旧包还是跳板包之前的老版本 (用户先手动装了新包): 它没有这个 provider. 这次不算试过 ——
        // 等它升到跳板包, 下次冷启动再来
        if (context.packageManager.resolveContentProvider(authority, 0) == null) {
            logger.info { "migration: 旧包 ${legacyPackageOf(context)} 还没有迁移通道, 下次启动再试" }
            return false
        }
        importing = true
        try {
            markAttempted(context)
            return requestAndRestore(context, koin, authority)
        } finally {
            importing = false
        }
    }

    /** 导入正在跑 (首次启动时在后台), 见 [isMigrationUiPending]. */
    @Volatile
    private var importing = false

    private suspend fun requestAndRestore(context: Context, koin: Koin, authority: String): Boolean {
        val json = try {
            val uri = Uri.parse("content://$authority")
            context.contentResolver
                .call(uri, SettingsMigrationProvider.METHOD_EXPORT_SETTINGS, null, null)
                ?.getString(SettingsMigrationProvider.KEY_BACKUP_JSON)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "migration: 向旧包要设置备份失败" }
            null
        }
        if (json.isNullOrBlank()) {
            logger.info { "migration: 旧包没有给出备份" }
            return false
        }
        val imported = try {
            koin.get<SettingsBackupService>().restore(json).also {
                logger.info { "migration: 导入${if (it) "成功" else "失败"}, ${json.length} 字节" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "migration: 导入设置备份失败" }
            false
        }
        if (imported) {
            importUserData(context, koin, authority)
            // 用户数据里的设置是整份替换的, 缓存目录要在它之后改写
            try {
                rewriteCacheDirSetting(context, koin.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "migration: 改写缓存目录设置失败" }
            }
            prefs(context).edit().putBoolean(KEY_IMPORTED, true).apply()
            _importSucceededThisProcess.value = true
        }
        return imported
    }

    /**
     * 设置备份之外的用户数据: 数据源与订阅、每集看到哪、播放记录、每部番的偏好等, 见 [UserDataMigration].
     *
     * 失败只记日志: 设置与登录已经接管, 这部分缺了用户还能自己补.
     */
    private suspend fun importUserData(context: Context, koin: Koin, authority: String) {
        try {
            val uri = Uri.Builder().scheme("content").authority(authority)
                .appendPath(SettingsMigrationProvider.PATH_USER_DATA).build()
            val text = withContext(Dispatchers.IO) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
            }
            if (text == null) {
                logger.info { "migration: 旧包没有交出用户数据" }
                return
            }
            UserDataMigration.import(context, koin, migrationJson.decodeFromString(LegacyUserData.serializer(), text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "migration: 接管用户数据失败" }
        }
    }

    private val migrationJson = Json { ignoreUnknownKeys = true }

    private val settled = CompletableDeferred<Unit>()

    /** 本次启动的接管结束了: 做完、失败, 或者根本不需要做. 见 [awaitSettled]. */
    fun markSettled() {
        settled.complete(Unit)
    }

    /**
     * 等本次启动的接管结束, 最多等 [SETTLE_TIMEOUT].
     *
     * 启动时写数据源、订阅的后台任务先等它: 它们按新包的空库写下的东西 (订阅里的数据源、默认数据源) 会被接管进来的
     * 整份替换掉, 或者跟接管进来的重复.
     */
    suspend fun awaitSettled() {
        withTimeoutOrNull(SETTLE_TIMEOUT) { settled.await() }
    }

    private val SETTLE_TIMEOUT = 60.seconds

    /**
     * 缓存目录设置里的旧包名换成本包的.
     *
     * 用户在旧包里选过缓存目录的话, 存下来的是旧包的专属目录 (`…/Android/data/<旧包名>/files/…`).
     * 原样接过来, 本包会一直往一个自己没有权限的目录里写缓存 —— 缓存功能整个坏掉.
     * 没选过 (null, 用系统默认) 的不用管.
     */
    private suspend fun rewriteCacheDirSetting(context: Context, settingsRepository: SettingsRepository) {
        val settings = settingsRepository.mediaCacheSettings.flow.first()
        val dir = settings.saveDir ?: return
        val rewritten = DistributionApplicationIds.rewritePathForCurrent(dir, legacyPackageOf(context), context.packageName)
            ?: return
        // 让各存储卷上本包的专属目录先存在 (应用自己建不了 Android/data 下的顶层目录, 要由系统建)
        context.getExternalFilesDirs(null)
        File(rewritten).mkdirs()
        settingsRepository.mediaCacheSettings.set(settings.copy(saveDir = rewritten))
        logger.info { "migration: 缓存目录设置 $dir -> $rewritten" }
    }

    /** 以前某次启动时导入成功过. */
    fun wasImported(context: Context): Boolean = prefs(context).getBoolean(KEY_IMPORTED, false)

    /**
     * 旧包装没装.
     *
     * Android 11 起包可见性受限, manifest 里要为它声明 `<queries>`, 否则这里一律查不到.
     */
    fun isLegacyInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(legacyPackageOf(context), 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** 旧包在桌面上的名字 (跳板包是「Animeko（旧版）」); 取不到时退回包名. */
    fun legacyLabel(context: Context): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(legacyPackageOf(context), 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            legacyPackageOf(context)
        }
    }

    /**
     * 弹系统的卸载确认框. 需要 `REQUEST_DELETE_PACKAGES` 权限 (targetSdk 28 起), 否则系统直接忽略.
     *
     * @return 确认框弹出来了
     */
    fun requestUninstallLegacy(context: Context): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${legacyPackageOf(context)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        logger.error(e) { "migration: 打不开卸载确认框" }
        false
    }

    /** 用户选了"暂不": 以后不再提示卸载. 想卸载时去系统设置里卸. */
    fun isUninstallPromptDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNINSTALL_PROMPT_DISMISSED, false)

    fun dismissUninstallPrompt(context: Context) {
        prefs(context).edit().putBoolean(KEY_UNINSTALL_PROMPT_DISMISSED, true).apply()
    }

    private fun hasAttempted(context: Context): Boolean = prefs(context).getBoolean(KEY_ATTEMPTED, false)

    private fun markAttempted(context: Context) {
        prefs(context).edit().putBoolean(KEY_ATTEMPTED, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
