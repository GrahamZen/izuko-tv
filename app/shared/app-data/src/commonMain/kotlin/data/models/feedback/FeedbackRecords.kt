/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.feedback

import kotlinx.serialization.Serializable
import me.him188.ani.app.data.network.TMDB_SUBJECT_MAP_REPOSITORY

/**
 * 这台电视提交过的反馈: 故障工单, 以及详情页里的标题 logo / 对应作品修正请求. 每条对应 GitHub 上的一个 issue,
 * Web 控制台「设置 → 反馈」里列出来; 故障工单的处理结果每天查一次 (见 FeedbackTickets). 另存着写到一半的反馈 ([drafts]) 与正在进行的现场录制.
 */
@Serializable
data class FeedbackRecords(
    /** 新的在前. */
    val items: List<FeedbackRecord> = emptyList(),
    /** 写到一半的反馈: 新工单与各张单的补充各一份 (按 [FeedbackDraft.target]). */
    val drafts: List<FeedbackDraft> = emptyList(),
    /** 正在现场录制; 同时只有一段. */
    val recording: FeedbackRecording? = null,
    /** 上次查故障工单处理结果的时刻 (毫秒). */
    val ticketsCheckedAt: Long? = null,
) {
    fun draft(target: Int): FeedbackDraft = drafts.firstOrNull { it.target == target } ?: FeedbackDraft(target)

    companion object {
        val Empty = FeedbackRecords()

        /** 最多留多少条, 再多丢最旧的. */
        const val MAX_ITEMS = 200
    }
}

/** 正在现场录制: 录给哪一份草稿 ([target], 同 [FeedbackDraft.target]), 从 [since] (毫秒) 开始. */
@Serializable
data class FeedbackRecording(val target: Int, val since: Long)

/** 一段时间 (毫秒, 两头都含). */
@Serializable
data class FeedbackWindow(val from: Long, val to: Long)

/**
 * 写到一半的一条反馈的附件: [target] = 0 是新工单, 其余是给那张单 (issue 号) 的补充.
 * 文字在控制台网页上 (没提交前不存电视).
 */
@Serializable
data class FeedbackDraft(
    val target: Int,
    /** 录好的现场录制. */
    val recorded: FeedbackWindow? = null,
    /** 日志从录制开始前多少分钟截起 (0 = 只要录制期间). */
    val logLeadMinutes: Int = 0,
    /** 附上的性能诊断报告 (控制台「维护 → 性能诊断」里的), 报告编号. */
    val perfReports: List<String> = emptyList(),
    /** 附上的照片 (存在电视上, 见控制台那边的照片存储). */
    val photos: List<FeedbackPhoto> = emptyList(),
) {
    val isEmpty: Boolean get() = recorded == null && perfReports.isEmpty() && photos.isEmpty()
}

/** 附上的一张照片: [id] 是电视上存的文件, [type] 为 `jpg` / `png` / `webp`. */
@Serializable
data class FeedbackPhoto(val id: String, val size: Long, val type: String)

/**
 * 一个还没传上去的附件: [name] 是工单服务里的名字 (`log.gz` / `photo-1.jpg`, 补充里的带 `r<k>-` 前缀);
 * 照片带 [photoId], 日志带 [log] (截哪几段、附哪些报告).
 */
@Serializable
data class FeedbackUpload(
    val name: String,
    val photoId: String? = null,
    val log: FeedbackLogSpec? = null,
)

/** 一份日志附件的内容: 录好的那段 (往前 [leadMinutes] 分钟) 的应用日志, 前面放附上的性能报告原文. */
@Serializable
data class FeedbackLogSpec(
    val recorded: FeedbackWindow? = null,
    val leadMinutes: Int = 0,
    val perfReports: List<String> = emptyList(),
)

@Serializable
data class FeedbackRecord(
    val kind: FeedbackKind,
    /** issue 编号; 在哪个仓库按 [kind] 定 (见 [FeedbackKind.publicRepository]). */
    val issue: Int,
    val subjectId: Int? = null,
    /** 提交时的作品名 (给人看的). */
    val subjectName: String? = null,
    /** 标题 logo 的语言 (`ja` / `zh` / `en` …). */
    val language: String? = null,
    /** 故障工单的一句话描述. */
    val summary: String? = null,
    /** 故障工单的详细描述 (控制台里会话的第一条). */
    val description: String? = null,
    /** 故障工单开单时带的附件名 (同 [FeedbackUpload.name]). */
    val files: List<String> = emptyList(),
    /** 提交的时刻 (毫秒). */
    val submittedAt: Long,
    /** 提交时同一处已经有请求开着 (内容一样或不一样): issue 不是这台电视开的, 结果一样要看. */
    val joinedExisting: Boolean = false,
    val state: FeedbackState = FeedbackState.OPEN,
    /** 上次查处理结果的时刻 (毫秒); 没查过为 `null`. */
    val checkedAt: Long? = null,
    /** 故障工单的凭证 (工单服务开单时给的): 传附件、查结果、补充说明都要带. */
    val token: String? = null,
    /** 还没传上去的附件 (开单或补充时声明了的). */
    val uploads: List<FeedbackUpload> = emptyList(),
    /** 工单服务上次回的 issue 更新时间 (原样存), 下次查时带上: 没变就不回详情. */
    val remoteUpdated: String? = null,
    /** 维护者的回复与用户的补充, 按时间先后. */
    val replies: List<FeedbackReply> = emptyList(),
    /** 维护者回复里给的测试包 (APK) 链接. */
    val apks: List<String> = emptyList(),
    /** 测试包的 SHA-256 (工单服务查到的 GitHub 附件摘要), 按链接; 经镜像下载时据此校验. */
    val apkSha256: Map<String, String> = emptyMap(),
    /** 有用户还没在控制台看过的回复或结果. */
    val unread: Boolean = false,
)

/** 故障工单下的一条回复. */
@Serializable
data class FeedbackReply(
    /** `true` = 维护者回复, `false` = 用户在控制台写的补充. */
    val fromMaintainer: Boolean,
    /** 时间 (工单服务给的 ISO 字符串). */
    val at: String,
    /** Markdown 原文 (用户补充只有文字, 附件在 [files]). */
    val body: String,
    /** 用户补充带的附件名 (同 [FeedbackUpload.name]). */
    val files: List<String> = emptyList(),
)

@Serializable
enum class FeedbackKind {
    /** 故障工单 (控制台提交, 带复现日志). */
    TICKET,

    /** 标题 logo 修正. */
    LOGO,

    /** 对应的 TMDB 作品修正. */
    ENTRY,
    ;

    /** 公开仓库的 issue 才能给出网页地址; 故障工单在私有仓库, 为 `null`. */
    val publicRepository: String?
        get() = when (this) {
            TICKET -> null
            LOGO, ENTRY -> TMDB_SUBJECT_MAP_REPOSITORY
        }
}

@Serializable
enum class FeedbackState {
    /** 还开着, 等维护者处理. */
    OPEN,

    /** 采纳了 (修正合并 / 故障修好). */
    ACCEPTED,

    /** 没采纳. */
    REJECTED,

    /** 关了, 原因不明. */
    CLOSED,
}
