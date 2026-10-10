/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.content.Context
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.settings.ProxyTester
import me.him188.ani.app.domain.settings.ServiceConnectionTester.TestState
import me.him188.ani.app.ui.settings.SettingsViewModel
import me.him188.ani.app.ui.settings.account.ProfileViewModel
import me.him188.ani.app.ui.settings.tabs.about.OpenSourceLibraryInfo
import me.him188.ani.app.ui.settings.tabs.about.loadOpenSourceLibraryList
import me.him188.ani.app.ui.settings.tabs.log.LogLanShareState
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/**
 * 设置页里实时值 ([TvSettingKey.Live]) 与动作 ([TvSettingsEdit.Run]) 用到的东西: 原来设置页的 ViewModel (复用它已经建好的状态),
 * 应用更新与账号的 ViewModel, 以及只在这一页有意义的状态 (一键测试的结果). 跟着设置页一起建、一起走.
 */
class TvSettingsDeps(
    val context: Context,
    val settings: SettingsViewModel,
    val appUpdate: AppUpdateViewModel,
    val profile: ProfileViewModel,
    val sketch: Sketch,
    val loadOpenSourceLibrariesJsons: suspend () -> List<ByteArray>,
    private val scope: CoroutineScope,
) {
    val sourceTests = TvSourceTests(scope)
    val proxyTest = TvProxyTest(scope)

    /** 清理图片缓存后加一, 占用量跟着重新统计. */
    val imageCacheRefresh = MutableStateFlow(0)

    /** 「扫码传到手机」的服务 (焦点在那一行时才起); null = 没起. */
    val logShare = MutableStateFlow<LogLanShareState?>(null)

    /** 各网盘正在进行的扫码登录 (焦点在没登录的那一行时才起), 按网盘 id. */
    val driveQr = MutableStateFlow<Map<String, CloudDriveQrLoginState>>(emptyMap())

    /** 扫码登录 Bangumi 这一页发起的那次授权 (见 TvSettingsEntriesApp 的 accountItems). */
    val loginRelay = MutableStateFlow(TvLoginRelay())

    /** 开源库列表: 进「开源许可」那一组 (或焦点停在它上面) 时才读 ([loadOpenSourceLibraries]); 没读完为 null. */
    val openSourceLibraries = MutableStateFlow<List<OpenSourceLibraryInfo>?>(null)
    private var openSourceLibrariesLoading = false

    /** 开始读开源库列表 (读过或正在读就不重来); 不随调用的焦点任务取消. */
    fun loadOpenSourceLibraries() {
        if (openSourceLibraries.value != null || openSourceLibrariesLoading) return
        openSourceLibrariesLoading = true
        scope.launch {
            try {
                openSourceLibraries.value = runCatching { loadOpenSourceLibraryList(loadOpenSourceLibrariesJsons) }
                    .onFailure { logger<TvSettingsDeps>().warn(it) { "Failed to load open source libraries" } }
                    .getOrDefault(emptyList())
            } finally {
                openSourceLibrariesLoading = false
            }
        }
    }
}

/**
 * 扫码登录 Bangumi 发起的授权: [url] 是画成二维码的授权页地址 (手机打开, 授权完经 Worker 跳回电视); 起不了时 [problem] 说为什么.
 */
data class TvLoginRelay(val url: String? = null, val problem: TvText? = null)

/** 数据源一键测试: 每个源一个结果, 测完的先出. */
enum class TvSourceTestStatus { Testing, Ok, Failed, Timeout }

class TvSourceTests(private val scope: CoroutineScope) {
    val results = MutableStateFlow<Map<String, TvSourceTestStatus>>(emptyMap())
    val running = MutableStateFlow(false)

    /** 测 [instances] 里的每一个 (同时最多 [PARALLELISM] 个, 每个最长 [TIMEOUT]); 正在测时不重来. */
    fun testAll(instances: List<MediaSourceInstance>) {
        if (running.value || instances.isEmpty()) return
        running.value = true
        results.value = instances.associate { it.instanceId to TvSourceTestStatus.Testing }
        scope.launch(Dispatchers.Default) {
            try {
                val permits = Semaphore(PARALLELISM)
                coroutineScope {
                    for (instance in instances) launch {
                        val status = permits.withPermit { test(instance) }
                        results.update { it + (instance.instanceId to status) }
                    }
                }
                logger.info { "Source test finished: ${results.value.values.groupingBy { it }.eachCount()}" }
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun test(instance: MediaSourceInstance): TvSourceTestStatus {
        val status = withTimeoutOrNull(TIMEOUT) {
            runCatching { instance.source.checkConnection() }
                .onFailure { logger.warn(it) { "Source test failed for ${instance.instanceId}" } }
                .getOrDefault(ConnectionStatus.FAILED)
        }
        return when (status) {
            ConnectionStatus.SUCCESS -> TvSourceTestStatus.Ok
            ConnectionStatus.FAILED -> TvSourceTestStatus.Failed
            null -> TvSourceTestStatus.Timeout
        }
    }

    private companion object {
        private val logger = logger<TvSourceTests>()
        const val PARALLELISM = 6
        val TIMEOUT = 20.seconds
    }
}

/** 各服务的连通测试 (同 Web 控制台「测试连接」): 按当前生效的代理测, 结果随测随出. */
class TvProxyTest(private val scope: CoroutineScope) {
    /** null = 还没测过; 测着时 [running] 为真. */
    val results = MutableStateFlow<Map<String, TestState>?>(null)
    val running = MutableStateFlow(false)

    fun start() {
        if (running.value) return
        running.value = true
        scope.launch(Dispatchers.Default) {
            val koin = KoinPlatform.getKoin()
            val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val tester = ProxyTester(koin.get<HttpClientProvider>(), testScope, koin.get<TmdbImageService>())
                testScope.launch { tester.testRunnerLoop() }
                withTimeoutOrNull(TIMEOUT) {
                    tester.testResult.first { result ->
                        results.value = result.idToStateMap
                        result.allCompleted()
                    }
                }
            } finally {
                testScope.cancel()
                running.value = false
            }
        }
    }

    private companion object {
        val TIMEOUT = 25.seconds
    }
}
