package com.zrg.tayf

import android.Manifest
import android.content.Context
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
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener, NfcAdapter.ReaderCallback {

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
    private lateinit var createDialog: LinearLayout
    private lateinit var noteInput: EditText
    private lateinit var pickedStatus: TextView

    private var isCreatingMode = false
    private var selectedImageBase64: String? = null
    private var activeCapsuleIndex = -1

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageBase64 = compressUriToBase64(uri)
            pickedStatus.text = "✓ تم إرفاق الصورة بنجاح"
            pickedStatus.setTextColor(Color.parseColor("#38BDF8"))
        }
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
    }

    private fun buildOfficialUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        val reticle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#8038BDF8"))
            }
        }
        root.addView(reticle)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(36), dp(16), 0) }
            background = roundBg("#CC0F172A", "#334155", 16)
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
            text = "وجّه الكاميرا نحو الزاوية المطلوبة"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
        }
        topBar.addView(headerRow)
        topBar.addView(statusText)
        root.addView(topBar)

        arCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(dp(290), -2, Gravity.CENTER)
            background = roundBg("#E60F172A", "#38BDF8", 20)
            elevation = dp(12).toFloat()
        }
        arTitle = TextView(this).apply {
            text = "محتوى مشفر مثبت في هذه الزاوية"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }
        arImage = ImageView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(220)).apply { topMargin = dp(10) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        arNote = TextView(this).apply {
            text = "قرّب بطاقة التعريف الوطنية من ظهر الهاتف لفتحه"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        arCard.addView(arTitle)
        arCard.addView(arImage)
        arCard.addView(arNote)
        root.addView(arCard)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(28)) }
        }
        val hideBtn = Button(this).apply {
            text = "تثبيت طَيْف في هذه الزاوية"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            background = roundBg("#38BDF8", "#38BDF8", 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnClickListener { openCreatePanel() }
        }
        val clearBtn = Button(this).apply {
            text = "مسح"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundBg("#991B1B", "#7F1D1D", 14)
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(52)).apply { marginStart = dp(10) }
            setOnClickListener {
                getCapsFile().delete()
                activeCapsuleIndex = -1
                arCard.visibility = View.GONE
                statusText.text = "تم مسح جميع نقاط الطَيْف"
            }
        }
        bottomBar.addView(hideBtn)
        bottomBar.addView(clearBtn)
        root.addView(bottomBar)

        createDialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(20), 0, dp(20), 0) }
            background = roundBg("#F20F172A", "#38BDF8", 20)
            elevation = dp(20).toFloat()
        }
        val dlgTitle = TextView(this).apply {
            text = "إخفاء محتوى في هذه الزاوية"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val pickImgBtn = Button(this).apply {
            text = "اختيار صورة من المعرض"
            setTextColor(Color.WHITE)
            background = roundBg("#1E293B", "#475569", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) }
            setOnClickListener { pickImageLauncher.launch("image/*") }
        }
        pickedStatus = TextView(this).apply {
            text = "لم يتم اختيار صورة بعد (اختياري)"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(6), 0, dp(10))
        }
        noteInput = EditText(this).apply {
            hint = "اكتب ملاحظة أو وصفاً سرياً..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundBg("#1E293B", "#334155", 12)
        }
        val nfcHint = TextView(this).apply {
            text = "قرّب بطاقة التعريف الوطنية من ظهر الهاتف الآن للقفل والحفظ"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(14), dp(10), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            background = roundBg("#172554", "#38BDF8", 12)
        }
        val cancelBtn = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundBg("#1E293B", "#334155", 12)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) }
            setOnClickListener { closeCreatePanel() }
        }
        createDialog.addView(dlgTitle)
        createDialog.addView(pickImgBtn)
        createDialog.addView(pickedStatus)
        createDialog.addView(noteInput)
        createDialog.addView(nfcHint)
        createDialog.addView(cancelBtn)
        root.addView(createDialog)

        setContentView(root)
    }

    private fun startHighResCamera() {
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

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val uidHex = tag.id.joinToString("") { "%02X".format(it) }
        runOnUiThread { handleNfcCard(uidHex) }
    }

    private fun handleNfcCard(uid: String) {
        if (isCreatingMode) {
            val textVal = noteInput.text.toString().trim()
            if (selectedImageBase64 == null && textVal.isEmpty()) {
                Toast.makeText(this, "اختر صورة أو اكتب نصاً أولاً", Toast.LENGTH_SHORT).show()
                return
            }
            val payload = JSONObject().apply {
                put("img", selectedImageBase64 ?: "")
                put("txt", textVal)
            }.toString()
            val arr = loadCapsArray()
            arr.put(JSONObject().apply {
                put("az", currentAzimuth.toDouble())
                put("pt", currentPitch.toDouble())
                put("enc", encryptAes(payload, uid))
            })
            getCapsFile().writeText(arr.toString())
            closeCreatePanel()
            statusText.text = "تم إخفاء الطَيْف وقفله بالبطاقة ($uid)"
            evaluateAngle()
        } else {
            val arr = loadCapsArray()
            if (arr.length() == 0) {
                statusText.text = "تم رصد البطاقة ($uid) — لا يوجد محتوى مخفي"
                return
            }
            val idx = if (activeCapsuleIndex in 0 until arr.length()) activeCapsuleIndex else arr.length() - 1
            val decrypted = decryptAes(arr.getJSONObject(idx).getString("enc"), uid)
            if (decrypted != null) {
                val obj = JSONObject(decrypted)
                val imgB64 = obj.optString("img", "")
                val txt = obj.optString("txt", "")
                arCard.visibility = View.VISIBLE
                arTitle.text = "تم التحقق من الهوية ($uid)"
                if (imgB64.isNotEmpty()) {
                    val bytes = Base64.decode(imgB64, Base64.DEFAULT)
                    arImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    arImage.visibility = View.VISIBLE
                } else arImage.visibility = View.GONE
                arNote.text = if (txt.isNotEmpty()) txt else "صورة مشفرة مخفية في هذه الزاوية"
                statusText.text = "تم فتح المحتوى بنجاح"
            } else {
                statusText.text = "البطاقة غير مطابقة لهذا القفل"
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientationAngles)
            currentAzimuth = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360) % 360).toFloat()
            currentPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            coordsText.text = "${currentAzimuth.toInt()}° | ${currentPitch.toInt()}°"
            if (!isCreatingMode) evaluateAngle()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun evaluateAngle() {
        val arr = loadCapsArray()
        if (arr.length() == 0) { arCard.visibility = View.GONE; activeCapsuleIndex = -1; return }
        var matched = -1; var dx = 0f; var dy = 0f
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val diffAz = ((obj.getDouble("az").toFloat() - currentAzimuth + 540f) % 360f) - 180f
            val diffPt = obj.getDouble("pt").toFloat() - currentPitch
            if (abs(diffAz) <= 22f && abs(diffPt) <= 22f) { matched = i; dx = diffAz * 10f; dy = diffPt * 10f; break }
        }
        if (matched != -1) {
            if (activeCapsuleIndex != matched) {
                activeCapsuleIndex = matched
                arImage.visibility = View.GONE
                arTitle.text = "طَيْف مشفر مثبت في هذه الزاوية"
                arNote.text = "قرّب بطاقة التعريف الوطنية لفتحه"
            }
            arCard.visibility = View.VISIBLE
            arCard.translationX = dx
            arCard.translationY = dy
        } else { activeCapsuleIndex = -1; arCard.visibility = View.GONE }
    }

    private fun openCreatePanel() {
        isCreatingMode = true; selectedImageBase64 = null; noteInput.setText("")
        pickedStatus.text = "لم يتم اختيار صورة بعد"; pickedStatus.setTextColor(Color.parseColor("#94A3B8"))
        createDialog.visibility = View.VISIBLE
    }

    private fun closeCreatePanel() { isCreatingMode = false; createDialog.visibility = View.GONE }

    private fun compressUriToBase64(uri: Uri): String? {
        return try {
            val original = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)) ?: return null
            val ratio = minOf(900f / original.width, 900f / original.height, 1f)
            val scaled = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    private fun encryptAes(plain: String, uid: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decryptAes(cipherText: String, uid: String): String? {
        return try {
            val p = cipherText.split(":")
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(p[1], Base64.NO_WRAP)))
        } catch (_: Exception) { null }
    }

    private fun getCapsFile(): File = File(filesDir, "tayf_anchors.json")
    private fun loadCapsArray(): JSONArray = if (getCapsFile().exists()) JSONArray(getCapsFile().readText()) else JSONArray()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startHighResCamera()
    }
}

