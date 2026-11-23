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
import android.nfc.*
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.*
import java.io.File
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun box(fill: String, stroke: String) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); setStroke(dp(1), Color.parseColor(stroke)); cornerRadius = dp(4).toFloat()
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
    private lateinit var pinIn: EditText

    private var state = "SCAN"
    private var pickedFile: Triple<ByteArray, String, String>? = null
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
        buildOfficialUI()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCam()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        processNfcIntent(intent)
    }

    private fun buildOfficialUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0D10")) }
        previewView = PreviewView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView)

        root.addView(View(this).apply { layoutParams = FrameLayout.LayoutParams(dp(28), dp(1), Gravity.CENTER); setBackgroundColor(Color.parseColor("#B3FFFFFF")) })
        root.addView(View(this).apply { layoutParams = FrameLayout.LayoutParams(dp(1), dp(28), Gravity.CENTER); setBackgroundColor(Color.parseColor("#B3FFFFFF")) })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(14), dp(34), dp(14), 0) }
            background = box("#E6111418", "#2E353F")
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "TAYF ZRG // SYSTEM"; textSize = 14f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#F3F4F6")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        coordsTxt = TextView(this).apply { text = "AZ:000° PT:00°"; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#9CA3AF")) }
        row.addView(coordsTxt)
        statusTxt = TextView(this).apply { text = "الحالة: جاهز للمسح والتثبيت الميداني"; textSize = 12f; setTextColor(Color.parseColor("#D1D5DB")); setPadding(0, dp(6), 0, 0) }
        top.addView(row); top.addView(statusTxt); root.addView(top)

        arCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(dp(295), -2, Gravity.CENTER); background = box("#F2111418", "#4B5563"); elevation = dp(12).toFloat()
        }
        arTitle = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#D97706")); gravity = Gravity.CENTER }
        arImg = ImageView(this).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(dp(260), dp(220)).apply { topMargin = dp(10) }; scaleType = ImageView.ScaleType.FIT_CENTER }
        arNote = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#E5E7EB")); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
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
        arCard.addView(arTitle); arCard.addView(arImg); arCard.addView(arNote); arCard.addView(unlockPinBtn); arCard.addView(openFileBtn); root.addView(arCard)

        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(26)) } }
        val addBtn = Button(this).apply {
            text = "تثبيت ملف أو صورة في هذه النقطة"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0B0D10"))
            background = box("#E5E7EB", "#FFFFFF"); layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
            setOnClickListener { state = "PICK"; pickedFile = null; noteIn.setText(""); pickedTxt.text = "لم يتم تحديد ملف"; createDlg.visibility = View.VISIBLE }
        }
        val delBtn = Button(this).apply {
            text = "إزالة"; textSize = 13f; setTextColor(Color.parseColor("#FCA5A5")); background = box("#1F1215", "#7F1D1D")
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(50)).apply { marginStart = dp(8) }
            setOnClickListener { VaultEngine.clearAll(this@MainActivity); activeIdx = -1; unlockedId = null; arCard.visibility = View.GONE; statusTxt.text = "الحالة: تم مسح السجل بالكامل" }
        }
        bot.addView(addBtn); bot.addView(delBtn); root.addView(bot)

        createDlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(16), 0, dp(16), 0) }; background = box("#FA111418", "#4B5563"); elevation = dp(20).toFloat()
        }
        createDlg.addView(TextView(this).apply { text = "إيداع وتشفير محتوى في الموقع الحالي"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#F9FAFB")) })
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
            setOnClickListener { if (validContent()) { createDlg.visibility = View.GONE; nfcBox.visibility = View.VISIBLE; state = "LOCK_NFC" } }
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
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); processNfcIntent(intent) }

    @Suppress("DEPRECATION")
    private fun processNfcIntent(intent: Intent?) {
        val tag: Tag = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        if (state == "LOCK_NFC") {
            VaultEngine.saveAnchor(this, az, pt, "NFC", "NFC_$uid", noteIn.text.toString().trim(), pickedFile)
            unlockedId = null; statusTxt.text = "الحالة: تم التثبيت والتشفير [ID:$uid]"; closeAll(); evalAngle()
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
            VaultEngine.saveAnchor(this, az, pt, "PIN", "PIN_$c", noteIn.text.toString().trim(), pickedFile)
            unlockedId = null; statusTxt.text = "الحالة: تم التثبيت والتشفير [PIN-KEY]"; closeAll(); evalAngle()
        } else if (tryUnlock("PIN_$c", "PIN-KEY")) closeAll()
        else { Toast.makeText(this, "خطأ: الرمز السري غير صحيح", Toast.LENGTH_SHORT).show(); pinIn.setText("") }
    }

    private fun tryUnlock(key: String, label: String): Boolean {
        val arr = VaultEngine.loadAll(this); if (activeIdx !in 0 until arr.length()) return false
        val item = arr.getJSONObject(activeIdx)
        val res = VaultEngine.unlockAnchor(this, item, key) ?: return false
        val (m, pb) = res; unlockedId = item.getString("id")
        arCard.visibility = View.VISIBLE; unlockPinBtn.visibility = View.GONE
        arTitle.text = "[ تم التحقق // $label ]"; arTitle.setTextColor(Color.parseColor("#10B981"))
        arNote.text = m.optString("txt").ifEmpty { m.optString("name") }
        if (pb != null && m.optString("mime").startsWith("image/")) {
            arImg.setImageBitmap(BitmapFactory.decodeByteArray(pb, 0, pb.size)); arImg.visibility = View.VISIBLE; openFileBtn.visibility = View.GONE
        } else if (pb != null) {
            arImg.visibility = View.GONE; outFile = File(cacheDir, m.optString("name")).apply { writeBytes(pb) }; outMime = m.optString("mime"); openFileBtn.visibility = View.VISIBLE
        }
        statusTxt.text = "الحالة: تم فك التشفير بنجاح"; return true
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotM, e.values); SensorManager.getOrientation(rotM, ori)
            az = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat(); pt = Math.toDegrees(ori[1].toDouble()).toFloat()
            coordsTxt.text = "AZ:${az.toInt()}° PT:${pt.toInt()}°"; if (state == "SCAN") evalAngle()
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun evalAngle() {
        val arr = VaultEngine.loadAll(this); if (arr.length() == 0) { arCard.visibility = View.GONE; activeIdx = -1; return }
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
                    arImg.visibility = View.GONE; openFileBtn.visibility = View.GONE
                    arTitle.text = "[ نقطة مشفرة مرصودة ]"; arTitle.setTextColor(Color.parseColor("#D97706"))
                    if (it.optString("lt") == "PIN") { arNote.text = "الحماية: رمز سري (PIN)"; unlockPinBtn.visibility = View.VISIBLE }
                    else { arNote.text = "الحما
