/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.subject.CharacterRole
import me.him188.ani.app.data.models.subject.PersonPosition
import me.him188.ani.app.data.persistent.database.entity.CharacterActorEntity
import me.him188.ani.app.data.persistent.database.entity.CharacterEntity
import me.him188.ani.app.data.persistent.database.entity.PersonEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectCharacterRelationEntity
import me.him188.ani.app.data.persistent.database.entity.SubjectPersonRelationEntity

/**
 * @since 4.0.0-alpha04
 */
@Dao
interface SubjectRelationsDao {
    @Upsert
    suspend fun upsertSubjectCharacterRelations(list: List<SubjectCharacterRelationEntity>)

    @Upsert
    suspend fun upsertSubjectPersonRelations(list: List<SubjectPersonRelationEntity>)

    @Upsert
    suspend fun upsertPersons(list: List<PersonEntity>)

    @Upsert
    suspend fun upsertCharacters(list: List<CharacterEntity>)

    @Upsert
    suspend fun upsertCharacterActors(list: List<CharacterActorEntity>)

    @Query("""DELETE FROM subject_person WHERE subjectId = :subjectId""")
    suspend fun deleteSubjectPersonRelations(subjectId: Int)

    @Query("""DELETE FROM subject_character WHERE subjectId = :subjectId""")
    suspend fun deleteSubjectCharacterRelations(subjectId: Int)

    @Query("""DELETE FROM character_actor WHERE characterId IN (:characterIds)""")
    suspend fun deleteCharacterActorsFor(characterIds: List<Int>)

    /**
     * 一次取数的全部落库包成**单个事务**.
     *
     * 之前是五次独立 upsert (各自一个自动提交事务): 一来 upsert 实体与 upsert 关系之间没有
     * 保护 (顺序是外键约束要求的, 中途失败留下"人有了、关系没写"的中间态), 二来五个事务触发
     * 五轮 invalidation, 每轮都惊动全部下游 Room flow (表级失效, 见
     * SubjectRelationsRepository.relationsFreshnessFlow 的教训). 单事务 = 全有或全无 + 一轮通知.
     *
     * **先删后插**: 只 upsert 的话, 上游已删掉的角色/制作人员/声优会作为幽灵行永久留在库里
     * (直到条目整个被级联删除). 只删本条目的关系行与**本次名单内角色**的声优行 ——
     * person/character 实体跨条目共享, 不能动.
     *
     * 顺序不能变: 先实体后关系, 否则 violate foreign key constraint.
     */
    @Transaction
    suspend fun upsertBatch(
        subjectId: Int,
        persons: List<PersonEntity>,
        characters: List<CharacterEntity>,
        characterActors: List<CharacterActorEntity>,
        personRelations: List<SubjectPersonRelationEntity>,
        characterRelations: List<SubjectCharacterRelationEntity>,
    ) {
        upsertPersons(persons)
        upsertCharacters(characters)
        if (characters.isNotEmpty()) deleteCharacterActorsFor(characters.map { it.characterId })
        upsertCharacterActors(characterActors)
        deleteSubjectPersonRelations(subjectId)
        deleteSubjectCharacterRelations(subjectId)
        upsertSubjectPersonRelations(personRelations)
        upsertSubjectCharacterRelations(characterRelations)
    }

    @Query(
        """
        SELECT * FROM person NATURAL JOIN subject_person as s
        WHERE s.subjectId = :subjectId
    """,
    )
    @Transaction
    fun subjectRelatedPersonsFlow(subjectId: Int): Flow<List<RelatedPersonView>>

    @Query(
        """
        SELECT * FROM character NATURAL JOIN subject_character as s
        WHERE s.subjectId = :subjectId
    """,
    )
    @Transaction
    fun subjectRelatedCharactersFlow(subjectId: Int): Flow<List<RelatedCharacterView>>

    @Query(
        """
        SELECT * FROM character_actor JOIN person ON character_actor.actorPersonId = person.personId
        WHERE characterId IN (:characterIds)
    """,
    )
    @Transaction
    fun characterActorsFlow(characterIds: IntArray): Flow<List<CharacterActorView>>
}

//data class RelatedPersonView(
//    val personId: Int = 0,
//    val name: String,
//    val nameCn: String,
//    val imageLarge: String,
//    val imageMedium: String,
//    val type: PersonType,
//    val summary: String,
//
//
//    val subjectId: Int,
//    val index: Int,
//    val position: PersonPosition,
//)

data class RelatedPersonView(
    val subjectId: Int,
    val index: Int,
    val position: PersonPosition,

    @Embedded
    val person: PersonEntity,
)

data class RelatedCharacterView(
    val subjectId: Int,
    val index: Int, // 在作品中的序号, 一般主角靠前
    val role: CharacterRole,

    @Embedded
    val character: CharacterEntity,
)

data class CharacterActorView(
    val characterId: Int,
    val actorPersonId: Int,
    @Embedded
    val person: PersonEntity,
)
