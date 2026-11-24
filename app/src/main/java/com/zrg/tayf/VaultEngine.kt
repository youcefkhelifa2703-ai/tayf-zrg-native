package com.zrg.tayf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VaultEngine {
    private fun vaultDir(ctx: Context) = File(ctx.filesDir, "tayf_vault").apply { if (!exists()) mkdirs() }
    private fun capsFile(ctx: Context) = File(ctx.filesDir, "tayf_spatial_v2.json")

    fun loadAll(ctx: Context): JSONArray =
        if (capsFile(ctx).exists()) JSONArray(capsFile(ctx).readText()) else JSONArray()

    fun clearAll(ctx: Context) {
        capsFile(ctx).delete()
        vaultDir(ctx).deleteRecursively()
    }

    fun readUri(ctx: Context, uri: Uri): Triple<ByteArray, String, String>? = try {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        var name = "DOC_${System.currentTimeMillis()}"
        ctx.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        val raw = ctx.contentResolver.openInputStream(uri)?.readBytes() ?: return@try null
        val bytes = if (mime.startsWith("image/")) {
            val b = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (b != null) {
                val r = minOf(1280f / b.width, 1280f / b.height, 1f)
                ByteArrayOutputStream().also {
                    Bitmap.createScaledBitmap(b, (b.width * r).toInt(), (b.height * r).toInt(), true)
                        .compress(Bitmap.CompressFormat.JPEG, 88, it)
                }.toByteArray()
            } else raw
        } else raw
        Triple(bytes, name, mime)
    } catch (_: Exception) { null }

    fun saveSpatialItem(
        ctx: Context, x: Float, y: Float, z: Float, lockType: String, secret: String,
        note: String, fileData: Triple<ByteArray, String, String>?
    ) {
        val id = System.currentTimeMillis().toString()
        var saved = ""
        if (fileData != null) {
            saved = "$id.enc"
            File(vaultDir(ctx), saved).writeBytes(encrypt(fileData.first, secret))
        }
        val meta = JSONObject().apply {
            put("txt", note); put("file", saved)
            put("name", fileData?.second ?: ""); put("mime", fileData?.third ?: "")
        }.toString()

        val arr = loadAll(ctx)
        arr.put(JSONObject().apply {
            put("id", id); put("x", x.toDouble()); put("y", y.toDouble()); put("z", z.toDouble())
            put("lt", lockType)
            put("meta", Base64.encodeToString(encrypt(meta.toByteArray(), secret), Base64.NO_WRAP))
        })
        capsFile(ctx).writeText(arr.toString())
    }

    fun unlockItem(ctx: Context, item: JSONObject, secret: String): Pair<JSONObject, ByteArray?>? {
        val dec = decrypt(Base64.decode(item.getString("meta"), Base64.NO_WRAP), secret) ?: return@try null
        val meta = JSONObject(String(dec))
        val fn = meta.optString("file")
        val bytes = if (fn.isNotEmpty()) decrypt(File(vaultDir(ctx), fn).readBytes(), secret) else null
        return Pair(meta, bytes)
    }

    private fun encrypt(plain: ByteArray, secret: String): ByteArray {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray, secret: String): ByteArray? = try {
        val l = data[0].toInt()
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(1, 1 + l)))
        c.doFinal(data.copyOfRange(1 + l, data.size))
    } catch (_: Exception) { null }
}
