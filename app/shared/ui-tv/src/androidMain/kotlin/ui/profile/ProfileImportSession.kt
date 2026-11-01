/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.profile.BangumiProfileConversion
import me.him188.ani.app.domain.profile.LocalProfileImporter
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把本地收藏导入 Bangumi 的那一次 (见 [LocalProfileImporter]): 别的本地用户的收藏, 或本地用户改成 Bangumi 用户时留下的
 * (见 [BangumiProfileConversion.merge]). 电视与 Web 控制台共用: 同一时间只跑一次,
 * 跑在应用级的作用域上 (关掉弹窗或网页不打断), 结果留着给之后打开的界面看. 在跑的时候不能换用户 —— 换用户会重启应用.
 */
object ProfileImportSession {
    private val logger = logger<ProfileImportSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ProfileImportSession"))

    sealed interface State {
        data object Idle : State

        /** @property waitingForRateLimit 正被 Bangumi 限频, 等一会儿自动接着导 (见 [LocalProfileImporter.import]) */
        data class Running(val sourceName: String, val done: Int, val total: Int, val waitingForRateLimit: Boolean = false) : State

        data class Finished(val sourceName: String, val result: LocalProfileImporter.Result) : State

        data class Failed(val sourceName: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is State.Running

    /**
     * 照 [preview] 导入, 在后台跑.
     * @return 开始了; 已经有一次在跑时为 `false`
     */
    fun start(importer: LocalProfileImporter, preview: LocalProfileImporter.Preview, sourceName: String): Boolean =
        start(preview, sourceName) { onProgress -> importer.import(preview, onProgress) }

    /**
     * 照 [preview] 跑 [run] (导入或合并), 在后台跑.
     * @return 开始了; 已经有一次在跑时为 `false`
     */
    fun start(
        preview: LocalProfileImporter.Preview,
        sourceName: String,
        run: suspend (onProgress: (LocalProfileImporter.Progress) -> Unit) -> LocalProfileImporter.Result,
    ): Boolean {
        synchronized(this) {
            if (isRunning) return false
            _state.value = State.Running(sourceName, done = 0, total = preview.toAdd.size)
        }
        scope.launch {
            _state.value = try {
                val result = run { p ->
                    _state.value = State.Running(sourceName, p.done, p.total, p.waitingForRateLimit)
                }
                State.Finished(sourceName, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Importing collections of user profile ${preview.source.id} failed" }
                State.Failed(sourceName, e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return true
    }

    /** 看过结果了 (关掉结果弹窗): 回到空闲, 下次打开不再显示上一次的结果. */
    fun acknowledge() {
        if (!isRunning) _state.value = State.Idle
    }
}
