/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.person

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import me.him188.ani.app.data.models.person.CharacterDetailsInfo
import me.him188.ani.app.data.models.person.CharacterSubjectInfo
import me.him188.ani.app.data.models.person.InfoboxRowInfo
import me.him188.ani.app.data.models.person.PersonCastInfo
import me.him188.ani.app.data.models.person.PersonCommentInfo
import me.him188.ani.app.data.models.person.PersonDetailsInfo
import me.him188.ani.app.data.models.person.PersonSubjectSummary
import me.him188.ani.app.data.models.person.PersonWorkInfo
import me.him188.ani.app.data.models.subject.CharacterInfo
import me.him188.ani.app.data.models.subject.CharacterRole
import me.him188.ani.app.data.models.subject.PersonInfo
import me.him188.ani.app.data.models.subject.PersonPosition
import me.him188.ani.app.data.models.subject.PersonType
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.network.mapper.toPersonInfo
import me.him188.ani.datasources.bangumi.next.apis.CharacterBangumiNextApi
import me.him188.ani.datasources.bangumi.next.apis.PersonBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextCharacter
import me.him188.ani.datasources.bangumi.next.models.BangumiNextComment
import me.him188.ani.datasources.bangumi.next.models.BangumiNextInfoboxItem
import me.him188.ani.datasources.bangumi.next.models.BangumiNextPerson
import me.him188.ani.datasources.bangumi.next.models.BangumiNextSlimSubject
import me.him188.ani.utils.ktor.ApiInvoker

/**
 * 人物 (声优/制作人员) 与角色详情页数据仓库, 直连 bangumi 的 `/p1/persons` 与 `/p1/characters`.
 *
 * 与 Ani 那套的差别: 参与作品数/出演角色数 Ani 在详情里一起下发, bangumi 要各问一次
 * (`limit=1` 只取 total), 所以详情页首屏是三个请求.
 */
