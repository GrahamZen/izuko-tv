/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.platform.ContextMP
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.tools.MonoTasker
import me.him188.ani.app.tools.update.DefaultFileDownloader
import me.him188.ani.app.tools.update.FileDownloaderState
import me.him188.ani.app.tools.update.InstallationResult
import me.him188.ani.app.tools.update.UpdateInstallationRunner
import me.him188.ani.app.tools.update.UpdateInstallationState
import me.him188.ani.app.tools.update.UpdateInstaller
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.list
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 主页使用的自动更新检查
 */
@Stable
class AppUpdateViewModel : AbstractViewModel(), KoinComponent {
    private val settingsRepository: SettingsRepository by inject()
    private val updateSettings = settingsRepository.updateSettings.flow
    private val updateManager: UpdateManager by inject()
    private val clientProvider: HttpClientProvider by inject()
    private val updateInstaller: UpdateInstaller by inject()
    private val installationRunner by lazy { UpdateInstallationRunner(updateInstaller) }

    private val fileDownloader by lazy { DefaultFileDownloader(clientProvider.get()) }
    private val updateChecker: UpdateChecker = UpdateChecker()

    /**
     * 最新的版本. 当 [checked] 为 `true` 时, `null` 表示没有新版本. 否则表示还没有检查过.
     */
    private val latestVersionFlow = MutableStateFlow<NewVersion?>(null)
    private val lastCheckTime: MutableStateFlow<Long> = MutableStateFlow(0L)

    /**
     * 新版本下载进度
     */
    private val fileDownloaderPresenter = FileDownloaderPresenter(fileDownloader, backgroundScope)
    private val autoCheckTasker = MonoTasker(backgroundScope)
    // Linux keeps the app alive while AppImageUpdate downloads and builds a replacement.
    // Track that work separately so the UI can show installation state and cancel it safely.
    private val installationTasker = MonoTasker(backgroundScope)
    private val checkUpdateErrorFlow = MutableStateFlow<LoadError?>(null)

    val presentationFlow = combine(
        latestVersionFlow,
        fileDownloaderPresenter.flow,
        autoCheckTasker.isRunning,
        installationRunner.state,
        checkUpdateErrorFlow,
    ) { latestVersion, fileDownloaderStats, isCheckingUpdate, installationState, checkUpdateError ->
        val latestVersion = latestVersion
        val state = when {
            // 还没检查过
            lastCheckTime.value == 0L -> AppUpdateState.ClickToCheck
            latestVersion == null -> AppUpdateState.AlreadyUpToDate
            installationState is UpdateInstallationState.Installing -> AppUpdateState.Installing(latestVersion)
            else -> {
                when (fileDownloaderStats.state) {
                    FileDownloaderState.Idle -> AppUpdateState.HasUpdate(latestVersion)
                    is FileDownloaderState.Failed ->
                        AppUpdateState.DownloadFailed(latestVersion, fileDownloaderStats.state.throwable)

                    FileDownloaderState.Downloading ->
                        AppUpdateState.Downloading(latestVersion, fileDownloaderStats)

                    is FileDownloaderState.Succeed ->
                        AppUpdateState.Downloaded(latestVersion, fileDownloaderStats.state.file)

                    is FileDownloaderState.Cancelled -> {
                        // 用户取消, 则不算失败, ClickToCheck 可以隐藏 UI 弹窗
                        AppUpdateState.ClickToCheck
                    }
                }
            }
        }

        AppUpdatePresentation(
            newVersion = latestVersion,
            state = state,
            fileDownloaderStats = fileDownloaderStats,
            isCheckingUpdate = isCheckingUpdate,
            checkUpdateError = checkUpdateError,
            installationFailure = (installationState as? UpdateInstallationState.Failed)?.result,
            isPlaceholder = latestVersion == null && fileDownloaderStats.isPlaceholder,
        )
    }.stateIn(
        scope = backgroundScope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = AppUpdatePresentation.Placeholder,
    )

    val isChecking get() = autoCheckTasker.isRunning.value
    private val downloadTasker = MonoTasker(backgroundScope)

    // 一小时内只会检查一次
    fun startAutomaticCheckLatestVersion() {
        if (autoCheckTasker.isRunning.value) {
            return
        } else {
            if (currentTimeMillis() - lastCheckTime.value < 1000 * 60 * 60 * 1) {
                return // 1 小时内检查过
            }

            startCheckLatestVersion(null)
        }
    }

