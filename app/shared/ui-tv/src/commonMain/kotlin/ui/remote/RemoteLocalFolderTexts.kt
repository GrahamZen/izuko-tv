/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 数据源页本地文件夹卡片与添加表单的文案 (见 [RemoteCloudDrive], 译文表见 [REMOTE_I18N_TABLE]). */
internal fun localFolderTexts() = listOf(
    RemoteText("本地文件夹", "Local folders", "本地文件夾", "本機資料夾"),
    RemoteText("本地文件夹（U 盘、移动硬盘、内部存储）", "Local folders (USB drives, external disks, internal storage)", "本地文件夾（U 盤、移動硬盤、內部存儲）", "本機資料夾（隨身碟、外接硬碟、內部儲存空間）"),
    RemoteText("U 盘、移动硬盘与内部存储里的视频", "Videos on USB drives, external disks and internal storage", "U 盤、移動硬盤與內部存儲裡的視頻", "隨身碟、外接硬碟與內部儲存空間裡的影片"),
    RemoteText("能读取", "Readable", "能讀取", "能讀取"),
    RemoteText("播放时在媒体文件夹里按番名找", "Playback looks for the show by title in the media folders", "播放時在媒體文件夾裡按番名找", "播放時在媒體資料夾裡按番名找"),
    RemoteText("还没有读取文件的权限", "No permission to read files yet", "還沒有讀取文件的權限", "還沒有讀取檔案的權限"),
    RemoteText("在电视上打开「允许管理所有文件」后才能读盘上的文件夹", "Turn on “Allow management of all files” on the TV to read folders on the drives", "在電視上打開「允許管理所有文件」後才能讀盤上的文件夾", "在電視上開啟「允許管理所有檔案」後才能讀取磁碟上的資料夾"),
    RemoteText("在电视上打开授权页面", "Open the permission page on the TV", "在電視上打開授權頁面", "在電視上開啟授權頁面"),
    RemoteText("电视上找不到这个页面时，在电脑上运行：{0}", "If the TV has no such page, run this on a computer: {0}", "電視上找不到這個頁面時，在電腦上運行：{0}", "電視上找不到這個頁面時，在電腦上執行：{0}"),
    RemoteText("还没有选：播放时在各块盘里按番名找（从盘的根目录往下）。选上放动画的文件夹，找得更快也更准。", "None chosen yet: playback searches every drive by title, starting from the top folder. Choose the folders that hold your anime for faster, more accurate matches.", "還沒有選：播放時在各塊盤裡按番名找（從盤的根目錄往下）。選上放動畫的文件夾，找得更快也更準。", "還沒有選：播放時在各個磁碟裡按番名找（從磁碟的根目錄往下）。選上放動畫的資料夾，找得更快也更準。"),
    RemoteText("修改名字", "Rename", "修改名字", "修改名字"),
    RemoteText("删除这个数据源", "Remove this source", "刪除這個數據源", "刪除這個資料源"),
    RemoteText("读电视上接着的 U 盘、移动硬盘与内部存储里的视频。添加后，播放时会在选的文件夹里按番名找，直接播放原文件。", "Reads videos on the USB drives, external disks and internal storage connected to the TV. Once added, playback looks for videos in the chosen folders by title and plays the original files.", "讀電視上接著的 U 盤、移動硬盤與內部存儲裡的視頻。添加後，播放時會在選的文件夾裡按番名找，直接播放原文件。", "讀取電視上接著的隨身碟、外接硬碟與內部儲存空間裡的影片。新增後，播放時會在選的資料夾裡按番名找，直接播放原檔案。"),
    RemoteText("添加后会打开设备上的存储，选上放动画的文件夹（媒体文件夹），找番更快也更准。", "After adding, the device’s storage opens so you can choose the folders that hold your anime (media folders) for faster, more accurate matches.", "添加後會打開設備上的存儲，選上放動畫的文件夾（媒體文件夾），找番更快也更準。", "新增後會開啟裝置上的儲存空間，選上放動畫的資料夾（媒體資料夾），找番更快也更準。"),
    RemoteText("删除{0}？选的媒体文件夹一起删掉，盘上的文件不受影响。", "Remove {0}? Its media folders are removed too. Files on the drives are not affected.", "刪除{0}？選的媒體文件夾一起刪掉，盤上的文件不受影響。", "刪除{0}？選的媒體資料夾一起刪掉，磁碟上的檔案不受影響。"),
    RemoteText("设备上的存储", "Storage on this device", "設備上的存儲", "裝置上的儲存空間"),
    RemoteText("这台设备不能读本地文件夹", "This device can’t read local folders", "這台設備不能讀本地文件夾", "這台裝置不能讀取本機資料夾"),
    RemoteText("已经允许了", "Already allowed", "已經允許了", "已經允許了"),
    RemoteText("已在电视上打开授权页面，用遥控器打开「允许管理所有文件」后回来刷新", "The permission page is open on the TV. Turn on “Allow management of all files” with the remote, then come back and refresh.", "已在電視上打開授權頁面，用遙控器打開「允許管理所有文件」後回來刷新", "已在電視上開啟授權頁面，用遙控器開啟「允許管理所有檔案」後回來重新整理"),
    RemoteText("电视上没有授权页面，在电脑上运行：{0}", "The TV has no permission page. Run this on a computer: {0}", "電視上沒有授權頁面，在電腦上運行：{0}", "電視上沒有授權頁面，在電腦上執行：{0}"),
)
