package com.subtrans.app

import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** เครื่องมือช่วยเรื่องภาษา / OCR / เปรียบเทียบข้อความ */
object Lang {

    fun isCjk(lang: String) = lang == "ja" || lang == "zh" || lang == "ko"

    fun mlkitCode(lang: String): String = when (lang) {
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        "ko" -> TranslateLanguage.KOREAN
        else -> TranslateLanguage.ENGLISH
    }

    fun recognizer(lang: String): TextRecognizer = when (lang) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun mlkitTranslator(lang: String): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(mlkitCode(lang))
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
    )

    /** ความคล้ายของข้อความ 0..1 (1 = เหมือนกัน) ใช้กันอ่านซับเดิมซ้ำเพราะ OCR พลาดทีละตัว */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = a.lowercase()
        val y = b.lowercase()
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return 1.0 - prev[y.length].toDouble() / max(x.length, y.length)
    }
}

/** แคชคำแปลล่าสุด (ซับซ้ำ เช่น "Yes." ไม่ต้องแปลใหม่) */
class TranslationCache(private val maxSize: Int = 300) {
    private val map = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxSize
    }

    @Synchronized
    fun get(k: String): String? = map[k]

    @Synchronized
    fun put(k: String, v: String) {
        map[k] = v
    }

    @Synchronized
    fun clear() = map.clear()
}

/**
 * แปลด้วย Gemini API (ต้องมี API key และอินเทอร์เน็ต)
 * ส่งประโยคก่อนหน้าไปด้วย เพื่อให้แปลต่อเนื่องเป็นธรรมชาติ
 */
class GeminiTranslator(private val apiKey: String, private val model: String) {

    class ApiException(val code: Int, msg: String) : Exception(msg)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun translate(
        text: String,
        sourceLang: String,
        context: List<String>,
        callback: (Result<String>) -> Unit
    ) {
        executor.execute {
            val r = runCatching { request(text, sourceLang, context) }
            main.post { callback(r) }
        }
    }

    private fun request(text: String, sourceLang: String, context: List<String>): String {
        val langName = when (sourceLang) {
            "ja" -> "Japanese"
            "zh" -> "Chinese"
            "ko" -> "Korean"
            else -> "English"
        }
        val system = "You translate video subtitles from $langName into natural, conversational Thai. " +
            "The input comes from OCR and may contain small recognition errors; silently fix them. " +
            "Reply with the Thai translation of the CURRENT line only. No quotes, no notes, no romanization."
        val prompt = buildString {
            if (context.isNotEmpty()) {
                append("Previous subtitle lines (for context only, do not translate):\n")
                context.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("CURRENT line:\n").append(text)
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 256))

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(resp).getJSONObject("error").getString("message") }
                    .getOrDefault("HTTP $code")
                throw ApiException(code, msg)
            }
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) out.append(p.optString("text"))
            }
            val result = out.toString().trim()
            if (result.isEmpty()) throw ApiException(0, "empty response")
            return result
        } finally {
            conn.disconnect()
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}
