/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

/**
 * 弹弹 play 回「已达到接口调用配额上限」(HTTP 200, 响应体 errorCode = [DandanplayBangumiCalls.QUOTA_EXCEEDED]).
 *
 * 额度按应用 (AppId) 计、所有用户共用, 要等弹弹 play 重置才能再调; 与这次请求的内容和用户的网络无关.
 */
class DandanplayQuotaExceededException(message: String) : IllegalStateException(message)
