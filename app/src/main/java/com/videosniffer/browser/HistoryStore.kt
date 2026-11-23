package com.videosniffer.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(
    val id: Long,
    val title: String,
    val url: String,
    /** Set only on the first entry of each day, so the list can show a date header. */
    val dateHeader: String?
)

/** Browsing history (page URL + title), newest first, grouped by day. */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "sniffer_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "url TEXT, title TEXT, date TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    private fun today(): String =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

    /** Same page visited again today moves to the top instead of repeating. */
    @Synchronized
    fun add(url: String, title: String) {
        if (url.isBlank()) return
        try {
            val db = writableDatabase
            val date = today()
            db.delete("history", "url = ? AND date = ?", arrayOf(url, date))
            val values = ContentValues().apply {
                put("url", url)
                put("title", title)
                put("date", date)
            }
            db.insert("history", null, values)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun getAll(): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        try {
            val c = readableDatabase.rawQuery(
                "SELECT id, url, title, date FROM history ORDER BY id DESC", null
            )
            c.use {
                var lastDate = ""
                while (it.moveToNext()) {
                    val date = it.getString(3) ?: ""
                    val header = if (date != lastDate) {
                        lastDate = date
                        date
                    } else null
                    out.add(
                        HistoryEntry(
                            it.getLong(0),
                            it.getString(2) ?: "",
                            it.getString(1) ?: "",
                            header
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun delete(id: Long) {
        try {
            writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        try {
            writableDatabase.execSQL("DELETE FROM history")
        } catch (_: Exception) {
        }
    }
}
