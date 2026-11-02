package com.subtrans.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.hypot

/**
 * บริการเบื้องหลังที่:
 *  1) จับภาพหน้าจอ (MediaProjection)
 *  2) แสดงปุ่มลอย "แปล" และกล่องคำแปล
 *  3) อ่านซับจากบริเวณล่างของจอด้วย OCR แล้วแปลอังกฤษ → ไทยแบบออฟไลน์
 *
 * แตะปุ่มลอย = แปลหน้าจอตอนนี้ 1 ครั้ง
 * กดค้างปุ่มลอย = เปิด/ปิดโหมดแปลอัตโนมัติ (ปุ่มเป็นสีเขียว)
 * แตะกล่องคำแปล = ซ่อนกล่อง, ลากปุ่มหรือกล่องเพื่อย้ายตำแหน่งได้
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.subtrans.app.STOP"
        const val PREFS = "settings"
        const val KEY_AREA = "area_percent"
        const val DEFAULT_AREA = 35
        private const val CHANNEL_ID = "subtrans"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_INTERVAL_MS = 1200L
        private const val COLOR_IDLE = 0xFF1E88E5.toInt()
        private const val COLOR_AUTO = 0xFF43A047.toInt()
        private const val COLOR_BUSY = 0xFFFB8C00.toInt()

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("capture").apply { start() }
    private val bg = Handler(bgThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latestImage: Image? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 0

    private var bubble: TextView? = null
    private var box: TextView? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var boxLp: WindowManager.LayoutParams

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
    )

    private var busy = false
    private var auto = false
    private var lastText = ""
    private var emptyCount = 0

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            captureAndTranslate(fromAuto = true)
            main.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android 14+: ต้องเรียก startForeground ก่อนขอ MediaProjection
        startAsForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (code != Activity.RESULT_OK || data == null || !Settings.canDrawOverlays(this)) {
            toast("เริ่มไม่สำเร็จ ลองใหม่จากหน้าแอป")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            toast("ไม่ได้รับสิทธิ์บันทึกหน้าจอ")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        setupCapture()
        addOverlays()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        running = true
        return START_NOT_STICKY
    }

    // ---------------- Notification / foreground ----------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "แปลซับสด", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("แปลซับกำลังทำงาน")
            .setContentText("แตะปุ่มลอยเพื่อแปล • กดค้างเพื่อแปลอัตโนมัติ")
            .setContentIntent(openPi)
            .addAction(0, "หยุด", stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------- Screen capture ----------------

    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // เก็บเฟรมล่าสุดไว้ตลอด เพื่อให้แตะแปลได้ทันทีแม้ภาพหยุดนิ่ง
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(frameLock) {
                    latestImage?.close()
                    latestImage = img
                }
            }
        }, bg)
        return r
    }

    private fun setupCapture() {
        val (w, h) = screenSize()
        capW = w
        capH = h
        dpi = resources.displayMetrics.densityDpi
        val r = newReader(w, h)
        reader = r
        virtualDisplay = projection?.createVirtualDisplay(
            "subtrans", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    /** หมุนจอ (เช่นดูวิดีโอแนวนอน) → ปรับขนาดภาพที่จับให้ตรงกับจอ */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = virtualDisplay ?: return
        val (w, h) = screenSize()
        if (w == capW && h == capH) return
        val old = reader
        val r = newReader(w, h)
        reader = r
        vd.resize(w, h, dpi)
        vd.surface = r.surface
        capW = w
        capH = h
        bg.post {
            synchronized(frameLock) {
                latestImage?.close()
                latestImage = null
            }
            old?.close()
        }
        // ให้กล่องคำแปลกว้างพอดีกับจอใหม่ และดึงปุ่มกลับเข้าจอ
        box?.let {
            boxLp.width = (w * 0.9).toInt()
            boxLp.x = (w * 0.05).toInt()
            safeUpdate(it, boxLp)
        }
        bubble?.let {
            bubbleLp.x = bubbleLp.x.coerceIn(0, (w - bubbleLp.width).coerceAtLeast(0))
            bubbleLp.y = bubbleLp.y.coerceIn(0, (h - bubbleLp.height).coerceAtLeast(0))
            safeUpdate(it, bubbleLp)
        }
    }

    /** แปลงเฟรมล่าสุดเป็นภาพ เฉพาะส่วนล่างของจอ และปิดทับบริเวณปุ่ม/กล่องของเราเอง */
    private fun grabSubtitleArea(masks: List<Rect>, areaPercent: Int): Bitmap? {
        synchronized(frameLock) {
            val img = latestImage ?: return null
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * w
            val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)

            val canvas = Canvas(full)
            val paint = Paint().apply { color = Color.BLACK }
            for (m in masks) canvas.drawRect(m, paint)

            val top = (h - h * areaPercent / 100).coerceIn(0, h - 1)
            val crop = Bitmap.createBitmap(full, 0, top, w, h - top)
            if (crop !== full) full.recycle()
            return crop
        }
    }

    // ---------------- OCR + translate ----------------

    private fun captureAndTranslate(fromAuto: Boolean) {
        if (busy) return
        busy = true
        setBubbleColor()

        val masks = listOfNotNull(bubble, box)
            .filter { it.isShown }
            .map { v ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            }
        val area = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AREA, DEFAULT_AREA)

        bg.post {
            val bmp = try {
                grabSubtitleArea(masks, area)
            } catch (e: Exception) {
                null
            }
            main.post onMain@{
                if (bmp == null) {
                    done()
                    if (!fromAuto) toast("ยังไม่ได้ภาพหน้าจอ ลองแตะอีกครั้ง")
                    return@onMain
                }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result -> handleText(cleanText(result), fromAuto) }
                    .addOnFailureListener {
                        done()
                        if (!fromAuto) toast("อ่านข้อความไม่สำเร็จ")
                    }
                    .addOnCompleteListener { bmp.recycle() }
            }
        }
    }

    private fun cleanText(result: Text): String {
        return result.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .flatMap { it.lines }
            .map { it.text.trim() }
            .filter { line -> line.count { it.isLetter() } >= 2 }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun handleText(text: String, fromAuto: Boolean) {
        if (text.isEmpty()) {
            done()
            if (fromAuto) {
                emptyCount++
                if (emptyCount >= 3) box?.visibility = View.GONE
            } else {
                toast("ไม่พบซับในบริเวณที่ตั้งไว้")
            }
            return
        }
        emptyCount = 0
        if (fromAuto && text == lastText) {
            done()
            return
        }
        lastText = text

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .continueWithTask { translator.translate(text) }
            .addOnSuccessListener { thai ->
                showTranslation(thai)
                done()
            }
            .addOnFailureListener {
                lastText = ""
                done()
                toast("แปลไม่สำเร็จ: ต้องดาวน์โหลดโมเดลภาษาไทยก่อน (ต่อเน็ตแล้วลองใหม่)")
            }
    }

    private fun done() {
        busy = false
        setBubbleColor()
    }

    // ---------------- Overlay views ----------------

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun addOverlays() {
        val size = dp(56)
        val b = TextView(this).apply {
            text = "แปล"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = capW - size - dp(8)
            y = capH / 3
        }

        val t = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xD9000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }
        boxLp = WindowManager.LayoutParams(
            (capW * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (capW * 0.05).toInt()
            y = dp(40)
        }

        wm.addView(t, boxLp)
        wm.addView(b, bubbleLp)
        bubble = b
        box = t
        setBubbleColor()

        makeDraggable(b, bubbleLp,
            onTap = {
                if (auto) toggleAuto() else captureAndTranslate(fromAuto = false)
            },
            onLongPress = { toggleAuto() }
        )
        makeDraggable(t, boxLp, onTap = { t.visibility = View.GONE }, onLongPress = null)
    }

    private fun toggleAuto() {
        auto = !auto
        main.removeCallbacks(autoTick)
        if (auto) {
            lastText = ""
            toast("เปิดแปลอัตโนมัติ (แตะปุ่มเพื่อปิด)")
            main.post(autoTick)
        } else {
            toast("ปิดแปลอัตโนมัติ")
        }
        setBubbleColor()
    }

    private fun setBubbleColor() {
        val b = bubble ?: return
        val color = when {
            busy && !auto -> COLOR_BUSY
            auto -> COLOR_AUTO
            else -> COLOR_IDLE
        }
        b.text = if (auto) "ออโต้" else "แปล"
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun showTranslation(thai: String) {
        val t = box ?: return
        t.text = thai
        t.visibility = View.VISIBLE
    }

    private fun makeDraggable(
        v: View,
        lp: WindowManager.LayoutParams,
        onTap: () -> Unit,
        onLongPress: (() -> Unit)?
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var longFired = false
        val slop = dp(8).toFloat()
        val longRunnable = Runnable {
            if (!moved) {
                longFired = true
                onLongPress?.invoke()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    longFired = false
                    if (onLongPress != null) main.postDelayed(longRunnable, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longRunnable)
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        safeUpdate(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRunnable)
                    if (!moved && !longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longRunnable)
            }
            true
        }
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- Cleanup ----------------

    override fun onDestroy() {
        running = false
        auto = false
        main.removeCallbacksAndMessages(null)
        bubble?.let { runCatching { wm.removeView(it) } }
        box?.let { runCatching { wm.removeView(it) } }
        bubble = null
        box = null

        virtualDisplay?.release()
        virtualDisplay = null
        val p = projection
        projection = null
        runCatching { p?.stop() }

        val r = reader
        reader = null
        bg.post {
            synchronized(frameLock) {
                latestImage?.close()
                latestImage = null
            }
            r?.close()
        }
        bgThread.quitSafely()

        recognizer.close()
        translator.close()
        super.onDestroy()
    }
}
