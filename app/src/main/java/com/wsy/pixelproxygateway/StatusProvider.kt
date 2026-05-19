package com.wsy.pixelproxygateway

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class StatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = requireNotNull(context)
        val status = StatusStore(context).loadFromDisk()
        val config = SettingsStore(context).load()
        val cursor = MatrixCursor(arrayOf("key", "value"))
        cursor.addRow(arrayOf("status_json", status.toJson().toString()))
        cursor.addRow(arrayOf("status_text", status.toText()))
        cursor.addRow(arrayOf("config_json", config.toJson(includePassword = false).toString()))
        cursor.addRow(arrayOf("logs", LogStore(context).tailAll(80)))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.wsy.pixelproxygateway.status"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
