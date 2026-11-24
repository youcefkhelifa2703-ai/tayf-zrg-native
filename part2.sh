cat << 'EOF' > app/src/main/java/com/zrg/tayf/HelperExt.kt
package com.zrg.tayf

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.*
import android.os.*
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.FileProvider
import java.io.File

fun Context.beep() {
    try {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(110, -1))
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
    } catch (_: Exception) {}
}

fun Context.openGhostFile(file: File?, mime: String) {
    val f = file ?: return
    if (mime.startsWith("audio/")) {
        MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
        return
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "فتح الملف"))
}

class RadarPulseView(c: Context) : View(c) {
    private var phase = 0f
    private var col = Color.parseColor("#CFB07E")
    private val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L; repeatCount = -1; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
        }
    }

    fun triggerLock() {
        col = Color.parseColor("#EED6AF")
        postDelayed({ col = Color.parseColor("#CFB07E") }, 2000)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - 14f
        for (i in 0..2) {
            val f = (phase + i * 0.33f) % 1f
            p1.color = col; p1.alpha = ((1f - f) * 120).toInt()
            canvas.drawCircle(cx, cy, r * f, p1)
        }
        p2.color = col; p2.alpha = 180
        canvas.drawCircle(cx, cy, 12f, p2)
    }
}
EOF
