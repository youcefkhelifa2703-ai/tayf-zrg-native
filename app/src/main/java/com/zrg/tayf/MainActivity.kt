package com.zrg.tayf

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
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

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true

        webView.webChromeClient = object : WebChromeClient() {
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

        setContentView(webView)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val html = buildHtmlPage()
        webView.loadDataWithBaseURL("https://tayf.zrg.local/", html, "text/html", "UTF-8", null)
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
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    @Suppress("DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            val uidHex = tag.id.joinToString("") { "%02X".format(it) }
            runOnUiThread {
                webView.evaluateJavascript("if(window.onNativeNfcScanned) window.onNativeNfcScanned('$uidHex');", null)
            }
        }
    }

    private fun buildHtmlPage(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang='ar' dir='rtl'><head><meta charset='UTF-8'/>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no'/>")
        sb.append("<style>")
        sb.append("*{box-sizing:border-box;margin:0;padding:0;font-family:system-ui,sans-serif}")
        sb.append("body{background:#000;color:#fff;overflow:hidden;height:100vh}")
        sb.append("#camera{position:fixed;inset:0;width:100%;height:100%;object-fit:cover;z-index:1}")
        sb.append(".hud{position:fixed;inset:0;z-index:2;pointer-events:none;display:flex;flex-direction:column;justify-content:space-between;padding:18px}")
        sb.append(".top{background:rgba(10,15,25,0.85);border:1px solid #00f0ff;border-radius:14px;padding:12px;text-align:center;pointer-events:auto}")
        sb.append(".brand{font-weight:900;color:#00f0ff;font-size:17px;margin-bottom:4px}")
        sb.append(".tel{display:flex;justify-content:space-around;font-size:11px;color:#94a3b8;margin-top:6px}")
        sb.append("#capsule{display:none;pointer-events:auto;align-self:center;background:rgba(0,15,25,0.92);border:2px solid #00f0ff;box-shadow:0 0 25px rgba(0,240,255,0.5);border-radius:20px;padding:20px;text-align:center;width:86%}")
        sb.append(".btn{border:none;border-radius:12px;padding:14px;font-weight:bold;font-size:15px;width:100%;pointer-events:auto}")
        sb.append(".cyan{background:#00f0ff;color:#000}.dark{background:#1e293b;color:#fff;margin-top:8px}")
        sb.append(".modal{display:none;position:fixed;inset:0;z-index:10;background:rgba(0,0,0,0.9);align-items:center;justify-content:center;padding:20px}")
        sb.append(".box{background:#0f172a;border:1px solid #00f0ff;border-radius:18px;padding:18px;width:100%}")
        sb.append(".tabs{display:flex;gap:6px;margin-bottom:12px}.tab{flex:1;padding:8px;background:#1e293b;color:#fff;border:none;border-radius:8px}")
        sb.append(".tab.active{background:#00f0ff;color:#000;font-weight:bold}")
        sb.append("textarea,input{width:100%;background:#1e293b;color:#fff;border:1px solid #334155;padding:10px;border-radius:10px;margin-bottom:10px}")
        sb.append(".pulse{border:1px dashed #00f0ff;padding:14px;border-radius:12px;text-align:center;color:#00f0ff;font-weight:bold;margin:10px 0}")
        sb.append("</style></head><body>")
        sb.append("<video id='camera' autoplay playsinline muted></video>")
        sb.append("<div class='hud'><div class='top'><div class='brand'>TAYF ZRG | طَيْف</div>")
        sb.append("<div id='status'>جاهز — وجّه الكاميرا أو قرّب بطاقة التعريف</div>")
        sb.append("<div class='tel'><span id='gps'>GPS: --</span><span id='comp'>الاتجاه: --°</span></div></div>")
        sb.append("<div id='capsule'><div style='font-size:40px' id='cicon'>🔒</div>")
        sb.append("<div style='font-weight:bold;margin:8px 0' id='ctitle'>طَيْف مشفر مرصود هنا!</div>")
        sb.append("<div style='font-size:13px;color:#00f0ff' id='cbody'>قرّب بطاقة التعريف الوطنية لفتحه فوراً</div></div>")
        sb.append("<button class='btn cyan' onclick='openCreate()'>📍 تثبيت طَيْف مشفر هنا</button></div>")
        sb.append("<div class='modal' id='modal'><div class='box'><h3 style='color:#00f0ff;margin-bottom:10px'>إنشاء طَيْف مشفر بالبطاقة</h3>")
        sb.append("<div class='tabs'><button class='tab active' onclick=\"setTab('text',this)\">📝 نص</button>")
        sb.append("<button class='tab' onclick=\"setTab('image',this)\">🖼️ صورة</button>")
        sb.append("<button class='tab' onclick=\"setTab('audio',this)\">🎙️ صوت</button></div>")
        sb.append("<div id='p-text'><textarea id='txt' rows='3' placeholder='اكتب الرسالة السرية...'></textarea></div>")
        sb.append("<div id='p-image' style='display:none'><input type='file' id='img' accept='image/*'/></div>")
        sb.append("<div id='p-audio' style='display:none'><button class='btn dark' id='rbtn' onclick='toggleRec()'>🎙️ بدء التسجيل</button><div id='rstat' style='font-size:12px;color:#94a3b8;margin-top:6px;text-align:center'></div></div>")
        sb.append("<div class='pulse'>📡 قرّب بطاقة التعريف الوطنية الآن للقفل والحفظ</div>")
        sb.append("<button class='btn dark' onclick='closeCreate()'>إلغاء</button></div></div>")
        sb.append("<script>")
        sb.append("let lat=0,lng=0,hdg=0,mode='idle',tab='text',mdata=null,rec=null,chunks=[];")
        sb.append("navigator.mediaDevices.getUserMedia({video:{facingMode:'environment'}}).then(s=>document.getElementById('camera').srcObject=s);")
        sb.append("navigator.geolocation.watchPosition(p=>{lat=p.coords.latitude;lng=p.coords.longitude;document.getElementById('gps').innerText=lat.toFixed(4)+','+lng.toFixed(4);});")
        sb.append("window.addEventListener('deviceorientationabsolute',e=>{if(e.alpha!=null){hdg=Math.round(360-e.alpha);document.getElementById('comp').innerText='الاتجاه: '+hdg+'°';}});")
        sb.append("function setTab(t,el){tab=t;document.querySelectorAll('.tab').forEach(b=>b.classList.remove('active'));el.classList.add('active');['text','image','audio'].forEach(p=>document.getElementById('p-'+p).style.display=(p===t?'block':'none'));}")
        sb.append("async function toggleRec(){const b=document.getElementById('rbtn');if(!rec||rec.state==='inactive'){const s=await navigator.mediaDevices.getUserMedia({audio:true});rec=new MediaRecorder(s);chunks=[];rec.ondataavailable=e=>chunks.push(e.data);rec.onstop=()=>{const bl=new Blob(chunks,{type:'audio/webm'});const r=new FileReader();r.onloadend=()=>{mdata=r.result;document.getElementById('rstat').innerText='✅ تم تجهيز الصوت';};r.readAsDataURL(bl);};rec.start();b.innerText='⏹️ إيقاف';}else{rec.stop();b.innerText='🎙️ إعادة';}}")
        sb.append("document.getElementById('img').onchange=e=>{const f=e.target.files[0];if(!f)return;const r=new FileReader();r.onload=()=>mdata=r.result;r.readAsDataURL(f);};")
        sb.append("function openCreate(){mode='lock';mdata=null;document.getElementById('modal').style.display='flex';}")
        sb.append("function closeCreate(){mode='idle';document.getElementById('modal').style.display='none';}")
        sb.append("async function crypt(txt,uid,enc){const h=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(uid));const k=await crypto.subtle.importKey('raw',h,{name:'AES-GCM'},false,['encrypt','decrypt']);if(enc){const iv=crypto.getRandomValues(new Uint8Array(12));const c=await crypto.subtle.encrypt({name:'AES-GCM',iv},k,new TextEncoder().encode(txt));return btoa(String.fromCharCode(...iv))+':'+btoa(String.fromCharCode(...new Uint8Array(c)));}else{const p=txt.split(':');const iv=Uint8Array.from(atob(p[0]),c=>c.charCodeAt(0));const d=Uint8Array.from(atob(p[1]),c=>c.charCodeAt(0));const dec=await crypto.subtle.decrypt({name:'AES-GCM',iv},k,d);return new TextDecoder().decode(dec);}}")
        sb.append("window.onNativeNfcScanned=async function(uid){if(mode==='lock'){let p=tab==='text'?document.getElementById('txt').value:mdata;if(!p){alert('أدخل المحتوى أولاً!');return;}const e=await crypt(p,uid,true);localStorage.setItem('tayf_cap',JSON.stringify({lat,lng,hdg,type:tab,data:e}));closeCreate();document.getElementById('status').innerText='✅ تم القفل بالبطاقة: '+uid;check();}else{const raw=localStorage.getItem('tayf_cap');if(!raw)return;const c=JSON.parse(raw);try{const clear=await crypt(c.data,uid,false);document.getElementById('cicon').innerText='🔓';document.getElementById('ctitle').innerText='تم فتح الطَيْف!';const b=document.getElementById('cbody');if(c.type==='text')b.innerHTML='<div style=\"font-size:18px;color:#4ade80;margin-top:8px\">'+clear+'</div>';if(c.type==='image')b.innerHTML='<img src=\"'+clear+'\" style=\"width:100%;border-radius:10px;margin-top:8px\"/>';if(c.type==='audio')b.innerHTML='<audio controls src=\"'+clear+'\" style=\"width:100%;margin-top:8px\"></audio>';document.getElementById('status').innerText='🎉 تم التحقق من الهوية';}catch(err){document.getElementById('status').innerText='❌ البطاقة غير مطابقة!';}}};")
        sb.append("function check(){document.getElementById('capsule').style.display=localStorage.getItem('tayf_cap')?'block':'none';}check();")
        sb.append("</script></body></html>")
        return sb.toString()
    }
}
