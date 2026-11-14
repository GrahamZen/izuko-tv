/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

/**
 * 一个房间: 房间号 + 密码。
 *
 * - **房间号是房主那台设备的设备号** ([TOGETHER_ROOM_NUMBER_DIGITS] 位数字, 第一次用一起看时随机生成, 之后不变):
 *   一台设备只开得了自己的房间, 别人只能加入。常一起看的朋友记一次就行。
 * - **密码随机生成** ([TOGETHER_ROOM_PASSWORD_DIGITS] 位数字), 房主可以随时换一个; 换了之后旧密码进不来。
 *
 * 话题名只由房间号算出, 密钥由房间号与密码一起算出 (见 [TogetherRoomKey]): 密码不对的人与房间里的人在同一个话题上,
 * 消息解不开, 房主据此回一句「密码不对」(见 [TogetherEnvelope.deny])。
 *
 * 两样都只认数字: 输入时夹的空格、连字符不算 (「2868 9131」与「28689131」是同一个)。
 */
data class TogetherRoomId(val number: String, val password: String) {
    override fun toString(): String = "TogetherRoomId($number)"

    companion object {
        /** 去掉空格与连字符; 剩下的不全是数字、位数不对时为 `null` */
        fun of(number: String, password: String): TogetherRoomId? {
            val n = digitsOf(number) ?: return null
            val p = digitsOf(password) ?: return null
            if (n.length != TOGETHER_ROOM_NUMBER_DIGITS || p.length != TOGETHER_ROOM_PASSWORD_DIGITS) return null
            return TogetherRoomId(n, p)
        }

        private fun digitsOf(text: String): String? {
            val compact = text.filterNot { it.isWhitespace() || it == '-' }
            return compact.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        }
    }
}

/** 房间号的位数 */
const val TOGETHER_ROOM_NUMBER_DIGITS = 8

/** 密码的位数 */
const val TOGETHER_ROOM_PASSWORD_DIGITS = 6

/** 房间号分两组显示 (2868 9131), 好念 */
fun formatTogetherRoomNumber(number: String): String =
    if (number.length == TOGETHER_ROOM_NUMBER_DIGITS) number.substring(0, 4) + " " + number.substring(4) else number

/** 密码分两组显示 (482 193) */
fun formatTogetherRoomPassword(password: String): String =
    if (password.length == TOGETHER_ROOM_PASSWORD_DIGITS) password.substring(0, 3) + " " + password.substring(3) else password

/**
 * 邀请: 发给朋友的一段话, 里面有房间号与密码。界面按自己的语言写这段话 (房间号、密码各一个占位), 这里只管从别人发来的
 * 一段话里认出房间号与密码 —— 朋友把它粘贴到控制台, 或者扫码得到它。
 */
object TogetherInvite {
    /**
     * 从一段话里认出房间号与密码: 依次取出所有数字, 正好是房间号加密码那么多位时前一段是房间号、后一段是密码;
     * 不是的话找「4 位 4 位」与「3 位 3 位」分组的两段 (邀请里的写法)。都认不出时为 `null`。
     */
    fun parse(text: String): TogetherRoomId? {
        val digits = text.filter { it in '0'..'9' }
        if (digits.length == TOGETHER_ROOM_NUMBER_DIGITS + TOGETHER_ROOM_PASSWORD_DIGITS) {
            return TogetherRoomId(digits.substring(0, TOGETHER_ROOM_NUMBER_DIGITS), digits.substring(TOGETHER_ROOM_NUMBER_DIGITS))
        }
        val match = GROUPED.find(text) ?: return null
        val (n1, n2, p1, p2) = match.destructured
        return TogetherRoomId(n1 + n2, p1 + p2)
    }

    private val GROUPED = Regex("""(?<!\d)(\d{4})[ -]?(\d{4})(?!\d)\D+?(?<!\d)(\d{3})[ -]?(\d{3})(?!\d)""")
}

/** 房间里没有房主 (房间号不对, 或者房主还没打开房间) */
class TogetherRoomEmptyException : Exception("Nobody is hosting this room")

/** 房主说密码不对 (或者房主换过密码) */
class TogetherWrongPasswordException : Exception("Wrong room password")

internal fun randomTogetherDigits(count: Int): String =
    platformSecureRandomBytes(count).joinToString("") { ((it.toInt() and 0xff) % 10).toString() }