class PersonDetailsRepository(
    private val personsApi: ApiInvoker<PersonBangumiNextApi>,
    private val charactersApi: ApiInvoker<CharacterBangumiNextApi>,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : Repository(defaultDispatcher) {

    fun personDetailsFlow(personId: Int): Flow<PersonDetailsInfo> = flow {
        val (person, workCount, castCount) = try {
            withContext(defaultDispatcher) {
                personsApi {
                    val person = getPerson(personId).body()
                    // 这两个计数 bangumi 只在列表接口的 total 里给
                    val works = getPersonWorks(personId, limit = 1).body().total
                    val casts = getPersonCasts(personId, limit = 1).body().total
                    Triple(person, works, casts)
                }
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
        emit(
            PersonDetailsInfo(
                person = person.toPersonInfo(),
                career = person.career,
                infobox = person.infobox.toRows(),
                collects = person.collects,
                commentCount = person.comment,
                workCount = workCount,
                castCount = castCount,
            ),
        )
    }

    fun characterDetailsFlow(characterId: Int): Flow<CharacterDetailsInfo> = flow {
        val (character, subjectCount) = try {
            withContext(defaultDispatcher) {
                charactersApi {
                    val character = getCharacter(characterId).body()
                    val subjects = getCharacterCasts(characterId, limit = 1).body().total
                    character to subjects
                }
            }
        } catch (e: Exception) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
        emit(
            CharacterDetailsInfo(
                character = character.toCharacterInfo(),
                role = character.role.value,
                summary = character.summary,
                infobox = character.infobox.toRows(),
                collects = character.collects,
                commentCount = character.comment,
                subjectCount = subjectCount,
            ),
        )
    }

    fun personWorksPager(personId: Int): Flow<PagingData<PersonWorkInfo>> = offsetPager { offset, limit ->
        personsApi { getPersonWorks(personId, limit = limit, offset = offset).body() }.let { page ->
            Paged(
                total = page.total,
                items = page.data.map {
                    PersonWorkInfo(
                        subject = it.subject.toSummary(),
                        positions = it.positions.map { position -> PersonPosition(position.type.id) },
                    )
                },
            )
        }
    }

    fun personCastsPager(personId: Int): Flow<PagingData<PersonCastInfo>> = offsetPager { offset, limit ->
        personsApi { getPersonCasts(personId, limit = limit, offset = offset).body() }.let { page ->
            Paged(
                total = page.total,
                // bangumi 按角色分组, 一个角色可能出现在多部作品里; UI 要的是 (作品, 角色) 一行一条
                items = page.data.flatMap { cast ->
                    cast.relations.map { relation ->
                        PersonCastInfo(
                            subject = relation.subject.toSummary(),
                            character = CharacterInfo(
                                id = cast.character.id,
                                name = cast.character.name,
                                nameCn = cast.character.nameCN,
                                actors = emptyList(),
                                imageMedium = cast.character.images?.medium ?: "",
                                imageLarge = cast.character.images?.large ?: "",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun characterSubjectsPager(characterId: Int): Flow<PagingData<CharacterSubjectInfo>> =
        offsetPager { offset, limit ->
            charactersApi { getCharacterCasts(characterId, limit = limit, offset = offset).body() }.let { page ->
                Paged(
                    total = page.total,
                    items = page.data.map {
                        CharacterSubjectInfo(
                            subject = it.subject.toSummary(),
                            role = CharacterRole(it.type),
                            actors = it.casts.map { cast -> cast.person.toPersonInfo() },
                        )
                    },
                )
            }
        }

    // bangumi 的吐槽箱不分页, 一次给全部
    fun personCommentsPager(personId: Int): Flow<PagingData<PersonCommentInfo>> = offsetPager { offset, _ ->
        if (offset > 0) return@offsetPager Paged(total = 0, items = emptyList())
        personsApi { getPersonComments(personId).body() }.let { comments ->
            Paged(total = comments.size, items = comments.map { it.toInfo() })
        }
    }

    fun characterCommentsPager(characterId: Int): Flow<PagingData<PersonCommentInfo>> = offsetPager { offset, _ ->
        if (offset > 0) return@offsetPager Paged(total = 0, items = emptyList())
        charactersApi { getCharacterComments(characterId).body() }.let { comments ->
            Paged(total = comments.size, items = comments.map { it.toInfo() })
        }
    }

    private class Paged<T>(val total: Int, val items: List<T>)

    private fun <T : Any> offsetPager(
        fetch: suspend (offset: Int, limit: Int) -> Paged<T>,
    ): Flow<PagingData<T>> = Pager(
        config = defaultPagingConfig,
        initialKey = 0,
        pagingSourceFactory = { OffsetPagingSource(fetch) },
    ).flow

    private inner class OffsetPagingSource<T : Any>(
        private val fetch: suspend (offset: Int, limit: Int) -> Paged<T>,
    ) : PagingSource<Int, T>() {
        override fun getRefreshKey(state: PagingState<Int, T>): Int? = state.anchorPosition

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = withContext(defaultDispatcher) {
            val offset = params.key ?: 0
            // 服务端 limit 上限为 100
            val limit = params.loadSize.coerceIn(1, 100)
            try {
                val page = fetch(offset, limit)
                val nextOffset = offset + page.items.size
                LoadResult.Page(
                    data = page.items,
                    prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
                    nextKey = if (page.items.isNotEmpty() && nextOffset < page.total) nextOffset else null,
                )
            } catch (e: Exception) {
                LoadResult.Error(RepositoryException.wrapOrThrowCancellation(e))
            }
        }
    }
}

private fun BangumiNextPerson.toPersonInfo(): PersonInfo {
    return PersonInfo(
        id = id,
        name = name,
        type = PersonType.fromId(type.value),
        careers = emptyList(),
        imageLarge = images?.large ?: "",
        imageMedium = images?.medium ?: "",
        summary = summary,
        locked = lock,
        nameCn = nameCN,
    )
}

private fun BangumiNextCharacter.toCharacterInfo(): CharacterInfo {
    return CharacterInfo(
        id = id,
        name = name,
        nameCn = nameCN,
        // 配音演员在 /casts 里, 详情本体不带
        actors = emptyList(),
        imageMedium = images?.medium ?: "",
        imageLarge = images?.large ?: "",
    )
}

private fun BangumiNextSlimSubject.toSummary(): PersonSubjectSummary {
    return PersonSubjectSummary(
        subjectId = id,
        name = name,
        nameCn = nameCN,
        imageLarge = images?.large ?: "",
    )
}

/** 与名字重复或不适合在“基本信息”表展示的 infobox 字段. */
private val HIDDEN_INFOBOX_KEYS = setOf("简体中文名")

private fun List<BangumiNextInfoboxItem>?.toRows(): List<InfoboxRowInfo> {
    if (this == null) return emptyList()
    return mapNotNull { item ->
        if (item.key in HIDDEN_INFOBOX_KEYS) return@mapNotNull null
        val value = item.propertyValues.joinToString("、") { v ->
            if (v.k != null) "${v.k} ${v.v}" else v.v
        }
        if (value.isBlank()) return@mapNotNull null
        InfoboxRowInfo(key = item.key, value = value)
    }
}

private fun BangumiNextComment.toInfo(): PersonCommentInfo {
    return PersonCommentInfo(
        id = id.toLong(),
        authorId = user?.id?.toString(),
        authorNickname = user?.nickname,
        authorAvatarUrl = user?.avatar?.large,
        content = content,
        createdAt = Instant.fromEpochSeconds(createdAt.toLong()),
        replyCount = replies.size,
    )
}
