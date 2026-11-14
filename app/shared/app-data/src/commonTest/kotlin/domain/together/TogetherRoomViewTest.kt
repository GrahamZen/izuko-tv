/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlin.test.Test
import kotlin.test.assertEquals

class TogetherRoomViewTest {
    private val playing = TogetherWatchingInfo(
        subjectId = 100, episodeId = 7, subjectName = "S", episodeSort = "07", episodeName = "E",
        positionMillis = 0, positionAtMillis = 0, durationMillis = 1_440_000, paused = false,
    )

    private fun member(
        isHost: Boolean = false,
        following: Boolean = true,
        state: TogetherMemberState = TogetherMemberState.WATCHING,
        watching: TogetherWatchingInfo? = playing,
    ) = TogetherMember(if (isHost) "host" else "member", "", isHost = isHost, following = following, state = state, lastSeenAt = 0, watching = watching)

    private fun snapshot(hostState: TogetherMemberState = TogetherMemberState.WATCHING, playback: TogetherWatchingInfo? = playing) = TogetherRoomSnapshot(
        version = 1, status = TogetherRoomStatus.OPEN, hostId = "host", roomTime = 0,
        members = listOf(member(isHost = true, state = hostState, watching = playback), member()),
        playback = playback?.let { TogetherPlayback(it) },
    )

    @Test
    fun `a playback session alone does not count as playing`() {
        assertEquals(TogetherMemberActivity.PLAYING, member().activity())
        // 片源还没装好; 只在后台准备着 (播放页不在眼前)
        assertEquals(TogetherMemberActivity.PREPARING, member(watching = playing.copy(loading = true, paused = true)).activity())
        assertEquals(TogetherMemberActivity.PREPARING, member(watching = playing.copy(onScreen = false, paused = true)).activity())
        assertEquals(TogetherMemberActivity.BUFFERING, member(watching = playing.copy(buffering = true)).activity())
        assertEquals(TogetherMemberActivity.PAUSED, member(watching = playing.copy(paused = true)).activity())
        // 暂停着顺便在缓冲: 算暂停
        assertEquals(TogetherMemberActivity.PAUSED, member(watching = playing.copy(paused = true, buffering = true)).activity())
        assertEquals(TogetherMemberActivity.IDLE, member(watching = null).activity())
    }

    @Test
    fun `offline and watching independently come before what is on the player`() {
        assertEquals(TogetherMemberActivity.OFFLINE, member(state = TogetherMemberState.DISCONNECTED).activity())
        assertEquals(TogetherMemberActivity.FREE, member(following = false).activity())
        assertEquals(TogetherMemberActivity.OFFLINE, member(following = false, state = TogetherMemberState.DISCONNECTED).activity())
        // 房主自己的「跟随」不起作用
        assertEquals(TogetherMemberActivity.PLAYING, member(isHost = true, following = false).activity())
    }

    @Test
    fun `the situation of the host follows whether an episode is open`() {
        assertEquals(TogetherSituation.HOST_IDLE, togetherSituation(snapshot(playback = null), isHost = true, following = true))
        assertEquals(TogetherSituation.HOST_WATCHING, togetherSituation(snapshot(), isHost = true, following = false))
    }

    @Test
    fun `the situation of a member follows the mode first and then the host`() {
        assertEquals(TogetherSituation.MEMBER_FOLLOWING, togetherSituation(snapshot(), isHost = false, following = true))
        assertEquals(TogetherSituation.MEMBER_WAITING, togetherSituation(snapshot(playback = null), isHost = false, following = true))
        assertEquals(TogetherSituation.MEMBER_HOST_OFFLINE, togetherSituation(snapshot(TogetherMemberState.DISCONNECTED), isHost = false, following = true))
        // 自由观看的不等房主: 房主掉线了也还是自由观看
        assertEquals(TogetherSituation.MEMBER_FREE, togetherSituation(snapshot(TogetherMemberState.DISCONNECTED), isHost = false, following = false))
        assertEquals(TogetherSituation.MEMBER_FREE, togetherSituation(snapshot(playback = null), isHost = false, following = false))
    }
}
