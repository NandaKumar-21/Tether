package com.tether.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Ephemeral chat storage. The database file lives under the app's private data
 * directory and is deleted in ChatActivity.onDestroy() (app close), so nothing
 * persists across sessions - this is the point, not an oversight.
 */
class ChatDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

    companion object {
        const val DB_NAME = "tether_chat_ephemeral.db"
        const val TABLE = "messages"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    data class Message(val id: Long, val role: String, val content: String, val timestamp: Long)

    fun insert(role: String, content: String): Long {
        val cv = android.content.ContentValues().apply {
            put("role", role)
            put("content", content)
            put("timestamp", System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun all(): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, timestamp FROM $TABLE ORDER BY id ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Message(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        return out
    }

    /** Last N turns, oldest first, for building the prompt without exceeding context. */
    fun lastTurns(n: Int): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, timestamp FROM $TABLE ORDER BY id DESC LIMIT ?",
            arrayOf((n * 2).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Message(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        return out.reversed()
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }
}
