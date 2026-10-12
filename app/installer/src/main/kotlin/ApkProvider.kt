/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 把下好的安装包只读地交给系统的安装界面 (见 [installIntent]).
 * 只认 [InstallFlow.downloadDir] 里的文件; 不引 AndroidX 的 FileProvider, 免得包变大.
 */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val context = context ?: throw FileNotFoundException(uri.toString())
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val file = File(InstallFlow.downloadDir(context), name)
        if (file.parentFile != InstallFlow.downloadDir(context) || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // 有的安装器先查文件名与大小
        val file = fileOf(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name, file.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val MIME_TYPE = "application/vnd.android.package-archive"

        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.installer.apk").appendPath(file.name).build()

        /**
         * 打开系统 (或电视自带) 安装界面的 Intent. 7.0 以前的安装界面不认 content://, 那些系统本来也装不了 Izuko TV.
         * 不带 [Intent.EXTRA_RETURN_RESULT]: 带了的话装好后不显示「已安装 [打开]」那一页.
         */
        fun installIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
