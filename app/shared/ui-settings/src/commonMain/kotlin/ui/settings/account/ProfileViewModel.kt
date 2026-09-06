/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.account

import androidx.compose.runtime.Immutable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.LocalProfileConversion
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.domain.profile.UserProfilesSave
import me.him188.ani.app.tools.MonoTasker
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.user.SelfInfoStateProducer
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.app.ui.user.TestSelfInfoUiState
import me.him188.ani.datasources.api.topic.FileSize.Companion.megaBytes
import me.him188.ani.utils.coroutines.SingleTaskExecutor
import me.him188.ani.utils.coroutines.flows.FlowRestarter
import me.him188.ani.utils.coroutines.flows.restartable
import me.him188.ani.utils.platform.annotations.TestOnly
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

/**
 * It is used on both [AccountSettingsPopupMedium] and [AccountS].
 */
class ProfileViewModel : AbstractViewModel(), KoinComponent {
    private val subjectCollectionRepo: SubjectCollectionRepository by inject()
    private val userRepo: UserRepository by inject()
    private val localProfileConversion: LocalProfileConversion by inject()
    private val bangumiProfileConversion: BangumiProfileConversion by inject()
    private val selfRecords: SelfCollectionRecords by inject()
    private val profileManager: UserProfileManager by inject()

    private val selfInfoStateProvider: SelfInfoStateProducer = SelfInfoStateProducer(koin = getKoin())

    private val avatarUploadTasker = SingleTaskExecutor(backgroundScope.coroutineContext)
    private val fullSyncTasker = MonoTasker(backgroundScope)

    private val stateRefresher = FlowRestarter()

    private val avatarUploadState =
        MutableStateFlow<EditProfileState.UploadAvatarState>(EditProfileState.UploadAvatarState.Default)

    val stateFlow = combine(
        selfInfoStateProvider.flow,
        avatarUploadState,
        profileManager.state,
        bangumiProfileConversion.pendingCollections.map { it.size },
    ) { selfInfoState, avatarState, profiles, pendingCount ->
        AccountSettingsState(
            selfInfo = selfInfoState,
            boundBangumi = selfInfoState.isSessionValid == true && selfInfoState.bangumiConnected == true,
            avatarUploadState = avatarState,
            // Bangumi 用户可以改成本地用户 (登录着的改的时候先退出)
            canConvertToLocal = localProfileConversion.isOffered,
            canConvertToBangumi = bangumiProfileConversion.isOffered,
            canSwitchProfile = profileManager.isSupported,
            // 改成 Bangumi 用户后还没登录: 之前的本地收藏还等着 (登录后再选合并还是删除)
            pendingLocalCollections = if (selfInfoState.isLocalProfile || selfInfoState.isSessionValid != false) 0 else pendingCount,
            // 只有一个用户时打开应用本来就不选人
            chooseProfileOnLaunch = profiles.chooseOnLaunch.takeIf { profileManager.isSupported && profiles.profiles.size >= 2 },
        )
    }
        .restartable(stateRefresher)
        .stateInBackground(
            initialValue = AccountSettingsState.Empty,
            started = SharingStarted.WhileSubscribed(5_000),
        )

    suspend fun logout() {
        withContext(Dispatchers.Default) {
            userRepo.clearSelfInfo()
        }
    }

    /** 当前用户库里自己的收藏记录有多少 (本地用户清除前、1 号改成本地用户前给人看). */
    suspend fun selfRecordCounts(): SelfCollectionRecords.Counts = selfRecords.counts()

    /** 本地用户清除自己的收藏记录. */
    suspend fun clearSelfRecords() = selfRecords.clear()

    /** 退出登录, 改成本地用户, 应用随后重启; [clearRecords] 为 true 时先清掉登录时留下的收藏记录. */
    suspend fun convertToLocal(clearRecords: Boolean, defaultName: String) =
        localProfileConversion.convert(clearRecords, defaultName)

    private val _convertToLocalProgress = MutableStateFlow<ConvertToLocalProgress?>(null)

    /** 「改成本地用户」进行到哪了 (见 [startConvertToLocal]); null = 还没开始. */
    val convertToLocalProgress: StateFlow<ConvertToLocalProgress?> = _convertToLocalProgress.asStateFlow()

