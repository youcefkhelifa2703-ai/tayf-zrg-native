package com.zrg.tayf

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var sensorManager: SensorManager
    private var nfcAdapter: NfcAdapter? = null

    private var currentAzimuth = 0f
    private var currentPitch = 0f
    private val rotMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private lateinit var statusText: TextView
    private lateinit var coordsText: TextView
    private lateinit var arCard: LinearLayout
    private lateinit var arTitle: TextView
    private lateinit var arImage: ImageView
    private lateinit var arNote: TextView
    private lateinit var unlockPinBtn: Button
    private lateinit var openFileBtn: Button

    private lateinit var createDialog: LinearLayout
    private lateinit var noteInput: EditText
    private lateinit var pickedStatus: TextView
    private lateinit var nfcWaitBox: LinearLayout
    private lateinit var nfcWaitText: TextView

    private lateinit var pinDialog: LinearLayout
    private lateinit var pinDialogTitle: TextView
    private lateinit var pinInput: EditText
    private lateinit var pinConfirmBtn: Button

    private var appState = "SCANNING" // SCANNING | PICKING | WAITING_LOCK_NFC | PIN_LOCK | PIN_UNLOCK
    private var selectedFileBytes: ByteArray? = null
    private var selectedFileName: String = ""
    private var selectedMimeType: String = ""
    private var activeCapsuleIndex = -1
    private var unlockedItemId: String? = null
    private var lastUnlockedFile: File? = null
    private var lastUnlockedMime: String = ""

    private val pickAnyFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) processSelectedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildOfficialUI()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHighResCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
        handleIncomingIntent(intent)
    }

    private fun buildOfficialUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        val reticle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#9938BDF8"))
            }
        }
        root.addView(reticle)

        // الشريط العلوي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(36), dp(16), 0) }
            background = roundBg("#D90F172A", "#334155", 16)
        }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "TAYF ZRG"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        coordsText = TextView(this).apply {
            text = "0° | 0°"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        headerRow.addView(title)
        headerRow.addView(coordsText)

        statusText = TextView(this).apply {
            text = "وجّه الكاميرا نحو أي زاوية لتثبيت أو كشف طَيْف"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
        }
        topBar.addView(headerRow)
        topBar.addView(statusText)
        root.addView(topBar)

        // بطاقة الطَيْف العائمة في المكان (AR Card)
        arCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER)
            background = roundBg("#EE0F172A", "#38BDF8", 20)
            elevation = dp(14).toFloat()
        }
        arTitle = TextView(this).apply {
            text = "🔒 طَيْف مشفر مثبت في هذا المكان"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }
        arImage = ImageView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(230)).apply { topMargin = dp(12) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        arNote = TextView(this).apply {
            text = "قرّب بطاقة التعريف الوطنية لفتحه"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        unlockPinBtn = Button(this).apply {
            text = "🔢 إدخال الكود السري لفتح المحتوى"
            visibility = View.GONE
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, Typeface.BOLD)
            background = roundBg("#38BDF8", "#38BDF8", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) }
            setOnClickListener { openPinDialog(isLocking = false) }
        }
        openFileBtn = Button(this).apply {
            text = "فتح الملف المحفوظ"
            visibility = View.GONE
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, Typeface.BOLD)
            background = roundBg("#4ADE80", "#4ADE80", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) }
            setOnClickListener { openDecryptedExternalFile() }
        }
        arCard.addView(arTitle)
        arCard.addView(arImage)
        arCard.addView(arNote)
        arCard.addView(unlockPinBtn)
        arCard.addView(openFileBtn)
        root.addView(arCard)

        // شريط التحكم السفلي
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(28)) }
        }
        val hideBtn = Button(this).apply {
            text = "تثبيت صورة أو ملف في هذا المكان"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            background = roundBg("#38BDF8", "#38BDF8", 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnClickListener { openCreatePanel() }
        }
        val clearBtn = Button(this).apply {
            text = "حذف"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundBg("#991B1B", "#7F1D1D", 14)
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(52)).apply { marginStart = dp(10) }
            setOnClickListener {
                getCapsFile().delete()
                getVaultDir().deleteRecursively()
                activeCapsuleIndex = -1
                unlockedItemId = null
                arCard.visibility = View.GONE
                statusText.text = "تم حذف جميع العناصر المثبتة"
            }
        }
        bottomBar.addView(hideBtn)
        bottomBar.addView(clearBtn)
        root.addView(bottomBar)

        // نافذة اختيار الصورة أو الملف + اختيار طريقة القفل
        createDialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(18), 0, dp(18), 0) }
            background = roundBg("#F50F172A", "#38BDF8", 20)
            elevation = dp(20).toFloat()
        }
        val dlgTitle = TextView(this).apply {
            text = "إخفاء صورة أو ملف في هذه الزاوية"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val pickImgBtn = Button(this).apply {
            text = "1. اختيار صورة من المعرض"
            setTextColor(Color.WHITE)
            background = roundBg("#1E293B", "#475569", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) }
            setOnClickListener { pickAnyFileLauncher.launch("image/*") }
        }
        val pickFileBtn = Button(this).apply {
            text = "أو اختيار أي ملف (PDF / وثيقة / صوت)"
            setTextColor(Color.WHITE)
            background = roundBg("#1E293B", "#475569", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }
            setOnClickListener { pickAnyFileLauncher.launch("*/*") }
        }
        pickedStatus = TextView(this).apply {
            text = "لم يتم اختيار ملف بعد"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(8))
        }
        noteInput = EditText(this).apply {
            hint = "اكتب ملاحظة سرية (اختياري)..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundBg("#1E293B", "#334155", 12)
        }

        val lockChoiceLabel = TextView(this).apply {
            text = "2. اختر طريقة القفل والحفظ الدائم:"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(14), 0, dp(8))
        }

        val lockButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val lockNfcBtn = Button(this).apply {
            text = "🪪 قفل بالبطاقة"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, Typeface.BOLD)
            background = roundBg("#38BDF8", "#38BDF8", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { requestCardToLock() }
        }
        val lockPinBtn = Button(this).apply {
            text = "🔢 قفل بكود"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = roundBg("#2563EB", "#60A5FA", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) }
            setOnClickListener { requestPinToLock() }
        }
        lockButtonsRow.addView(lockNfcBtn)
        lockButtonsRow.addView(lockPinBtn)

        val cancelBtn = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundBg("#1E293B", "#334155", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }
            setOnClickListener { closeAllDialogs() }
        }
        createDialog.addView(dlgTitle)
        createDialog.addView(pickImgBtn)
        createDialog.addView(pickFileBtn)
        createDialog.addView(pickedStatus)
        createDialog.addView(noteInput)
        createDialog.addView(lockChoiceLabel)
        createDialog.addView(lockButtonsRow)
        createDialog.addView(cancelBtn)
        root.addView(createDialog)

        // نافذة انتظار البطاقة (NFC)
        nfcWaitBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(28), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(22), 0, dp(22), 0) }
            background = roundBg("#FA090D16", "#38BDF8", 22)
            elevation = dp(24).toFloat()
        }
        val nfcIcon = TextView(this).apply {
            text = "📡"
            textSize = 42f
            gravity = Gravity.CENTER
        }
        nfcWaitText = TextView(this).apply {
            text = "يرجى تقريب بطاقة التعريف الوطنية من ظهر الهاتف الآن..."
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        }
        val cancelNfcBtn = Button(this).apply {
            text = "إلغاء العملية"
            setTextColor(Color.WHITE)
            background = roundBg("#1E293B", "#475569", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46))
            setOnClickListener { closeAllDialogs() }
        }
        nfcWaitBox.addView(nfcIcon)
        nfcWaitBox.addView(nfcWaitText)
        nfcWaitBox.addView(cancelNfcBtn)
        root.addView(nfcWaitBox)

        // نافذة إدخال الكود السري (PIN)
        pinDialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) }
            background = roundBg("#FA090D16", "#60A5FA", 22)
            elevation = dp(24).toFloat()
        }
        pinDialogTitle = TextView(this).apply {
            text = "أدخل الرمز السري (الكود)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        pinInput = EditText(this).apply {
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundBg("#1E293B", "#3B82F6", 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        pinConfirmBtn = Button(this).apply {
            text = "تأكيد"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = roundBg("#2563EB", "#60A5FA", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) }
            setOnClickListener { handlePinSubmit() }
        }
        val cancelPinBtn = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundBg("#1E293B", "#334155", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }
            setOnClickListener { closeAllDialogs() }
        }
        pinDialog.addView(pinDialogTitle)
        pinDialog.addView(pinInput)
        pinDialog.addView(pinConfirmBtn)
        pinDialog.addView(cancelPinBtn)
        root.addView(pinDialog)

        setContentView(root)
    }

    private fun startHighResCamera() {
        previewView.post {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (nfcAdapter != null && nfcAdapter!!.isEnabled) {
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Exception) {}
            val uidHex = tag.id.joinToString("") { "%02X".format(it) }
            handleNfcCard(uidHex)
        }
    }

    private fun hasValidContent(): Boolean {
        val textVal = noteInput.text.toString().trim()
        if (selectedFileBytes == null && textVal.isEmpty()) {
            T
