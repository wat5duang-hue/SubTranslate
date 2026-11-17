package com.subtrans.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    private lateinit var prefs: Prefs
    private lateinit var overlayBtn: Button
    private lateinit var modelBtn: Button
    private lateinit var startBtn: Button
    private lateinit var regionLabel: TextView
    private lateinit var preview: OutlineTextView

    private val main = Handler(Looper.getMainLooper())
    private var modelReady = false
    private var downloading = false
    private var pendingAutostart = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, result.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                toast("เริ่มแล้ว! เปิดแอปวิดีโอ แล้วแตะปุ่มลอย \"แปล\"")
                moveTaskToBack(true)
            } else {
                toast("ยกเลิกการอนุญาตบันทึกหน้าจอ")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "แปลซับสด v3.0"
        setContentView(buildUi())
        pendingAutostart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) pendingAutostart = true
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- UI ----------------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // แถบแท็บด้านบน
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFF3F5F7.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pages = List(4) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(36))
            }
        }
        val tabNames = listOf("เริ่มต้น", "หน้าตาซับ", "ตั้งค่า AI", "ขั้นสูง")
        val tabButtons = mutableListOf<TextView>()

        fun selectTab(idx: Int) {
            pages.forEachIndexed { i, p -> p.visibility = if (i == idx) View.VISIBLE else View.GONE }
            tabButtons.forEachIndexed { i, b ->
                val active = i == idx
                b.setTextColor(if (active) Color.WHITE else 0xFF333333.toInt())
                b.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                b.background = GradientDrawable().apply {
                    setColor(if (active) 0xFF1E88E5.toInt() else Color.TRANSPARENT)
                    cornerRadius = dp(8).toFloat()
                }
            }
        }

        tabNames.forEachIndexed { i, name ->
            val btn = TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { selectTab(i) }
            }
            tabButtons.add(btn)
            tabBar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val scroll = ScrollView(this).apply {
            val container = FrameLayout(this@MainActivity)
            pages.forEach { container.addView(it) }
            addView(container)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Helper สำหรับสร้าง View ลงในแท็บที่ระบุ
        val full = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

        fun text(col: LinearLayout, s: String, size: Float = 15f, bold: Boolean = false, color: Int = 0xFF333333.toInt()) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, dp(4))
            }.also { col.addView(it) }

        fun header(col: LinearLayout, s: String) = text(col, s, 17f, bold = true, color = Color.BLACK).apply {
            setPadding(0, dp(16), 0, dp(4))
        }

        fun button(col: LinearLayout, onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setOnClickListener { onClick() }
        }.also { col.addView(it, full().apply { topMargin = dp(6) }) }

        fun radios(col: LinearLayout, options: List<String>, selected: Int, vertical: Boolean = false, onPick: (Int) -> Unit) {
            val g = RadioGroup(this).apply {
                orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
            }
            options.forEachIndexed { i, label ->
                g.addView(RadioButton(this).apply {
                    id = 1000 + i
                    text = label
                    textSize = 15f
                    setPadding(0, 0, dp(12), 0)
                })
            }
            g.check(1000 + selected)
            g.setOnCheckedChangeListener { _, id -> onPick(id - 1000) }
            col.addView(g)
        }

        fun slider(col: LinearLayout, label: (Int) -> String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
            val t = text(col, label(value))
            col.addView(SeekBar(this).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        t.text = label(p + min)
                        onChange(p + min)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }

        // ==================== แท็บ 0: เริ่มต้น ====================
        val pStart = pages[0]
        text(pStart, "แปลซับบนหน้าจอเป็นภาษาไทย", 19f, bold = true, color = Color.BLACK)

        header(pStart, "1. อนุญาตแสดงทับแอปอื่น")
        overlayBtn = button(pStart) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        header(pStart, "2. ภาษาของซับ")
        val langCodes = Prefs.LANGUAGES.keys.toList()
        radios(pStart, Prefs.LANGUAGES.values.toList(), langCodes.indexOf(prefs.sourceLang).coerceAtLeast(0)) {
            prefs.sourceLang = langCodes[it]
            modelReady = false
            refresh()
        }
        modelBtn = button(pStart) { downloadModel() }

        header(pStart, "3. เริ่มใช้งาน")
        startBtn = button(pStart) { onStartStop() }
        text(
            pStart,
            "• แตะปุ่มลอย = แปลตอนนี้\n" +
                "• กดค้างปุ่มลอย = เมนู (แปลอัตโนมัติ, พักชั่วคราว, เลือกบริเวณซับ, ประวัติ, ปิด)\n" +
                "• ลากปุ่มไปไหนก็ได้ ปล่อยแล้วจะชิดขอบจอ\n" +
                "• เพิ่มปุ่มลัด \"แปลซับ\" ในแผงตั้งค่าด่วนได้",
            14f
        )
        button(pStart) { startActivity(Intent(this, HistoryActivity::class.java)) }.text = "ดูประวัติคำแปล"

        // ==================== แท็บ 1: หน้าตาซับ ====================
        val pStyle = pages[1]
        header(pStyle, "ตัวอย่างคำแปล")
        val previewBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(0xFF8D6E63.toInt(), 0xFF5D8AA8.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        preview = OutlineTextView(this).apply { text = "ตัวอย่างคำแปลภาษาไทย" }
        previewBox.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        pStyle.addView(previewBox, full().apply { topMargin = dp(4); bottomMargin = dp(8) })

        slider(pStyle, { "ขนาดตัวอักษร: $it" }, 14, 36, prefs.textSizeSp) {
            prefs.textSizeSp = it
            updatePreview()
        }
        slider(pStyle, { "ความหนาขอบดำ: $it" }, 0, 8, prefs.strokeDp) {
            prefs.strokeDp = it
            updatePreview()
        }
        slider(pStyle, { if (it == 0) "พื้นหลังกล่องคำแปล: ปิด" else "พื้นหลังกล่องคำแปล: ${(it * 100) / 220}%" }, 0, 220, prefs.bgAlpha) {
            prefs.bgAlpha = it
            updatePreview()
        }
        text(pStyle, "สีตัวอักษร")
        radios(pStyle, listOf("ขาว", "เหลือง"), if (prefs.textColor == Prefs.COLOR_YELLOW) 1 else 0) {
            prefs.textColor = if (it == 1) Prefs.COLOR_YELLOW else Prefs.COLOR_WHITE
            updatePreview()
        }
        text(pStyle, "ตำแหน่งคำแปล")
        radios(pStyle, listOf("ด้านบนจอ (ลากย้ายได้)", "เหนือซับเดิม"), prefs.positionMode) {
            prefs.positionMode = it
        }
        updatePreview()

        // ==================== แท็บ 2: ตั้งค่า AI ====================
        val pAi = pages[2]
        header(pAi, "แปลด้วย AI (Gemini)")
        text(
            pAi,
            "ใช้ Google Gemini แปลให้เป็นธรรมชาติขึ้น ต้องต่อเน็ต และต้องมี API key " +
                "(ขอฟรีที่ aistudio.google.com/apikey) ถ้า AI ใช้ไม่ได้ แอปจะกลับไปใช้ตัวแปลออฟไลน์ให้เอง",
            14f
        )
        val aiSwitch = Switch(this).apply {
            text = "เปิดใช้ AI"
            textSize = 16f
            isChecked = prefs.aiOn
            setPadding(0, dp(8), 0, dp(8))
        }
        pAi.addView(aiSwitch, full())

        val aiVisionSwitch = Switch(this).apply {
            text = "อ่านภาพซับด้วย AI โดยตรง (Vision)"
            textSize = 15f
            isChecked = prefs.aiVision
            setPadding(0, dp(4), 0, dp(8))
        }
        pAi.addView(aiVisionSwitch, full())
        text(pAi, "เหมาะกับอนิเมะ/เกมที่ฟอนต์อ่านยาก (ใช้โควตา AI เพิ่มขึ้นเล็กน้อย)", 12f, color = 0xFF666666.toInt())

        text(pAi, "API key", 14f)
        val keyEdit = EditText(this).apply {
            setText(prefs.aiKey)
            hint = "วาง API key ที่นี่"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        pAi.addView(keyEdit, full())

        text(pAi, "รุ่น AI", 14f)
        val savedModel = prefs.aiModel
        var modelChoice = when (savedModel) {
            Prefs.DEFAULT_MODEL -> 0
            Prefs.MODEL_ACCURATE -> 1
            Prefs.MODEL_LATEST -> 2
            else -> 3
        }
        lateinit var modelEdit: EditText
        radios(
            pAi,
            listOf("เร็ว (3.5 Flash-Lite)", "แม่นกว่า (3.6 Flash)", "ล่าสุด (3.8 Flash)", "กำหนดเอง"),
            modelChoice,
            vertical = true
        ) {
            modelChoice = it
            modelEdit.visibility = if (it == 3) View.VISIBLE else View.GONE
        }
        modelEdit = EditText(this).apply {
            setText(if (modelChoice == 3) savedModel else "")
            hint = "ชื่อรุ่น เช่น ${Prefs.DEFAULT_MODEL}"
            isSingleLine = true
            visibility = if (modelChoice == 3) View.VISIBLE else View.GONE
        }
        pAi.addView(modelEdit, full())

        fun selectedModel(): String = when (modelChoice) {
            0 -> Prefs.DEFAULT_MODEL
            1 -> Prefs.MODEL_ACCURATE
            2 -> Prefs.MODEL_LATEST
            else -> modelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_MODEL }
        }

        button(pAi) {
            val key = keyEdit.text.toString().trim()
            if (key.isEmpty()) {
                toast("ใส่ API key ก่อนทดสอบ")
                return@button
            }
            val model = selectedModel()
            toast("กำลังทดสอบ $model ...")
            val tester = GeminiTranslator(key, model)
            tester.testConnection { r ->
                tester.close()
                r.onSuccess { toast("✓ เชื่อมต่อ AI ($model) สำเร็จ!") }
                    .onFailure { e ->
                        val msg = (e as? GeminiTranslator.ApiException)?.message ?: e.message ?: "ไม่ทราบสาเหตุ"
                        toast("✗ ทดสอบไม่ผ่าน: $msg")
                    }
            }
        }.text = "ทดสอบ API Key"

        text(pAi, "ประเภทวิดีโอที่ดู (AI จะเลือกสำนวนให้เข้ากับเรื่อง)", 14f)
        var genreChoice = prefs.genre
        radios(pAi, Prefs.GENRES, genreChoice, vertical = true) { genreChoice = it }

        text(pAi, "น้ำเสียงคำแปล", 14f)
        var toneChoice = prefs.tone
        radios(pAi, Prefs.TONES, toneChoice) { toneChoice = it }

        text(pAi, "กำลังดูเรื่องอะไร (ไม่บังคับ)", 14f)
        val topicEdit = EditText(this).apply {
            setText(prefs.topic)
            hint = "เช่น หนูน้อยหมวกแดง นิทานเด็ก"
        }
        pAi.addView(topicEdit, full())

        text(pAi, "ชื่อเฉพาะ / คำศัพท์ (ไม่บังคับ) บรรทัดละคำ แบบ คำเดิม=คำไทย", 14f)
        val glossaryEdit = EditText(this).apply {
            setText(prefs.glossary)
            hint = "Red Riding Hood=หนูน้อยหมวกแดง\nWolf=หมาป่า"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        pAi.addView(glossaryEdit, full())

        button(pAi) {
            val key = keyEdit.text.toString().trim()
            if (aiSwitch.isChecked && key.isEmpty()) {
                toast("ใส่ API key ก่อน")
                return@button
            }
            prefs.aiKey = key
            prefs.aiModel = selectedModel()
            prefs.aiVision = aiVisionSwitch.isChecked
            prefs.genre = genreChoice
            prefs.tone = toneChoice
            prefs.topic = topicEdit.text.toString()
            prefs.glossary = glossaryEdit.text.toString()
            prefs.aiOn = aiSwitch.isChecked
            toast(if (aiSwitch.isChecked) "บันทึกแล้ว: ใช้ AI แปล" else "บันทึกแล้ว: ใช้ตัวแปลออฟไลน์")
        }.text = "บันทึกการตั้งค่า AI"

        // ==================== แท็บ 3: ขั้นสูง ====================
        val pAdv = pages[3]
        header(pAdv, "บริเวณซับ")
        text(pAdv, "ตั้งได้จากปุ่มลอย: กดค้าง → \"เลือกบริเวณซับ\" แล้วลากกรอบครอบซับ (แนวตั้งและแนวนอนตั้งแยกกัน)", 14f)
        regionLabel = text(pAdv, "")
        button(pAdv) {
            prefs.setRegion(false, null)
            prefs.setRegion(true, null)
            refresh()
            toast("ใช้บริเวณซับค่าเริ่มต้นแล้ว")
        }.text = "รีเซ็ตบริเวณซับเป็นค่าเริ่มต้น"

        header(pAdv, "ประสิทธิภาพและการอ่านซับ")
        slider(
            pAdv,
            { "ความเร็วสแกนโหมดออโต้: ${it}ms (ค่าน้อย = ตอบสนองไว)" },
            250, 1500, prefs.scanIntervalMs.toInt()
        ) {
            prefs.scanIntervalMs = it.toLong()
        }

        val enhanceSwitch = Switch(this).apply {
            text = "ปรับความคมชัดภาพก่อนอ่าน (OCR)"
            textSize = 15f
            isChecked = prefs.ocrEnhance
            setPadding(0, dp(10), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> prefs.ocrEnhance = checked }
        }
        pAdv.addView(enhanceSwitch, full())

        text(
            pAdv,
            "หมายเหตุ: Netflix, Disney+ และแอปที่ป้องกันการบันทึกหน้าจอจะเห็นเป็นจอดำ จึงแปลไม่ได้ " +
                "ใช้ได้กับ YouTube, ไลฟ์ และเว็บวิดีโอทั่วไป",
            13f, color = 0xFF777777.toInt()
        )

        selectTab(0)
        return root
    }

    private fun updatePreview() {
        preview.setStyle(prefs.textSizeSp, prefs.strokeDp)
        preview.textColor = prefs.textColor
        preview.bgAlpha = prefs.bgAlpha
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        overlayBtn.text = if (overlayOk) "✓ อนุญาตแล้ว" else "อนุญาตแสดงทับแอปอื่น"
        overlayBtn.isEnabled = !overlayOk

        regionLabel.text = "แนวตั้ง: " + (if (prefs.hasCustomRegion(false)) "กำหนดเอง" else "ค่าเริ่มต้น") +
            "  •  แนวนอน: " + (if (prefs.hasCustomRegion(true)) "กำหนดเอง" else "ค่าเริ่มต้น")

        startBtn.text = if (OverlayService.running) "■ หยุดแปลซับ" else "▶ เริ่มแปลซับ"
        updateModelButton()
        checkModels { ok ->
            modelReady = ok
            updateModelButton()
            if (pendingAutostart) {
                pendingAutostart = false
                if (!OverlayService.running) onStartStop()
            }
        }
    }

    /** ตรวจว่ามีโมเดลแปลของภาษาที่เลือก + ภาษาไทยในเครื่องแล้วหรือยัง */
    private fun checkModels(cb: (Boolean) -> Unit) {
        val mm = RemoteModelManager.getInstance()
        val codes = mutableListOf(TranslateLanguage.THAI)
        if (prefs.sourceLang != "en") codes.add(Lang.mlkitCode(prefs.sourceLang))
        val tasks: List<Task<Boolean>> = codes.map { mm.isModelDownloaded(TranslateRemoteModel.Builder(it).build()) }
        var remaining = tasks.size
        var allOk = true
        tasks.forEach { t ->
            t.addOnCompleteListener { r ->
                if (!(r.isSuccessful && r.result == true)) allOk = false
                remaining--
                if (remaining == 0) cb(allOk)
            }
        }
    }

    private fun updateModelButton() {
        val name = Prefs.LANGUAGES[prefs.sourceLang] ?: ""
        modelBtn.text = when {
            modelReady -> "✓ มีคำแปล$name → ไทยแล้ว"
            downloading -> "กำลังดาวน์โหลด..."
            else -> "ดาวน์โหลดคำแปล$name → ไทย (ต้องต่อเน็ต)"
        }
        modelBtn.isEnabled = !modelReady && !downloading
    }

    // ---------------- Actions ----------------

    private fun downloadModel() {
        downloading = true
        updateModelButton()
        val translator = Lang.mlkitTranslator(prefs.sourceLang)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                toast("ดาวน์โหลดเสร็จแล้ว")
            }
            .addOnFailureListener { toast("ดาวน์โหลดไม่สำเร็จ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่") }
            .addOnCompleteListener {
                downloading = false
                updateModelButton()
                translator.close()
            }
    }

    private fun onStartStop() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            main.postDelayed({ refresh() }, 500)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("ทำขั้นที่ 1 ก่อน")
            return
        }
        if (!modelReady && !(prefs.aiOn && prefs.aiKey.isNotBlank())) {
            toast("ทำขั้นที่ 2 ก่อน (ดาวน์โหลดคำแปล)")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
