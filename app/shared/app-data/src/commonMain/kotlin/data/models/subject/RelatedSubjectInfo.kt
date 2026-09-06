package me.him188.ani.app.data.models.subject

import androidx.compose.runtime.Immutable

@Immutable
class RelatedSubjectInfo(
    val subjectId: Int,
    /**
     * null 表示其他类型
     */
    val relation: SubjectRelation?,
    val name: String?,
    val nameCn: String,
    val image: String?,
) {
    val displayName get() = nameCn.ifBlank { name } ?: nameCn

    companion object {
        fun sortList(subjectList: List<RelatedSubjectInfo>): List<RelatedSubjectInfo> {
            return subjectList.sortedByDescending {
                when (it.relation) {
                    SubjectRelation.PREQUEL -> 10
                    SubjectRelation.SEQUEL -> 9
                    SubjectRelation.DERIVED -> 8
                    SubjectRelation.SPECIAL -> 7
                    // 以下几档是 2026-09-06 补的 (原先全落进 null, 卡片下方一片空白).
                    // 排在正片关系之后 —— 它们描述"与本作的关系", 不是本作的续篇
                    SubjectRelation.MAIN_STORY -> 6
                    SubjectRelation.SUMMARY -> 5
                    SubjectRelation.FULL_STORY -> 4
                    SubjectRelation.ALTERNATIVE_VERSION -> 3
                    SubjectRelation.ADAPTATION -> 2
                    SubjectRelation.SAME_SETTING -> 1
                    SubjectRelation.DIFFERENT_SETTING,
                    SubjectRelation.CHARACTER_APPEARANCE,
                    SubjectRelation.COLLABORATION,
                    null,
                    -> 0
                }
            }
        }
    }
}

/**
 * 关联条目与本条目的关系. 取值来自 bangumi 的 relation id (见 `BangumiRelatedPeopleService`),
 * **只覆盖动画与动画那一组** (调用方按 `type=2` 过滤, 书籍/音乐/游戏那几组 id 到不了这里).
 *
 * 没列进来的 (99 其他) 映射成 `null`, 卡片下方不显示关系标签.
 */
enum class SubjectRelation {
    /**
     * 对应 Bangumi "续集", 包括第二季, 外传
     */
    SEQUEL,

    /**
     * 对应 Bangumi "前传"
     */
    PREQUEL,

    /**
     * 对应 Bangumi "衍生", 例如《转生史莱姆日记》
     */
    DERIVED,

    /**
     * 对应 Bangumi "番外篇". 例如 OAD
     */
    SPECIAL,

    /**
     * 对应 Bangumi "主线故事": 本条目是衍生/番外, 这一条指向它所属的正传.
     * 「Re:ゼロから始める休憩時間」的正传是「Re:ゼロから始める異世界生活」.
     */
    MAIN_STORY,

    /** 对应 Bangumi "总集篇". */
    SUMMARY,

    /** 对应 Bangumi "全集": 分割放送的作品合并成的完整版. */
    FULL_STORY,

    /** 对应 Bangumi "不同演绎": 同一个故事的另一版动画化 (重制版/剧场版重构). */
    ALTERNATIVE_VERSION,

    /** 对应 Bangumi "改编". */
    ADAPTATION,

    /** 对应 Bangumi "相同世界观". */
    SAME_SETTING,

    /** 对应 Bangumi "不同世界观". */
    DIFFERENT_SETTING,

    /** 对应 Bangumi "角色出演": 本作角色在对方作品里客串 (如「異世界かるてっと」). */
    CHARACTER_APPEARANCE,

    /** 对应 Bangumi "联动". */
    COLLABORATION,
}
