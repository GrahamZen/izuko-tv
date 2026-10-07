/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 各网盘 (按网盘 id, 见 `CloudDriveProtocol.id`) 的账号. 存在整机设置里, 设置备份不包含.
 */
@Serializable
data class CloudDriveAccounts(
    val accounts: Map<String, CloudDriveAccount> = emptyMap(),
) {
    fun of(driveId: String): CloudDriveAccount = accounts[driveId] ?: CloudDriveAccount.Default

    fun with(driveId: String, account: CloudDriveAccount): CloudDriveAccounts =
        copy(accounts = if (account == CloudDriveAccount.Default) accounts - driveId else accounts + (driveId to account))

    override fun toString(): String = "CloudDriveAccounts(${accounts.entries.joinToString { "${it.key}=${it.value}" }})"

    companion object {
        val Default = CloudDriveAccounts()
    }
}

/**
 * 一个网盘账号与播放方式.
 *
 * [cookie] 是登录后的整段 Cookie 请求头, 由扫码登录或手填写入, 接口响应里轮换的登录 Cookie 也回写到这里.
 * 与 [PikPakConfig] 的密码一样经 [ObscuredStringSerializer] 混淆后落盘, 只防随手查看, 不是加密.
 *
 * [nickname] 与 [tier] 是登录时与每次校验账号时顺手记下的展示信息, 不参与鉴权.
 */
@Serializable
data class CloudDriveAccount(
    @Serializable(with = ObscuredStringSerializer::class)
    val cookie: String = "",
    val nickname: String = "",
    /** 账号档位 (如会员类型), 由协议的 `tier` 操作给出. */
    @SerialName("memberType")
    val tier: String = "",
    val playbackMode: CloudDrivePlaybackMode = CloudDrivePlaybackMode.ORIGINAL,
    /**
     * 播放别人的分享时, 转存到的那个文件夹的 id (找到或建过一次就记下). 空表示还没有.
     * 搜自己网盘时跳过这个文件夹, 不用每次先去根目录找它.
     */
    val shareSaveFolderId: String = "",
    /**
     * 用户在 Web 控制台给条目手动指定的网盘文件夹与文件 (条目 id → 指定). 文件 id 只在这个账号里有效, 退出登录时跟着清掉.
     */
    val subjectPicks: Map<Int, DriveSubjectPicks> = emptyMap(),
    /**
     * 用户在 Web 控制台给视频手动挂上的网盘字幕文件 (视频 → 字幕, 见 `CloudDriveService.subtitleKeyOf`), 播放这个视频时一并带上.
     * 同 [subjectPicks] 只在这个账号里有效.
     */
    val pickedSubtitles: Map<String, List<DrivePickedSubtitle>> = emptyMap(),
    /**
     * 自动记下的「条目在这个文件夹里」(条目 id → 播过的那一集所在的文件夹): 之后先列它, 找到要的那一集就不再全盘搜索.
     * 与手动指定的 [subjectPicks] 分开存; 同 [subjectPicks] 只在这个账号里有效. 按记下的先后保留最近的若干个.
     */
    val rememberedFolders: Map<Int, DriveRememberedFolder> = emptyMap(),
    /**
     * 「分享搜索」数据源自动记下的分享文件夹 (`数据源 id:条目 id` → 播过的那一集在分享里所在的文件夹): 之后那个源先只列它,
     * 有要的那一集就不再去站点搜索、也不再打开别的分享. 按记下的先后保留最近的若干个.
     */
    val rememberedShares: Map<String, DriveRememberedShare> = emptyMap(),
) {
    val isLoggedIn: Boolean get() = cookie.isNotBlank()

    override fun toString(): String {
        return "CloudDriveAccount(cookie.hash=${if (cookie.isNotEmpty()) cookie.hashCode() else ""}, " +
                "nickname=$nickname, tier=$tier, playbackMode=$playbackMode, shareSaveFolderId=$shareSaveFolderId, " +
                "subjectPicks=${subjectPicks.size}, pickedSubtitles=${pickedSubtitles.size}, rememberedFolders=${rememberedFolders.size}, rememberedShares=${rememberedShares.size})"
    }

    companion object {
        val Default = CloudDriveAccount()
    }
}

/**
 * 一个条目在网盘里手动指定的位置 (自动匹配对不上时用).
 *
 * @property folders 「这部番就在这个文件夹里」: 里面的视频按文件名认集, 不再按条目名与季过滤
 * @property files 单独指定的文件: 「这个文件是第几集」, 盖过文件夹里认出的
 */
@Serializable
data class DriveSubjectPicks(
    val folders: List<DrivePickedFolder> = emptyList(),
    val files: List<DrivePickedFile> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
}

@Serializable
data class DrivePickedFolder(val fid: String, val name: String)

/**
 * 自动记下的条目所在文件夹, 见 [CloudDriveAccount.rememberedFolders].
 *
 * @property path 从搜到的那个文件夹到这个文件夹的名字 (从外到里, 最后一个是它自己): 列它的时候照自动搜索一样按这些名字认季
 */
@Serializable
data class DriveRememberedFolder(val fid: String, val path: List<String>)

