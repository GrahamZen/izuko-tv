/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.domain.profile.UserProfileRegistry
import me.him188.ani.app.platform.AppRestarter
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台的用户卡片 (RemoteProfiles + 页面上的 PROFILES_SCRIPT): 添加只建不切, 切换先回应再重启, 删不了的给理由;
 * 网页记着生成时是哪个用户, 提示轮询报的不一样就重载; 用到的文案都在译文表里.
 */
class RemoteProfilesTest {
    private class CountingRestarter : AppRestarter {
        @Volatile
        var restarts = 0
        override val isSupported: Boolean get() = true
        override fun restart() {
            restarts++
        }
    }

    private val registry = UserProfileRegistry.inMemory()
    private val restarter = CountingRestarter()
    private val deletedFiles = mutableListOf<Int>()
    private val manager = UserProfileManager(registry, restarter, deleteFiles = { deletedFiles += it.id })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        stopKoin()
        startKoin { modules(module { single { manager } }) }
        // 换用户要重启应用, 只有界面在前台时才做 (见 TvRemoteControl.bringToFrontForRestart)
        TvRemoteControl.setTvForeground(true)
    }

    @AfterTest
    fun tearDown() {
        TvRemoteControl.setTvForeground(false)
        stopKoin()
        scope.cancel()
    }

    /** 同服务端: 路径与查询串分开 (`?` 前后). */
    private fun get(path: String): JsonObject? =
        RemoteProfiles.handle(LanHttpRequest("GET", path.substringBefore('?'), path.substringAfter('?', ""), ByteArray(0)), scope)

    private fun post(path: String, vararg fields: Pair<String, String>): JsonObject {
        val body = fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return RemoteProfiles.handle(LanHttpRequest("POST", path, "", body.toByteArray()), scope)!!
    }

    private val JsonObject.ok get() = getValue("ok").jsonPrimitive.boolean
    private val JsonObject.message get() = getValue("message").jsonPrimitive.content

    private fun users(): List<JsonObject> = get("api/profiles")!!.getValue("users").jsonArray.map { it.jsonObject }

    @Test
    fun `只有一个用户时列出它 没起名的显示默认名`() {
        val state = get("api/profiles")!!
        assertTrue(state.getValue("supported").jsonPrimitive.boolean)
        assertEquals(1, state.getValue("currentId").jsonPrimitive.int)
        val only = users().single()
        assertEquals("用户 1", only.getValue("name").jsonPrimitive.content)
        assertEquals("", only.getValue("raw").jsonPrimitive.content)
        assertTrue(only.getValue("current").jsonPrimitive.boolean)
        assertTrue(only.getValue("primary").jsonPrimitive.boolean)
        assertEquals("用户 2", state.getValue("nextName").jsonPrimitive.content)
    }

    @Test
    fun `添加只建不切`() {
        val added = post("api/profiles/add", "name" to "  Bob ")
        assertTrue(added.ok)
        assertEquals(2, added.getValue("id").jsonPrimitive.int)
        assertEquals("已添加「Bob」", added.message)
        assertEquals(listOf("用户 1", "Bob"), users().map { it.getValue("name").jsonPrimitive.content })
        assertEquals(1, registry.state.value.currentId)
        assertEquals(0, restarter.restarts)
    }

    @Test
    fun `添加时不填名字就把默认名存下来`() {
        post("api/profiles/add", "name" to "")
        assertEquals("用户 2", registry.state.value.find(2)!!.name)
    }

    @Test
    fun `改名去掉首尾空格 清空后存默认名`() {
        post("api/profiles/add", "name" to "Bob")
        assertTrue(post("api/profiles/rename", "id" to "2", "name" to " Alice ").ok)
        assertEquals("Alice", registry.state.value.find(2)!!.name)
        post("api/profiles/rename", "id" to "2", "name" to "")
        assertEquals("用户 2", registry.state.value.find(2)!!.name)
    }

    @Test
    fun `添加本地档 列表里标出来`() {
        val added = post("api/profiles/add", "name" to "Carol", "kind" to "local")
        assertTrue(added.ok)
        val carol = registry.state.value.find(added.getValue("id").jsonPrimitive.int)!!
        assertTrue(carol.isLocal)
        // 本地档不弹登录
        assertFalse(carol.pendingLogin)
        assertTrue(users().last().getValue("local").jsonPrimitive.boolean)
        assertFalse(users().first().getValue("local").jsonPrimitive.boolean)
    }

    @Test
    fun `第一个用户与正在用的用户删不了 并说明原因`() {
        val primary = post("api/profiles/delete", "id" to "1")
        assertFalse(primary.ok)
        assertEquals("第一个用户只能改名，不能删除", primary.message)
        assertEquals(1, users().size)
        assertTrue(deletedFiles.isEmpty())
    }

    @Test
    fun `删掉别人连同他的文件`() {
        post("api/profiles/add", "name" to "Bob")
        val deleted = post("api/profiles/delete", "id" to "2")
        assertTrue(deleted.ok)
        assertEquals("已删除「Bob」", deleted.message)
        assertEquals(listOf(2), deletedFiles)
        assertEquals(1, users().size)
    }

    @Test
    fun `切到自己或不存在的人不重启`() {
        assertFalse(post("api/profiles/switch", "id" to "1").ok)
        assertFalse(post("api/profiles/switch", "id" to "9").ok)
        assertFalse(post("api/profiles/switch", "id" to "x").ok)
        runBlocking { delay(800) }
        assertEquals(0, restarter.restarts)
        assertEquals(1, registry.state.value.currentId)
    }

    @Test
    fun `切换先回应 再记下下次的用户并重启`() {
        post("api/profiles/add", "name" to "Bob")
        val switched = post("api/profiles/switch", "id" to "2")
        assertTrue(switched.ok)
        assertEquals("Bob", switched.getValue("name").jsonPrimitive.content)
        // 回应的时候还没重启: 网页服务跟着进程停, 回应要先发出去
        assertEquals(0, restarter.restarts)
        runBlocking { withTimeout(5.seconds) { while (restarter.restarts == 0) delay(20) } }
        assertEquals(1, restarter.restarts)
        assertEquals(2, registry.state.value.currentId)
    }

    @Test
    fun `当前用户没登录 Bangumi 时不能导入 并说明原因`() {
        val added = post("api/profiles/add", "name" to "Carol", "kind" to "local")
        val state = get("api/profiles")!!
        assertFalse(state.getValue("canImport").jsonPrimitive.boolean)
        val preview = get("api/profiles/import?id=" + added.getValue("id").jsonPrimitive.int)!!
        assertFalse(preview.ok)
        assertEquals("要先在电视上登录 Bangumi，才能把收藏导入这个账号", preview.message)
        // 不是本地用户的也不能导
        assertEquals("只能导入本地用户的收藏", get("api/profiles/import?id=1")!!.message)
        assertFalse(post("api/profiles/import", "id" to added.getValue("id").jsonPrimitive.int.toString()).ok)
    }

    @Test
    fun `不认识的路径与方法交回去`() {
        assertNull(get("api/profiles/add"))
        assertNull(RemoteProfiles.handle(LanHttpRequest("POST", "api/profiles/nope", "", ByteArray(0)), scope))
    }

    @Test
    fun `页面记着生成时的用户 提示轮询报的不一样就重载`() {
        val page = renderRemoteControlPage(initialTab = "settings", searchFormHtml = "", requestSectionHtml = "", profileId = 3)
        assertContains(page, "window.profileId = 3;")
        assertContains(page, """<div id="set-profiles"></div>""")
        assertContains(page, "n.user !== window.profileId")
        assertContains(page, "window.reloadForUser = function")
    }

    @Test
    fun `用户卡片的文案都在译文表里`() {
        val page = renderRemoteControlPage(initialTab = "settings", searchFormHtml = "", requestSectionHtml = "")
        val table = REMOTE_I18N_TABLE.associateBy { it.zh }
        val webKeys = listOf(
            "用户", "用户 {0}", "当前", "切换", "改名", "删除", "名字", "取消", "添加", "保存", "添加用户", "刷新", "知道了",
            "已切换到「{0}」", "正在切换到「{0}」", "电视还没有切换好",
            "第一个用户只能改名，不能删除", "要删除正在用的用户，先切换到别人",
            "每个用户有自己的收藏、播放记录和 Bangumi 登录；设置、数据源和缓存的视频是这台电视上大家共用的。",
            "电视上的 Izuko 正在重新打开，好了之后这个页面会自动刷新。",
            "看看电视上的 Izuko 有没有重新打开。打开了就刷新这个页面；没打开的话，在电视上打开 Izuko 再刷新。",
            "切换到「{0}」？\n\n电视上的 Izuko 会重新打开，正在播放的会停下。",
            "删除「{0}」？\n\n这个用户的收藏、播放记录和登录都会从这台电视上删掉。缓存的视频是大家共用的，不会删。",
            "「添加用户」只新建，不会切过去；要用时点那个人右边的「切换」，电视上的 Izuko 会重新打开，这个页面随后自动刷新。",
            "点头像或名字：改名或删除。第一个用户和正在用的用户不能删除。",
            "本地", "登录 Bangumi", "不登录",
            "收藏、看过和评分只记在这台电视上，适合登录不了 Bangumi 的人。",
            "收藏、看过和评分同步到这个人的 Bangumi 账号；切过去时先登录，也可以跳过。",
            "添加时选「不登录」就是本地用户：收藏、看过和评分只记在这台电视上，不能登录 Bangumi。",
            "这是本地用户：收藏、看过和评分只记在这台电视上，不能登录 Bangumi。想同步到 Bangumi，请在上面的「用户」里新建一个登录 Bangumi 的用户。",
            "把收藏导入当前用户的 Bangumi 账号", "导入「{0}」的收藏", "正在读取 Bangumi 上的收藏…",
            "没有要导入的：这些收藏在 Bangumi 上都已经有了。",
            "会在当前用户的 Bangumi 账号里新加 {0} 部收藏，带上评分与短评，看过的集标成看过。",
            "Bangumi 上已经收藏的 {0} 部不动。",
            "加上去的每一条都会出现在这个账号的 Bangumi 时间线上。Bangumi 不能取消收藏，要撤回只能去 Bangumi 网页上逐条改。",
            "{0} 分", "看过 {0} 集", "导入 {0} 部", "正在导入 {0}/{1}…", "导入没有完成：{0}",
            "导入完成：新加 {0} 部收藏，标了 {1} 集看过。", "跳过已经有的 {0} 部。", "{0} 部没有导好：", "收藏加上了，看过的集没标上",
        )
        for (key in webKeys) {
            // 脚本源码里的换行写作 \n
            assertContains(page, "T('" + key.replace("\n", "\\n") + "'")
            assertTrue(key in table, "译文表里没有「$key」")
        }
        val serverKeys = listOf(
            "用户 {0}", "操作失败：{0}", "已添加「{0}」", "添加超时，请重试", "保存超时，请重试", "已保存", "删除超时，请重试", "已删除「{0}」",
            "电视现在就是「{0}」", "电视上没有显示 Izuko，切换不了。先在电视上打开 Izuko 再试",
            "第一个用户只能改名，不能删除", "要删除正在用的用户，先切换到别人", "这台设备不支持多用户", "没有这个用户了，刷新页面再看看",
            "本地用户不能登录 Bangumi。想同步到 Bangumi，请在「用户」里新建一个登录 Bangumi 的用户",
            "只能导入本地用户的收藏", "要先在电视上登录 Bangumi，才能把收藏导入这个账号", "读取 Bangumi 上的收藏超时，请重试",
            "正在导入，等这一次导完", "预览已经过期，请重新打开导入", "开始导入", "正在导入收藏，导完再切换用户",
        )
        for (key in serverKeys) assertTrue(key in table, "译文表里没有「$key」")
    }

    @Test
    fun `导出只给本地用户 导入只进当前的本地用户 对不上就让网页停下`() {
        val local = post("api/profiles/add", "name" to "Carol", "kind" to "local").getValue("id").jsonPrimitive.int
        assertEquals("只有本地用户可以导出", get("api/profiles/export?id=1")!!.message)
        assertEquals("没有这个用户了，刷新页面再看看", get("api/profiles/export?id=99")!!.message)
        // 当前是 1 号 (登录 Bangumi 的那种): 导不进来
        val notLocal = post("api/profiles/restore/collection", "id" to "1", "entry" to """{"subjectId":1,"type":"DOING"}""")
        assertFalse(notLocal.ok)
        assertTrue(notLocal.getValue("stop").jsonPrimitive.boolean)
        assertEquals("只能导入到本地用户", notLocal.message)
        // 网页以为的当前用户与电视上的对不上 (电视上换过人)
        val stale = post("api/profiles/restore/playback", "id" to local.toString(), "records" to "[]")
        assertTrue(stale.getValue("stop").jsonPrimitive.boolean)
        assertEquals("电视上的用户换了，刷新页面再导入", stale.message)
        assertTrue(post("api/profiles/restore/check", "id" to "1", "subjects" to "1,2").getValue("stop").jsonPrimitive.boolean)
    }

    @Test
    fun `导出导入的文案都在译文表里`() {
        val page = renderRemoteControlPage(initialTab = "settings", searchFormHtml = "", requestSectionHtml = "")
        val table = REMOTE_I18N_TABLE.associateBy { it.zh }
        val webKeys = listOf(
            "导出收藏和播放进度", "从文件导入", "已导出：{0} 部收藏，{1} 条播放进度", "读不了这个文件", "这不是 Izuko 导出的用户文件",
            "这个文件是新版本的 Izuko 导出的，先把电视上的 Izuko 升级到新版再导入", "从文件导入到「{0}」", "正在比对电视上已有的收藏…",
            "没有要导入的：文件里的收藏这个用户都已经有了。", "会新加 {0} 部收藏，带上评分、短评和看过的集。", "已经收藏的 {0} 部不动。",
            "播放进度 {0} 条，电视上更新的不会被盖掉。", "文件里的 {0} 部收藏这个用户都已经有了，不动。", "条目信息要从 Bangumi 取，收藏多的话要等一会儿；导的时候别关这个页面。",
            "导入", "停止", "导入完成：", "已导入：", "新加 {0} 部收藏，标了 {1} 集看过，导入 {2} 条播放进度。",
            "再导一次会接着导剩下的，导过的会跳过。", "连不上电视了。", "已停止。",
            "本地用户可以「导出收藏和播放进度」存成文件，换电视或重装后，在新的本地用户里「从文件导入」；已经收藏的不动。",
            "想把本地用户的收藏搬进 Bangumi：先切到登录了 Bangumi 的用户，再点那个本地用户，选「把收藏导入当前用户的 Bangumi 账号」。",
        )
        for (key in webKeys) {
            assertContains(page, "T('" + key + "'")
            assertTrue(key in table, "译文表里没有「$key」")
        }
        val serverKeys = listOf(
            "只有本地用户可以导出", "导出超时，请重试", "读取超时，请重试", "这一条读不了", "从 Bangumi 取条目信息超时",
            "Bangumi 上打不开这个条目", "取条目信息失败：{0}", "播放进度读不了", "写入超时，请重试",
            "电视上的用户换了，刷新页面再导入", "只能导入到本地用户",
        )
        for (key in serverKeys) assertTrue(key in table, "译文表里没有「$key」")
    }
}
