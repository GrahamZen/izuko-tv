/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.persistent.JsonUserDataFile
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.days

/** @property rows 番 → 这部番的选择 */
@Serializable
data class SubjectSourcesState(
    val rows: Map<Int, SubjectSourceRow> = emptyMap(),
) {
    /** 用户顺着看的线索; 认不出的记录 (以后版本加的线索类型) 当作没有. */
    fun lineageOf(subjectId: Int): PlaybackLineage? = rows[subjectId]?.played?.let(::decodeLineage)
}

/**
 * 一部番的选择. 字段都带默认值: 文件里多出、少了哪一项都照样读 (见 [JsonUserDataFile]).
 *
 * @property played 用户顺着看的线索 ([PlaybackLineage] 的 JSON), 切集时先顺着它找
 * @property lastPlayedMillis 上次顺着它播的时刻, 放不下时先丢久没播的
 */
@Serializable
data class SubjectSourceRow(
    val played: String? = null,
    val lastPlayedMillis: Long = 0,
) {
    val isEmpty: Boolean get() = played == null
}

/**
 * 每部番的选择: 用户顺着看的线索 (切集时先顺着它找), 开播时从这里读. 整机一份, 和选源偏好一样不分用户.
 *
 * 不按时间过期, 放得下就留着 (过几个月重看还能接上线索). 超过 [maxRows] 部时丢到 [trimTo] 部, 先丢最久没播的 (见 [trimmedTo]).
 */
class SubjectSourceTable internal constructor(
    private val store: Store,
    private val legacy: LegacyLineages? = null,
    private val clock: () -> Long = { currentTimeMillis() },
    private val maxRows: Int = MAX_ROWS,
    private val trimTo: Int = TRIM_TO_ROWS,
) {
    internal interface Store {
        suspend fun read(): SubjectSourcesState?

        /** 写成了没有 */
        suspend fun write(state: SubjectSourcesState): Boolean
    }

    /** 独立存放的线索 (条目 → [PlaybackLineage] 的 JSON): 升级前的版本存的, 和换电视从旧电视带来的. */
    internal interface LegacyLineages {
        suspend fun read(): Map<Int, String>
        suspend fun remove(subjectIds: Collection<Int>)
    }

    private val lock = Mutex()

    @Volatile
    private var loaded: SubjectSourcesState? = null

    /** 当前内容; 不等正在进行的写. */
    suspend fun state(): SubjectSourcesState = loaded ?: lock.withLock { load() }

    /** 改一部番的那一行: [transform] 没改时返回传进去的那个对象, 不写文件. */
    suspend fun updateRow(subjectId: Int, transform: (SubjectSourceRow) -> SubjectSourceRow): Unit = lock.withLock {
        val current = load()
        val row = current.rows[subjectId] ?: SubjectSourceRow()
        val changed = transform(row)
        if (changed == row) return@withLock
        val rows = if (changed.isEmpty) current.rows - subjectId else current.rows + (subjectId to changed)
        val next = SubjectSourcesState(rows).trimmedIfNeeded()
        loaded = next
        store.write(next)
    }

    /** [lineage] 为 null 时删掉线索. */
    suspend fun setPlayed(subjectId: Int, lineage: PlaybackLineage?) = updateRow(subjectId) {
        if (lineage == null) it.copy(played = null) else it.copy(played = encodeLineage(lineage), lastPlayedMillis = clock())
    }

    /** 还在顺着线索播: 记一下时间. 一天内记过就不再写文件. */
    suspend fun markPlayed(subjectId: Int) = updateRow(subjectId) { row ->
        val now = clock()
        if (row.played == null || now - row.lastPlayedMillis < MARK_PLAYED_INTERVAL) row else row.copy(lastPlayedMillis = now)
    }

    /** 换电视带走的线索: 键 → JSON, 同升级前存在选源偏好里的格式 (只认这种的旧版本新电视也接得上; 新电视上按 [LegacyLineages] 导入). */
    suspend fun exportedLineages(): Map<String, String> =
        state().rows.mapNotNull { (subjectId, row) -> row.played?.let { LEGACY_KEY_PREFIX + subjectId to it } }.toMap()

    private suspend fun load(): SubjectSourcesState {
        loaded?.let { return it }
        val read = store.read() ?: SubjectSourcesState()
        val imported = importLegacy(read)
        loaded = imported
        return imported
    }

    /**
     * 独立存放的线索并进表里后删掉. 两边都有的取它: 表写成了才删 (没写成时留着, 下次启动再并一遍), 能走到这里的只会是升级或刚从旧电视带来的.
     */
    private suspend fun importLegacy(state: SubjectSourcesState): SubjectSourcesState {
        val old = legacy?.read().orEmpty()
        if (old.isEmpty()) return state
        val now = clock()
        var rows = state.rows
        for ((subjectId, encoded) in old) {
            // 认不出的不导入, 同读出来认不出时当作没有
            if (decodeLineage(encoded) == null) continue
            rows = rows + (subjectId to (rows[subjectId] ?: SubjectSourceRow()).copy(played = encoded, lastPlayedMillis = now))
        }
        logger.info { "SubjectSourceTable: imported ${old.size} playback lineages" }
        val next = SubjectSourcesState(rows).trimmedIfNeeded()
        if (store.write(next)) legacy?.remove(old.keys) else logger.warn { "SubjectSourceTable: the table was not written, keeping the legacy lineages" }
        return next
    }

    private fun SubjectSourcesState.trimmedIfNeeded(): SubjectSourcesState {
        if (rows.size <= maxRows) return this
        val trimmed = trimmedTo(trimTo)
        logger.info { "SubjectSourceTable: trimmed from ${rows.size} to ${trimmed.rows.size} subjects" }
        return trimmed
    }

    companion object {
        const val FILE_NAME = "subject_sources.json"

        /** 整个文件一次读写, 太大会拖慢第一次开播. 一部番一百多字节, 这个上限约 400 KB, 多数人几年也到不了. */
        internal const val MAX_ROWS = 3000
        internal const val TRIM_TO_ROWS = 2500

        internal const val LEGACY_KEY_PREFIX = "playback_lineage:"

        private val MARK_PLAYED_INTERVAL = 1.days.inWholeMilliseconds

        private val logger = logger<SubjectSourceTable>()
    }
}