/**
 * 「分享搜索」自动记下的分享文件夹, 见 [CloudDriveAccount.rememberedShares].
 *
 * @property siteTitle 站点上的剧名, 认季用 (同搜到时)
 * @property folderId 分享里的文件夹; 文件直接放在分享根上时是根
 * @property path 分享里从根到这个文件夹的名字: 列它的时候照搜到时一样按这些名字认季
 */
@Serializable
data class DriveRememberedShare(
    val shareId: String,
    val passcode: String,
    val siteTitle: String,
    val folderId: String,
    val path: List<String>,
)

/**
 * @property episode 第几集 (集号的字符串写法)
 */
@Serializable
data class DrivePickedFile(
    val fid: String,
    val fileName: String,
    val parentFid: String = "",
    val size: Long = 0,
    val episode: String,
)

/** 手动挂到某个视频上的一个网盘字幕文件. */
@Serializable
data class DrivePickedSubtitle(val fid: String, val fileName: String)

enum class CloudDrivePlaybackMode {
    /** 原文件直链. 画质无损, 有的网盘对非会员限速. */
    ORIGINAL,

    /** 网盘转码后的流, 取账号能用的最高一档. */
    TRANSCODED,
}

/**
 * 用户给各个条目添加的网盘分享链接 (在 Web 控制台的播放器页粘贴), 按网盘 id 分开: 播放这个条目时「我添加的分享」数据源打开它们找剧集.
 *
 * 存在整机设置里, 所有用户共用 (与数据源一样).
 */
@Serializable
data class CloudDriveAddedShares(
    val drives: Map<String, DriveAddedShares> = emptyMap(),
) {
    fun of(driveId: String): DriveAddedShares = drives[driveId] ?: DriveAddedShares()

    fun with(driveId: String, shares: DriveAddedShares): CloudDriveAddedShares =
        copy(drives = if (shares.subjects.isEmpty()) drives - driveId else drives + (driveId to shares))

    companion object {
        val Default = CloudDriveAddedShares()
    }
}

/**
 * 一个网盘里给各个条目添加的分享.
 *
 * @property subjects 条目 id 到这个条目添加过的分享, 按添加顺序
 */
@Serializable
data class DriveAddedShares(
    val subjects: Map<Int, List<DriveAddedShare>> = emptyMap(),
) {
    fun of(subjectId: Int): List<DriveAddedShare> = subjects[subjectId].orEmpty()

    /**
     * 给 [subjectId] 加上 [share]; 同一个分享已经加过时换成新的 (提取码、标题可能变了), 位置不变, 手动指定过的文件与新的合并,
     * 记下的剧集保留.
     */
    fun plus(subjectId: Int, share: DriveAddedShare): DriveAddedShares {
        val current = of(subjectId)
        val index = current.indexOfFirst { it.shareId == share.shareId }
        val updated = if (index < 0) {
            current + share
        } else {
            current.toMutableList().apply {
                val old = this[index]
                this[index] = share.copy(picks = old.picks + share.picks, files = share.files.ifEmpty { old.files })
            }
        }
        return copy(subjects = subjects + (subjectId to updated))
    }

    /** 换掉 [subjectId] 下分享 [shareId] 记下的剧集; 没有这个分享时不变. */
    fun withFiles(subjectId: Int, shareId: String, files: List<DriveAddedShareFile>): DriveAddedShares {
        val current = of(subjectId)
        if (current.none { it.shareId == shareId }) return this
        val updated = current.map { if (it.shareId == shareId) it.copy(files = files) else it }
        return copy(subjects = subjects + (subjectId to updated))
    }

    fun minus(subjectId: Int, shareId: String): DriveAddedShares {
        val remaining = of(subjectId).filterNot { it.shareId == shareId }
        return copy(subjects = if (remaining.isEmpty()) subjects - subjectId else subjects + (subjectId to remaining))
    }
}

/**
 * @property title 分享的标题 (网盘给的), 用来显示与认季
 * @property addedAtMillis 添加的时刻
 * @property picks 用户手动指定的文件: 分享里的文件 id 到它是第几集 (集号的字符串写法). 认不出集号的文件靠它对上,
 * 也盖过自动认出的集号
 * @property files 最近一次打开分享时对上的剧集. 分享后来被清空或打不开时, 其中已经转存到自己网盘的照常能播
 */
@Serializable
data class DriveAddedShare(
    val shareId: String,
    val passcode: String = "",
    val title: String = "",
    val addedAtMillis: Long = 0,
    val picks: Map<String, String> = emptyMap(),
    val files: List<DriveAddedShareFile> = emptyList(),
)

/**
 * 分享里对上的一个剧集文件 (转存要用的字段都在).
 *
 * @property folders 从分享根到文件所在文件夹的名字
 * @property episode 第几集 (集号的字符串写法)
 * @property parentFid 在分享里所在文件夹的 id, 播放时到这里找外挂字幕
 */
@Serializable
data class DriveAddedShareFile(
    val fid: String,
    val fileName: String,
    val size: Long = 0,
    @SerialName("shareFidToken")
    val shareToken: String = "",
    val folders: List<String> = emptyList(),
    val episode: String,
    val parentFid: String = "",
)
