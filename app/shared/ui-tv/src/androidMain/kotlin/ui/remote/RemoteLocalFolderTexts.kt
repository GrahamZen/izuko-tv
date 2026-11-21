/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 数据源页本地文件夹卡片的文案 (见 [RemoteCloudDrive], 译文表见 [REMOTE_I18N_TABLE]). */
internal fun localFolderTexts() = listOf(
    RemoteText("本地文件夹", "Local folders", "本地文件夾", "本機資料夾"),
    RemoteText("能读取", "Readable", "能讀取", "能讀取"),
    RemoteText("播放时在媒体文件夹里按番名找", "Playback looks for the show by title in the media folders", "播放時在媒體文件夾裡按番名找", "播放時在媒體資料夾裡按番名找"),
    RemoteText("还没有读取文件的权限", "No permission to read files yet", "還沒有讀取文件的權限", "還沒有讀取檔案的權限"),
    RemoteText("在电视上打开「允许管理所有文件」后才能读盘上的文件夹", "Turn on “Allow management of all files” on the TV to read folders on the drives", "在電視上打開「允許管理所有文件」後才能讀盤上的文件夾", "在電視上開啟「允許管理所有檔案」後才能讀取磁碟上的資料夾"),
    RemoteText("在电视上打开授权页面", "Open the permission page on the TV", "在電視上打開授權頁面", "在電視上開啟授權頁面"),
    RemoteText("电视上找不到这个页面时，在电脑上运行：{0}", "If the TV has no such page, run this on a computer: {0}", "電視上找不到這個頁面時，在電腦上運行：{0}", "電視上找不到這個頁面時，在電腦上執行：{0}"),
    RemoteText("在电视上允许「存储」权限后才能读盘上的文件夹", "Allow the “Storage” permission on the TV to read folders on the drives", "在電視上允許「儲存空間」權限後才能讀盤上的文件夾", "在電視上允許「儲存空間」權限後才能讀取磁碟上的資料夾"),
    RemoteText("在电视上弹出授权框", "Show the permission prompt on the TV", "在電視上彈出授權框", "在電視上顯示授權對話框"),
    RemoteText("电视上没有弹出授权框时，在电脑上运行：{0}", "If no prompt appears on the TV, run this on a computer: {0}", "電視上沒有彈出授權框時，在電腦上運行：{0}", "電視上沒有出現授權對話框時，在電腦上執行：{0}"),
    RemoteText("还没有添加：播放时在各块盘里按番名找（从盘的根目录往下）。添加放动画的文件夹，找得更快也更准。", "None added yet: playback searches every drive by title, starting from the top folder. Add the folders that hold your anime for faster, more accurate matches.", "還沒有添加：播放時在各塊盤裡按番名找（從盤的根目錄往下）。添加放動畫的文件夾，找得更快也更準。", "還沒有新增：播放時在各個磁碟裡按番名找（從磁碟的根目錄往下）。新增放動畫的資料夾，找得更快也更準。"),
    RemoteText("修改名字", "Rename", "修改名字", "修改名字"),
    RemoteText("删除{0}？选的媒体文件夹一起删掉，盘上的文件不受影响。", "Remove {0}? Its media folders are removed too. Files on the drives are not affected.", "刪除{0}？選的媒體文件夾一起刪掉，盤上的文件不受影響。", "刪除{0}？選的媒體資料夾一起刪掉，磁碟上的檔案不受影響。"),
    RemoteText("设备上的存储", "Storage on this device", "設備上的存儲", "裝置上的儲存空間"),
    RemoteText("这台设备不能读本地文件夹", "This device can’t read local folders", "這台設備不能讀本地文件夾", "這台裝置不能讀取本機資料夾"),
    RemoteText("已经允许了", "Already allowed", "已經允許了", "已經允許了"),
    RemoteText("已在电视上打开授权页面，用遥控器打开「允许管理所有文件」后回来刷新", "The permission page is open on the TV. Turn on “Allow management of all files” with the remote, then come back and refresh.", "已在電視上打開授權頁面，用遙控器打開「允許管理所有文件」後回來刷新", "已在電視上開啟授權頁面，用遙控器開啟「允許管理所有檔案」後回來重新整理"),
    RemoteText(
        "已在电视上弹出授权框，选「允许」后回来刷新；之前拒绝过的会打开应用信息，在「权限」里打开「存储」",
        "A permission prompt is showing on the TV. Choose “Allow”, then come back and refresh. If it was denied before, the app info page opens instead: turn on “Storage” under “Permissions”.",
        "已在電視上彈出授權框，選「允許」後回來刷新；之前拒絕過的會打開應用程式資料，在「權限」裡打開「儲存空間」",
        "已在電視上顯示授權對話框，選「允許」後回來重新整理；之前拒絕過的會開啟應用程式資訊，在「權限」裡開啟「儲存空間」",
    ),
    RemoteText("电视上没有授权页面，在电脑上运行：{0}", "The TV has no permission page. Run this on a computer: {0}", "電視上沒有授權頁面，在電腦上運行：{0}", "電視上沒有授權頁面，在電腦上執行：{0}"),
)