private fun encodeLineage(lineage: PlaybackLineage): String = DataStoreJson.encodeToString(PlaybackLineage.serializer(), lineage)

private fun decodeLineage(encoded: String): PlaybackLineage? =
    runCatching { DataStoreJson.decodeFromString(PlaybackLineage.serializer(), encoded) }.getOrNull()

/** 放不下时丢到只剩 [target] 部: 先丢最久没播的. */
internal fun SubjectSourcesState.trimmedTo(target: Int): SubjectSourcesState {
    if (rows.size <= target) return this
    val dropped = rows.entries
        .sortedBy { it.value.lastPlayedMillis }
        .take(rows.size - target)
        .mapTo(HashSet()) { it.key }
    return copy(rows = rows - dropped)
}

internal fun subjectSourceTable(file: SystemPath, legacyStore: DataStore<Preferences>): SubjectSourceTable =
    SubjectSourceTable(FileSubjectSourceStore(file), DataStoreLegacyLineages(legacyStore))

internal class FileSubjectSourceStore(file: SystemPath) : SubjectSourceTable.Store {
    private val cache = JsonUserDataFile(file, SubjectSourcesState.serializer())

    override suspend fun read(): SubjectSourcesState? = cache.read()
    override suspend fun write(state: SubjectSourcesState): Boolean = cache.write(state)
}

/** 选源偏好 DataStore 里的 `playback_lineage:<条目>`: 升级前的版本把线索存在这, 换电视也按这个格式带. */
internal class DataStoreLegacyLineages(private val store: DataStore<Preferences>) : SubjectSourceTable.LegacyLineages {
    override suspend fun read(): Map<Int, String> =
        store.data.first().asMap().entries.mapNotNull { (key, value) ->
            val subjectId = key.name.takeIf { it.startsWith(SubjectSourceTable.LEGACY_KEY_PREFIX) }
                ?.removePrefix(SubjectSourceTable.LEGACY_KEY_PREFIX)?.toIntOrNull()
            if (subjectId != null && value is String) subjectId to value else null
        }.toMap()

    override suspend fun remove(subjectIds: Collection<Int>) {
        store.edit { preferences ->
            for (subjectId in subjectIds) preferences.remove(stringPreferencesKey(SubjectSourceTable.LEGACY_KEY_PREFIX + subjectId))
        }
    }
}
