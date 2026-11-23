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

        if (allPermissionsGranted()) {
            startHighResCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
                100
            )
        }
    }

    private fun buildOfficialUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. كاميرا CameraX عالية الدقة
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        // 2. مؤشر التصويب الدقيق في منتصف الزاوية
        val reticle = View(this).apply {
            val size = dp(36)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#8038BDF8"))
            }
        }
        root.addView(reticle)

        // 3. الشريط العلوي الرسمي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(16), dp(36), dp(16), 0) }
            background = roundBg("#CC0F172A", "#334155", 16)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "TAYF ZRG"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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

        // 4. بطاقة الطَيْف العائمة في الزاوية (AR Card)
        arCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(dp(290), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
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
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(220)).apply {
                topMargin = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        arNote = TextView(this).apply {
            text = "قرّب بطاقة التعريف الوطنية من ظهر الهاتف لفتح المحتوى"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        arCard.addView(arTitle)
        arCard.addView(arImage)
        arCard.addView(arNote)
        root.addView(arCard)

        // 5. شريط التحكم السفلي
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(dp(16), 0, dp(16), dp(28)) }
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
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(52)).apply {
                marginStart = dp(10)
            }
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

        // 6. نافذة الإخفاء والقفل بالبطاقة
        createDialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(dp(20), 0, dp(20), 0) }
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(14) }
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            background = roundBg("#172554", "#38BDF8", 12)
        }

        val cancelBtn = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundBg("#1E293B", "#334155", 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(10) }
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // تفعيل وضع القراءة المباشر للشرائح البيومترية (IsoDep + NfcA + NfcB)
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
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

            val encrypted = encryptAes(payload, uid)
            val arr = loadCapsArray()
            val item = JSONObject().apply {
                put("az", currentAzimuth.toDouble())
                put("pt", currentPitch.toDouble())
                put("enc", encrypted)
            }
            arr.put(item)
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
            val item = arr.getJSONObject(idx)
            val decrypted = decryptAes(item.getString("enc"), uid)
            if (decrypted != null) {
                val obj = JSONObject(decrypted)
                val imgB64 = obj.optString("img", "")
                val txt = obj.optString("txt", "")

                arCard.visibility = View.VISIBLE
                arTitle.text = "تم التحقق من الهوية ($uid)"
                if (imgB64.isNotEmpty()) {
                    val bytes = Base64.decode(imgB64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    arImage.setImageBitmap(bmp)
                    arImage.visibility = View.VISIBLE
                } else {
                    arImage.visibility = View.GONE
                }
                arNote.text = if (txt.isNotEmpty()) txt else "صورة مشفرة مخفية في هذه الزاوية"
                statusText.text = "تم فتح المحتوى بنجاح"
            } else {
                statusText.text = "البطاقة غير مطابقة لهذا القفل"
                Toast.makeText(this, "هذه البطاقة لا تملك صلاحية فتح هذه الزاوية", Toast.LENGTH_SHORT).show()
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
        if (arr.length() == 0) {
            arCard.visibility = View.GONE
            activeCapsuleIndex = -1
            return
        }
        var matched = -1
        var dx = 0f
        var dy = 0f
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val targetAz = obj.getDouble("az").toFloat()
            val targetPt = obj.getDouble("pt").toFloat()
            val diffAz = ((targetAz - currentAzimuth + 540f) % 360f) - 180f
            val diffPt = targetPt - currentPitch
            if (abs(diffAz) <= 22f && abs(diffPt) <= 22f) {
                matched = i
                dx = diffAz * 10f
                dy = diffPt * 10f
                break
            }
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
        } else {
            activeCapsuleIndex = -1
            arCard.visibility = View.GONE
        }
    }

    private fun openCreatePanel() {
        isCreatingMode = true
        selectedImageBase64 = null
        noteInput.setText("")
        pickedStatus.text = "لم يتم اختيار صورة بعد"
        pickedStatus.setTextColor(Color.parseColor("#94A3B8"))
        createDialog.visibility = View.VISIBLE
    }

    private fun closeCreatePanel() {
        isCreatingMode = false
        createDialog.visibility = View.GONE
    }

    private fun compressUriToBase64(uri: Uri): String? {
        return try {
            val stream = contentResolver.openInputStream(uri)
            val original = BitmapFactory.decodeStream(stream) ?: return null
            val maxDim = 900
            val ratio = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
            val scaled = Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt(),
                (original.height * ratio).toInt(),
                true
            )
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptAes(plain: String, uid: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    private fun decryptAes(cipherText: String, uid: String): String? {
        return try {
            val parts = cipherText.split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpe
