package com.zrg.tayf

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var audioRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            arrayOf(result.data!!.data!!)
        } else null
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true

        webView.addJavascriptInterface(NativeBridge(), "AndroidNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "اختر صورة"))
                return true
            }
        }

        setContentView(webView)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (hasAllPermissions()) {
            loadApp()
        } else {
            requestAllPermissions()
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun startAudioRecord(): Boolean {
            return try {
                audioFile = File(cacheDir, "tayf_voice.m4a")
                audioRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@MainActivity)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                audioRecorder?.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64000)
                    setAudioSamplingRate(22050)
                    setOutputFile(audioFile!!.absolutePath)
                    prepare()
                    start()
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun stopAudioRecord(): String {
            return try {
                audioRecorder?.stop()
                audioRecorder?.release()
                audioRecorder = null
                val bytes = audioFile?.readBytes() ?: return ""
                "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        val perms = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val perms = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ActivityCompat.requestPermissions(this, perms, 101)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadApp()
    }

    private fun loadApp() {
        webView.loadDataWithBaseURL("https://tayf.zrg.local/", buildHtmlPage(), "text/html", "UTF-8", null)
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
        sb.append("body{background:#050811;color:#fff;overflow:hidden;height:100vh}")
        sb.append("#camera{position:fixed;inset:0;width:100%;height:100%;object-fit:cover;z-index:1;background:#000}")
        sb.append(".crosshair{position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);width:44px;height:44px;border:1.5px dashed rgba(0,240,255,0.65);border-radius:50%;z-index:2;pointer-events:none}")
        sb.append(".hud{position:fixed;inset:0;z-index:3;pointer-events:none;display:flex;flex-direction:column;justify-content:space-between;padding:16px}")
        sb.append(".top{background:rgba(10,15,25,0.85);border:1px solid #00f0ff;border-radius:14px;padding:12px;text-align:center;pointer-events:auto}")
        sb.append(".brand{font-weight:900;color:#00f0ff;font-size:17px;margin-bottom:4px}")
        sb.append(".tel{display:flex;justify-content:space-around;font-size:11px;color:#cbd5e1;margin-top:6px}")
        sb.append("#capsule{display:none;pointer-events:auto;position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:rgba(0,18,30,0.94);border:2px solid #00f0ff;box-shadow:0 0 30px rgba(0,240,255,0.6);border-radius:20px;padding:18px;text-align:center;width:84%;z-index:4}")
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
        sb.append("<div class='crosshair'></div>")
        sb.append("<div class='hud'><div class='top'><div class='brand'>TAYF ZRG | طَيْف</div>")
        sb.append("<div id='status'>وجّه الكاميرا أو قرّب بطاقة التعريف</div>")
        sb.append("<div class='tel'><span id='gps'>GPS: --</span><span id='comp'>الاتجاه: --°</span><span id='tilt'>الميلان: --°</span></div></div>")
        sb.append("<div style='display:flex;gap:8px;pointer-events:auto'>")
        sb.append("<button class='btn cyan' style='flex:3' onclick='openCreate()'>📍 تثبيت طَيْف في هذا المكان</button>")
        sb.append("<button class='btn dark' style='flex:1;margin-top:0;background:#7f1d1d' onclick='clearCaps()'>🗑️</button></div></div>")
        sb.append("<div id='capsule'><div style='font-size:38px' id='cicon'>🔒</div>")
        sb.append("<div style='font-weight:bold;margin:6px 0' id='ctitle'>طَيْف مشفر مثبت هنا!</div>")
        sb.append("<div style='font-size:13px;color:#00f0ff' id='cbody'>قرّب بطاقة التعريف الوطنية لفتحه</div></div>")
        sb.append("<div class='modal' id='modal'><div class='box'><h3 style='color:#00f0ff;margin-bottom:10px'>تثبيت طَيْف في هذه النقطة</h3>")
        sb.append("<div class='tabs'><button class='tab active' onclick=\"setTab('text',this)\">📝 نص</button>")
        sb.append("<button class='tab' onclick=\"setTab('image',this)\">🖼️ صورة</button>")
        sb.append("<button class='tab' onclick=\"setTab('audio',this)\">🎙️ صوت</button></div>")
        sb.append("<div id='p-text'><textarea id='txt' rows='3' placeholder='اكتب الرسالة السرية...'></textarea></div>")
        sb.append("<div id='p-image' style='display:none'><input type='file' id='img' accept='image/*'/><div id='istat' style='font-size:12px;color:#4ade80;text-align:center'></div></div>")
        sb.append("<div id='p-audio' style='display:none'><button class='btn dark' id='rbtn' onclick='toggleRec()'>🎙️ اضغط لبدء التسجيل</button><div id='rstat' style='font-size:12px;color:#4ade80;margin-top:6px;text-align:center'></div></div>")
        sb.append("<div class='pulse'>📡 قرّب بطاقة التعريف الوطنية الآن للقفل والحفظ</div>")
        sb.append("<button class='btn dark' onclick='closeCreate()'>إلغاء</button></div></div>")
        sb.append("<script>")
        sb.append("let lat=0,lng=0,hdg=0,pitch=45,mode='idle',tab='text',mdata=null,isRec=false,caps=[],lastSeen=null;")
        sb.append("function openDB(){return new Promise((res,rej)=>{const r=indexedDB.open('TayfDB',1);r.onupgradeneeded=e=>e.target.result.createObjectStore('caps',{keyPath:'id'});r.onsuccess=e=>res(e.target.result);r.onerror=rej;});}")
        sb.append("async function loadCaps(){const db=await openDB();return new Promise(res=>{const tx=db.transaction('caps','readonly');const req=tx.objectStore('caps').getAll();req.onsuccess=()=>{caps=req.result||[];updateAR();res();};});}")
        sb.append("async function saveCap(c){const db=await openDB();const tx=db.transaction('caps','readwrite');tx.objectStore('caps').put(c);await loadCaps();}")
        sb.append("async function clearCaps(){const db=await openDB();const tx=db.transaction('caps','readwrite');tx.objectStore('caps').clear();caps=[];lastSeen=null;updateAR();document.getElementById('status').innerText='تم مسح الكبسولات';}")
        sb.append("navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'}},audio:false}).then(s=>{const v=document.getElementById('camera');v.srcObject=s;v.play();});")
        sb.append("navigator.geolocation.watchPosition(p=>{lat=p.coords.latitude;lng=p.coords.longitude;document.getElementById('gps').innerText=lat.toFixed(4)+','+lng.toFixed(4);},null,{enableHighAccuracy:true});")
        sb.append("function onOrient(e){if(e.alpha!=null){hdg=Math.round(360-e.alpha);document.getElementById('comp').innerText='الاتجاه: '+hdg+'°';}if(e.beta!=null){pitch=Math.round(e.beta);document.getElementById('tilt').innerText='الميلان: '+pitch+'°';}updateAR();}")
        sb.append("window.addEventListener('deviceorientationabsolute',onOrient);window.addEventListener('deviceorientation',onOrient);")
        sb.append("function setTab(t,el){tab=t;document.querySelectorAll('.tab').forEach(b=>b.classList.remove('active'));el.classList.add('active');['text','image','audio'].forEach(p=>document.getElementById('p-'+p).style.display=(p===t?'block':'none'));}")
        sb.append("function toggleRec(){const b=document.getElementById('rbtn');if(!isRec){if(AndroidNative.startAudioRecord()){isRec=true;b.innerText='⏹️ إيقاف التسجيل';document.getElementById('rstat').innerText='جاري التسجيل...';}}else{mdata=AndroidNative.stopAudioRecord();isRec=false;b.innerText='🎙️ إعادة التسجيل';document.getElementById('rstat').innerText=mdata?'✅ تم حفظ الصوت بنجاح':'❌ فشل التسجيل';}}")
        sb.append("document.getElementById('img').onchange=e=>{const f=e.target.files[0];if(!f)return;const r=new FileReader();r.onload=ev=>{const im=new Image();im.onload=()=>{const cv=document.createElement('canvas');const max=900;let w=im.width,h=im.height;if(w>max){h=h*(max/w);w=max;}cv.width=w;cv.height=h;cv.getContext('2d').drawImage(im,0,0,w,h);mdata=cv.toDataURL('image/jpeg',0.75);document.getElementById('istat').innerText='✅ تم تجهيز الصورة';};im.src=ev.target.result;};r.readAsDataURL(f);};")
        sb.append("function openCreate(){mode='lock';mdata=null;document.getElementById('istat').innerText='';document.getElementById('rstat').innerText='';document.getElementById('modal').style.display='flex';}")
        sb.append("function closeCreate(){mode='idle';document.getElementById('modal').style.display='none';}")
        sb.append("async function crypt(txt,uid,enc){const h=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(uid));const k=await crypto.subtle.importKey('raw',h,{name:'AES-GCM'},false,['encrypt','decrypt']);if(enc){const iv=crypto.getRandomValues(new Uint8Array(12));const c=await crypto.subtle.encrypt({name:'AES-GCM',iv},k,new TextEncoder().encode(txt));return btoa(String.fromCharCode(...iv))+':'+btoa(String.fromCharCode(...new Uint8Array(c)));}else{const p=txt.split(':');const iv=Uint8Array.from(atob(p[0]),c=>c.charCodeAt(0));const d=Uint8Array.from(atob(p[1]),c=>c.charCodeAt(0));const dec=await crypto.subtle.decrypt({name:'AES-GCM',iv},k,d);return new TextDecoder().decode(dec);}}")
        sb.append("window.onNativeNfcScanned=async function(uid){if(mode==='lock'){let p=tab==='text'?document.getElementById('txt').value:mdata;if(!p){alert('أدخل النص أو اختر الصورة أو سجل الصوت أولاً!');return;}const e=await crypt(p,uid,true);const item={id:Date.now(),lat,lng,hdg,pitch,type:tab,data:e,unlocked:false,clear:''};await saveCap(item);lastSeen=item;closeCreate();document.getElementById('status').innerText='✅ تم القفل بالبطاقة: '+uid;renderCap(item);}else{const target=lastSeen||(caps.length?caps[caps.length-1]:null);if(!target){document.getElementById('status').innerText='لا يوجد طَيْف محفوظ بعد';return;}try{const clear=await crypt(target.data,uid,false);target.unlocked=true;target.clear=clear;lastSeen=target;document.getElementById('status').innerText='🎉 تم فتح الطَيْف بالبطاقة ('+uid+')';renderCap(target);}catch(err){document.getElementById('status').innerText='❌ البطاقة غير مطابقة!';}}};")
        sb.append("function renderCap(c){const el=document.getElementById('capsule');el.style.display='block';if(!c.unlocked){document.getElementById('cicon').innerText='🔒';document.getElementById('ctitle').innerText='طَيْف مشفر مثبت هنا!';document.getElementById('cbody').innerText='قرّب بطاقة التعريف الوطنية لفتحه';}else{document.getElementById('cicon').innerText='🔓';document.getElementById('ctitle').innerText='محتوى الطَيْف:';const b=document.getElementById('cbody');if(c.type==='text')b.innerHTML='<div style=\"font-size:18px;color:#4ade80;margin-top:8px\">'+c.clear+'</div>';if(c.type==='image')b.innerHTML='<img src=\"'+c.clear+'\" style=\"width:100%;max-height:220px;object-fit:contain;border-radius:10px;margin-top:8px\"/>';if(c.type==='audio')b.innerHTML='<audio controls src=\"'+c.clear+'\" style=\"width:100%;margin-top:8px\"></audio>';}}")
        sb.append("function updateAR(){const el=document.getElementById('capsule');if(!caps.length){el.style.display='none';return;}let found=null,dx=0,dy=0;for(const c of caps){let diffH=((c.hdg-hdg+540)%360)-180;let diffP=c.pitch-pitch;if(Math.abs(diffH)<=35&&Math.abs(diffP)<=35){found=c;dx=diffH*6;dy=diffP*6;break;}}if(found){if(!lastSeen||lastSeen.id!==found.id){lastSeen=found;renderCap(found);}el.style.display='block';el.style.transform='translate(calc(-50% + '+dx+'px), calc(-50% + '+dy+'px))';}else{el.style.display='none';}}")
        sb.append("loadCaps();")
        sb.append("</script></body></html>")
        return sb.toString()
    }
}
