/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable

/**
 * 夸克网盘账号与播放方式.
 *
 * [cookie] 是登录后的整段 Cookie 请求头, 由扫码登录写入, 接口响应里轮换的 `__puus` 也回写到这里.
 * 与 [PikPakConfig] 的密码一样经 [ObscuredStringSerializer] 混淆后落盘, 只防随手查看, 不是加密.
 * 设置备份不包含这份配置.
 *
 * [nickname] 与 [memberType] 是登录时与每次校验账号时顺手记下的展示信息, 不参与鉴权.
 */
@Serializable
data class QuarkConfig(
    @Serializable(with = ObscuredStringSerializer::class)
    val cookie: String = "",
    val nickname: String = "",
    /**
     * 夸克接口 `member` 返回的 `member_type`, 如 `NORMAL`, `EXP_SVIP`, `SUPER_VIP`.
     */
    val memberType: String = "",
    val playbackMode: QuarkPlaybackMode = QuarkPlaybackMode.ORIGINAL,
    /**
     * 播放别人的分享时, 转存到的那个文件夹的 id (找到或建过一次就记下). 空表示还没有.
     * 搜自己网盘时跳过这个文件夹, 不用每次先去根目录找它.
     */
    val shareSaveFolderId: String = "",
    /**
     * 用户在 Web 控制台给条目手动指定的网盘文件夹与文件 (条目 id → 指定). 文件 id 只在这个账号里有效, 退出登录时跟着清掉.
     */
    val subjectPicks: Map<Int, QuarkSubjectPicks> = emptyMap(),
) {
    val isLoggedIn: Boolean get() = cookie.isNotBlank()

    override fun toString(): String {
        return "QuarkConfig(cookie.hash=${if (cookie.isNotEmpty()) cookie.hashCode() else ""}, " +
                "nickname=$nickname, memberType=$memberType, playbackMode=$playbackMode, shareSaveFolderId=$shareSaveFolderId, " +
                "subjectPicks=${subjectPicks.size})"
    }

    companion object {
        val Default = QuarkConfig()
    }
}

/**
 * 一个条目在网盘里手动指定的位置 (自动匹配对不上时用).
 *
 * @property folders 「这部番就在这个文件夹里」: 里面的视频按文件名认集, 不再按条目名与季过滤
 * @property files 单独指定的文件: 「这个文件是第几集」, 盖过文件夹里认出的
 */
@Serializable
data class QuarkSubjectPicks(
    val folders: List<QuarkPickedFolder> = emptyList(),
    val files: List<QuarkPickedFile> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
}

@Serializable
data class QuarkPickedFolder(val fid: String, val name: String)

/**
 * @property episode 第几集 (集号的字符串写法)
 */
@Serializable
data class QuarkPickedFile(
    val fid: String,
    val fileName: String,
    val parentFid: String = "",
    val size: Long = 0,
    val episode: String,
)

enum class QuarkPlaybackMode {
    /**
     * 原文件直链. 画质无损, 非会员会被限速.
     */
    ORIGINAL,

    /**
     * 夸克转码后的流, 取账号能用的最高一档. 非会员只有最低档.
     */
    TRANSCODED,
}
