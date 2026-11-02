package com.subtrans.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ประวัติคำแปล เก็บเป็นไฟล์ JSON ในเครื่อง (สูงสุด 500 รายการล่าสุด) */
object HistoryStore {

    data class Entry(val time: Long, val source: String, val thai: String)

    private const val FILE = "history.json"
    private const val MAX = 500
    private var cache: MutableList<Entry>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun load(ctx: Context): List<Entry> = ensure(ctx).toList()

    @Synchronized
    fun add(ctx: Context, source: String, thai: String) {
        val list = ensure(ctx)
        if (list.isNotEmpty() && list.last().source == source) return
        list.add(Entry(System.currentTimeMillis(), source, thai))
        while (list.size > MAX) list.removeAt(0)
        save(ctx, list)
    }

    @Synchronized
    fun clear(ctx: Context) {
        ensure(ctx).clear()
        file(ctx).delete()
    }

    private fun ensure(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val list = mutableListOf<Entry>()
        runCatching {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Entry(o.getLong("t"), o.getString("s"), o.getString("th")))
                }
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("t", it.time).put("s", it.source).put("th", it.thai)) }
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file(ctx))
        }
    }
}
