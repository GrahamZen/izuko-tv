/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.recommendation.SEQUEL_SEASON_RULES
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SequelSeasonTableTest {
    private fun tsv(rules: Int = SEQUEL_SEASON_RULES) = listOf(
        "# bangumi-sequel-seasons v1 rules=$rules max_id=500000 dump=dump-2026-09-22.210341Z.zip | 列: bgm_id, 可以换成的那几季",
        "425998\t140001,278826,316247",
        "8\t793",
        // 以下都认不出, 要跳过
        "abc\t1",
        "9\t",
        "10",
    ).joinToString("\n")

    private val tempDirectory = SystemPaths.createTempDirectory("sequel-season-table-test")

    @AfterTest
    fun cleanup() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `查表 - 有这一行给候选，覆盖到却没有给空，比表新的给 null`() {
        val table = assertNotNull(SequelSeasonTable.parse(tsv()))
        assertEquals(SEQUEL_SEASON_RULES, table.rules)
        assertEquals(500000, table.maxId)
        assertEquals("dump-2026-09-22.210341Z.zip", table.dump)
        assertEquals(2, table.size)
        assertEquals(listOf(140001, 278826, 316247), table.candidates(425998))
        assertEquals(listOf(793), table.candidates(8))
        assertEquals(emptyList(), table.candidates(400602))
        assertNull(table.candidates(500001))
    }

    @Test
    fun `认不出的内容不当表`() {
        assertNull(SequelSeasonTable.parse("<html><body>404</body></html>"))
        assertNull(SequelSeasonTable.parse("# bangumi-sequel-seasons v1 max_id=1\n1\t2"), "缺判据版本")
        assertNull(SequelSeasonTable.parse("# bangumi-sequel-seasons v1 rules=1\n1\t2"), "缺覆盖范围")
    }

    @Test
    fun `判据版本与本应用一致才用`() = runTest {
        val file = tempDirectory.resolve("table.tsv").apply { writeText(tsv()) }
        assertEquals(listOf(793), repository(file).current()?.candidates(8))
    }

    @Test
    fun `判据版本对不上的表不用`() = runTest {
        val file = tempDirectory.resolve("table.tsv").apply { writeText(tsv(rules = SEQUEL_SEASON_RULES + 1)) }
        assertNull(repository(file).current())
    }

    /** 真实时间跑: 等首轮下载的那 3 秒超时在虚拟时间里会立刻到点. */
    @Test
    fun `本地没有表文件 - 下整份，不带下载元数据里的 ETag`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(tsv(), HttpStatusCode.OK)
            },
        )
        val file = tempDirectory.resolve("table.tsv")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val repository = SequelSeasonTableRepository(
                cache = MemoryDataStore(SequelSeasonTableCache(etag = "\"old\"", source = "x", checkedAt = Long.MAX_VALUE)),
                tableFile = file,
                client = { client.asScopedHttpClient() },
                scope = scope,
            )
            val table = withContext(Dispatchers.Default) { withTimeout(10.seconds) { repository.current() } }
            assertEquals(listOf(140001, 278826, 316247), table?.candidates(425998))
            assertTrue(file.exists())
            assertEquals(tsv(), file.readText())
            assertNull(requests.first().headers[HttpHeaders.IfNoneMatch])
        } finally {
            scope.cancel()
            client.close()
        }
    }

    /** 不下载 (检查时间是新的); 下载一旦发生就是测试写错了. */
    private fun TestScope.repository(file: SystemPath) = SequelSeasonTableRepository(
        cache = MemoryDataStore(SequelSeasonTableCache(checkedAt = Long.MAX_VALUE)),
        tableFile = file,
        client = { error("不应下载") },
        scope = backgroundScope,
    )
}
