package com.zrg.tayf

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.media.*
import android.net.Uri
import android.nfc.*
import android.os.*
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.*
import org.json.*
import java.io.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.*
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(fill: String, stroke: String, r: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); setStroke(dp(1), Color.parseColor(stroke)); cornerRadius = dp(r).toFloat()
    }

    private lateinit var previewView: PreviewView
    private lateinit var sensorManager: SensorManager
    private var nfcAdapter: NfcAdapter? = null
    private var az = 0f; private var pt = 0f
    private val rotM = FloatArray(9); private val ori = FloatArray(3)

    private lateinit var statusTxt: TextView; private lateinit var coordsTxt: TextView
    private lateinit var arCard: LinearLayout; private lateinit var arTitle: TextView
    private lateinit var arImg: ImageView; private lateinit var arNote: TextView
    private lateinit var unlockPinBtn: Button; private lateinit var openFileBtn: Button
    private lateinit var createDlg: LinearLayout; private lateinit var noteIn: EditText
    private lateinit var pickedTxt: TextView; private lateinit var nfcBox: LinearLayout
    private lateinit var pinDlg: LinearLayout; private lateinit var pinTitle: TextView
    private lateinit var pinIn: EditText; private lateinit var pinOkBtn: Button

    private var state = "SCAN"
    private var fileBytes: ByteArray? = null; private var fileName = ""; private var fileMime = ""
    private var activeIdx = -1; private var unlockedId: String? = null
    private var outFile: File? = null; private var outMime = ""

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readPickedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCam()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        onNewIntent(intent)
    }

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView)

        val aim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.parseColor("#9938BDF8")) }
        }
        root.addView(aim)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(34), dp(16), 0) }
            background = bg("#D90F172A", "#334155", 16)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val brand = TextView(this).apply { text = "TAYF ZRG"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#38BDF8")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        coordsTxt = TextView(this).apply { text = "0° | 0°"; textSize = 12f; setTextColor(Color.parseColor("#94A3B8")) }
        row.addView(brand); row.addView(coordsTxt)
        statusTxt = TextView(this).apply { text = "وجّه الكاميرا نحو الزاوية لتثبيت أو كشف طَيْف"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0) }
        top.addView(row); top.addView(statusTxt); root.addView(top)

        arCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(dp(295), -2, Gravity.CENTER); background = bg("#EE0F172A", "#38BDF8", 20); elevation = dp(14).toFloat()
        }
        arTitle = TextView(this).apply { textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#38BDF8")); gravity = Gravity.CENTER }
        arImg = ImageView(this).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(dp(255), dp(220)).apply { topMargin = dp(10) }; scaleType = ImageView.ScaleType.FIT_CENTER }
        arNote = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#E2E8F0")); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        unlockPinBtn = Button(this).apply {
            text = "🔢 إدخال الكود السري لفتح المحتوى"; visibility = View.GONE; setTextColor(Color.parseColor("#0F172A")); setTypeface(null, Typeface.BOLD)
            background = bg("#38BDF8", "#38BDF8", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) }
            setOnClickListener { openPin(false) }
        }
        openFileBtn = Button(this).apply {
            text = "فتح الملف المحفوظ"; visibility = View.GONE; setTextColor(Color.parseColor("#0F172A")); setTypeface(null, Typeface.BOLD)
            background = bg("#4ADE80", "#4ADE80", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) }
            setOnClickListener { openOutFile() }
        }
        arCard.addView(arTitle); arCard.addView(arImg); arCard.addView(arNote); arCard.addView(unlockPinBtn); arCard.addView(openFileBtn); root.addView(arCard)

        val bot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(26)) }
        }
        val addBtn = Button(this).apply {
            text = "تثبيت صورة أو ملف في هذا المكان"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            background = bg("#38BDF8", "#38BDF8", 14); layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnClickListener { state = "PICK"; fileBytes = null; noteIn.setText(""); pickedTxt.text = "لم يتم اختيار ملف بعد"; createDlg.visibility = View.VISIBLE }
        }
        val delBtn = Button(this).apply {
            text = "حذف"; textSize = 13f; setTextColor(Color.WHITE); background = bg("#991B1B", "#7F1D1D", 14)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(52)).apply { marginStart = dp(10) }
            setOnClickListener { capsFile().delete(); vaultDir().deleteRecursively(); activeIdx = -1; unlockedId = null; arCard.visibility = View.GONE; statusTxt.text = "تم حذف جميع العناصر" }
        }
        bot.addView(addBtn); bot.addView(delBtn); root.addView(bot)

        createDlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(18), 0, dp(18), 0) }
            background = bg("#F50F172A", "#38BDF8", 20); elevation = dp(20).toFloat()
        }
        val dTitle = TextView(this).apply { text = "إخفاء صورة أو ملف في هذه الزاوية"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) }
        val imgBtn = Button(this).apply { text = "1. اختيار صورة من المعرض"; setTextColor(Color.WHITE); background = bg("#1E293B", "#475569", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) }; setOnClickListener { pickLauncher.launch("image/*") } }
        val flBtn = Button(this).apply { text = "أو اختيار أي ملف (PDF / وثيقة)"; setTextColor(Color.WHITE); background = bg("#1E293B", "#475569", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }; setOnClickListener { pickLauncher.launch("*/*") } }
        pickedTxt = TextView(this).apply { text = "لم يتم اختيار ملف بعد"; textSize = 12f; setTextColor(Color.parseColor("#94A3B8")); setPadding(0, dp(8), 0, dp(8)) }
        noteIn = EditText(this).apply { hint = "ملاحظة سرية (اختياري)..."; setHintTextColor(Color.parseColor("#64748B")); setTextColor(Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(12)); background = bg("#1E293B", "#334155", 12) }
        val chLbl = TextView(this).apply { text = "2. اختر طريقة القفل والحفظ:"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#38BDF8")); setPadding(0, dp(12), 0, dp(8)) }
        val rowLock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bNfc = Button(this).apply {
            text = "🪪 قفل بالبطاقة"; textSize = 13f; setTextColor(Color.parseColor("#0F172A")); setTypeface(null, Typeface.BOLD); background = bg("#38BDF8", "#38BDF8", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { if (validContent()) { createDlg.visibility = View.GONE; nfcBox.visibility = View.VISIBLE; state = "LOCK_NFC" } }
        }
        val bPin = Button(this).apply {
            text = "🔢 قفل بكود"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); background = bg("#2563EB", "#60A5FA", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) }
            setOnClickListener { if (validContent()) openPin(true) }
        }
        rowLock.addView(bNfc); rowLock.addView(bPin)
        val cBtn = Button(this).apply { text = "إلغاء"; setTextColor(Color.parseColor("#CBD5E1")); background = bg("#1E293B", "#334155", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }; setOnClickListener { closeAll() } }
        createDlg.addView(dTitle); createDlg.addView(imgBtn); createDlg.addView(flBtn); createDlg.addView(pickedTxt); createDlg.addView(noteIn); createDlg.addView(chLbl); createDlg.addView(rowLock); createDlg.addView(cBtn)
        root.addView(createDlg)

        nfcBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(24), dp(26), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(22), 0, dp(22), 0) }; background = bg("#FA090D16", "#38BDF8", 22); elevation = dp(24).toFloat()
        }
        nfcBox.addView(TextView(this).apply { text = "📡"; textSize = 40f; gravity = Gravity.CENTER })
        nfcBox.addView(TextView(this).apply { text = "قرّب بطاقة التعريف الوطنية من ظهر الهاتف الآن لقفل الملف"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(16)) })
        nfcBox.addView(Button(this).apply { text = "إلغاء"; setTextColor(Color.WHITE); background = bg("#1E293B", "#475569", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(44)); setOnClickListener { closeAll() } })
        root.addView(nfcBox)

        pinDlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(22), dp(22), dp(22), dp(22))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) }; background = bg("#FA090D16", "#60A5FA", 22); elevation = dp(24).toFloat()
        }
        pinTitle = TextView(this).apply { textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        pinIn = EditText(this).apply { hint = "••••"; setHintTextColor(Color.parseColor("#64748B")); setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setPadding(dp(12), dp(12), dp(12), dp(12)); background = bg("#1E293B", "#3B82F6", 12); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } }
        pinOkBtn = Button(this).apply { text = "تأكيد"; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); background = bg("#2563EB", "#60A5FA", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) }; setOnClickListener { submitPin() } }
        val pinCancel = Button(this).apply { text = "إلغاء"; setTextColor(Color.parseColor("#CBD5E1")); background = bg("#1E293B", "#334155", 12); layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }; setOnClickListener { closeAll() } }
        pinDlg.addView(pinTitle); pinDlg.addView(pinIn); pinDlg.addView(pinOkBtn); pinDlg.addView(pinCancel)
        root.addView(pinDlg)
        setContentView(root)
    }

    private fun startCam() {
        previewView.post {
            val f = ProcessCameraProvider.getInstance(this)
            f.addListener({
                val cp = f.get(); val p = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                try { cp.unbindAll(); cp.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p) } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (nfcAdapter?.isEnabled == true) {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            nfcAdapter?.enableForegroundDispatch(this, pi, null, null)
        }
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this); nfcAdapter?.disableForegroundDispatch(this) }

    @Suppress("DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 160) } catch (_: Exception) {}
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        if (state == "LOCK_NFC") { saveAnchor("NFC_$uid", "NFC", "البطاقة ($uid)"); closeAll() }
        else if (activeIdx >= 0 && !unlockCapsule("NFC_$uid", "البطاقة ($uid)")) Toast.makeText(this, "❌ بطاقة خاطئة!", Toast.LENGTH_SHORT).show()
    }

    private fun validContent(): Boolean {
        if (fileBytes == null && noteIn.text.toString().trim().isEmpty()) { Toast.makeText(this, "اختر صورة أو اكتب نصاً أولاً", Toast.LENGTH_SHORT).show(); return false }
        return true
    }

    private fun openPin(lock: Boolean) {
        createDlg.visibility = View.GONE; pinIn.setText("")
        pinTitle.text = if (lock) "أنشئ كوداً سرياً للقفل" else "أدخل الكود السري للفتح"
        state = if (lock) "PIN_LOCK" else "PIN_UNLOCK"; pinDlg.visibility = View.VISIBLE
    }

    private fun submitPin() {
        val c = pinIn.text.toString().trim()
        if (c.length < 4) { Toast.makeText(this, "أدخل 4 أرقام على الأقل", Toast.LENGTH_SHORT).show(); return }
        if (state == "PIN_LOCK") { saveAnchor("PIN_$c", "PIN", "الكود السري"); closeAll() }
        else if (unlockCapsule("PIN_$c", "الكود السري")) closeAll()
        else { Toast.makeText(this, "❌ كود خاطئ!", Toast.LENGTH_SHORT).show(); pinIn.setText("") }
    }

    private fun saveAnchor(key: String, type: String, label: String) {
        val id = System.currentTimeMillis().toString()
        var saved = ""
        if (fileBytes != null) { saved = "$id.enc"; File(vaultDir(), saved).writeBytes(encBytes(fileBytes!!, key)) }
        val meta = JSONObject().apply { put("txt", noteIn.text.toString().trim()); put("file", saved); put("name", fileName); put("mime", fileMime) }.toString()
        val arr = loadArr().apply { put(JSONObject().apply { put("id", id); put("az", az.toDouble()); put("pt", pt.toDouble()); put("lt", type); put("meta", Base64.encodeToString(encBytes(meta.toByteArray(), key), Base64.NO_WRAP)) }) }
        capsFile().writeText(arr.toString()); unlockedId = null; statusTxt.text = "✓ تم التثبيت والقفل عبر $label"; evalAngle()
    }

    private fun unlockCapsule(key: String, label: String): Boolean {
        val arr = loadArr(); if (activeIdx !in 0 until arr.length()) return false
        val item = arr.getJSONObject(activeIdx)
        val dec = decBytes(Base64.decode(item.getString("meta"), Base64.NO_WRAP), key) ?: return false
        val m = JSONObject(String(dec)); unlockedId = item.getString("id")
        arCard.visibility = View.VISIBLE; unlockPinBtn.visibility = View.GONE; arTitle.text = "🔓 تم الفتح عبر $label"
        arNote.text = m.optString("txt").ifEmpty { m.optString("name") }
        val fn = m.optString("file")
        if (fn.isNotEmpty()) {
            val pb = decBytes(File(vaultDir(), fn).readBytes(), key)
            if (pb != null && m.optString("mime").startsWith("image/")) { arImg.setImageBitmap(BitmapFactory.decodeByteArray(pb, 0, pb.size)); arImg.visibility = View.VISIBLE; openFileBtn.visibility = View.GONE }
            else if (pb != null) { arImg.visibility = View.GONE; outFile = File(cacheDir, m.optString("name")).apply { writeBytes(pb) }; outMime = m.optString("mime"); openFileBtn.visibility = View.VISIBLE }
        }
        return true
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotM, e.values); SensorManager.getOrientation(rotM, ori)
            az = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat(); pt = Math.toDegrees(ori[1].toDouble()).toFloat()
            coordsTxt.text = "${az.toInt()}° \vert{}${pt.toInt()}°"; if (state == "SCAN") evalAngle()
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun evalAngle() {
        val arr = loadArr(); if (arr.length() == 0) { arCard.visibility = View.GONE; activeIdx = -1; return }
        var m = -1; var dx = 0f; var dy = 0f
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dA = ((o.getDouble("az").toFloat() - az + 540f) % 360f) - 180f; val dP = o.getDouble("pt").toFloat() - pt
            if (abs(dA) <= 24f && abs(dP) <= 24f) { m = i; dx = dA * 10f; dy = dP * 10f; break }
        }
        if (m != -1) {
            val it = arr.getJSONObject(m); val id = it.getString("id")
            if (activeIdx != m || unlockedId != id) {
                activeIdx = m
                if (unlockedId != id) {
                    arImg.visibility = View.GONE; openFileBtn.visibility = View.GONE; arTitle.text = "🔒 محتوى مشفر في هذه الزاوية"
                    if (it.optString("lt") == "PIN") { arNote.text = "محمي برمز سري (كود)"; unlockPinBtn.visibility = View.VISIBLE }
                    else { arNote.
