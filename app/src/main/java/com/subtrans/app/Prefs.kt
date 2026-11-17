package com.subtrans.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ค่าตั้งทั้งหมดของแอป เก็บใน SharedPreferences (API Key เข้ารหัสด้วย AndroidKeyStore) */
class Prefs(context: Context) {

    companion object {
        const val FILE = "settings"

        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TEXT_SIZE = "text_size_sp"
        const val KEY_STROKE = "stroke_dp"
        const val KEY_COLOR = "text_color"
        const val KEY_BG_ALPHA = "bg_alpha"
        const val KEY_POSITION = "position_mode"
        const val KEY_SCAN_INTERVAL = "scan_interval_ms"
        const val KEY_OCR_ENHANCE = "ocr_enhance"
        const val KEY_AI_ON = "ai_on"
        const val KEY_AI_KEY = "ai_key"
        const val KEY_AI_KEY_ENC = "ai_key_enc"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_AI_VISION = "ai_vision"
        const val KEY_GENRE = "ai_genre"
        const val KEY_TONE = "ai_tone"
        const val KEY_TOPIC = "ai_topic"
        const val KEY_GLOSSARY = "ai_glossary"

        /** คีย์ที่เปลี่ยนแล้วต้องล้างแคชคำแปล */
        val STYLE_KEYS = setOf(KEY_GENRE, KEY_TONE, KEY_TOPIC, KEY_GLOSSARY)

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val MODEL_ACCURATE = "gemini-3.6-flash"
        const val MODEL_LATEST = "gemini-3.8-flash"

        /** ประเภทวิดีโอ (ใช้กับการแปลด้วย AI) */
        val GENRES = listOf("ทั่วไป", "อนิเมะ/การ์ตูน", "ซีรีส์/หนัง", "สารคดี/ข่าว", "เกม/สตรีม", "การ์ตูนเด็ก/เรียนภาษา")
        val TONES = listOf("เป็นกันเอง", "สุภาพ")
        private const val KEY_REGION_P = "region_portrait"
        private const val KEY_REGION_L = "region_landscape"

        const val POSITION_FREE = 0      // วางเองด้านบนจอ ลากย้ายได้
        const val POSITION_FOLLOW = 1    // วางเหนือซับเดิมอัตโนมัติ

        const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        const val COLOR_YELLOW = 0xFFFFEB3B.toInt()

        /** บริเวณซับเริ่มต้น: ล่าง 35% ของจอ เต็มความกว้าง */
        val DEFAULT_REGION = RectF(0f, 0.65f, 1f, 1f)

        /** ภาษาต้นทางที่รองรับ: รหัส ML Kit → ชื่อไทย */
        val LANGUAGES = linkedMapOf(
            "en" to "อังกฤษ",
            "ja" to "ญี่ปุ่น",
            "zh" to "จีน",
            "ko" to "เกาหลี"
        )

        private const val KEYSTORE_ALIAS = "subtrans_ai_key"
        private const val ENC_PREFIX = "v1:"
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

    /** ความทึบพื้นหลังกล่องคำแปล (0 = โปร่งใสไม่มีพื้นหลัง, สูงสุด 220) */
    var bgAlpha: Int
        get() = sp.getInt(KEY_BG_ALPHA, 0).coerceIn(0, 220)
        set(v) = sp.edit().putInt(KEY_BG_ALPHA, v.coerceIn(0, 220)).apply()

    var positionMode: Int
        get() = sp.getInt(KEY_POSITION, POSITION_FREE)
        set(v) = sp.edit().putInt(KEY_POSITION, v).apply()

    /** ความเร็วในการสแกนซับโหมดออโต้ (มิลลิวินาที) */
    var scanIntervalMs: Long
        get() = sp.getInt(KEY_SCAN_INTERVAL, 450).coerceIn(250, 1500).toLong()
        set(v) = sp.edit().putInt(KEY_SCAN_INTERVAL, v.toInt().coerceIn(250, 1500)).apply()

    /** ปรับความคมชัด/ขาวดำของภาพก่อนส่งเข้า ML Kit OCR */
    var ocrEnhance: Boolean
        get() = sp.getBoolean(KEY_OCR_ENHANCE, true)
        set(v) = sp.edit().putBoolean(KEY_OCR_ENHANCE, v).apply()

    var aiOn: Boolean
        get() = sp.getBoolean(KEY_AI_ON, false)
        set(v) = sp.edit().putBoolean(KEY_AI_ON, v).apply()

    /** อ่านและเขียน API Key โดยเข้ารหัสด้วย AndroidKeyStore (รองรับการย้ายค่าจากแบบเดิมอัตโนมัติ) */
    var aiKey: String
        get() {
            val enc = sp.getString(KEY_AI_KEY_ENC, null)
            if (!enc.isNullOrBlank()) {
                decryptKey(enc)?.let { return it }
            }
            val legacy = sp.getString(KEY_AI_KEY, "") ?: ""
            if (legacy.isNotBlank()) {
                // ย้ายค่าเดิมไปเก็บแบบเข้ารหัสและลบค่าเดิม
                val encrypted = encryptKey(legacy)
                if (encrypted != null) {
                    sp.edit().putString(KEY_AI_KEY_ENC, encrypted).remove(KEY_AI_KEY).apply()
                }
            }
            return legacy
        }
        set(v) {
            val clean = v.trim()
            if (clean.isEmpty()) {
                sp.edit().remove(KEY_AI_KEY_ENC).remove(KEY_AI_KEY).apply()
                return
            }
            val encrypted = encryptKey(clean)
            if (encrypted != null) {
                sp.edit().putString(KEY_AI_KEY_ENC, encrypted).remove(KEY_AI_KEY).apply()
            } else {
                sp.edit().putString(KEY_AI_KEY, clean).apply()
            }
        }

    var aiModel: String
        get() = (sp.getString(KEY_AI_MODEL, "") ?: "").ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString(KEY_AI_MODEL, v.trim()).apply()

    /** โหมด AI อ่านภาพซับโดยตรง (Multimodal) */
    var aiVision: Boolean
        get() = sp.getBoolean(KEY_AI_VISION, false)
        set(v) = sp.edit().putBoolean(KEY_AI_VISION, v).apply()

    var genre: Int
        get() = sp.getInt(KEY_GENRE, 0).coerceIn(0, GENRES.size - 1)
        set(v) = sp.edit().putInt(KEY_GENRE, v).apply()

    var tone: Int
        get() = sp.getInt(KEY_TONE, 0).coerceIn(0, TONES.size - 1)
        set(v) = sp.edit().putInt(KEY_TONE, v).apply()

    var topic: String
        get() = sp.getString(KEY_TOPIC, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOPIC, v.trim()).apply()

    /** คำศัพท์/ชื่อเฉพาะ บรรทัดละคำ รูปแบบ "คำต้นฉบับ=คำไทย" */
    var glossary: String
        get() = sp.getString(KEY_GLOSSARY, "") ?: ""
        set(v) = sp.edit().putString(KEY_GLOSSARY, v.trim()).apply()

    fun aiStyle() = GeminiTranslator.Style(genre, tone, topic, glossary)

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

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encryptKey(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ct = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        "$ENC_PREFIX$iv:$ct"
    }.getOrNull()

    private fun decryptKey(stored: String): String? = runCatching {
        if (!stored.startsWith(ENC_PREFIX)) return null
        val parts = stored.removePrefix(ENC_PREFIX).split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}
