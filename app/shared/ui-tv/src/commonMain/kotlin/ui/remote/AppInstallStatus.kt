/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.compose.runtime.Immutable
import me.him188.ani.app.tools.update.FileDownloadStage

/** 电视上这一次下载并安装 (见 [RemoteAppUpdate.status]) 走到哪一步. */
internal enum class AppInstallPhase {
    Idle,

    /** 下载中 (挑线路、收数据、换线路、校验) */
    Downloading,

    /** 下好了, 在核对是不是本项目的包、签名对不对 */
    Verifying,

    /** 下好、核对过了, 等退出播放再装 (电视上发起的不在播放时弹安装确认) */
    WaitingPlayer,

    /** 往系统的安装会话里写 */
    Preparing,

    /** 等用户在系统的确认框里点「安装」 */
    Confirm,

    /** 等应用回到前台再弹确认框 (手机上发起、电视上在别的应用里时) */
    WaitingFront,
    Failed,
    Success;

    /** 正在下载、核对、写入或等着装: 这时不接新的安装 */
    val busy: Boolean get() = this == Downloading || this == Verifying || this == WaitingPlayer || this == Preparing
}

/** 装的是什么. */
internal enum class AppInstallKind {
    /** 检查到的新版本 (手机控制台「应用更新」里下载并安装) */
    Update,

    /** 工单回复里维护者给的测试包 (手机上「装到电视」、电视上通知中心里「安装测试包」) */
    TestPackage,

    /** 手机传上来的安装包 */
    Upload,
}

/**
 * 电视上这一次下载并安装的状态: 手机控制台「应用更新」与电视 (动作面板顶上、通知中心「我的反馈」里的测试包) 读的是同一份.
 *
 * @param kind 装的是什么
 * @param issue 测试包是哪张工单回复里的 ([AppInstallKind.TestPackage] 时有)
 * @param name 版本名或安装包的文件名
 * @param otherPackage 装的是另一个包 (另一个 Izuko TV) 时它的包名; null = 装本应用自己
 * @param stage 下载走到哪一步, 只在 [AppInstallPhase.Downloading] 时有
 * @param progress 0~1: 下载中知道总长时是已下的比例, 写入安装会话时是写了的比例; 别的时候为 null
 * @param error 没装上的原因 (照手机控制台的语言写)
 * @param startedOnTv 在电视上发起的 (通知中心里按「安装测试包」): 没装上时弹动作面板
 * @param canRetry 没装上、安装包还在: 可以不重新下载再装一次 ([RemoteAppUpdate.retryInstall])
 */
@Immutable
internal class AppInstallStatus(
    val phase: AppInstallPhase,
    val kind: AppInstallKind = AppInstallKind.Update,
    val issue: Int? = null,
    val name: String? = null,
    val otherPackage: String? = null,
    val stage: FileDownloadStage? = null,
    val progress: Float? = null,
    val error: String? = null,
    val startedOnTv: Boolean = false,
    val canRetry: Boolean = false,
) {
    /** 已下了多少字节 (下载中收数据时有) */
    val downloaded: Long? get() = (stage as? FileDownloadStage.Transferring)?.downloadedBytes

    /** 安装包一共多少字节 (下载中、知道总长时有) */
    val total: Long? get() = (stage as? FileDownloadStage.Transferring)?.totalBytes

    companion object {
        val Idle = AppInstallStatus(AppInstallPhase.Idle)
    }
}
