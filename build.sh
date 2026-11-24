#!/bin/bash
set -e
rm -rf app/src/main/java/*
mkdir -p app/src/main/java/com/zrg/tayf app/src/main/res/xml

cat << 'EOF' > app/build.gradle
plugins { id 'com.android.application'; id 'org.jetbrains.kotlin.android' }
android {
    namespace 'com.zrg.tayf'; compileSdk 34
    defaultConfig { applicationId "com.zrg.tayf"; minSdk 26; targetSdk 34; versionCode 4; versionName "4.0-STEALTH" }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = '17' }
}
dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'; implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'; implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
EOF

echo '<?xml version="1.0" encoding="utf-8"?><paths><cache-path name="ghost_cache" path="." /></paths>' > app/src/main/res/xml/file_paths.xml

cat << 'EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.NFC" /><uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" /><uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.VIBRATE" /><uses-feature android:name="android.hardware.nfc" android:required="true" />
    <application android:allowBackup="false" android:label="TAYF GHOST" android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="portrait">
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
        </activity>
        <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" android:exported="false" android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
EOF

bash part2.sh

cat << 'EOF' > app/src/main/java/com/zrg/tayf/MainActivity.kt
package com.zrg.tayf
import android.Manifest; import android.content.pm.PackageManager; import android.graphics.*; import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder; import android.net.Uri; import android.nfc.*; import android.nfc.tech.IsoDep; import android.os.*
import android.provider.OpenableColumns; import android.text.InputType; import android.view.*; import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts; import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat; import androidx.core.content.ContextCompat; import androidx.core.content.FileProvider
import kotlinx.coroutines.*; import okhttp3.MediaType.Companion.toMediaType; import okhttp3.OkHttpClient
import okhttp3.Request; import okhttp3.RequestBody.Companion.toRequestBody; import org.json.JSONObject; import java.io.*
import java.security.MessageDigest; import java.security.SecureRandom; import java.util.concurrent.TimeUnit
import javax.crypto.Cipher; import javax.crypto.SecretKeyFactory; import javax.crypto.spec.*

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private var nfcAdapter: NfcAdapter? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isSendMode = true; private var attachedBytes: ByteArray? = null; private var attachedName = ""; private var attachedMime = ""
    private var cameraPhotoUri: Uri? = null; private var recorder: MediaRecorder? = null; private var isRecording = false
    private var recordedAudioFile: File? = null; private var receivedFile: File? = null; private var receivedMime = ""

    private lateinit var radarView: RadarPulseView; private lateinit var terminalLog: TextView; private lateinit var attachmentBadge: TextView
    private lateinit var pinInput: EditText; private lateinit var noteInput: EditText; private lateinit var previewImage: ImageView
    private lateinit var openFileBtn: Button; private lateinit var modeSendBtn: Button; private lateinit var modeReceiveBtn: Button
    private lateinit var sendControlsBox: LinearLayout; private lateinit var recordBtn: Button

    private val camLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok && cameraPhotoUri != null) loadUri(cameraPhotoUri!!, "CAM_${System.currentTimeMillis()}.jpg", "image/jpeg") }
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadUri(uri, getName(uri), contentResolver.getType(uri) ?: "application/octet-stream") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.statusBarColor = Color.parseColor("#080C11"); nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080C11")) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 48, 40, 56) }
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card("#0E141F", "#2A3A4D", 16f); setPadding(32, 28, 32, 28) }
        hdr.addView(TextView(this).apply { text = "TAYF GHOST"; textSize = 21f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.parseColor("#CFB07E")); gravity = Gravity.CENTER })
        hdr.addView(TextView(this).apply { text = "NFC GHOST CARRIER // STEALTH EDITION"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#808F9C")); gravity = Gravity.CENTER; setPadding(0, 8, 0, 0) })
        radarView = RadarPulseView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 210).apply { topMargin = 16; bottomMargin = 12 } }
        val mRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 18) }
        modeSendBtn = Button(this).apply { text = "إرسال (TX)"; textSize = 13f; typeface = Typeface.MONOSPACE; layoutParams = LinearLayout.LayoutParams(0, 115, 1f).apply { rightMargin = 10 }; setOnClickListener { switchMode(true) } }
        modeReceiveBtn = Button(this).apply { text = "استلام (RX)"; textSize = 13f; typeface = Typeface.MONOSPACE; layoutParams = LinearLayout.LayoutParams(0, 115, 1f).apply { leftMargin = 10 }; setOnClickListener { switchMode(false) } }
        mRow.addView(modeSendBtn); mRow.addView(modeReceiveBtn)
        val kCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card("#0F1624", "#24334A", 14f); setPadding(28, 22, 28, 22) }
        kCard.addView(TextView(this).apply { text = "🔒 الرمز السري (اختياري - اتركه فارغاً للعمل بالبطاقة فقط)"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#C4D1DB")); setPadding(0, 0, 0, 10) })
        pinInput = EditText(this).apply { hint = "الرمز السري (اختياري)..."; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setTextColor(Color.parseColor("#CFB07E")); setHintTextColor(Color.parseColor("#5A6B7C")); background = card("#070A10", "#2B3D59", 10f); setPadding(26, 22, 26, 22); typeface = Typeface.MONOSPACE }
        kCard.addView(pinInput)
        sendControlsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card("#0F1624", "#24334A", 14f); setPadding(28, 22, 28, 22); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 } }
        noteInput = EditText(this).apply { hint = "رسالة نصية مرافقة (اختياري)..."; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#5A6B7C")); background = card("#070A10", "#2B3D59", 10f); setPadding(26, 20, 26, 20) }
        val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 18, 0, 10) }
        val cBtn = Button(this).apply { text = "📷 كاميرا"; textSize = 12f; setTextColor(Color.WHITE); background = card("#192233", "#2A3D5E", 10f); layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { rightMargin = 8 }; setOnClickListener { launchCam() } }
        recordBtn = Button(this).apply { text = "🎙️ صوت"; textSize = 12f; setTextColor(Color.WHITE); background = card("#192233", "#5C2631", 10f); layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { leftMargin = 4; rightMargin = 4 }; setOnClickListener { toggleRec() } }
        val fBtn = Button(this).apply { text = "📁 ملفات"; textSize = 12f; setTextColor(Color.WHITE); background = card("#192233", "#254735", 10f); layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { leftMargin = 8 }; setOnClickListener { fileLauncher.launch(arrayOf("*/*")) } }
        tRow.addView(cBtn); tRow.addView(recordBtn); tRow.addView(fBtn)
        attachmentBadge = TextView(this).apply { text = "لا يوجد ملف (صور، فيديو، PDF، صوت)"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#6C7D91")); setPadding(8, 10, 8, 4) }
        sendControlsBox.addView(noteInput); sendControlsBox.addView(tRow); sendControlsBox.addView(attachmentBadge)
        previewImage = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 420).apply { topMargin = 18 }; scaleType = ImageView.ScaleType.FIT_CENTER; background = card("#0A0F17", "#24334A", 12f); visibility = View.GONE }
        openFileBtn = Button(this).apply { text = "▶ فتح الملف المستلم"; setTextColor(Color.BLACK); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); background = card("#CFB07E", "#CFB07E", 12f); layoutParams = LinearLayout.LayoutParams(-1, 120).apply { topMargin = 14 }; visibility = View.GONE; setOnClickListener { openGhostFile(receivedFile, receivedMime) } }
        terminalLog = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#CFB07E")); background = card("#040609", "#1E2A3A", 12f); setPadding(26, 22, 26, 22); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 } }
        root.addView(hdr); root.addView(radarView); root.addView(mRow); root.addView(kCard); root.addView(sendControlsBox); root.addView(previewImage); root.addView(openFileBtn); root.addView(terminalLog)
        scroll.addView(root); setContentView(scroll); switchMode(true)
    }

    private fun card(bg: String, st: String, r: Float) = GradientDrawable().apply { setColor(Color.parseColor(bg)); setStroke(2, Color.parseColor(st)); cornerRadius = r * resources.displayMetrics.density }
    private fun log(m: String, err: Boolean = false) { terminalLog.setTextColor(Color.parseColor(if (err) "#C96D7F" else "#CFB07E")); terminalLog.text = m }
    private fun switchMode(s: Boolean) {
        isSendMode = s
        modeSendBtn.background = card(if (s) "#1F3528" else "#0F1624", if (s) "#CFB07E" else "#24334A", 12f); modeSendBtn.setTextColor(Color.parseColor(if (s) "#CFB07E" else "#6B7C8C"))
        modeReceiveBtn.background = card(if (!s) "#182C3D" else "#0F1624", if (!s) "#CFB07E" else "#24334A", 12f); modeReceiveBtn.setTextColor(Color.parseColor(if (!s) "#CFB07E" else "#6B7C8C"))
        sendControlsBox.visibility = if (s) View.VISIBLE else View.GONE; openFileBtn.visibility = View.GONE; if (!s) previewImage.visibility = View.GONE
        log(if (s) "[TX-READY] جهّز الحزمة ثم لامس بطاقة التعريف..." else "[RX-READY] لامس بطاقة التعريف لسحب الحزمة وفك التشفير...")
    }
    private fun launchCam() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101); return }
        val f = File(cacheDir, "GHOST_${System.currentTimeMillis()}.jpg"); cameraPhotoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f); camLauncher.launch(cameraPhotoUri)
    }
    private fun toggleRec() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102); return }
        if (!isRecording) {
            recordedAudioFile = File(cacheDir, "VOICE_${System.currentTimeMillis()}.m4a")
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()).apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setOutputFile(recordedAudioFile!!.absolutePath); prepare(); start() }
            isRecording = true; recordBtn.text = "⏹️ إيقاف"; log("[REC] جاري تسجيل الصوت...")
        } else {
            recorder?.stop(); recorder?.release(); recorder = null; isRecording = false; recordBtn.text = "🎙️ صوت"
            val f = recordedAudioFile ?: return; attachedBytes = f.readBytes(); attachedName = f.name; attachedMime = "audio/mp4"
            attachmentBadge.text = "✅ صوت: $attachedName (${attachedBytes!!.size / 1024} KB)"; log("[READY] التسجيل جاهز، لامس البطاقة الآن!")
        }
    }
    private fun getName(u: Uri): String { contentResolver.query(u, null, null, null, null)?.use { c -> val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) return c.getString(i) }; return "FILE_${System.currentTimeMillis()}.bin" }
    private fun loadUri(u: Uri, n: String, m: String) {
        val raw = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return
        if (m.startsWith("image/")) {
            val orig = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return; val s = minOf(1080f / orig.width, 1080f / orig.height, 1f)
            val bmp = Bitmap.createScaledBitmap(orig, (orig.width * s).toInt(), (orig.height * s).toInt(), true)
            attachedBytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.JPEG, 78, this) }.toByteArray()
            attachedName = "$n.jpg"; attachedMime = "image/jpeg"; previewImage.setImageBitmap(bmp); previewImage.visibility = View.VISIBLE
        } else {
            if (raw.size > 14 * 1024 * 1024) { log("[LIMIT] الحد الأقصى 14 MB", true); return }
            attachedBytes = raw; attachedName = n; attachedMime = m; previewImage.visibility = View.GONE
        }
        attachmentBadge.text = "✅ الملف: $attachedName (${attachedBytes!!.size / 1024} KB)"; log("[READY] الحزمة جاهزة (${attachedBytes!!.size / 1024} KB)، لامس البطاقة الآن!")
    }
    override fun onResume() { super.onResume(); nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null) }
    override fun onPause() { super.onPause(); nfcAdapter?.disableReaderMode(this) }
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return; val uid = tag.id.joinToString("") { "%02X".format(it) }; val isIso = IsoDep.get(tag) != null
        runOnUiThread { beep(); radarView.triggerLock(); if (!isIso) { log("[ERR] استخدم بطاقة التعريف البيومترية (IsoDep)!", true); return@runOnUiThread }; val pin = pinInput.text.toString().trim(); if (isSendMode) tx(uid, pin) else rx(uid, pin) }
    }
    private fun deriveKey(uid: String, pin: String): Pair<String, SecretKeySpec> {
        val salt = MessageDigest.getInstance("SHA-256").digest("TAYF_DZ_$uid".toByteArray())
        val k = if (pin.isNotEmpty()) SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 50000, 256)).encoded else MessageDigest.getInstance("SHA-256").digest("NOPIN_$uid".toByteArray())
        return Pair("tayf_ghost_" + MessageDigest.getInstance("SHA-256").digest(k).take(14).joinToString("") { "%02x".format(it) }, SecretKeySpec(k, "AES"))
    }
    private fun tx(uid: String, pin: String) {
        val note = noteInput.text.toString().trim(); if (attachedBytes == null && note.isEmpty()) { log("[WARN] أرفق ملفاً أو اكتب رسالة أولاً!", true); return }
        log("[AES-256] جاري تشفير الحزمة وشحنها...")
        scope.launch(Dispatchers.IO) {
            try {
                val (topic, key) = deriveKey(uid, pin); val out = ByteArrayOutputStream()
                DataOutputStream(out).apply { writeInt(0x54415946); writeUTF(note); writeUTF(attachedName); writeUTF(attachedMime); writeInt(attachedBytes?.size ?: 0); if (attachedBytes != null) write(attachedBytes) }
                val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }; val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
                val blob = iv + c.doFinal(out.toByteArray())
                client.newCall(Request.Builder().url("https://ntfy.sh/$topic").header("Filename", "payload.enc").put(blob.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
                    withContext(Dispatchers.Main) { if (r.isSuccessful) { beep(); log("[TX-OK] ✅ تم الشحن بنجاح (${blob.size / 1024} KB)!") } else log("[TX-ERR] فشل الرفع (${r.code})", true) }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { log("[ERR] ${e.message}", true) } }
        }
    }
    private fun rx(uid: String, pin: String) {
        log("[RX] جاري سحب الحزمة وفك التشفير...")
        scope.launch(Dispatchers.IO) {
            try {
                val (topic, key) = deriveKey(uid, pin); var url: String? = null
                client.newCall(Request.Builder().url("https://ntfy.sh/$topic/json?poll=1").get().build()).execute().use { r ->
                    for (line in r.body?.string().orEmpty().trim().lines().asReversed()) { if (line.isBlank()) continue; val ev = JSONObject(line); if (ev.has("attachment")) { url = ev.getJSONObject("attachment").getString("url"); break } }
                }
                if (url == null) { withContext(Dispatchers.Main) { log("[RX-EMPTY] لا توجد حزمة لهذه البطاقة أو الرمز غير مطابق.", true) }; return@launch }
                val blob = client.newCall(Request.Builder().url(url!!).get().build()).execute().use { it.body?.bytes() } ?: throw Exception("Empty")
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
                val dis = DataInputStream(ByteArrayInputStream(c.doFinal(blob.copyOfRange(12, blob.size))))
                if (dis.readInt() != 0x54415946) throw Exception("Bad magic")
                val note = dis.readUTF(); val fName = dis.readUTF(); val mime = dis.readUTF(); val len = dis.readInt()
                val fData = if (len > 0) ByteArray(len).also { dis.readFully(it) } else null
                withContext(Dispatchers.Main) {
                    beep()
                    if (fData != null && fData.isNotEmpty()) {
                        val safe = fName.ifBlank { "RX_${System.currentTimeMillis()}.bin" }; receivedFile = File(cacheDir, safe).apply { writeBytes(fData) }; receivedMime = mime
                        if (mime.startsWith("image/")) { previewImage.setImageBitmap(BitmapFactory.decodeByteArray(fData, 0, fData.size)); previewImage.visibility = View.VISIBLE } else previewImage.visibility = View.GONE
                        openFileBtn.text = "▶ فتح: $safe"; openFileBtn.visibility = View.VISIBLE
                    }
                    log("[RX-OK] ✅ تم الاستخراج بنجاح!\n${if (note.isNotBlank()) "الرسالة: $note" else ""}")
                }
                } catch (e: Exception) { withContext(Dispatchers.Main) { log("[ERR] تعذر فك التشفير: تأكد من البطاقة والرمز.", true) } }
        }
    }
    override fun onDestroy() { super.onDestroy(); recorder?.release(); scope.cancel() }
}
EOF

