package com.subtrans.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF

/** ค่าตั้งทั้งหมดของแอป เก็บใน SharedPreferences */
class Prefs(context: Context) {

    companion object {
        const val FILE = "settings"

        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TEXT_SIZE = "text_size_sp"
        const val KEY_STROKE = "stroke_dp"
        const val KEY_COLOR = "text_color"
        const val KEY_POSITION = "position_mode"
        const val KEY_AI_ON = "ai_on"
        const val KEY_AI_KEY = "ai_key"
        const val KEY_AI_MODEL = "ai_model"
        private const val KEY_REGION_P = "region_portrait"
        private const val KEY_REGION_L = "region_landscape"

        const val POSITION_FREE = 0      // วางเองด้านบนจอ ลากย้ายได้
        const val POSITION_FOLLOW = 1    // วางเหนือซับเดิมอัตโนมัติ

        const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        const val COLOR_YELLOW = 0xFFFFEB3B.toInt()

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        /** บริเวณซับเริ่มต้น: ล่าง 35% ของจอ เต็มความกว้าง */
        val DEFAULT_REGION = RectF(0f, 0.65f, 1f, 1f)

        /** ภาษาต้นทางที่รองรับ: รหัส ML Kit → ชื่อไทย */
        val LANGUAGES = linkedMapOf(
            "en" to "อังกฤษ",
            "ja" to "ญี่ปุ่น",
            "zh" to "จีน",
            "ko" to "เกาหลี"
        )
    }

    val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString(KEY_SOURCE_LANG, "en") ?: "en"
        set(v) = sp.edit().putString(KEY_SOURCE_LANG, v).apply()

    var textSizeSp: Int
        get() = sp.getInt(KEY_TEXT_SIZE, 20)
        set(v) = sp.edit().putInt(KEY_TEXT_SIZE, v).apply()

    var strokeDp: Int
        get() = sp.getInt(KEY_STROKE, 4)
        set(v) = sp.edit().putInt(KEY_STROKE, v).apply()

    var textColor: Int
        get() = sp.getInt(KEY_COLOR, COLOR_WHITE)
        set(v) = sp.edit().putInt(KEY_COLOR, v).apply()

    var positionMode: Int
        get() = sp.getInt(KEY_POSITION, POSITION_FREE)
        set(v) = sp.edit().putInt(KEY_POSITION, v).apply()

    var aiOn: Boolean
        get() = sp.getBoolean(KEY_AI_ON, false)
        set(v) = sp.edit().putBoolean(KEY_AI_ON, v).apply()

    var aiKey: String
        get() = sp.getString(KEY_AI_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_AI_KEY, v.trim()).apply()

    var aiModel: String
        get() = (sp.getString(KEY_AI_MODEL, "") ?: "").ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString(KEY_AI_MODEL, v.trim()).apply()

    /** บริเวณซับ (สัดส่วน 0–1 ของจอ) แยกตามแนวตั้ง/แนวนอน */
    fun region(landscape: Boolean): RectF {
        val s = sp.getString(if (landscape) KEY_REGION_L else KEY_REGION_P, null) ?: return RectF(DEFAULT_REGION)
        val p = s.split(",").mapNotNull { it.toFloatOrNull() }
        return if (p.size == 4) RectF(p[0], p[1], p[2], p[3]) else RectF(DEFAULT_REGION)
    }

    fun setRegion(landscape: Boolean, r: RectF?) {
        val key = if (landscape) KEY_REGION_L else KEY_REGION_P
        if (r == null) sp.edit().remove(key).apply()
        else sp.edit().putString(key, "${r.left},${r.top},${r.right},${r.bottom}").apply()
    }

    fun hasCustomRegion(landscape: Boolean): Boolean =
        sp.contains(if (landscape) KEY_REGION_L else KEY_REGION_P)
}
