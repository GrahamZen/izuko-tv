/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.utils.io.SystemPath

/**
 * 反馈里各端不一样的东西: 日志与照片放在哪、「维护 → 性能诊断」的报告 (只有安卓有)、装工单回复里的测试包 (只有安卓能装 APK).
 * 应用内的反馈弹窗与 Web 控制台都经它取.
 */
internal expect object FeedbackPlatform {
    /** 应用日志的目录 (见 [ReproLogBundle]); 取的时候先把还在缓冲里的日志写进去. 没有为 null. */
    fun logsDir(): SystemPath?

    /** 应用私有的文件目录 (反馈的照片存在下面, 见 [FeedbackPhotoStore]); 没有为 null. */
    fun filesDir(): SystemPath?

    /** 已有的性能诊断报告, 新的在前, 最多 [limit] 份 (不带原文); 没有性能诊断的端为空. */
    fun perfReports(limit: Int): List<FeedbackPerfReport>

    /** 报告 [id] (带原文); 不在了为 null. */
    fun perfReport(id: String): FeedbackPerfReport?

    /** 装工单回复里的测试包 (APK); 装不了的端 (iOS) 为 null, 界面上只给链接. */
    val apkInstaller: FeedbackApkInstaller?
}

/** 装工单回复里的测试包: 下载、按工单服务给的摘要校验、交给系统安装 (同 Web 控制台「装到电视」那条路). */
internal interface FeedbackApkInstaller {
    /** 最近交来的那个测试包正在做的那一步 (下载进度、校验、等确认…) 或刚出的错; 空闲为 null. */
    val status: StateFlow<FeedbackApkStatus?>

    /** 开始装工单 [issue] 回复里的 [url] ([sha256] = 工单服务查到的摘要). */
    fun install(url: String, sha256: String?, issue: Int): FeedbackApkInstall
}

/** 测试包 [url] 装到哪一步了, [text] 是给人看的一句话. */
internal class FeedbackApkStatus(val url: String, val text: String)

internal enum class FeedbackApkInstall {
    /** 开始下载了 (进度见 [FeedbackApkInstaller.status]). */
    STARTED,

    /** 还没允许本应用安装应用: 打开了授权页, 允许后再点一次. */
    NEED_PERMISSION,

    /** 正在装别的, 等这次完成. */
    BUSY,

    /** 应用还没准备好 (进程刚起来). */
    NOT_READY,
}

/**
 * 附到反馈的一份性能诊断报告 (安卓「维护 → 性能诊断」里已有的体检 / 录制): 列出来怎么显示、issue 里写哪一行, 以及报告原文 (放在日志附件开头).
 */
internal class FeedbackPerfReport(
    val id: String,
    /** `health` = 设备体检, 其余 = 录制. */
    val kind: String,
    val seconds: Int,
    /** 录制时停在哪个页面 (导航栈顶). */
    val page: String?,
    /** 保存的时刻, 给人看的 (`MM-dd HH:mm:ss`). */
    val time: String,
    /** 第一条结论; 没发现问题时为 `null`. */
    val headline: String?,
    /** 报告原文 (JSON); 列表里的不带, 为空. */
    val text: String,
) {
    val health: Boolean get() = kind == "health"

    /** issue 里「性能报告」那张表的一行: 键给维护者看 (中文), 值是结论. */
    val row: Pair<String, String>
        get() = (if (health) "设备体检 $time" else "录制 $seconds 秒 · ${page ?: "?"} · $time") to (headline ?: "没有发现问题")
}
