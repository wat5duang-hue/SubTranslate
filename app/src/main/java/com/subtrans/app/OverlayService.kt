package com.subtrans.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlin.math.abs
import kotlin.math.hypot

/**
 * บริการเบื้องหลังที่จับภาพหน้าจอ อ่านซับ (OCR) แล้วแปลเป็นไทย
 *
 * ปุ่มลอย:
 *  - แตะ = แปลตอนนี้ 1 ครั้ง (ถ้าเปิดออโต้อยู่ = ปิดออโต้)
 *  - กดค้าง = เปิดเมนู (ออโต้ / เลือกบริเวณซับ / ประวัติ / ตั้งค่า / ปิด)
 *  - ลาก = ย้าย ปล่อยแล้วชิดขอบจอเอง
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.subtrans.app.STOP"
        private const val CHANNEL_ID = "subtrans"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_INTERVAL_MS = 600L
        private const val SAME_TEXT_SIMILARITY = 0.8
        private const val FRAME_DIFF_THRESHOLD = 4.0
        private const val BUBBLE_FADE_MS = 3000L
        private const val COLOR_IDLE = 0xFF1E88E5.toInt()
        private const val COLOR_AUTO = 0xFF43A047.toInt()
        private const val COLOR_BUSY = 0xFFFB8C00.toInt()

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("capture").apply { start() }
    private val bg = Handler(bgThread.looper)

    // ----- จับภาพหน้าจอ -----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latestImage: Image? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 0
    private var lastSignature: IntArray? = null

    // ----- หน้าจอซ้อน -----
    private var bubble: TextView? = null
    private var box: OutlineTextView? = null
    private var menu: LinearLayout? = null
    private var picker: FrameLayout? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var boxLp: WindowManager.LayoutParams
    private var snapAnim: ValueAnimator? = null

    // ----- OCR / แปล -----
    private var lang = "en"
    private var recognizer: TextRecognizer? = null
    private var mlTranslator: Translator? = null
    private var gemini: GeminiTranslator? = null
    private val cache = TranslationCache()
    private val recentLines = ArrayDeque<String>()
    private var lastAiErrorToast = 0L

    private var busy = false
    private var auto = false
    private var autoBeforePicker = false
    private var lastText = ""
    private var emptyCount = 0

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            captureAndTranslate(fromAuto = true)
            main.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    private val fadeBubble = Runnable { bubble?.animate()?.alpha(0.45f)?.setDuration(300)?.start() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_SOURCE_LANG -> setupLanguage()
            Prefs.KEY_AI_ON, Prefs.KEY_AI_KEY, Prefs.KEY_AI_MODEL -> setupAi()
            Prefs.KEY_TEXT_SIZE, Prefs.KEY_STROKE, Prefs.KEY_COLOR -> applyBoxStyle()
            Prefs.KEY_POSITION -> resetBoxPosition()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
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
        setupLanguage()
        setupAi()
        addOverlays()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        running = true
        refreshTile()
        return START_NOT_STICKY
    }

    private fun refreshTile() {
        runCatching { TileService.requestListeningState(this, ComponentName(this, SubTileService::class.java)) }
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
            .setContentText("แตะปุ่มลอยเพื่อแปล • กดค้างเพื่อเปิดเมนู")
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

    // ---------------- Language / engines ----------------

    private fun setupLanguage() {
        lang = prefs.sourceLang
        recognizer?.close()
        mlTranslator?.close()
        recognizer = Lang.recognizer(lang)
        mlTranslator = Lang.mlkitTranslator(lang).also {
            it.downloadModelIfNeeded(DownloadConditions.Builder().build())
        }
        cache.clear()
        recentLines.clear()
        lastText = ""
    }

    private fun setupAi() {
        gemini?.close()
        gemini = if (prefs.aiOn && prefs.aiKey.isNotBlank()) GeminiTranslator(prefs.aiKey, prefs.aiModel) else null
        cache.clear()
        lastText = ""
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

    private val landscape get() = capW > capH

    /** หมุนจอ → ปรับขนาดภาพที่จับให้ตรงกับจอ */
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
        lastSignature = null
        bg.post {
            synchronized(frameLock) {
                latestImage?.close()
                latestImage = null
            }
            old?.close()
        }
        closeMenu()
        closePicker(save = false)
        box?.let {
            it.maxWidthPx = (w * 0.9).toInt()
            resetBoxPosition()
        }
        bubble?.let {
            bubbleLp.x = if (bubbleLp.x > 0) w - bubbleLp.width else 0
            bubbleLp.y = bubbleLp.y.coerceIn(0, (h - bubbleLp.height).coerceAtLeast(0))
            safeUpdate(it, bubbleLp)
        }
    }

    private class Grab(val bitmap: Bitmap, val left: Int, val top: Int)

    /** ตัดเฉพาะบริเวณซับจากเฟรมล่าสุด และปิดทับปุ่ม/คำแปลของเราเองด้วยสีดำ */
    private fun grabRegion(masks: List<Rect>, region: RectF): Grab? {
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

            val l = (region.left * w).toInt().coerceIn(0, w - 16)
            val t = (region.top * h).toInt().coerceIn(0, h - 16)
            val r = (region.right * w).toInt().coerceIn(l + 16, w)
            val b = (region.bottom * h).toInt().coerceIn(t + 16, h)
            val crop = Bitmap.createBitmap(full, l, t, r - l, b - t)
            if (crop !== full) full.recycle()
            return Grab(crop, l, t)
        }
    }

    /** ลายเซ็นภาพขนาดเล็ก (ขาวดำ 64x16) ใช้ตรวจว่าภาพเปลี่ยนหรือไม่ */
    private fun signature(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, 64, 16, true)
        val px = IntArray(64 * 16)
        small.getPixels(px, 0, 64, 0, 0, 64, 16)
        if (small !== bmp) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        return px
    }

    private fun sameFrame(a: IntArray?, b: IntArray): Boolean {
        if (a == null || a.size != b.size) return false
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size < FRAME_DIFF_THRESHOLD
    }

    // ---------------- OCR + translate ----------------

    private fun captureAndTranslate(fromAuto: Boolean) {
        if (busy || picker != null) return
        busy = true
        setBubbleColor()
        if (!fromAuto) lastText = ""

        val masks = listOfNotNull<View>(bubble, box, menu)
            .filter { it.isShown }
            .map { v ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            }
        val region = prefs.region(landscape)
        val rec = recognizer

        bg.post {
            val grab = try {
                grabRegion(masks, region)
            } catch (e: Exception) {
                null
            }
            // ประหยัดแบต: ภาพไม่เปลี่ยน (เช่นหยุดวิดีโอ) → ไม่ต้องอ่านซ้ำ
            var unchanged = false
            if (grab != null && fromAuto) {
                val sig = signature(grab.bitmap)
                unchanged = sameFrame(lastSignature, sig)
                lastSignature = sig
            }
            main.post onMain@{
                if (grab == null || rec == null) {
                    done()
                    if (!fromAuto) toast("ยังไม่ได้ภาพหน้าจอ ลองแตะอีกครั้ง")
                    return@onMain
                }
                if (unchanged) {
                    grab.bitmap.recycle()
                    done()
                    return@onMain
                }
                rec.process(InputImage.fromBitmap(grab.bitmap, 0))
                    .addOnSuccessListener { result ->
                        val (text, bounds) = cleanText(result)
                        bounds?.offset(grab.left, grab.top)
                        handleText(text, bounds, fromAuto)
                    }
                    .addOnFailureListener {
                        done()
                        if (!fromAuto) toast("อ่านข้อความไม่สำเร็จ")
                    }
                    .addOnCompleteListener { grab.bitmap.recycle() }
            }
        }
    }

    /** รวมบรรทัดซับเป็นข้อความเดียว และคืนกรอบรวมของซับ (พิกัดในภาพที่ตัด) */
    private fun cleanText(result: Text): Pair<String, Rect?> {
        val cjk = Lang.isCjk(lang)
        val minLetters = if (cjk) 1 else 2
        val lines = result.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .flatMap { it.lines }
            .filter { line -> line.text.trim().count { it.isLetter() } >= minLetters }
        var bounds: Rect? = null
        for (l in lines) {
            val b = l.boundingBox ?: continue
            if (bounds == null) bounds = Rect(b) else bounds.union(b)
        }
        val sep = if (lang == "ja" || lang == "zh") "" else " "
        val text = lines.joinToString(sep) { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
        return text to bounds
    }

    private fun handleText(text: String, bounds: Rect?, fromAuto: Boolean) {
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
        // ซับเดิม (OCR อาจอ่านต่างกันนิดหน่อย) → ไม่ต้องแปลใหม่
        if (fromAuto && Lang.similarity(text, lastText) >= SAME_TEXT_SIMILARITY) {
            done()
            return
        }
        lastText = text

        cache.get(text)?.let {
            showTranslation(it, bounds)
            done()
            return
        }

        val g = gemini
        if (g != null) {
            g.translate(text, lang, recentLines.toList()) { r ->
                r.onSuccess { onTranslated(text, it, bounds) }
                    .onFailure { e ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAiErrorToast > 30_000) {
                            lastAiErrorToast = now
                            val msg = when ((e as? GeminiTranslator.ApiException)?.code) {
                                400, 403 -> "API key หรือชื่อรุ่นไม่ถูกต้อง"
                                429 -> "ใช้ AI เกินโควตา"
                                else -> "เชื่อมต่อ AI ไม่ได้"
                            }
                            toast("$msg • ใช้ตัวแปลออฟไลน์แทน")
                        }
                        translateOffline(text, bounds)
                    }
            }
        } else {
            translateOffline(text, bounds)
        }
    }

    private fun translateOffline(text: String, bounds: Rect?) {
        val t = mlTranslator ?: return done()
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .continueWithTask { t.translate(text) }
            .addOnSuccessListener { onTranslated(text, it, bounds) }
            .addOnFailureListener {
                lastText = ""
                done()
                toast("แปลไม่สำเร็จ: ต้องดาวน์โหลดโมเดลภาษาก่อน (ต่อเน็ตแล้วลองใหม่)")
            }
    }

    private fun onTranslated(source: String, thai: String, bounds: Rect?) {
        cache.put(source, thai)
        recentLines.addLast(source)
        while (recentLines.size > 3) recentLines.removeFirst()
        showTranslation(thai, bounds)
        done()
        val app = applicationContext
        bg.post { HistoryStore.add(app, source, thai) }
    }

    private fun done() {
        busy = false
        setBubbleColor()
    }

    // ---------------- Overlay helpers ----------------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** ระยะเหลื่อมระหว่างพิกัดหน้าต่างกับพิกัดจอจริง (วัดจากปุ่มลอย) */
    private fun windowOffset(): Pair<Int, Int> {
        val b = bubble ?: return 0 to 0
        if (!b.isAttachedToWindow || b.visibility != View.VISIBLE) return 0 to 0
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return (loc[0] - bubbleLp.x) to (loc[1] - bubbleLp.y)
    }

    private fun addOverlays() {
        val size = dp(56)
        val b = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
        }
        bubbleLp = overlayParams(size, size).apply {
            x = capW - size
            y = capH / 3
        }

        val t = OutlineTextView(this).apply {
            maxWidthPx = (capW * 0.9).toInt()
            visibility = View.GONE
        }
        boxLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)

        wm.addView(t, boxLp)
        wm.addView(b, bubbleLp)
        bubble = b
        box = t
        applyBoxStyle()
        resetBoxPosition()
        setBubbleColor()
        wakeBubble()

        makeDraggable(b, bubbleLp,
            onTap = { if (auto) toggleAuto() else captureAndTranslate(fromAuto = false) },
            onLongPress = { openMenu() },
            onRelease = { snapBubble() }
        )
        makeDraggable(t, boxLp, onTap = { t.visibility = View.GONE }, onLongPress = null, onRelease = null)
    }

    private fun applyBoxStyle() {
        val t = box ?: return
        t.setStyle(prefs.textSizeSp, prefs.strokeDp)
        t.textColor = prefs.textColor
    }

    private fun resetBoxPosition() {
        val t = box ?: return
        boxLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        boxLp.x = 0
        boxLp.y = dp(40)
        safeUpdate(t, boxLp)
    }

    private fun showTranslation(thai: String, bounds: Rect?) {
        val t = box ?: return
        t.text = thai
        t.visibility = View.VISIBLE
        if (prefs.positionMode == Prefs.POSITION_FOLLOW && bounds != null) {
            // วางเหนือซับเดิม (ไม่ทับ เพื่อให้แอปยังอ่านซับบรรทัดต่อไปได้)
            t.measure(
                View.MeasureSpec.makeMeasureSpec(t.maxWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val w = t.measuredWidth
            val h = t.measuredHeight
            val (ox, oy) = windowOffset()
            var y = bounds.top - h - dp(2)
            if (y < 0) y = bounds.bottom + dp(2)
            val x = (bounds.centerX() - w / 2).coerceIn(0, (capW - w).coerceAtLeast(0))
            boxLp.gravity = Gravity.TOP or Gravity.START
            boxLp.x = x - ox
            boxLp.y = y - oy
            safeUpdate(t, boxLp)
        }
        // โหมดอิสระ: คงตำแหน่งที่ผู้ใช้ลากไว้
    }

    // ---------------- Bubble ----------------

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

    private fun wakeBubble() {
        val b = bubble ?: return
        main.removeCallbacks(fadeBubble)
        b.animate().cancel()
        b.alpha = 1f
        main.postDelayed(fadeBubble, BUBBLE_FADE_MS)
    }

    /** ปล่อยปุ่มแล้วเลื่อนไปชิดขอบซ้าย/ขวาที่ใกล้กว่า */
    private fun snapBubble() {
        val b = bubble ?: return
        val (ox, _) = windowOffset()
        val center = bubbleLp.x + ox + bubbleLp.width / 2
        val target = if (center < capW / 2) -ox else capW - bubbleLp.width - ox
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(bubbleLp.x, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleLp.x = it.animatedValue as Int
                safeUpdate(b, bubbleLp)
            }
            start()
        }
        bubbleLp.y = bubbleLp.y.coerceIn(0, (capH - bubbleLp.height).coerceAtLeast(0))
    }

    private fun toggleAuto() {
        auto = !auto
        main.removeCallbacks(autoTick)
        if (auto) {
            lastText = ""
            lastSignature = null
            toast("เปิดแปลอัตโนมัติ (แตะปุ่มเพื่อปิด)")
            main.post(autoTick)
        } else {
            toast("ปิดแปลอัตโนมัติ")
        }
        setBubbleColor()
    }

    // ---------------- Menu ----------------

    private fun openMenu() {
        closeMenu()
        val bg = GradientDrawable().apply {
            setColor(0xF0222222.toInt())
            cornerRadius = dp(12).toFloat()
        }
        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(4), dp(6), dp(4), dp(6))
            elevation = dp(8).toFloat()
        }
        fun item(label: String, action: () -> Unit) {
            m.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(16), dp(12), dp(24), dp(12))
                setOnClickListener {
                    closeMenu()
                    action()
                }
            })
        }
        item(if (auto) "■  หยุดแปลอัตโนมัติ" else "▶  แปลอัตโนมัติ") { toggleAuto() }
        item("▢  เลือกบริเวณซับ") { openPicker() }
        item("☰  ประวัติคำแปล") { openActivity(HistoryActivity::class.java) }
        item("⚙  ตั้งค่า") { openActivity(MainActivity::class.java) }
        item("✕  ปิดแปลซับ") { stopSelf() }

        m.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                closeMenu()
                true
            } else false
        }

        m.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val (ox, _) = windowOffset()
        val bubbleCenter = bubbleLp.x + ox + bubbleLp.width / 2
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        ).apply {
            x = if (bubbleCenter > capW / 2) bubbleLp.x - m.measuredWidth - dp(8)
            else bubbleLp.x + bubbleLp.width + dp(8)
            y = bubbleLp.y.coerceIn(0, (capH - m.measuredHeight).coerceAtLeast(0))
        }
        wm.addView(m, lp)
        menu = m
        wakeBubble()
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    private fun openActivity(cls: Class<*>) {
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- Region picker ----------------

    private fun openPicker() {
        if (picker != null) return
        autoBeforePicker = auto
        if (auto) toggleAuto()
        bubble?.visibility = View.GONE
        box?.visibility = View.GONE

        val frame = FrameLayout(this)
        val view = RegionPickerView(this)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6222222.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        panel.addView(TextView(this).apply {
            text = "ลากกรอบสีเขียวให้ครอบบริเวณที่มีซับ"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun btn(label: String, color: Int, action: () -> Unit) {
            row.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        btn("บันทึก", COLOR_AUTO) { savePicker(view) }
        btn("ค่าเริ่มต้น", 0xFF757575.toInt()) {
            prefs.setRegion(landscape, null)
            toast("ใช้บริเวณซับค่าเริ่มต้น (ล่าง 35% ของจอ)")
            closePicker(save = false)
        }
        btn("ยกเลิก", 0xFFE53935.toInt()) { closePicker(save = false) }
        panel.addView(row)
        frame.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(48) })

        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        wm.addView(frame, lp)
        picker = frame

        // วางกรอบตามบริเวณที่ตั้งไว้ (แปลงพิกัดจอ → พิกัดใน View)
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val r = prefs.region(landscape)
            view.rect.set(
                r.left * capW - loc[0], r.top * capH - loc[1],
                r.right * capW - loc[0], r.bottom * capH - loc[1]
            )
            view.invalidate()
        }
    }

    private fun savePicker(view: RegionPickerView) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val r = view.rect
        val f = RectF(
            ((r.left + loc[0]) / capW).coerceIn(0f, 1f),
            ((r.top + loc[1]) / capH).coerceIn(0f, 1f),
            ((r.right + loc[0]) / capW).coerceIn(0f, 1f),
            ((r.bottom + loc[1]) / capH).coerceIn(0f, 1f)
        )
        prefs.setRegion(landscape, f)
        toast("บันทึกบริเวณซับแล้ว (" + (if (landscape) "แนวนอน" else "แนวตั้ง") + ")")
        closePicker(save = true)
    }

    private fun closePicker(save: Boolean) {
        val p = picker ?: return
        runCatching { wm.removeView(p) }
        picker = null
        bubble?.visibility = View.VISIBLE
        lastText = ""
        lastSignature = null
        wakeBubble()
        if (autoBeforePicker && !auto) toggleAuto()
        if (save && !auto) captureAndTranslate(fromAuto = false)
    }

    // ---------------- Drag ----------------

    private fun makeDraggable(
        v: View,
        lp: WindowManager.LayoutParams,
        onTap: () -> Unit,
        onLongPress: (() -> Unit)?,
        onRelease: (() -> Unit)?
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
                    if (v === bubble) {
                        wakeBubble()
                        snapAnim?.cancel()
                    }
                    // ลากกล่องคำแปลในโหมดอิสระ: เปลี่ยนเป็นพิกัดจริงก่อน
                    if (lp.gravity != (Gravity.TOP or Gravity.START)) {
                        val loc = IntArray(2)
                        v.getLocationOnScreen(loc)
                        val (ox, oy) = windowOffset()
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.x = loc[0] - ox
                        lp.y = loc[1] - oy
                    }
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    longFired = false
                    if (onLongPress != null) main.postDelayed(longRunnable, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longRunnable)
                        if (v === bubble) closeMenu()
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        safeUpdate(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRunnable)
                    if (moved) onRelease?.invoke()
                    else if (!longFired) onTap()
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
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        main.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        closeMenu()
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
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

        recognizer?.close()
        mlTranslator?.close()
        gemini?.close()
        refreshTile()
        super.onDestroy()
    }
}
