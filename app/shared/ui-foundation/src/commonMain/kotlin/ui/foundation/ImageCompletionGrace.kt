/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger

/**
 * 同时挂在后台补下的图片数上限.
 *
 * 取 2, 而不是按"聚焦 + 四个邻居"来估: 队列里装的全是**已经离场**的卡片, 邻居数是"还没到的
 * 目标"的量级, 拿它当依据本身就不对. 更关键的是 OkHttp 对同一 host 默认只放 5 个并发, 而
 * 卡片封面与补下走的是同一个图床 —— 补下占得多, 真正可见的新卡片就得排队, 窄带宽下这正是
 * 要避免的事.
 *
 * 尤其要防的是**整页销毁**那一下 (进详情页/播放页): 满屏没下完的卡片同时触发 `onDispose`,
 * 一瞬间就能提交十几个, 而那一刻带宽本该全留给目的页的首图.
 */
private const val MAX_PENDING_COMPLETIONS = 2

private val logger = logger("ImageCompletionGrace")

/**
 * 还在后台补下的请求. 超上限时**丢弃新提交的**, 让已经在跑的那几个跑完.
 *
 * 早先是"挤掉最老的", 实测下来两头都坏 (2026-08-15 日志: 27 次提交 / 19 次被挤掉 / 只有 8 次
 * 真跑完):
 * - **没达成目的**: 被 `dispose()` 的那些, 下到一半的字节照样作废 —— 而这个机制存在的理由
 *   恰恰就是"别让下到一半的字节作废". 上限从 6 降到 2 之后, 挤掉成了常态, 于是它七成时间在
 *   做无用功.
 * - **把取消模式带回来了**: `dispose()` 取消的是正在读的 TLS socket, 而这正是
 *   Android 11 Conscrypt 并发关闭崩溃的引信 (见 `TvHeroImagePrefetch.retain` 的说明).
 *
 * 丢弃新的则两条都不占: 不取消任何在途连接, 而且每次都真能攒下完整的几张. 代价是最新离场的
 * 那张不补 —— 它恰恰是最可能马上被划回来的, 但届时可见请求自己会重下, 与不补下时一样.
 *
 * 不加锁: 提交点是 Compose 的 `onDispose`, 移除点是补下协程的 finally ([completionScope] 在主线程上调度), 两者都在主线程.
 */
private val pendingCompletions = LinkedHashMap<String, Job>()

/** 补下协程的作用域: 不跟着组合走, 卡片离场之后照样跑完. 主线程调度, 真正的下载在 [downloadToCache] 里切到 IO. */
private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 图片加载的"完成宽限": 卡片离开组合时那张还没下完的图, 交给后台跑完并写进磁盘缓存.
 *
 * 图片库把请求绑在组合上, 卡片一离开就取消, 而**已经下到一半的字节全废** —— HTTP 缓存要拿到
 * 完整响应才落盘. 带宽宽裕时这无所谓, 一张图几百毫秒就下完了, 根本碰不到取消; 窄带宽上却是
 * 致命的: 一张 400 KB 的封面要六七秒, 远慢于遥控器导航的节奏, 于是每张图都在"下一半 → 丢弃
 * → 回来重下"里打转, 永远下不完, 卡片就在骨架和图之间反复闪 (issue #7 报告者的日志里
 * 五十多条 CANCELLED, 同一张封面被取消两次才终于下完).
 *
 * 补下**只落盘、不解码** (见 [downloadToCache]): 卡片已经不在屏上了, 解出位图没人用, 还占着屏上卡片的解码队列;
 * 写到磁盘就够 —— 焦点转回来时从本地读, 不必再走网络.
 *
 * 只补**真正发出过**的请求, 补的也是**实际发出的地址**: AsyncImage 测到尺寸才发请求, 而 Bangumi 封面按显示宽度换成了
 * 图床的缩略图 (见 bangumiCoverThumbnailUrl) —— 补下原图的话下次显示用不上. 预组合了却没摆上屏的卡片 (懒列表的预取)
 * 还没发请求, 离场时什么都不做, 不占补下的名额.
 *
 * 用法: 拿到的对象交给 [AsyncImage] 的 `completionGrace`, 由它记下发出的地址、上屏时置位.
 * ```
 * val grace = rememberImageCompletionGrace(imageUrl)
 * AsyncImage(imageUrl, ..., completionGrace = grace)
 * ```
 *
 * @param url 卡片要显示的图片 URL; 换了就重新计.
 */
@Composable
fun rememberImageCompletionGrace(
    url: String?,
    sketch: Sketch = LocalSketch.current,
): ImageCompletionGrace {
    val context = LocalPlatformContext.current
    val grace = remember(url) { ImageCompletionGrace() }
    DisposableEffect(grace, sketch) {
        onDispose {
            // 在 onDispose 里读, 不是组合期间读 —— 不会给这个卡片建立重组订阅
            val target = grace.requestUrl
            if (!grace.loaded && target != null) {
                submitImageCompletion(context, sketch, target)
            }
        }
    }
    return grace
}

/** 见 [rememberImageCompletionGrace]. 只在主线程读写 (组合后的 SideEffect、加载回调、onDispose). */
class ImageCompletionGrace internal constructor() {
    /** 这张图已经上屏 (AsyncImage 加载成功时置位). */
    internal var loaded: Boolean = false

    /** 实际发出的地址, AsyncImage 发请求时写入; 补下的就是它. 还没发过请求时为 null. */
    internal var requestUrl: String? = null
}

private fun submitImageCompletion(context: PlatformContext, sketch: Sketch, url: String) {
    if (pendingCompletions.containsKey(url)) return
    if (pendingCompletions.size >= MAX_PENDING_COMPLETIONS) {
        logger.debug { "Completion queue full, dropping: $url" }
        return
    }
    // 非立即调度: 先登记再开跑, finally 里的移除一定在登记之后
    pendingCompletions[url] = completionScope.launch {
        try {
            sketch.downloadToCache(context, url)
            logger.debug { "Completed in background: $url" }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 补下失败无所谓: 卡片回到屏上时自己会再请求一次
        } finally {
            pendingCompletions.remove(url)
        }
    }
}
