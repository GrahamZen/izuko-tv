/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web.captcha

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.domain.mediasource.web.LoadedPage

/**
 * 浏览器 cookie. CEF 能提供完整属性; Android WebView 只能拿到 name=value, 其余为 `null`.
 */
data class BrowserCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val expiresEpochMillis: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
)

/**
 * 资源请求拦截决定, 用于视频资源嗅探.
 */
enum class InterceptDecision {
    /** 放行该请求 */
    Continue,

    /** 取消该请求 (已捕获到目标资源) */
    Block,
}

/**
 * 平台浏览器适配器. 平台只实现 "一个能被驱动的浏览器", 不含任何业务逻辑.
 *
 * ### 线程铁律
 *
 * - 所有方法都是 `suspend` (或立即返回), 内部自行 marshal 到 CEF/Main 线程;
 * - 浏览器回调线程 (CEF 的 EDT、Android 的 Main) 上只允许 `tryEmit` / `complete`,
 *   禁止任何形式的等待 (`runBlocking`、`invokeAndWait`、信号量等);
 * - [collectCookies] 用 `suspendCancellableCoroutine` 桥接回调, 由调用方协程消费, 永不阻塞 UI 线程.
 */
interface CaptchaBrowser : AutoCloseable {
    /**
     * 浏览器的真实 User-Agent, 用于 HTTP 侧身份对齐 (`cf_clearance` 绑定 UA).
     */
    val userAgent: String

    /**
     * 主 frame 每次加载完成时 emit (url, html). 回调线程上通过 `tryEmit` 发射.
     */
    val pageLoads: SharedFlow<LoadedPage>

    /**
     * 是否正在加载页面. 供交互对话框显示进度条.
     */
    val isLoading: StateFlow<Boolean>

    suspend fun navigate(url: String)

    /**
     * 当前页面的快照 (url + html). 页面未就绪时返回 `null`.
     */
    suspend fun currentPage(): LoadedPage?

    /**
     * 在当前页面执行脚本. 实现必须自行切换到平台要求的浏览器线程.
     *
     * 自动图片验证码策略通过脚本读取验证码图片并填写答案; 页面是否已通过仍由
     * `PageEvaluator` 统一判定, 平台层不解释脚本结果.
     */
    suspend fun executeJavaScript(script: String)

    /**
     * 收集 [urls] 各自可见的 cookies (去重由调用方负责).
     */
    suspend fun collectCookies(urls: List<String>): List<BrowserCookie>

    /**
     * 设置资源请求拦截器 (视频资源嗅探). 传 `null` 清除.
     *
     * [handler] 会在浏览器网络线程被调用, 必须快速返回, 禁止阻塞.
     */
    fun setResourceInterceptor(handler: ((url: String) -> InterceptDecision)?)

    /**
     * 设置**导航**拦截器 (与 [setResourceInterceptor] 的资源请求是两回事). 传 `null` 清除.
     *
     * [handler] 返回 `true` = 这一跳由调用方接管, 浏览器不要加载它.
     *
     * 用途是 OAuth 回调: bangumi 授权完会把浏览器导航到 `ani://bangumi-oauth-callback?code=...`,
     * 而自定义 scheme 走不到资源拦截 (Android 的 `shouldInterceptRequest` 不覆盖它, 让它加载只会
     * 得到 `ERR_UNKNOWN_URL_SCHEME`). 拦在这里等于**根本不经过系统**, 桌面端也就不需要
     * 注册 scheme 或起本地服务器.
     *
     * 只有 Android (`shouldOverrideUrlLoading`) 与桌面 CEF (`onBeforeBrowse`) 实现了它;
     * 其余平台默认空实现 —— 那些平台的登录走"外部浏览器 + 系统 deep link"那条路.
     *
     * [handler] 在浏览器线程被调用, 必须快速返回.
     */
    fun setNavigationInterceptor(handler: ((url: String) -> Boolean)?) {}

    /**
     * 浏览器视图 (desktop `SwingPanel` / android `AndroidView`).
     *
     * TV (fork): Android 实现会在视图上叠一层遥控器虚拟光标 (方向键移动 / 确认键点击 /
     * 长按确认键完成 / 返回键退出), 通过下面两个回调把"退出/完成"通知给宿主对话框
     * (返回键会被 WebView 抢去做历史后退, 系统返回分发不到 Dialog, 只能在此层拦截).
     * 其余平台忽略这两个回调.
     */
    @Composable
    fun View(
        modifier: Modifier,
        onExitRequest: (() -> Unit)? = null,
        onConfirmRequest: (() -> Unit)? = null,
        tvInputMode: TvWebInputMode = TvWebInputMode.Cursor,
    )
}

/**
 * 电视上怎么操作这个网页. 只有 Android 实现认它 (桌面有鼠标键盘).
 */
enum class TvWebInputMode {
    /**
     * 虚拟光标: 方向键移动一个圆点, 确认键在圆点处注入触摸点击.
     * 光标走到视口边缘时带着页面滚动 (否则折叠线以下的按钮永远够不着).
     *
     * **默认就用它**, 验证码页与 bangumi 登录页都是. 理由是[焦点遍历][NativeFocus]**给不出保证**:
     * 方向键只能到达标准可聚焦元素 (`a[href]` / `button` / `input` / 带 tabindex 的), 页面用
     * `<div onclick>` 挂点击处理时它永远碰不到 —— 而验证码的滑块图块正是这种. 光标是注入触摸事件,
     * 页面上任何东西都点得到.
     *
     * 按键在 **tunnel 阶段**就被截走 (见 Android 实现的 onPreviewKeyEvent), 不依赖 WebView
     * 吐不吐这一键, 所以行为在各家 ROM 上一致.
     */
    Cursor,

    /**
     * 网页自己的方向键焦点遍历: 焦点在链接/输入框/按钮之间跳, 移动时页面自动滚到可见,
     * 选中输入框弹出系统输入法. 页面加载完还会自动把焦点放到第一个可见的可操作元素上
     * (不这么做的话 DOM 里一个焦点都没有, 方向键第一下不知道从哪儿开始).
     *
     * **够不到 `<div onclick>` 这类非可聚焦元素**, 所以不当默认, 只作为顶栏那个钮的另一档:
     * 表单页想用输入法时切过来更顺手.
     *
     * 曾经试过"网页优先, 光标兜底"(WebView 没吃下这一键才动光标), **在真机上不成立** ——
     * Android WebView 一律吃掉方向键, 不管焦点有没有真的移动, 冒泡兜底从不触发
     * (2026-09-06 用户实测: 光标推不动).
     */
    NativeFocus,
}

interface CaptchaBrowserFactory {
    /**
     * 当前平台是否支持浏览器. iOS 为 `false`.
     */
    val isSupported: Boolean

    /**
     * 平台推荐的最大暖会话数 (LRU 上限). 桌面 3, Android 2.
     */
    val recommendedMaxSessions: Int get() = 3

    /**
     * 创建一个新的浏览器实例. [isSupported] 为 `false` 时抛出 [UnsupportedOperationException].
     */
    suspend fun create(): CaptchaBrowser
}

/**
 * 无浏览器平台 (iOS) 与无头环境 (测试工具) 使用的工厂.
 */
object UnsupportedCaptchaBrowserFactory : CaptchaBrowserFactory {
    override val isSupported: Boolean get() = false
    override suspend fun create(): CaptchaBrowser =
        throw UnsupportedOperationException("CaptchaBrowser is not supported on this platform")
}
