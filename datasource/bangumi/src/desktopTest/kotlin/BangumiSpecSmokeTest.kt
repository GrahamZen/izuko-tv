/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.bangumi

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.models.BangumiSearchSubjectsRequest
import me.him188.ani.datasources.bangumi.models.BangumiSearchSubjectsRequestFilter
import me.him188.ani.datasources.bangumi.models.BangumiSubjectType
import me.him188.ani.datasources.bangumi.next.apis.EpisodeBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.SubjectBangumiNextApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 用真实响应喂一遍生成的模型, 证明 spec 没过期.
 *
 * 这个测试存在的原因: 反序列化失败只在**真的调用那个端点**时才炸, 编译期与单测都发现不了.
 * 上一次 spec 过期时, `/v0/search/subjects` 的模型把 `score`/`rank` 声明成顶层必填字段, 而服务器
 * 把它们放在 `rating` 里 —— 一旦有代码去调搜索就是 `MissingFieldException`.
 *
 * **默认跳过**: 打真实网络, 放进 CI 会被 bangumi 按 IP 限流. 本机验证时开:
 * ```
 * ANI_BANGUMI_E2E=1 ./gradlew :datasource:bangumi:desktopTest --tests "*BangumiSpecSmoke*"
 * ```
 * 改 `v0.yaml` / `p1.yaml` / `keepPaths` 之后必须跑一次.
 */
class BangumiSpecSmokeTest {
    private val subjectId = 302286 // 死神 千年血战篇
    private val userAgent = "animeko-fork/1.0 (https://github.com/GrahamZen/animeko)"

    private fun enabled(): Boolean {
        if (System.getenv("ANI_BANGUMI_E2E") != "1") {
            println("跳过: 需要真实网络, 设 ANI_BANGUMI_E2E=1 才跑")
            return false
        }
        return true
    }

    // bangumi 会挡掉没有 UA 的请求, 生成的 ApiClient 自建 HttpClient 时不带 UA
    private val withUserAgent: (HttpClientConfig<*>) -> Unit = { config ->
        config.install(UserAgent) { agent = userAgent }
    }

    private fun nextApi() = SubjectBangumiNextApi(BangumiApiProvider.NEXT_BASE_URL, null, withUserAgent)
    private fun nextEpisodeApi() = EpisodeBangumiNextApi(BangumiApiProvider.NEXT_BASE_URL, null, withUserAgent)
    private fun v0Api() = DefaultApi(BangumiApiProvider.V0_BASE_URL, null, withUserAgent)

    @Test
    fun `v0 搜索能反序列化`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        val resp = v0Api().searchSubjects(
            limit = 5,
            bangumiSearchSubjectsRequest = BangumiSearchSubjectsRequest(
                keyword = "葬送のフリーレン",
                sort = BangumiSearchSubjectsRequest.Sort.MATCH,
                filter = BangumiSearchSubjectsRequestFilter(type = listOf(BangumiSubjectType.Anime)),
            ),
        ).body()
        assertTrue(resp.data.orEmpty().isNotEmpty(), "搜索没返回条目")
        // rating 在嵌套对象里, 不在顶层 —— 这正是上次模型过期踩的地方
        assertTrue(resp.data!!.first().rating.total >= 0)
    }

    @Test
    fun `p1 条目详情能反序列化`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        val subject = nextApi().getSubject(subjectId).body()
        assertEquals(subjectId, subject.id)
        assertTrue(subject.name.isNotBlank())
    }

    @Test
    fun `p1 分集列表能反序列化`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        val episodes = nextApi().getSubjectEpisodes(subjectId, limit = 5).body()
        assertTrue(episodes.data.isNotEmpty(), "分集列表为空")
    }

    @Test
    fun `p1 关联条目_角色_制作人员_推荐能反序列化`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        val api = nextApi()
        assertTrue(api.getSubjectRelations(subjectId).body().data.isNotEmpty(), "关联条目为空")
        assertTrue(api.getSubjectCharacters(subjectId).body().data.isNotEmpty(), "角色为空")
        assertTrue(api.getSubjectStaffPersons(subjectId).body().data.isNotEmpty(), "制作人员为空")
        api.getSubjectRecs(subjectId).body() // 可能为空, 只要能反序列化
    }

    @Test
    fun `p1 剧集评论能反序列化`() = runTest(timeout = 2.minutes) {
        if (!enabled()) return@runTest
        // 1127992 = 死神 千年血战篇 第 1 集, 评论数三位数
        nextEpisodeApi().getEpisodeComments(1127992).body()
    }
}
