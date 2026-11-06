package com.chagok.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Where the logbook is kept. An interface so the recorder can be tested without a device. */
interface LogbookStorage {
    fun load(): Logbook
    fun save(logbook: Logbook)
}

/**
 * Saves the logbook as one JSON file inside the app's private storage.
 * Local only: nothing leaves the car unless the user shows a QR code.
 */
class TripStore(context: Context) : LogbookStorage {
    private val file = File(context.filesDir, "logbook.json")
    private val tmp = File(context.filesDir, "logbook.json.tmp")

    override fun load(): Logbook {
        // A save interrupted between "write temp" and "rename": the temp file is the newest complete copy.
        if (!file.exists() && tmp.exists()) tmp.renameTo(file)
        if (!file.exists()) return Logbook()
        return try {
            Logbook.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            // Keep the unreadable file instead of overwriting it with an empty logbook on the next save.
            val backup = File(file.parentFile, "logbook.broken-${System.currentTimeMillis()}.json")
            file.renameTo(backup)
            Log.e("Chagok", "logbook load failed; kept as ${backup.name}, starting empty", e)
            Logbook()
        }
    }

    /** Write to a temp file first so a crash mid-write cannot corrupt the logbook. */
    @Synchronized
    override fun save(logbook: Logbook) {
        try {
            tmp.writeText(logbook.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e("Chagok", "logbook save failed", e)
        }
    }
}
