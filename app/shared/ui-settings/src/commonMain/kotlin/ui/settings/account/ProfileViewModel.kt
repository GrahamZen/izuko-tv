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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.profile.LocalProfileConversion
import me.him188.ani.app.domain.profile.SelfCollectionRecords
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

/**
 * It is used on both [AccountSettingsPopupMedium] and [AccountS].
 */
class ProfileViewModel : AbstractViewModel(), KoinComponent {
    private val subjectCollectionRepo: SubjectCollectionRepository by inject()
    private val userRepo: UserRepository by inject()
    private val localProfileConversion: LocalProfileConversion by inject()
    private val selfRecords: SelfCollectionRecords by inject()

    private val selfInfoStateProvider: SelfInfoStateProducer = SelfInfoStateProducer(koin = getKoin())

    private val avatarUploadTasker = SingleTaskExecutor(backgroundScope.coroutineContext)
    private val fullSyncTasker = MonoTasker(backgroundScope)

    private val stateRefresher = FlowRestarter()

    private val avatarUploadState =
        MutableStateFlow<EditProfileState.UploadAvatarState>(EditProfileState.UploadAvatarState.Default)

    val stateFlow = combine(
        selfInfoStateProvider.flow,
        avatarUploadState,
    ) { selfInfoState, avatarState ->
        AccountSettingsState(
            selfInfo = selfInfoState,
            boundBangumi = selfInfoState.isSessionValid == true && selfInfoState.bangumiConnected == true,
            avatarUploadState = avatarState,
            // 没登录的 1 号可以改成本地用户 (登录着的先退出)
            canConvertToLocal = localProfileConversion.isOffered && selfInfoState.isSessionValid == false,
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

    /** 改成本地用户, 应用随后重启; [clearRecords] 为 true 时先清掉之前登录留下的收藏记录. */
    suspend fun convertToLocal(clearRecords: Boolean, defaultName: String) =
        localProfileConversion.convert(clearRecords, defaultName)

    companion object {
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
