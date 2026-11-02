package com.subtrans.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : ComponentActivity() {

    private lateinit var overlayBtn: Button
    private lateinit var modelBtn: Button
    private lateinit var startBtn: Button
    private lateinit var areaLabel: TextView

    private val main = Handler(Looper.getMainLooper())
    private val thaiModel = TranslateRemoteModel.Builder(TranslateLanguage.THAI).build()
    private var modelReady = false
    private var downloading = false

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
        title = "แปลซับสด"
        setContentView(buildUi())
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
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        fun text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = 0xFF333333.toInt()) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(6))
            }.also { col.addView(it) }

        fun button(onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setOnClickListener { onClick() }
        }.also {
            col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }

        text("แปลซับอังกฤษเป็นไทยบนหน้าจอ", 20f, bold = true, color = Color.BLACK)
        text("ทำ 3 ขั้นตอนด้านล่างครั้งแรกครั้งเดียว แล้วกด \"เริ่มแปลซับ\"")

        text("ขั้นที่ 1", bold = true, color = Color.BLACK)
        overlayBtn = button {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        text("ขั้นที่ 2 (ต้องต่อเน็ต ประมาณ 30MB)", bold = true, color = Color.BLACK)
        modelBtn = button { downloadModel() }

        text("ขั้นที่ 3 ตั้งบริเวณที่มีซับ", bold = true, color = Color.BLACK)
        areaLabel = text("")
        val prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
        val seek = SeekBar(this).apply {
            max = 80 // 20% ถึง 100%
            progress = prefs.getInt(OverlayService.KEY_AREA, OverlayService.DEFAULT_AREA) - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putInt(OverlayService.KEY_AREA, p + 20).apply()
                    updateAreaLabel(p + 20)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(seek)
        updateAreaLabel(seek.progress + 20)

        startBtn = button { onStartStop() }

        text("วิธีใช้", 17f, bold = true, color = Color.BLACK).apply { setPadding(0, dp(20), 0, dp(4)) }
        text(
            "• แตะปุ่มลอย = แปลซับบนจอตอนนี้\n" +
                "• กดค้างปุ่มลอย = แปลอัตโนมัติทุก ~1 วินาที (ปุ่มเป็นสีเขียว) แตะอีกครั้งเพื่อปิด\n" +
                "• ลากปุ่มหรือกล่องคำแปลไปวางตรงไหนก็ได้\n" +
                "• แตะกล่องคำแปลเพื่อซ่อน\n" +
                "• ปิดได้จากแถบแจ้งเตือน กด \"หยุด\"\n\n" +
                "หมายเหตุ: Netflix, Disney+ และแอปที่ป้องกันการบันทึกหน้าจอจะเห็นเป็นจอดำ จึงแปลไม่ได้ " +
                "ใช้ได้กับ YouTube, ไลฟ์ และเว็บวิดีโอทั่วไป"
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(col)
        }
    }

    private fun updateAreaLabel(percent: Int) {
        areaLabel.text = "อ่านซับจากส่วนล่าง $percent% ของจอ (ซับอยู่สูงกว่านี้ให้เลื่อนเพิ่ม)"
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        overlayBtn.text = if (overlayOk) "✓ อนุญาตแสดงทับแอปอื่นแล้ว" else "อนุญาตแสดงทับแอปอื่น"
        overlayBtn.isEnabled = !overlayOk

        RemoteModelManager.getInstance().isModelDownloaded(thaiModel)
            .addOnSuccessListener { ok ->
                modelReady = ok
                updateModelButton()
            }
        updateModelButton()

        startBtn.text = if (OverlayService.running) "■ หยุดแปลซับ" else "▶ เริ่มแปลซับ"
    }

    private fun updateModelButton() {
        modelBtn.text = when {
            modelReady -> "✓ มีคำแปลภาษาไทยแล้ว"
            downloading -> "กำลังดาวน์โหลด..."
            else -> "ดาวน์โหลดคำแปลอังกฤษ → ไทย"
        }
        modelBtn.isEnabled = !modelReady && !downloading
    }

    // ---------------- Actions ----------------

    private fun downloadModel() {
        downloading = true
        updateModelButton()
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
        )
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
        if (!modelReady) {
            toast("ทำขั้นที่ 2 ก่อน")
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
