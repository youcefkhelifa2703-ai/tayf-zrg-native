package com.zrg.tayf

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAllPermissions()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    callback.invoke(origin, true, false)
                }
            }
        }
        setContentView(webView)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        webView.loadDataWithBaseURL("https://tayf.zrg.local/", getAppHtml(), "text/html", "UTF-8", null)
    }

    private fun requestAllPermissions() {
        val perms = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            val uidHex = tag.id.joinToString("") { "%02X".format(it) }
            runOnUiThread {
                webView.evaluateJavascript("window.onNativeNfcScanned('$uidHex')", null)
            }
        }
    }

    private fun getAppHtml(): String = """
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, sans-serif; }
    body { background: #000; color: #fff; overflow: hidden; height: 100vh; }
    #camera { position: fixed; inset: 0; width: 100%; height: 100%; object-fit: cover; z-index: 1; }
    .hud { position: fixed; inset: 0; z-index: 2; pointer-events: none; display: flex; flex-direction: column; justify-content: space-between; padding: 18px; }
    .top-bar { background: rgba(10,15,25,0.85); border: 1px solid #00f0ff; border-radius: 14px; padding: 12px; text-align: center; pointer-events: auto; }
    .brand { font-weight: 900; color: #00f0ff; font-size: 16px; letter-spacing: 1px; margin-bottom: 4px; }
    .telemetry { display: flex; justify-content: space-around; font-size: 11px; color: #94a3b8; margin-top: 6px; font-family: monospace; }
    #capsule { display: none; pointer-events: auto; align-self: center; background: rgba(0,15,25,0.9); border: 2px solid #00f0ff; box-shadow: 0 0 25px rgba(0,240,255,0.5); border-radius: 20px; padding: 20px; text-align: center; width: 85%; }
    .btn { border: none; border-radius: 12px; padding: 14px; font-weight: bold; font-size: 15px; width: 100%; pointer-events: auto; cursor: pointer; }
    .btn-cyan { background: #00f0ff; color: #000; }
    .btn-dark { background: #1e293b; color: #fff; margin-top: 8px; }
    .modal { display: none; position: fixed; inset: 0; z-index: 10; background: rgba(0,0,0,0.9); align-items: center; justify-content: center; padding: 20px; }
    .box { background: #0f172a; border: 1px solid #00f0ff; border-radius: 18px; padding: 18px; width: 100%; }
    .tabs { display: flex; gap: 6px; margin-bottom: 12px; }
    .tab { flex: 1; padding: 8px; background: #1e293b; color: #fff; border: none; border-radius: 8px; font-size: 13px; }
    .tab.active { background: #00f0ff; color: #000; font-weight: bold; }
    textarea, input[type="file"] { width: 100%; background: #1e293b; color: #fff; border: 1px solid #334155; padding: 10px; border-radius: 10px; margin-bottom: 10px; }
    .nfc-pulse { border: 1px dashed #00f0ff; padding: 14px; border-radius: 12px; text-align: center; color: #00f0ff; font-weight: bold; margin: 10px 0; animation: pulse 1.5s infinite; }
    @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.4; } }
  </style>
</head>
<body>
  <video id="camera" autoplay playsinline muted></video>
  <div class="hud">
    <div class="top-bar">
      <div class="brand">TAYF ZRG | طَيْف</div>
      <div id="status">جاهز — وجّه الكاميرا أو قرّب بطاقة التعريف</div>
      <div class="telemetry">
        <span id="gps">GPS: --</span>
        <span id="comp">الاتجاه: --°</span>
      </div>
    </div>

    <div id="capsule">
      <div style="font-size:40px" id="cap-icon">🔒</div>
      <div style="font-weight:bold; margin:8px 0" id="cap-title">طَيْف مشفر مرصود في هذا المكان!</div>
      <div style="font-size:13px; color:#00f0ff" id="cap-body">قرّب بطاقة التعريف الوطنية من ظهر الهاتف لفتحه فوراً</div>
    </div>

    <button class="btn btn-cyan" onclick="openCreate()">📍 تثبيت طَيْف مشفر هنا</button>
  </div>

  <div class="modal" id="create-modal">
    <div class="box">
      <h3 style="color:#00f0ff; margin-bottom:10px">إنشاء طَيْف مشفر بالبطاقة</h3>
      <div class="tabs">
        <button class="tab active" onclick="setTab('text', this)">📝 نص</button>
        <button class="tab" onclick="setTab('image', this)">🖼️ صورة</button>
        <button class="tab" onclick="setTab('audio', this)">🎙️ صوت</button>
      </div>
      <div id="panel-text"><textarea id="txt-input" rows="3" placeholder="اكتب الرسالة السرية..."></textarea></div>
      <div id="panel-image" style="display:none"><input type="file" id="img-input" accept="image/*" capture="environment" /></div>
      <div id="panel-audio" style="display:none">
        <button class="btn btn-dark" id="rec-btn" onclick="toggleRecord()">🎙️ اضغط لبدء التسجيل الصوتي</button>
        <div id="rec-status" style="font-size:12px; color:#94a3b8; margin-top:6px; text-align:center"></div>
      </div>
      <div class="nfc-pulse">📡 قرّب بطاقة التعريف الوطنية الآن للقفل والحفظ</div>
      <button class="btn btn-dark" onclick="closeCreate()">إلغاء</button>
    </div>
  </div>

  <script>
    let lat = null, lng = null, heading = 0, mode = 'idle', currentTab = 'text';
    let mediaData = null, mediaRecorder = null, audioChunks = [];

    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      .then(s => document.getElementById('camera').srcObject = s);

    navigator.geolocation.watchPosition(p => {
      lat = p.coords.latitude; lng = p.coords.longitude;
      document.getElementById('gps').innerText = lat.toFixed(4) + ',' + lng.toFixed(4);
    }, null, { enableHighAccuracy: true });

    window.addEventListener('deviceorientationabsolute', e => {
      if (e.alpha != null) { heading = Math.round(360 - e.alpha); document.getElementById('comp').innerText = 'الاتجاه: ' + heading + '°'; }
    });

    function setTab(t, el) {
      currentTab = t;
      document.querySelectorAll('.tab').forEach(b => b.classList.remove('active'));
      el.classList.add('active');
      ['text','image','audio'].forEach(p => document.getElementById('panel-'+p).style.display = (p===t?'block':'none'));
    }

    async function toggleRecord() {
      const btn = document.getElementById('rec-btn');
      if (!mediaRecorder || mediaRecorder.state === 'inactive') {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        mediaRecorder = new MediaRecorder(stream);
        audioChunks = [];
        mediaRecorder.ondataavailable = e => audioChunks.push(e.data);
        mediaRecorder.onstop = () => {
          const blob = new Blob(audioChunks, { type: 'audio/webm' });
          const reader = new FileReader();
          reader.onloadend = () => { mediaData = reader.result; document.getElementById('rec-status').innerText = '✅ تم تجهيز المقطع الصوتي'; };
          reader.readAsDataURL(blob);
        };
        mediaRecorder.start();
        btn.innerText = '⏹️ إيقاف التسجيل';
      } else {
        mediaRecorder.stop();
        btn.innerText = '🎙️ إعادة التسجيل';
      }
    }

    document.getElementById('img-input').onchange = e => {
      const file = e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => mediaData = reader.result;
      reader.readAsDataURL(file);
    };

    function openCreate() { mode = 'locking'; mediaData = null; document.getElementById('create-modal').style.display = 'flex'; }
    function closeCreate() { mode = 'idle'; document.getElementById('create-modal').style.display = 'none'; }

    async function cryptoProcess(text, uid, encrypt) {
      const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(uid));
      const key = await crypto.subtle.importKey('raw', hash, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
      if (encrypt) {
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const enc = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(text));
        return btoa(String.fromCharCode(...iv)) + ':' + btoa(String.fromCharCode(...new Uint8Array(enc)));
      } else {
        const [ivB64, dataB64] = text.split(':');
        const iv = Uint8Array.from(atob(ivB64), c => c.charCodeAt(0));
        const data = Uint8Array.from(atob(dataB64), c => c.charCodeAt(0));
        const dec = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, data);
        return new TextDecoder().decode(dec);
      }
    }

    window.onNativeNfcScanned = async function(uid) {
      if (mode === 'locking') {
        let payload = currentTab === 'text' ? document.getElementById('txt-input').value : mediaData;
        if (!payload) { alert('أدخل النص أو اختر الصورة أو سجل الصوت أولاً!'); return; }
        const encrypted = await cryptoProcess(payload, uid, true);
        const cap = { lat: lat || 0, lng: lng || 0, heading, type: currentTab, data: encrypted };
        localStorage.setItem('tayf_cap', JSON.stringify(cap));
        closeCreate();
        document.getElementById('status').innerText = '✅ تم قفل الطَيْف ببطاقتك (' + uid + ')';
        checkView();
      } else {
        const raw = localStorage.getItem('tayf_cap');
        if (!raw) return;
        const cap = JSON.parse(raw);
        try {
          const clear = await cryptoProcess(cap.data, uid, false);
          document.getElementById('cap-icon').innerText = '🔓';
          document.getElementById('cap-title').innerText = 'تم فتح الطَيْف بنجاح!';
          const body = document.getElementById('cap-body');
          if (cap.type === 'text') body.innerHTML = '<div style="font-size:18px;color:#4ade80;margin-top:8px">' + clear + '</div>';
          if (cap.type === 'image') body.innerHTML = '<img src="' + clear + '" style="width:100%;border-radius:10px;margin-top:8px"/>';
          if (cap.type === 'audio') body.innerHTML = '<audio controls src="' + clear + '" style="width:100%;margin-top:8px"></audio>';
          document.getElementById('status').innerText = '🎉 تم التحقق من الهوية';
        } catch (e) {
          document.getElementById('status').innerText = '❌ البطاقة غير مطابقة!';
        }
      }
    };

    function checkView() {
      const raw = localStorage.getItem('tayf_cap');
      const el = document.getElementById('capsule');
      if (!raw) { el.style.display = 'none'; return; }
      el.style.display = 'block';
    }
    checkView();
  </script>
</body>
</html>
    """.trimIndent()
}
