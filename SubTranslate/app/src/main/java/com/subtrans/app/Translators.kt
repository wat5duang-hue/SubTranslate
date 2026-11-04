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

    private val DASH = Regex("^[-–—]\\s*")
    private val MUSIC = Regex("[♪♫♬#]+")
    private val LONE_L = Regex("(?<=^|\\s)l(?=\\s|$|')")

    /** แก้คำที่ OCR อ่านพลาดบ่อย และทำให้ซับตัวพิมพ์ใหญ่ทั้งบรรทัดแปลได้ดีขึ้น */
    fun fixLine(raw: String, lang: String): String {
        var s = MUSIC.replace(raw, " ").replace(Regex("\\s+"), " ").trim()
        if (lang != "en") return s
        s = s.replace('|', 'I')
            .replace("1'm", "I'm")
            .replace("1'll", "I'll")
            .replace("1'd", "I'd")
            .replace("1've", "I've")
        s = LONE_L.replace(s, "I")
        // ซับ "ALL CAPS" ตัวแปลมักเข้าใจผิดว่าเป็นคำย่อ → แปลงเป็นตัวพิมพ์ปกติ
        val letters = s.count { it.isLetter() }
        if (letters >= 4 && s.none { it.isLowerCase() }) {
            s = s.lowercase()
                .replace(Regex("(?<=^|[.!?]\\s|[-–—]\\s?)([a-z])")) { it.value.uppercase() }
                .replace(Regex("\\bi\\b"), "I")
                .replace(Regex("\\bi'"), "I'")
        }
        return s
    }

    /**
     * รวมบรรทัดที่ OCR อ่านได้เป็นประโยค
     * ถ้าเป็นบทสนทนา 2 คน (ขึ้นต้นด้วย "-") จะแยกเป็นบรรทัดละคน คั่นด้วย \n
     */
    fun joinLines(lines: List<String>, lang: String): String {
        val sep = if (lang == "ja" || lang == "zh") "" else " "
        val dialogue = lines.count { DASH.containsMatchIn(it) } >= 1 && lines.size >= 2
        if (!dialogue) return lines.joinToString(sep).trim()
        val segs = mutableListOf<String>()
        for (l in lines) {
            if (DASH.containsMatchIn(l) || segs.isEmpty()) segs.add(DASH.replace(l, ""))
            else segs[segs.size - 1] = segs.last() + sep + l
        }
        return segs.filter { it.isNotBlank() }.joinToString("\n") { "- $it" }
    }

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
 * ส่งประโยคก่อนหน้า "พร้อมคำแปล" ไปด้วย เพื่อให้ชื่อ สรรพนาม และน้ำเสียงต่อเนื่องกัน
 */
class GeminiTranslator(private val apiKey: String, private val model: String) {

    /** สไตล์การแปลที่ผู้ใช้ตั้ง */
    data class Style(val genre: Int, val tone: Int, val topic: String, val glossary: String)

    class ApiException(val code: Int, msg: String) : Exception(msg)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun translate(
        text: String,
        sourceLang: String,
        history: List<Pair<String, String>>,
        style: Style,
        callback: (Result<String>) -> Unit
    ) {
        executor.execute {
            val r = runCatching { request(text, sourceLang, history, style) }
            main.post { callback(r) }
        }
    }

    private fun genreGuide(genre: Int): String = when (genre) {
        1 -> "This is anime/animation. Keep the energy and emotion of the characters; use expressive spoken Thai " +
            "the way Thai anime subtitles do. Keep Japanese honorific nuance naturally (e.g. senpai → รุ่นพี่) " +
            "and transliterate character names into Thai script."
        2 -> "This is a drama/movie. Use natural spoken Thai dialogue like professional Thai subtitles; " +
            "pick pronouns (ผม/ฉัน/เรา/แก/นาย/เธอ) and end particles that fit the relationship between speakers."
        3 -> "This is a documentary/news. Use clear, neutral, accurate Thai; translate technical terms correctly " +
            "and keep numbers, names and units exact."
        4 -> "This is a game/live stream. Use casual, lively Thai like Thai gamers and streamers; " +
            "keep common gaming terms in their usual Thai or English form (e.g. boss, level, skill)."
        5 -> "This is a children's cartoon or language-learning video. Use simple, short, easy Thai words."
        else -> "Use natural spoken Thai like professional Thai subtitles."
    }

    private fun toneGuide(tone: Int): String = if (tone == 1)
        "Register: polite. Use polite Thai and add ครับ/ค่ะ where a polite speaker would."
    else
        "Register: casual and natural. Do not add ครับ/ค่ะ unless the speaker is clearly being polite or formal."

    private fun request(
        text: String,
        sourceLang: String,
        history: List<Pair<String, String>>,
        style: Style
    ): String {
        val langName = when (sourceLang) {
            "ja" -> "Japanese"
            "zh" -> "Chinese"
            "ko" -> "Korean"
            else -> "English"
        }
        val system = buildString {
            append("You are a professional subtitle translator from $langName into Thai.\n")
            append(genreGuide(style.genre)).append('\n')
            append(toneGuide(style.tone)).append('\n')
            append("The text was read from the screen by OCR and may contain recognition errors " +
                "(wrong letters, missing spaces, stray symbols). Infer the intended words and translate the meaning.\n")
            append("Subtitles are often split mid-sentence. If the current line is an unfinished sentence " +
                "or continues the previous line, translate it as a natural Thai fragment that flows from the previous translation.\n")
            append("Keep names, pronouns and terms consistent with the previous translations and the glossary.\n")
            append("Translate meaning and intent, not word for word; idioms and slang become natural Thai equivalents.\n")
            append("If the input has several lines starting with \"- \" (different speakers), output the same number of lines, each starting with \"- \".\n")
            append("Output ONLY the Thai subtitle text. No quotes, notes, explanations or romanization.")
        }
        val prompt = buildString {
            if (style.topic.isNotBlank()) append("What is being watched: ").append(style.topic).append("\n\n")
            val terms = style.glossary.lines().map { it.trim() }.filter { it.contains('=') }
            if (terms.isNotEmpty()) {
                append("Glossary (always use these Thai renderings):\n")
                terms.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            if (history.isNotEmpty()) {
                append("Previous subtitles and their Thai translations (context only):\n")
                history.forEach { (src, th) -> append(src.replace('\n', ' ')).append(" → ").append(th.replace('\n', ' ')).append('\n') }
                append('\n')
            }
            append("Translate the CURRENT subtitle:\n").append(text)
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 512))

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
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
            val result = out.toString().trim().trim('"', '“', '”')
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