    /**
     * @param context 为 null 则不会自动下载
     */
    fun startCheckLatestVersion(
        uriHandler: UriHandler?
    ) {
        autoCheckTasker.launch {
            val updateSettings = updateSettings.first()

            checkUpdateErrorFlow.value = null
            val ver = try {
                if (!updateSettings.autoCheckUpdate) {
                    logger.info { "autoCheckUpdate disabled" }
                    return@launch
                }
                logger.info { "Checking latest version, updateSettings=${updateSettings}" }

                updateChecker.checkLatestVersion(updateSettings.releaseClass)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                checkUpdateErrorFlow.value = LoadError.fromException(e)
                logger.info { "Auto update checking failed due to IOException: $e" } // 故意不打印堆栈
                return@launch
            } finally {
                lastCheckTime.value = currentTimeMillis()
            }

            latestVersionFlow.update { ver }

            if (ver != null && updateSettings.autoDownloadUpdate) {
                logger.info { "autoDownloadUpdate is true, starting download" }
                startDownload(ver, uriHandler)
            }
        }
    }

    fun startDownload(ver: NewVersion, uriHandler: UriHandler?) {
        downloadTasker.launch {
            val settings = updateSettings.first()
            if (!settings.inAppDownload) {
                if (uriHandler == null) {
                    logger.warn { "uriHandler is null, cannot navigate to browser (may happen for auto check)" }
                    return@launch
                }
                ver.downloadUrlAlternatives.firstOrNull()?.let {
                    uriHandler.openUri(it)
                } ?: run {
                    logger.warn { "No download URL found, ignoring" }
                }
                return@launch
            }

            // Linux prepares a small zsync file; other platforms prepare the package URL unchanged.
            val preparationUrls = updateInstaller.getUpdatePreparationUrls(ver.downloadUrlAlternatives)
            val dir = updateManager.saveDir
            if (dir.exists()) {
                // 删除旧的文件
                val allowedFilenames = preparationUrls.map {
                    it.substringAfterLast("/", "")
                }.let { list ->
                    list + list.map { "$it.sha1" }
                }
                for (file in dir.list()) {
                    if (file.name == ".DS_Store") continue

                    if (allowedFilenames.none { file.name.contains(it) }) {
                        logger.info { "Deleting old installer: $file" }
                        updateManager.deleteInstaller(file.inSystem)
                    }
                }
            }

            withContext(Dispatchers.IO) { dir.createDirectories() }
            fileDownloader.download(
                alternativeUrls = preparationUrls,
                filenameProvider = { it.substringAfterLast("/", "") },
                saveDir = dir,
            )
        }
    }

    fun restartDownload(uriHandler: UriHandler) {
        latestVersionFlow.value?.let { startDownload(it, uriHandler) }
    }

    fun install(context: ContextMP) {
        val state = presentationFlow.value.state as? AppUpdateState.Downloaded
            ?: return
        installationTasker.launch(Dispatchers.Main) {
            installationRunner.install(
                file = state.file,
                packageUrls = state.version.downloadUrlAlternatives,
                context = context,
            )
        }
    }

    fun dismissInstallationFailure() {
        installationRunner.dismissFailure()
    }

    fun cancelDownload() {
        if (installationTasker.isRunning.value) {
            installationTasker.cancel()
        } else {
            downloadTasker.cancel()
        }
    }
}

@Immutable
data class AppUpdatePresentation(
    val newVersion: NewVersion?,
    val state: AppUpdateState,
    val fileDownloaderStats: FileDownloaderStats,
    val isCheckingUpdate: Boolean,
    val checkUpdateError: LoadError? = null,
    val installationFailure: InstallationResult.Failed? = null,
    val currentVersion: String = currentAniBuildConfig.versionName,
    val isPlaceholder: Boolean = false,
) {
    val isDownloading = when (state) {
        AppUpdateState.AlreadyUpToDate -> false
        AppUpdateState.ClickToCheck -> false
        is AppUpdateState.DownloadFailed -> true
        is AppUpdateState.Downloaded -> true
        is AppUpdateState.Downloading -> true
        is AppUpdateState.HasUpdate -> false
        is AppUpdateState.Installing -> true
    }
    val downloadError = (state as? AppUpdateState.DownloadFailed)?.throwable?.let { LoadError.fromException(it) }

    val hasUpdate = state is AppUpdateState.HasUpdate

    companion object {
        val Placeholder = AppUpdatePresentation(
            newVersion = null,
            state = AppUpdateState.ClickToCheck,
            fileDownloaderStats = FileDownloaderStats.Placeholder,
            isCheckingUpdate = false,
            isPlaceholder = true,
        )
    }
}


