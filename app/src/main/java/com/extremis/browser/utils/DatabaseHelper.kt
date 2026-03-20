package com.extremis.browser.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.extremis.browser.models.Bookmark
import com.extremis.browser.models.HistoryItem

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "extremis_browser.db"
        private const val DB_VERSION = 1

        private const val TABLE_BOOKMARKS = "bookmarks"
        private const val TABLE_HISTORY = "history"

        private const val COL_ID = "id"
        private const val COL_TITLE = "title"
        private const val COL_URL = "url"
        private const val COL_DATE = "date_added"
        private const val COL_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_BOOKMARKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_URL TEXT NOT NULL,
                $COL_DATE INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_URL TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BOOKMARKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    // ── Bookmarks ───────────────────────────────────────────────────────

    fun addBookmark(bookmark: Bookmark): Long {
        val values = ContentValues().apply {
            put(COL_TITLE, bookmark.title)
            put(COL_URL, bookmark.url)
            put(COL_DATE, bookmark.dateAdded)
        }
        return writableDatabase.insert(TABLE_BOOKMARKS, null, values)
    }

    fun getBookmarks(): List<Bookmark> {
        val list = mutableListOf<Bookmark>()
        val cursor = readableDatabase.query(
            TABLE_BOOKMARKS, null, null, null, null, null,
            "$COL_DATE DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Bookmark(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        title = it.getString(it.getColumnIndexOrThrow(COL_TITLE)),
                        url = it.getString(it.getColumnIndexOrThrow(COL_URL)),
                        dateAdded = it.getLong(it.getColumnIndexOrThrow(COL_DATE))
                    )
                )
            }
        }
        return list
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete(TABLE_BOOKMARKS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun isBookmarked(url: String): Boolean {
        val cursor = readableDatabase.query(
            TABLE_BOOKMARKS, arrayOf(COL_ID),
            "$COL_URL = ?", arrayOf(url),
            null, null, null, "1"
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    // ── History ─────────────────────────────────────────────────────────

    fun addToHistory(item: HistoryItem): Long {
        val values = ContentValues().apply {
            put(COL_TITLE, item.title)
            put(COL_URL, item.url)
            put(COL_TIMESTAMP, item.timestamp)
        }
        return writableDatabase.insert(TABLE_HISTORY, null, values)
    }

    fun getHistory(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val cursor = readableDatabase.query(
            TABLE_HISTORY, null, null, null, null, null,
            "$COL_TIMESTAMP DESC", "200"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        title = it.getString(it.getColumnIndexOrThrow(COL_TITLE)),
                        url = it.getString(it.getColumnIndexOrThrow(COL_URL)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                    )
                )
            }
        }
        return list
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }
}
