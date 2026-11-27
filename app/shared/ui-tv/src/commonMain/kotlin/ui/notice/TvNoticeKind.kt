/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.foundation.widgets.Toaster

/**
 * 一种通知: 标题与摘要、右栏的说明与操作 (见 docs/contributing/code/notices.md). 一个种类一个文件, 在 [TvNotices] 的种类表里登记一行.
 *
 * 发通知的一方只碰数据层: 消息在事件发生处 `Notices.post`, 跟着状态的由这里的 [source] 给出.
 */
interface TvNoticeKind {
    /** 种类 id, 同 [Notice.kind]. */
    val id: String

    /** 类别: 列表行左边的图标 (给了 [cover] 的画封面)、右栏标题上面的签. */
    val category: TvNoticeCategory

    /** 跟着状态的来源 (启动时接到 `Notices` 上, 一个进程一次); 只发消息的种类为 null. */
    fun source(): Flow<List<Notice>>? = null

    /** 标题: 怎么了, 写得短、一行放得下 (列表、气泡、手机上的横幅都用它); 切换语言后照样对. null = 去处已经不在了, 这条不列. */
    suspend fun title(notice: Notice): String?

    /** 列表里标题下面那一行: 是哪一个 (作品、反馈…), 或说明的开头; null = 没有这一行. 切换语言后照样对. */
    suspend fun summary(notice: Notice): String? = null

    /** 这条通知说的是哪一部作品: 给了, 列表行、横幅与手机控制台的列表左边画它的封面, 不画类别图标 (见 [TvNoticeCover]); 说的不是某一部作品的为 null. */
    suspend fun cover(notice: Notice): TvNoticeCover? = null

    /** [title]、[summary] 与 [detail] 除了通知本身还看的状态: 它一变, 列出来的通知重算一遍 (该不列的不列); 只看通知本身的为 null. */
    fun textChanges(): Flow<*>? = null

    /** 电视上的去处; null = 没有 (只是告知). 给了 [detail] 的种类照它的操作走, 这里只在说明取不到时用. */
    fun destination(notice: Notice): TvNoticeDestination?

    /** 聚焦这一条时右栏写的说明与处理它的操作. null = 右栏写标题与摘要, 有 [destination] 时它是唯一的操作 (「查看」). */
    suspend fun detail(notice: Notice): TvNoticeDetail? = null

    /** 控制台上的去处 (控制台页面的地址, 如 `#settings/feedback`); null = 控制台上只能「在电视上打开」. */
    fun consoleHref(notice: Notice): String? = null

    /**
     * 这一类在哪个签里看: null = 「通知」签里列一行. 别的签的 id ([TvNoticeTab.id]; 通知的键就是那个签里那一行的键, 如反馈回复在「我的反馈」签)
     * = 不在「通知」签与手机控制台的通知列表里列, 由那个签自己提醒 (签上的红点) 与标读过; 照样算侧边栏「通知」的数字与通知气泡,
     * 气泡上按「查看」开到那个签的那一行.
     */
    val tab: String? get() = null
}

/** 在「通知」签里列 (见 [TvNoticeKind.tab]). */
internal val TvNoticeKind.listed: Boolean get() = tab == null

/**
 * 右栏 (标题是 [TvNoticeKind.title]): 标题下面一行是哪一个 [subject] (作品、反馈; 不关哪一个的为 null)、正文 [body] (一项一段),
 * 与处理它的操作 [actions] (只有一个时不画按钮, 在列表里按确定键直接做; 几个时按确定键或右键进右栏挑一个; 没有的只是告知).
 * [qr] 是说明里的外部链接 (见 [TvNoticeQr]), 画在正文后面、操作前面.
 */
@Immutable
class TvNoticeDetail(
    val body: List<TvNoticeParagraph>,
    val actions: List<TvNoticeAction>,
    val subject: String? = null,
    val qr: TvNoticeQr? = null,
)

/**
 * 正文里的一段.
 *
 * @param text 这一段的字 (可以带粗体)
 * @param title 小标题, 写在这一段上面 (如新版本里一项改动的名字、「维护者的回复」); 没有就只是一段话
 * @param bullet 前面画一个点 (列成一条条的要点)
 * @param maxLines 至多几行 (电视、触屏都是): 摘要类的说明截掉的去操作里看全; 默认写全
 * @param longName 资源名这类长串: 触屏上操作画成按钮, 右栏放不下, 截到三行; 电视上照常写全
 * @param tone 这一段的颜色 (见 [TvNoticeTone])
 */
@Immutable
data class TvNoticeParagraph(
    val text: AnnotatedString,
    val title: String? = null,
    val bullet: Boolean = false,
    val maxLines: Int = Int.MAX_VALUE,
    val longName: Boolean = false,
    val tone: TvNoticeTone = TvNoticeTone.Normal,
) : TvNoticeBlock {
    constructor(
        text: String,
        title: String? = null,
        bullet: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
        longName: Boolean = false,
        tone: TvNoticeTone = TvNoticeTone.Normal,
    ) : this(AnnotatedString(text), title, bullet, maxLines, longName, tone)
}

/**
 * 正文里的一个二维码, 画 [url] 的码 ([url] 为 null = 还没有码, 只写字). 两种排法:
 * - 有 [title]: 码在左, 右边竖排 [title] (大字)、[caption]、[status] (点 + 字, 颜色见 [statusTone]), 整块与码垂直居中;
 * - 没有 [title]: 码下面写 [caption] (如外部链接上的字).
 *
 * [openOnTouch] = 这是一个外部链接: 触屏上不画码, 画一颗在浏览器里打开的按钮 (控制台上是一个链接); false 的码在触屏上照样画 (给另一台手机扫).
 * [console] = 这是控制台的码 (扫了打开这台设备的控制台): 没有控制台的平台不画这一块.
 */
@Immutable
data class TvNoticeQr(
    val url: String?,
    val caption: String?,
    val title: String? = null,
    val status: String? = null,
    val statusTone: TvNoticeTone = TvNoticeTone.Normal,
    val openOnTouch: Boolean = false,
    val console: Boolean = false,
) : TvNoticeBlock

/**
 * 一个操作: 按钮上的字 [label] (只写动作, 如「自动更新」) 与按了去哪.
 *
 * [quick] 给了 = 它是这一条的第一个操作时, 通知横幅上短按直接做它, 横幅第二行写「按确定键[quick]」
 * (短的动作名, 如「查看」). null = 一按就做不合适 (开始下载安装这类事先要看说明的), 短按照旧打开通知弹窗.
 */
@Immutable
class TvNoticeAction(val label: String, val destination: TvNoticeDestination, val quick: String? = null)

/** 按确定后去哪. */
sealed interface TvNoticeDestination {
    /** 跳到一个页面; 在那页按返回回到首页时通知弹窗重新打开. */
    class Page(val navigate: (AniNavigator) -> Unit) : TvNoticeDestination

    /** 在通知弹窗上叠一个弹窗, 关掉回到列表. */
    class Dialog(val content: @Composable (onDismiss: () -> Unit) -> Unit) : TvNoticeDestination

    /** 当场做完的一件事 (如重新搜索): 做完弹一条提示, 留在列表. */
    class Run(val run: suspend (Toaster) -> Unit) : TvNoticeDestination
}