    /**
     * 在后台改成本地用户 (同 [convertToLocal]), 进度写进 [convertToLocalProgress]: 登录着、留下记录时要先取账号里的收藏, 要一会儿,
     * 对话框开着显示进度; 跑在这个 ViewModel 的作用域里, 对话框关了也不打断. 成功时应用重启; 失败时什么都没改, 进度换成失败原因.
     */
    fun startConvertToLocal(clearRecords: Boolean, defaultName: String) {
        if (_convertToLocalProgress.value is ConvertToLocalProgress.Downloading) return
        _convertToLocalProgress.value = ConvertToLocalProgress.Downloading(0, 0)
        backgroundScope.launch(Dispatchers.Main) {
            try {
                localProfileConversion.convert(clearRecords, defaultName) { p ->
                    _convertToLocalProgress.value = ConvertToLocalProgress.Downloading(p.done, p.total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _convertToLocalProgress.value = ConvertToLocalProgress.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** 看过失败原因了 (关掉对话框), 下次打开从头来. */
    fun resetConvertToLocal() {
        if (_convertToLocalProgress.value is ConvertToLocalProgress.Failed) _convertToLocalProgress.value = null
    }

    /** 登录着时账号里的收藏条数 (改成本地用户前给人看); 取不到为 null. */
    suspend fun accountCollectionCount(): Int? =
        withTimeoutOrNull(ACCOUNT_COUNT_TIMEOUT) { subjectCollectionRepo.subjectCollectionCountsFlow().filterNotNull().first().total }

    /** 本地用户改成 Bangumi 用户, 应用随后重启; 本地收藏留着, 登录后再选合并到账号还是删除 (见 [BangumiProfileConversion]). */
    suspend fun convertToBangumi() = bangumiProfileConversion.convert()

    /** 见 [AccountSettingsState.chooseProfileOnLaunch]. */
    suspend fun setChooseProfileOnLaunch(enabled: Boolean) = profileManager.setChooseOnLaunch(enabled)

    companion object {
        private val ACCOUNT_COUNT_TIMEOUT = 10.seconds
        private val NICKNAME_MATCHER = Regex("^[\u4E00-\u9FFF\u3040-\u309F\u30A0-\u30FFa-zA-Z\\d_]+$")
    }
}

@Immutable
class AccountSettingsState(
    val selfInfo: SelfInfoUiState,
    val boundBangumi: Boolean,
    val avatarUploadState: EditProfileState.UploadAvatarState,
    /** 显示「改成本地用户」, 见 [LocalProfileConversion]. */
    val canConvertToLocal: Boolean = false,
    /** 显示「改成 Bangumi 用户」, 见 [BangumiProfileConversion]. */
    val canConvertToBangumi: Boolean = false,
    /** 这个平台能换用户 (见 [UserProfileManager.isSupported]): 显示「切换用户」. */
    val canSwitchProfile: Boolean = false,
    /** 改成 Bangumi 用户后还没登录时, 等着处理的本地收藏条数 (其余情况为 0). */
    val pendingLocalCollections: Int = 0,
    /**
     * 「打开应用时选择用户」开着没有 (整机一份, 见 [UserProfilesSave.chooseOnLaunch]);
     * `null` = 不显示这一项: 只有一个用户, 或这个平台不能换人.
     */
    val chooseProfileOnLaunch: Boolean? = null,
) {
    companion object {
        val Empty = AccountSettingsState(
            selfInfo = SelfInfoUiState(null, true, null, null),
            boundBangumi = false,
            avatarUploadState = EditProfileState.UploadAvatarState.Default,
        )
    }
}

@Immutable
class EditProfileState(
    val nickname: String,
) {
    companion object {
        val Empty = EditProfileState(
            nickname = "",
        )
    }

    @Immutable
    sealed interface UploadAvatarState {
        data object Default : UploadAvatarState

        data object Uploading : UploadAvatarState

        data class Success(val url: String) : UploadAvatarState

        sealed interface Failed : UploadAvatarState

        data object InvalidFormat : Failed

        data object SizeExceeded : Failed

        data class UnknownError(val file: PlatformFile, val loadError: LoadError) : Failed

        data class UnknownErrorWithRetry(
            val loadError: LoadError,
            val onRetry: () -> Unit,
        ) : Failed
    }
}

@OptIn(TestOnly::class)
val TestAccountSettingsState
    get() = AccountSettingsState(
        TestSelfInfoUiState,
        false,
        EditProfileState.UploadAvatarState.Default,
    )

private object AvatarImageProcessor {
}
