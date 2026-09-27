/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.profile

import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileRegistryTest {
    private val tempDirectory = SystemPaths.createTempDirectory("user-profile-registry-test")
    private val file = tempDirectory.resolve(UserProfileRegistry.FILE_NAME)

    @AfterTest
    fun cleanup() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `没有文件 - 只有 1 号用户且不写文件`() {
        val registry = UserProfileRegistry.load(file)
        assertEquals(listOf(UserProfile.PRIMARY_ID), registry.state.value.profiles.map { it.id })
        assertEquals(UserProfile.PRIMARY_ID, registry.state.value.currentId)
        assertFalse(file.exists())
    }

    @Test
    fun `新建的用户写进文件 - 重新读回来一样`() = runTest {
        val registry = UserProfileRegistry.load(file)
        val added = registry.add(" 小明 ", UserProfileKind.BANGUMI)
        assertEquals(2, added.id)
        assertEquals("小明", added.name)
        assertTrue(added.pendingLogin)

        val reloaded = UserProfileRegistry.load(file)
        assertEquals(registry.state.value, reloaded.state.value)
        assertFalse(tempDirectory.resolve(UserProfileRegistry.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `本地用户不用走登录那一步`() = runTest {
        val registry = UserProfileRegistry.load(file)
        assertFalse(registry.add("", UserProfileKind.LOCAL).pendingLogin)
    }

    @Test
    fun `1 号用户与当前用户不能删`() = runTest {
        val registry = UserProfileRegistry.load(file)
        val added = registry.add("", UserProfileKind.BANGUMI)
        assertNull(registry.remove(UserProfile.PRIMARY_ID))

        registry.setCurrent(added.id)
        assertNull(registry.remove(added.id))
        assertEquals(2, registry.state.value.profiles.size)
    }

    @Test
    fun `删掉的编号不复用`() = runTest {
        val registry = UserProfileRegistry.load(file)
        val second = registry.add("", UserProfileKind.BANGUMI)
        assertEquals(second, registry.remove(second.id))
        assertEquals(3, registry.add("", UserProfileKind.BANGUMI).id)
    }

    @Test
    fun `换当前用户立即写进文件`() = runTest {
        val registry = UserProfileRegistry.load(file)
        val added = registry.add("", UserProfileKind.BANGUMI)
        registry.setCurrent(added.id)
        assertEquals(added.id, UserProfileRegistry.load(file).state.value.currentId)
    }

    @Test
    fun `文件坏了 - 当只有 1 号用户且不删文件`() {
        file.writeText("{not json")
        val registry = UserProfileRegistry.load(file)
        assertEquals(UserProfilesSave.Default, registry.state.value)
        assertEquals("{not json", file.readText())
    }

    @Test
    fun `当前用户不存在或缺 1 号用户时修正`() {
        file.writeText("""{"profiles":[{"id":3,"name":"a"}],"currentId":7,"nextId":2}""")
        val save = UserProfileRegistry.load(file).state.value
        assertEquals(listOf(1, 3), save.profiles.map { it.id })
        assertEquals(UserProfile.PRIMARY_ID, save.currentId)
        assertEquals(4, save.nextId)
    }

    @Test
    fun `打开应用时选人默认开着 - 关掉立即写进文件`() = runTest {
        val registry = UserProfileRegistry.load(file)
        registry.add("", UserProfileKind.BANGUMI)
        assertTrue(registry.state.value.chooseOnLaunch)

        registry.setChooseOnLaunch(false)
        assertFalse(UserProfileRegistry.load(file).state.value.chooseOnLaunch)
    }

    @Test
    fun `文件里没有选人开关时当开着`() {
        file.writeText("""{"profiles":[{"id":1},{"id":2,"name":"a"}],"currentId":2,"nextId":3}""")
        assertTrue(UserProfileRegistry.load(file).state.value.chooseOnLaunch)
    }

    @Test
    fun `修正文件内容时选人开关不变`() {
        file.writeText("""{"profiles":[{"id":3,"name":"a"}],"currentId":7,"nextId":2,"chooseOnLaunch":false}""")
        assertFalse(UserProfileRegistry.load(file).state.value.chooseOnLaunch)
    }

    @Test
    fun `1 号用户的文件名不变 - 其他人在扩展名前加后缀`() {
        val primary = UserProfile(UserProfile.PRIMARY_ID)
        assertEquals("authSession", primary.scopedFileName("authSession"))
        assertEquals(UserProfile.PRIMARY_DATABASE_FILE_NAME, primary.databaseFileName)

        val second = UserProfile(2)
        assertEquals("authSession_user2", second.scopedFileName("authSession"))
        assertEquals("recommendation-collections_user2.json", second.scopedFileName("recommendation-collections.json"))
        assertEquals("ani_room_database_user2.db", second.databaseFileName)
    }
}
