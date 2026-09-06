/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.subscription

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.him188.ani.app.data.models.ApiFailure
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.data.repository.RepositoryRequestError
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.data.repository.RepositorySubjectNotAccessibleException
import me.him188.ani.app.data.repository.RepositoryUnknownException
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.app.domain.mediasource.codec.FactoryNotFoundException
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription.UpdateError
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.Uuid
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MediaSourceSubscriptionUpdater(
    private val subscriptions: MediaSourceSubscriptionRepository,
    private val mediaSourceManager: MediaSourceManager,
    private val codecManager: MediaSourceCodecManager,
    private val requester: MediaSourceSubscriptionRequester,
    private val getCurrentTimeMillis: () -> Long = { currentTimeMillis() },
) {
    /**
     * 每个订阅连续失败的次数, 只存在于内存中.
     * 进程重启一般意味着网络环境已经变了, 所以重启后从头开始退避, 立即重试一次.
     */
    private val consecutiveFailures = mutableMapOf<String, Int>()

    /**
     * @param force to ignore lastUpdated time
     * @param onProgress 这一轮要更新几个 ([total]), 开始更新第 [updating] 个 (从 1 起) 时调一次; 订阅是一个个依次拉的,
     *   界面据此写「更新中 1/3」
     * @return delay duration to check next time
     */
    suspend fun updateAllOutdated(
        force: Boolean = false,
        onProgress: (updating: Int, total: Int) -> Unit = { _, _ -> },
    ): Duration {
        logger.info { "MediaSourceSubscriptionUpdater.updateAllOutdated" }
        val subscriptions = subscriptions.flow.first()
        val currentTimeMillis = getCurrentTimeMillis()

        var nextDelay: Duration? = null
        fun proposeNextDelay(duration: Duration) {
            nextDelay = nextDelay?.coerceAtMost(duration) ?: duration
        }

        // 上次更新失败时用一个短得多的重试间隔. 冷启动时设备网络还没就绪是常态 (电视盒子尤其明显),
        // 若按正常的 updatePeriod (默认 1 小时) 等待, 这期间用户一个订阅数据源都没有, 只剩 BT 源可用.
        fun periodOf(subscription: MediaSourceSubscription): Duration =
            failureRetryPeriodOrNull(subscription) ?: subscription.updatePeriod

        fun shouldUpdate(subscription: MediaSourceSubscription, period: Duration): Boolean {
            if (!subscription.enabled) return false
            if (force) return true
            if (subscription.lastUpdated == null) return true
            return (currentTimeMillis - subscription.lastUpdated.timeMillis).milliseconds > period
        }

        // 失败计数只在更新完那一个之后才变, 先数一遍与下面逐个判的结果一致
        val total = subscriptions.count { shouldUpdate(it, periodOf(it)) }
        var started = 0

        for (subscription in subscriptions) {
            val period = periodOf(subscription)

            if (!shouldUpdate(subscription, period)) {
                val elapsed = (currentTimeMillis - (subscription.lastUpdated?.timeMillis ?: currentTimeMillis))
                    .milliseconds
                proposeNextDelay((period - elapsed).coerceAtLeast(Duration.ZERO))
                continue
            }

            onProgress(++started, total)
            if (updateAndRecordResult(subscription, currentTimeMillis)) {
                consecutiveFailures.remove(subscription.subscriptionId)
                proposeNextDelay(subscription.updatePeriod)
            } else {
                val failures = consecutiveFailures.increment(subscription.subscriptionId)
                val retryPeriod = failureRetryPeriod(failures, subscription.updatePeriod)
                logger.info {
                    "Failed to update subscription ${subscription.url} ($failures consecutive failures), " +
                            "retrying in $retryPeriod"
                }
                proposeNextDelay(retryPeriod)
            }
        }

        return nextDelay
            ?: subscriptions.filter { it.enabled }.minOfOrNull { subscription -> subscription.updatePeriod }
            ?: DEFAULT_UPDATE_PERIOD
    }

    /**
     * 若这个订阅上次更新是失败的, 返回本次应当采用的 (短) 重试间隔; 上次成功或从未更新过则返回 `null`.
     */
    private fun failureRetryPeriodOrNull(subscription: MediaSourceSubscription): Duration? {
        val lastUpdated = subscription.lastUpdated ?: return null
        if (lastUpdated.mediaSourceCount != null) return null // 上次成功
        return failureRetryPeriod(
            // 进程刚启动时内存里没有计数, 但持久化的状态说明至少失败过一次, 按第一次重试算
            consecutiveFailures[subscription.subscriptionId] ?: 1,
            subscription.updatePeriod,
        )
    }

    /**
     * 立即更新 [subscriptionId] 对应的订阅, 忽略更新周期. 订阅不存在或已禁用时不做任何事.
     */
    suspend fun update(subscriptionId: String) {
        val subscription = subscriptions.flow.first().find { it.subscriptionId == subscriptionId } ?: return
        if (!subscription.enabled) return
        if (updateAndRecordResult(subscription, currentTimeMillis())) {
            consecutiveFailures.remove(subscription.subscriptionId)
        }
    }

    /**
     * @return 是否更新成功 (订阅在更新期间被禁用也算成功)
     */
    private suspend fun updateAndRecordResult(subscription: MediaSourceSubscription, currentTimeMillis: Long): Boolean {
        logger.info { "Updating subscription: ${subscription.url}" }

        suspend fun setResult(result: UpdateResult?, error: UpdateError? = null) {
            this.subscriptions.update(subscription.subscriptionId) { old ->
                old.copy(
                    lastUpdated = MediaSourceSubscription.LastUpdated(
                        currentTimeMillis,
                        mediaSourceCount = result?.mediaSourceCount,
                        error = error,
                        unsupportedMediaSourceCount = result?.unsupportedMediaSourceCount ?: 0,
                    ),
                )
            }
        }

        return try {
            val result = updateSubscription(subscription)
            if (result != null) setResult(result)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedSubscriptionSchemaException) {
            setResult(null, UpdateError(e.toString(), null, requiresNewerApp = true))
            false
        } catch (e: RepositoryException) {
            when (e) {
                is RepositoryAuthorizationException ->
                    setResult(null, UpdateError(e.toString(), ApiFailure.Unauthorized))

                is RepositoryNetworkException ->
                    setResult(null, UpdateError(e.toString(), ApiFailure.NetworkError))

                is RepositoryRateLimitedException ->
                    setResult(
                        null,
                        UpdateError("请求过于频繁", null), // TODO: 2024/12/3 use ApiFailure.RateLimited
                    )

                is RepositoryServiceUnavailableException ->
                    setResult(null, UpdateError(e.toString(), ApiFailure.ServiceUnavailable))

                is RepositoryUnknownException ->
                    setResult(null, UpdateError(e.toString(), null))

                is RepositoryRequestError ->
                    setResult(null, UpdateError(e.localizedMessage, null))

                // 订阅更新不碰条目, 这个分支只是为了穷尽
                is RepositorySubjectNotAccessibleException ->
                    setResult(null, UpdateError(e.toString(), null))
            }
            false
        } catch (e: Exception) {
            logger.error(e) { "Failed to update subscription ${subscription.url}" }
            setResult(null, UpdateError(e.toString(), null))
            false
        }
    }

    /**
     * 订阅中的一个数据源. [arguments] 为 `null` 表示这个客户端无法解析它, 例如未知的数据源类型或更高的版本.
     */
    class RemoteEntry(
        val data: ExportedMediaSourceData,
        val arguments: MediaSourceArguments?,
    ) {
        val id: String? get() = data.id

        // 无法解析时也读出名称, 用来对应本地已有的数据源
        val name: String? = arguments?.name
            ?: ((data.arguments as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

        override fun toString(): String = "RemoteEntry(id=$id, name=$name, supported=${arguments != null})"
    }

    class ExistingArgument(
        val save: MediaSourceSave,
        val arguments: MediaSourceArguments?,
    ) {
        val id: String? get() = save.config.idInSubscription
        val name: String? get() = arguments?.name

        override fun toString(): String = "ExistingArgument(instanceId=${save.instanceId}, id=$id, name=$name)"
    }

    private class UpdateResult(
        val mediaSourceCount: Int,
        val unsupportedMediaSourceCount: Int,
    )

    @Throws(RepositoryException::class, CancellationException::class)
    private suspend fun updateSubscription(subscription: MediaSourceSubscription): UpdateResult? {
        // 下载新订阅列表
        val updateData = requester.request(subscription)
        val remoteEntries = updateData.mediaSources.map { data ->
            val arguments = try {
                codecManager.decode(data)
            } catch (e: Exception) {
                logger.warn(e) { "Unsupported media source in subscription ${subscription.url}: ${data.factoryId} v${data.version}" }
                null
            }
            RemoteEntry(data, arguments)
        }

        var result: UpdateResult? = null
        // Serialize source reconciliation with subscription availability changes. Network I/O stays outside the transaction.
        subscriptions.update(subscription.subscriptionId) { current ->
            if (current.enabled) {
                applyUpdate(current, remoteEntries)
                result = UpdateResult(
                    mediaSourceCount = remoteEntries.size,
                    unsupportedMediaSourceCount = remoteEntries.count { it.arguments == null },
                )
                current.copy(metadata = updateData.metadata)
            } else {
                current
            }
        }
        return result
    }

    private suspend fun applyUpdate(
        subscription: MediaSourceSubscription,
        remoteEntries: List<RemoteEntry>,
    ) {
        // 获取现有的
        val existing = mediaSourceManager.getListBySubscriptionId(subscriptionId = subscription.subscriptionId)
            .map { save ->
                ExistingArgument(save, deserializeArgumentsOrNull(save))
            }

        // 计算差异
        val diff = calculateDiff(remoteEntries, existing)
        logger.info { "updateSubscription diff: $diff" }

        // 解决差异
        mediaSourceManager.removeInstances(diff.removed.map { it.save.instanceId })

        val orderedInstanceIds = mutableListOf<String>()
        for ((remote, local) in diff.matched) {
            when {
                local == null -> {
                    val id = Uuid.randomString()
                    mediaSourceManager.addInstance(
                        id,
                        id,
                        remote.data.factoryId,
                        MediaSourceConfig(
                            serializedArguments = remote.data.arguments,
                            subscriptionId = subscription.subscriptionId,
                            idInSubscription = remote.id,
                            tags = remote.data.tags,
                        ),
                    )
                    orderedInstanceIds.add(id)
                }

                // 无法解析新的配置, 保留本地已有的, 等用户升级客户端
                remote.arguments == null -> orderedInstanceIds.add(local.save.instanceId)

                else -> {
                    val updated = local.save.config.copy(
                        serializedArguments = remote.data.arguments,
                        idInSubscription = remote.id,
                        tags = remote.data.tags,
                    )
                    if (!mediaSourceManager.updateConfig(local.save.instanceId, updated)) {
                        logger.error { "Failed to update existing save ${local.save.instanceId}" }
                    }
                    orderedInstanceIds.add(local.save.instanceId)
                }
            }
        }

        // 更新排序, 让本地的排序跟远程一致
        mediaSourceManager.partiallyReorderInstances(orderedInstanceIds)
    }

    private fun deserializeArgumentsOrNull(save: MediaSourceSave): MediaSourceArguments? {
        return save.config.serializedArguments?.let {
            try {
                codecManager.deserializeArgument(save.factoryId, it)
            } catch (e: FactoryNotFoundException) {
                // 本地保存了这个版本不认识的数据源 (例如降级后). 它不参与订阅更新, 不能让整个订阅刷新失败
                logger.warn(e) { "Cannot decode media source ${save.mediaSourceId} of unknown factory ${save.factoryId}, ignoring" }
                null
            }
        }
    }

    data class Diff(
        val removed: List<ExistingArgument>,
        /**
         * 按订阅中的顺序. 本地没有对应的数据源时为 `null`, 需要新建. 无法解析且本地没有对应的数据源不在其中.
         */
        val matched: List<Pair<RemoteEntry, ExistingArgument?>>,
    )

    internal companion object {
        private val logger = logger<MediaSourceSubscriptionUpdater>()

        private val DEFAULT_UPDATE_PERIOD = 1.hours

        /**
         * 更新失败后的重试间隔, 按连续失败次数递增. 超出这个表之后就一直用最后一项.
         *
         * 头几次故意很短: 最常见的失败原因是应用启动的头几秒设备网络还没就绪, 过一会就好了.
         */
        private val FAILURE_RETRY_PERIODS = listOf(15.seconds, 30.seconds, 1.minutes, 5.minutes, 15.minutes)

        /**
         * @param consecutiveFailures 已经连续失败了几次 (至少 1 次)
         */
        fun failureRetryPeriod(consecutiveFailures: Int, updatePeriod: Duration): Duration =
            FAILURE_RETRY_PERIODS[(consecutiveFailures - 1).coerceIn(0, FAILURE_RETRY_PERIODS.lastIndex)]
                // 正常间隔本身就比重试间隔还短时 (用户自己调过), 没必要更频繁
                .coerceAtMost(updatePeriod)

        fun MutableMap<String, Int>.increment(key: String): Int =
            ((this[key] ?: 0) + 1).also { this[key] = it }

        internal fun calculateDiff(remoteEntries: List<RemoteEntry>, existing: List<ExistingArgument>): Diff {
            val unclaimed = existing.toMutableList()
            val localByRemote = arrayOfNulls<ExistingArgument>(remoteEntries.size)

            fun claim(predicate: (RemoteEntry, ExistingArgument) -> Boolean) {
                remoteEntries.forEachIndexed { index, remote ->
                    if (localByRemote[index] != null) return@forEachIndexed
                    val local = unclaimed.firstOrNull {
                        it.save.factoryId == remote.data.factoryId && predicate(remote, it)
                    } ?: return@forEachIndexed
                    unclaimed.remove(local)
                    localByRemote[index] = local
                }
            }
            // 先按 id 对应. 名称可能被订阅作者修改, 只在有一方没有 id 时使用
            claim { remote, local -> remote.id != null && remote.id == local.id }
            claim { remote, local -> (remote.id == null || local.id == null) && remote.name != null && remote.name == local.name }

            val matched = remoteEntries.mapIndexedNotNull { index, remote ->
                val local = localByRemote[index]
                if (local == null && remote.arguments == null) return@mapIndexedNotNull null
                remote to local
            }
            // 新订阅里没有对应的, 说明被删除了
            return Diff(removed = unclaimed, matched = matched)
        }
    }
}
