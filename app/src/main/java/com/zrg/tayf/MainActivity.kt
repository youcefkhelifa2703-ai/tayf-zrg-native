package com.zrg.tayf

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.nfc.*
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.*
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import java.io.File
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun box(fill: String, stroke: String) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); setStroke(dp(1), Color.parseColor(stroke)); cornerRadius = dp(4).toFloat()
    }

    private lateinit var arView: ARSceneView
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var statusTxt: TextView; private lateinit var distTxt: TextView
    private lateinit var targetHud: LinearLayout; private lateinit var targetTitle: TextView
    private lateinit var targetImg: ImageView; private lateinit var targetNote: TextView
    private lateinit var unlockPinBtn: Button; private lateinit var openFileBtn: Button
    private lateinit var createDlg: LinearLayout; private lateinit var noteIn: EditText
    private lateinit var pickedTxt: TextView; private lateinit var nfcBox: LinearLayout
    private lateinit var pinDlg: LinearLayout; private lateinit var pinTitle: TextView
    private lateinit var pinIn: EditText

    private var state = "SCAN"
    private var pickedFile: Triple<ByteArray, String, String>? = null
    private var hitPos = floatArrayOf(0f, 0f, -1.5f)
    private var activeIdx = -1; private var unlockedId: String? = null
    private var outFile: File? = null; private var outMime = ""

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedFile = VaultEngine.readUri(this, uri)
            if (pickedFile != null) {
                pickedTxt.text = "المرفق: ${pickedFile!!.second}"
                pickedTxt.setTextColor(Color.parseColor("#10B981"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildTacticalUI()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
        processNfcIntent(intent)
    }

    private fun buildTacticalUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0D10")) }
        arView = ARSceneView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            configureSession { _, config ->
                config.focusMode = Config.FocusMode.AUTO
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            onSessionUpdated = { _, frame ->
                if (frame.camera.trackingState == TrackingState.TRACKING && state == "SCAN") {
                    val camPose = frame.camera.pose
                    val hits = frame.hitTest(width / 2f, height / 2f)
                    if (hits.isNotEmpty()) {
                        val p = hits[0].hitPose
                        hitPos = floatArrayOf(p.tx(), p.ty(), p.tz())
                    } else {
                        val fwd = camPose.zAxis
                        hitPos = floatArrayOf(camPose.tx() - fwd[0] * 1.5f, camPose.ty() - fwd[1] * 1.5f, camPose.tz() - fwd[2] * 1.5f)
                    }
                    evaluateSpatialTargets(camPose.tx(), camPose.ty(), camPose.tz(), camPose.zAxis)
                }
            }
        }
        root.addView(arView)
        root.addView(View(this).apply { layoutParams = FrameLayout.LayoutParams(dp(26), dp(1), Gravity.CENTER); setBackgroundColor(Color.parseColor("#CCFFFFFF")) })
        root.addView(View(this).apply { layoutParams = FrameLayout.LayoutParams(dp(1), dp(26), Gravity.CENTER); setBackgroundColor(Color.parseColor("#CCFFFFFF")) })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(14), dp(34), dp(14), 0) }
            background = box("#E6111418", "#2E353F")
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "TAYF ZRG // SPATIAL AR"; textSize = 14f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#F3F4F6")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        distTxt = TextView(this).apply { text = "DIST: --.-m"; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#9CA3AF")) }
        row.addView(distTxt)
        statusTxt = TextView(this).apply { text = "الحالة: مسح الفضاء المكاني للغرفة..."; textSize = 12f; setTextColor(Color.parseColor("#D1D5DB")); setPadding(0, dp(6), 0, 0) }
        top.addView(row); top.addView(statusTxt); root.addView(top)

        targetHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(dp(295), -2, Gravity.CENTER); background = box("#F2111418", "#4B5563"); elevation = dp(12).toFloat()
        }
        targetTitle = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#D97706")); gravity = Gravity.CENTER }
        targetImg = ImageView(this).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(dp(260), dp(220)).apply { topMargin = dp(10) }; scaleType = ImageView.ScaleType.FIT_CENTER }
        targetNote = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#E5E7EB")); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        unlockPinBtn = Button(this).apply {
            text = "إدخال الرمز السري للفتح"; visibility = View.GONE; textSize = 13f; setTextColor(Color.parseColor("#0B0D10")); setTypeface(null, Typeface.BOLD)
            background = box("#E5E7EB", "#E5E7EB"); layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) }
            setOnClickListener { openPin(false) }
        }
        openFileBtn = Button(this).apply {
            text = "فتح الملف المسترجع"; visibility = View.GONE; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            background = box("#065F46", "#059669"); layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) }
            setOnClickListener { openOutFile() }
        }
        targetHud.addView(targetTitle); targetHud.addView(targetImg); targetHud.addView(targetNote); targetHud.addView(unlockPinBtn); targetHud.addView(openFileBtn); root.addView(targetHud)

        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(26)) } }
        val addBtn = Button(this).apply {
            text = "تثبيت ملف في هذا الموقع المكاني"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0B0D10"))
            background = box("#E5E7EB", "#FFFFFF"); layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
            setOnClickListener { state = "PICK"; pickedFile = null; noteIn.setText(""); pickedTxt.text = "لم يتم تحديد ملف"; createDlg.visibility = View.VISIBLE }
        }
        val delBtn = Button(this).apply {
            text = "إزالة"; textSize = 13f; setTextColor(Color.parseColor("#FCA5A5")); background = box("#1F1215", "#7F1D1D")
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(50)).apply { marginStart = dp(8) }
            setOnClickListener { VaultEngine.clearAll(this@MainActivity); activeIdx = -1; unlockedId = null; targetHud.visibility = View.GONE; statusTxt.text = "الحالة: تم مسح جميع النقاط المكانية" }
        }
        bot.addView(addBtn); bot.addView(delBtn); root.addView(bot)

        createDlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(16), 0, dp(16), 0) }; background = box("#FA111418", "#4B5563"); elevation = dp(20).toFloat()
        }
        createDlg.addView(TextView(this).apply { text = "إيداع وتشفير محتوى في الإحداثي الحالي"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#F9FAFB")) })
        createDlg.addView(Button(this).apply { text = "تحديد صورة من الجهاز"; textSize = 13f; setTextColor(Color.parseColor("#F3F4F6")); background = box("#1C2128", "#374151"); layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) }; setOnClickListener { pickLauncher.launch("image/*") } })
        createDlg.addView(Button(this).apply { text = "تحديد مستند أو ملف (PDF / وثائق)"; textSize = 13f; setTextColor(Color.parseColor("#F3F4F6")); background = box("#1C2128", "#374151"); layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }; setOnClickListener { pickLauncher.launch("*/*") } })
        pickedTxt = TextView(this).apply { text = "لم يتم تحديد ملف"; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, dp(8), 0, dp(8)) }
        noteIn = EditText(this).apply { hint = "إدخال بيان أو ملاحظة سرية..."; setHintTextColor(Color.parseColor("#6B7280")); setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = box("#161A20", "#374151") }
        createDlg.addView(pickedTxt); createDlg.addView(noteIn)
        createDlg.addView(TextView(this).apply { text = "تحديد بروتوكول القفل والحفظ الدائم:"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#D97706")); setPadding(0, dp(14), 0, dp(8)) })

        val rowLock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowLock.addView(Button(this).apply {
            text = "قفل بالبطاقة (NFC)"; textSize = 12f; setTextColor(Color.parseColor("#0B0D10")); setTypeface(null, Typeface.BOLD); background = box("#E5E7EB", "#FFFFFF")
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                if (nfcAdapter == null) Toast.makeText(this@MainActivity, "هذا الهاتف لا يدعم NFC — استخدم الرمز السري", Toast.LENGTH_LONG).show()
                else if (validContent()) { createDlg.visibility = View.GONE; nfcBox.visibility = View.VISIBLE; state = "LOCK_NFC" }
            }
        })
        rowLock.addView(Button(this).apply {
            text = "قفل برمز سري (PIN)"; textSize = 12f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); background = box("#1F2937", "#6B7280")
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) }
            setOnClickListener { if (validContent()) openPin(true) }
        })
        createDlg.addView(rowLock)
        createDlg.addView(Button(this).apply { text = "إلغاء الأمر"; textSize = 12f; setTextColor(Color.parseColor("#9CA3AF")); background = box("#111418", "#374151"); layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) }; setOnClickListener { closeAll() } })
        root.addView(createDlg)

        nfcBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(22), dp(24), dp(22), dp(22))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(20), 0, dp(20), 0) }; background = box("#FA0B0D10", "#D97706"); elevation = dp(24).toFloat()
        }
        nfcBox.addView(TextView(this).apply { text = "[ NFC / ISO-14443 ]"; textSize = 14f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#D97706")); gravity = Gravity.CENTER })
        nfcBox.addView(TextView(this).apply { text = "قرّب بطاقة التعريف الوطنية من ظهر الجهاز لإتمام القفل والتشفير"; textSize = 14f; setTextColor(Color.parseColor("#F3F4F6")); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(18)) })
        nfcBox.addView(Button(this).apply { text = "إلغاء"; textSize = 12f; setTextColor(Color.WHITE); background = box("#1C2128", "#4B5563"); layoutParams = LinearLayout.LayoutParams(-1, dp(42)); setOnClickListener { closeAll() } })
        root.addView(nfcBox)

        pinDlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(22), dp(22), dp(22), dp(22))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(22), 0, dp(22), 0) }; background = box("#FA0B0D10", "#6B7280"); elevation = dp(24).toFloat()
        }
        pinTitle = TextView(this).apply { textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#F3F4F6")); gravity = Gravity.CENTER }
        pinIn = EditText(this).apply { hint = "0000"; setHintTextColor(Color.parseColor("#4B5563")); setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setPadding(dp(12), dp(12), dp(12), dp(12)); background = box("#161A20", "#4B5563"); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } }
        pinDlg.addView(pinTitle); pinDlg.addView(pinIn)
        pinDlg.addView(Button(this).apply { text = "تأكيد"; textSize = 13f; setTextColor(Color.parseColor("#0B0D10")); setTypeface(null, Typeface.BOLD); background = box("#E5E7EB", "#FFFFFF"); layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) }; setOnClickListener { submitPin() } })
        pinDlg.addView(Button(this).apply { text = "إلغاء"; textSize = 12f; setTextColor(Color.parseColor("#9CA3AF")); background = box("#111418", "#374151"); layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) }; setOnClickListener { closeAll() } })
        root.addView(pinDlg)
        setContentView(root)
    }

    private fun evaluateSpatialTargets(cx: Float, cy: Float, cz: Float, zAxis: FloatArray) {
        val arr = VaultEngine.loadAll(this)
        if (arr.length() == 0) { runOnUiThread { targetHud.visibility = View.GONE }; activeIdx = -1; return }

        var matched = -1; var dist = 999f
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dx = o.getDouble("x").toFloat() - cx
            val dy = o.getDouble("y").toFloat() - cy
            val dz = o.getDouble("z").toFloat() - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val dot = (-zAxis[0] * dx - zAxis[1] * dy - zAxis[2] * dz) / (d + 0.001f)
            if (dot > 0.82f && d < 8.0f && d < dist) { matched = i; dist = d }
        }

        runOnUiThread {
            if (matched != -1) {
                distTxt.text = "DIST: ${"%.1f".format(dist)}m"
                val it = arr.getJSONObject(matched); val id = it.getString("id")
                val scale = (1.3f / (dist + 0.6f)).coerceIn(0.65f, 1.05f)
                targetHud.scaleX = scale; targetHud.scaleY = scale

                if (activeIdx != matched || unlockedId != id) {
                    activeIdx = matched
                    if (unlockedId != id) {
                        targetImg.visibility = View.GONE; openFileBtn.visibility = View.GONE
                        targetTitle.text = if (dist <= 1.3f) "[ في النطاق // جاهز للفتح ]" else "[ هدف مكاني // اقترب للفتح ]"
                        targetTitle.setTextColor(if (dist <= 1.3f) Color.parseColor("#D97706") else Color.parseColor("#9CA3AF"))
                        if (it.optString("lt") == "PIN") {
                            targetNote.text = "المسافة: ${"%.1f".format(dist)}م — محمي برمز سري"
                            unlockPinBtn.visibility = if (dist <= 1.5f) View.VISIBLE else View.GONE
                        } else {
                            targetNote.text = "المسافة: ${"%.1f".format(dist)}م — قرّب البطاقة الوطنية عند الاقتراب"
                            unlockPinBtn.visibility = View.GONE
                        }
                    }
                }
                targetHud.visibility = View.VISIBLE
            } else {
                distTxt.text = "DIST: --.-m"; activeIdx = -1; unlockedId = null; targetHud.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter?.isEnabled == true) {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            nfcAdapter?.enableForegroundDispatch(this, pi, null, null)
        }
    }

    override fun onPause() { super.onPause(); nfcAdapter?.disableForegroundDispatch(this) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); processNfcIntent(intent) }

    @Suppress("DEPRECATION")
    private fun processNfcIntent(intent: Intent?) {
        val tag: Tag = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        if (state == "LOCK_NFC") {
            VaultEngine.saveSpatialItem(this, hitPos[0], hitPos[1], hitPos[2], "NFC", "NFC_$uid", noteIn.text.toString().trim(), pickedFile)
            unlockedId = null; statusTxt.text = "الحالة: تم التثبيت المكاني [ID:$uid]"; closeAll()
        } else if (activeIdx >= 0 && !tryUnlock("NFC_$uid", "ID:$uid")) {
            Toast.makeText(this, "مرفوض: البطاقة غير مطابقة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validContent(): Boolean {
        if (pickedFile == null && noteIn.text.toString().trim().isEmpty()) { Toast.makeText(this, "يرجى تحديد ملف أو إدخال بيان أولاً", Toast.LENGTH_SHORT).show(); return false }
        return true
    }

    private fun openPin(lock: Boolean) {
        createDlg.visibility = View.GONE; pinIn.setText("")
        pinTitle.text = if (lock) "تعيين الرمز السري للقفل" else "إدخال الرمز السري لفك التشفير"
        state = if (lock) "PIN_LOCK" else "PIN_UNLOCK"; pinDlg.visibility = View.VISIBLE
    }

    private fun submitPin() {
        val c = pinIn.text.toString().trim()
        if (c.length < 4) { Toast.makeText(this, "الحد الأدنى 4 أرقام", Toast.LENGTH_SHORT).show(); return }
        if (state == "PIN_LOCK") {
            VaultEngine.saveSpatialItem(this, hitPos[0], hitPos[1], hitPos[2], "PIN", "PIN_$c", noteIn.text.toString().trim(), pickedFile)
            unlockedId = null; statusTxt.text = "الحالة: تم التثبيت المكاني [PIN-KEY]"; closeAll()
        } else if (tryUnlock("PIN_$c", "PIN-KEY")) closeAll()
        else { Toast.makeText(this, "خطأ: الرمز السري غير صحيح", Toast.LENGTH_SHORT).show(); pinIn.setText("") }
    }

    private fun tryUnlock(key: String, label: String): Boolean {
        val arr = VaultEngine.loadAll(this); if (activeIdx !in 0 until arr.length()) return false
        val item = arr.getJSONObject(activeIdx)
        val res = VaultEngine.unlockItem(this, item, key) ?: return false
        val (m, pb) = res; unlockedId = item.getString("id")
        targetHud.visibility = View.VISIBLE; unlockPinBtn.visibility = View.GONE
        targetTitle.text = "[ تم التحقق // $label ]"; targetTitle.setTextColor(Color.parseColor("#10B981"))
        targetNote.text = m.optString("txt").ifEmpty { m.optString("name") }
        if (pb != null && m.optString("mime").startsWith("image/")) {
            targetImg.setImageBitmap(BitmapFactory.decodeByteArray(pb, 0, pb.size)); targetImg.visibility = View.VISIBLE; openFileBtn.visibility = View.GONE
        } else if (pb != null) {
            targetImg.visibility = View.GONE; outFile = File(cacheDir, m.optString("name")).apply { writeBytes(pb) }; outMime = m.optString("mime"); openFileBtn.visibility = View.VISIBLE
        }
        statusTxt.text = "الحالة: تم فك التشفير بنجاح"; return true
    }

    private fun closeAll() { state = "SCAN"; createDlg.visibility = View.GONE; nfcBox.visibility = View.GONE; pinDlg.visibility = View.GONE }

    private fun openOutFile() {
        val f = outFile ?: return
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply { setDataAndType(FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", f), outMime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "فتح الملف")) } catch (_: Exception) {}
    }
}