@Immutable
class NewVersion(
    val name: String,
    val changelogs: List<Changelog>,
    /**
     * 所有可行的下载地址. 任意一个都可以用
     */
    val downloadUrlAlternatives: List<String>,
    val publishedAt: String,
) {
    val majorChanges = changelogs.asSequence().flatMap { changelog ->
        changelog.changes.lineSequence()
            .filterNot { it.isBlank() }
            .map { it.removePrefix("- ").removePrefix("* ") }
    }.take(4).toList()

    /**
     * 完整更新内容, 给详情弹窗用 (气泡上只放得下 [majorChanges] 那前几条).
     *
     * 落后多个版本时逐版列出, 每段前面加上版本号 —— 不然几十条堆在一起看不出哪条属于哪版.
     */
    val detailedChanges: String = changelogs.joinToString("\n\n") { changelog ->
        if (changelogs.size > 1) "### ${changelog.version}\n${changelog.detailedChanges}"
        else changelog.detailedChanges
    }
}

@Immutable
class Changelog(
    val version: String,
    val publishedAt: String,
    changes: String
) {
    /**
     * 完整正文, **保留**小节标题 (`### 修复` 等) —— 详情弹窗按标题分段显示, 几十条更新才读得下去.
     */
    val detailedChanges: String = cleanReleaseBody(changes, keepSectionHeadings = true)

    /** 条目正文, 不含小节标题 —— 更新气泡上只列前几条, 标题混在里面反而看不出哪条是更新内容. */
    val changes: String = cleanReleaseBody(changes, keepSectionHeadings = false)
}

/**
 * 把 GitHub release body 洗成可直接显示的正文.
 *
 * 两个调用方 ([Changelog.changes] 与 [Changelog.detailedChanges]) 只差"要不要留小节标题"一项,
 * 其余四步 (截取、去 Full Changelog、去引用块、折叠空行) 完全相同 —— 原本是两条各自写死的管线,
 * 结果去引用块那步只加进了其中一条, 另一条上线后才补.
 *
 * @param keepSectionHeadings true 保留 `### 修复` 这类小节标题; false 连标题一起丢掉只留条目.
 */
private fun cleanReleaseBody(body: String, keepSectionHeadings: Boolean): String = body
    // 本 fork 的 release body 前半是下载链接表 (见 ci-helper/release-template.md),
    // 真正的更新内容从 "## 本次更新" 标题开始; 没有该标题 (上游格式) 则原样使用
    .substringAfter("## 本次更新", body)
    .lineSequence()
    .filterNot {
        it.startsWith("**Full Changelog**: ", ignoreCase = true)
                || it.startsWith("Full Changelog:", ignoreCase = true)
    }
    // 引用块只从**气泡**那份里丢: 气泡是逐行纯文本, 那一段带 markdown 链接
    // (`> 遥控器使用说明, 见 [README](...)`) 显示出来就是一串方括号和网址.
    // 详情弹窗那份留着 —— 它现在按 markdown 渲染 (见 NewVersionDetailsDialog), 引用块与链接
    // 都画得出来, 而那一段恰恰是最该让人看到的说明
    .filterNot { line ->
        val trimmed = line.trimStart()
        !keepSectionHeadings && (trimmed.startsWith(">") || trimmed.startsWith("#"))
    }
    .joinToString("\n")
    // 删掉整行后留下的连续空行折叠成一个空行
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

@TestOnly
val TestNewVersion
    get() = NewVersion(
        "1.0.0",
        listOf(
            Changelog(
                "1.0.0", "",
                "- Major feature 1\n- Major feature 2",
            ),
        ),
        listOf("https://example.com"),
        "2024-01-02",
    )

@TestOnly
object TestAppUpdatePresentations {
    @TestOnly
    val HasUpdate
        get() = AppUpdatePresentation(
            newVersion = TestNewVersion,
            state = AppUpdateState.HasUpdate(TestNewVersion),
            fileDownloaderStats = FileDownloaderStats.Placeholder,
            isCheckingUpdate = false,
        )

    @TestOnly
    val Downloading
        get() = AppUpdatePresentation(
            newVersion = TestNewVersion,
            state = AppUpdateState.Downloading(TestNewVersion, TestFileDownloaderStats.Downloading),
            fileDownloaderStats = FileDownloaderStats.Placeholder,
            isCheckingUpdate = false,
        )

    @TestOnly
    val Succeed
        get() = AppUpdatePresentation(
            newVersion = TestNewVersion,
            state = AppUpdateState.Downloaded(TestNewVersion, kotlinx.io.files.Path("").inSystem),
            fileDownloaderStats = FileDownloaderStats.Placeholder,
            isCheckingUpdate = false,
        )

    @TestOnly
    val Failed
        get() = AppUpdatePresentation(
            newVersion = TestNewVersion,
            state = AppUpdateState.DownloadFailed(TestNewVersion, RepositoryNetworkException()),
            fileDownloaderStats = FileDownloaderStats.Placeholder,
            isCheckingUpdate = false,
        )
}
