package com.subtrans.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Date

/** หน้าประวัติคำแปล: แตะรายการเพื่อคัดลอก และส่งออกเป็น .txt / .srt ได้ */
class HistoryActivity : Activity() {

    companion object {
        private const val REQ_EXPORT = 101
    }

    private var items: List<HistoryStore.Entry> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private var exportAsSrt = false

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ประวัติคำแปล"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "ประวัติคำแปล"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF212121.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "แตะรายการเพื่อคัดลอก"
            setTextColor(0xFF666666.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "ส่งออก"
            isAllCaps = false
            setOnClickListener { showExportDialog() }
        })
        bar.addView(Button(this).apply {
            text = "ล้างทั้งหมด"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setMessage("ลบประวัติคำแปลทั้งหมด?")
                    .setPositiveButton("ลบ") { _, _ ->
                        HistoryStore.clear(this@HistoryActivity)
                        reload()
                    }
                    .setNegativeButton("ยกเลิก", null)
                    .show()
            }
        })
        root.addView(bar)

        empty = TextView(this).apply {
            text = "ยังไม่มีประวัติ"
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, 0)
            setTextColor(0xFF999999.toInt())
        }
        root.addView(empty)

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val row = (convert as? LinearLayout) ?: LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(TextView(context).apply { textSize = 12f; setTextColor(0xFF999999.toInt()) })
                    addView(TextView(context).apply { textSize = 14f; setTextColor(0xFF555555.toInt()) })
                    addView(TextView(context).apply {
                        textSize = 17f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
                    })
                }
                val e = items[p]
                (row.getChildAt(0) as TextView).text =
                    DateFormat.format("d/M HH:mm:ss", Date(e.time))
                (row.getChildAt(1) as TextView).text = e.source
                (row.getChildAt(2) as TextView).text = e.thai
                return row
            }
        }
        val list = ListView(this).apply {
            adapter = this@HistoryActivity.adapter
            setOnItemClickListener { _, _, p, _ ->
                val e = items[p]
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("คำแปล", "${e.source}\n${e.thai}"))
                Toast.makeText(this@HistoryActivity, "คัดลอกแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showExportDialog() {
        val raw = HistoryStore.load(this)
        if (raw.isEmpty()) {
            Toast.makeText(this, "ยังไม่มีประวัติให้ส่งออก", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("เลือกรูปแบบไฟล์")
            .setItems(arrayOf("ข้อความ (.txt)", "ไฟล์ซับไตเติล (.srt)")) { _, which ->
                exportAsSrt = (which == 1)
                val ext = if (exportAsSrt) "srt" else "txt"
                val stamp = DateFormat.format("yyyyMMdd_HHmm", Date())
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "subtranslate_$stamp.$ext")
                }
                startActivityForResult(intent, REQ_EXPORT)
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val entries = HistoryStore.load(this)
        val content = if (exportAsSrt) HistoryStore.formatSrt(entries) else HistoryStore.formatTxt(entries)
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
        }.onSuccess {
            Toast.makeText(this, "ส่งออกไฟล์สำเร็จ", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "ส่งออกไม่สำเร็จ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items = HistoryStore.load(this).asReversed() // ใหม่สุดอยู่บน
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }
}
