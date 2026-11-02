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
        title = "แปลซับสด"
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

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(40))
        }
        val full = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

        fun text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = 0xFF333333.toInt()) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, dp(4))
            }.also { col.addView(it) }

        fun header(s: String) = text(s, 18f, bold = true, color = Color.BLACK).apply {
            setPadding(0, dp(22), 0, dp(4))
        }

        fun button(onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setOnClickListener { onClick() }
        }.also { col.addView(it, full().apply { topMargin = dp(6) }) }

        fun radios(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
            val g = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
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

        fun slider(label: (Int) -> String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
            val t = text(label(value))
            col.addView(SeekBar(this).apply {
                this.max = max - min
                progress = value - min
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

        // ----- เริ่มต้น -----
        text("แปลซับบนหน้าจอเป็นภาษาไทย", 20f, bold = true, color = Color.BLACK)

        header("1. อนุญาตแสดงทับแอปอื่น")
        overlayBtn = button {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        header("2. ภาษาของซับ")
        val langCodes = Prefs.LANGUAGES.keys.toList()
        radios(Prefs.LANGUAGES.values.toList(), langCodes.indexOf(prefs.sourceLang).coerceAtLeast(0)) {
            prefs.sourceLang = langCodes[it]
            modelReady = false
            refresh()
        }
        modelBtn = button { downloadModel() }

        header("3. เริ่มใช้งาน")
        startBtn = button { onStartStop() }
        text(
            "• แตะปุ่มลอย = แปลตอนนี้\n" +
                "• กดค้างปุ่มลอย = เมนู (แปลอัตโนมัติ, เลือกบริเวณซับ, ประวัติ, ปิด)\n" +
                "• ลากปุ่มไปไหนก็ได้ ปล่อยแล้วจะชิดขอบจอ\n" +
                "• เพิ่มปุ่มลัด \"แปลซับ\" ในแผงตั้งค่าด่วนได้ (ดึงแถบแจ้งเตือนลง → แก้ไขปุ่ม)",
            14f
        )

        // ----- หน้าตาคำแปล -----
        header("หน้าตาคำแปล")
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
        col.addView(previewBox, full().apply { topMargin = dp(4); bottomMargin = dp(4) })

        slider({ "ขนาดตัวอักษร: $it" }, 14, 36, prefs.textSizeSp) {
            prefs.textSizeSp = it
            updatePreview()
        }
        slider({ "ความหนาขอบดำ: $it" }, 0, 8, prefs.strokeDp) {
            prefs.strokeDp = it
            updatePreview()
        }
        text("สีตัวอักษร")
        radios(listOf("ขาว", "เหลือง"), if (prefs.textColor == Prefs.COLOR_YELLOW) 1 else 0) {
            prefs.textColor = if (it == 1) Prefs.COLOR_YELLOW else Prefs.COLOR_WHITE
            updatePreview()
        }
        text("ตำแหน่งคำแปล")
        radios(listOf("ด้านบนจอ (ลากย้ายได้)", "เหนือซับเดิม"), prefs.positionMode) {
            prefs.positionMode = it
        }
        updatePreview()

        // ----- บริเวณซับ -----
        header("บริเวณซับ")
        text("ตั้งได้จากปุ่มลอย: กดค้าง → \"เลือกบริเวณซับ\" แล้วลากกรอบครอบซับ (แนวตั้งและแนวนอนตั้งแยกกัน)", 14f)
        regionLabel = text("")
        button {
            prefs.setRegion(false, null)
            prefs.setRegion(true, null)
            refresh()
            toast("ใช้บริเวณซับค่าเริ่มต้นแล้ว")
        }.text = "รีเซ็ตบริเวณซับเป็นค่าเริ่มต้น"

        // ----- AI -----
        header("แปลด้วย AI (ไม่บังคับ)")
        text(
            "ใช้ Google Gemini แปลให้เป็นธรรมชาติขึ้น ต้องต่อเน็ต และต้องมี API key " +
                "(ขอฟรีที่ aistudio.google.com/apikey) ถ้าใช้เกินโควตาฟรีอาจมีค่าใช้จ่าย " +
                "ถ้า AI ใช้ไม่ได้ แอปจะกลับไปใช้ตัวแปลออฟไลน์ให้เอง",
            14f
        )
        val aiSwitch = Switch(this).apply {
            text = "เปิดใช้ AI"
            textSize = 16f
            isChecked = prefs.aiOn
        }
        col.addView(aiSwitch, full())
        text("API key", 14f)
        val keyEdit = EditText(this).apply {
            setText(prefs.aiKey)
            hint = "วาง API key ที่นี่"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(keyEdit, full())
        text("ชื่อรุ่น (ปล่อยว่าง = ${Prefs.DEFAULT_MODEL})", 14f)
        val modelEdit = EditText(this).apply {
            setText(prefs.sp.getString(Prefs.KEY_AI_MODEL, "") ?: "")
            hint = Prefs.DEFAULT_MODEL
            isSingleLine = true
        }
        col.addView(modelEdit, full())
        button {
            val key = keyEdit.text.toString().trim()
            if (aiSwitch.isChecked && key.isEmpty()) {
                toast("ใส่ API key ก่อน")
                return@button
            }
            prefs.aiKey = key
            prefs.aiModel = modelEdit.text.toString()
            prefs.aiOn = aiSwitch.isChecked
            toast(if (aiSwitch.isChecked) "บันทึกแล้ว: ใช้ AI แปล" else "บันทึกแล้ว: ใช้ตัวแปลออฟไลน์")
        }.text = "บันทึกการตั้งค่า AI"

        // ----- อื่นๆ -----
        header("อื่นๆ")
        button { startActivity(Intent(this, HistoryActivity::class.java)) }.text = "ดูประวัติคำแปล"
        text(
            "หมายเหตุ: Netflix, Disney+ และแอปที่ป้องกันการบันทึกหน้าจอจะเห็นเป็นจอดำ จึงแปลไม่ได้ " +
                "ใช้ได้กับ YouTube, ไลฟ์ และเว็บวิดีโอทั่วไป",
            13f, color = 0xFF777777.toInt()
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(col)
        }
    }

    private fun updatePreview() {
        preview.setStyle(prefs.textSizeSp, prefs.strokeDp)
        preview.textColor = prefs.textColor
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
