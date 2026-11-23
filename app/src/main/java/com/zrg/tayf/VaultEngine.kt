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
    private fun capsFile(ctx: Context) = File(ctx.filesDir, "tayf_anchors.json")

    fun loadAll(ctx: Context): JSONArray =
        if (capsFile(ctx).exists()) JSONArray(capsFile(ctx).readText()) else JSONArray()

    fun clearAll(ctx: Context) {
        capsFile(ctx).delete()
        vaultDir(ctx).deleteRecursively()
    }

    fun readUri(ctx: Context, uri: Uri): Triple<ByteArray, String, String>? {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = "FILE_${System.currentTimeMillis()}"
            ctx.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
            val raw = ctx.contentResolver.openInputStream(uri)?.readBytes() ?: return null
            val finalBytes = if (mime.startsWith("image/")) {
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bmp != null) {
                    val r = minOf(1200f / bmp.width, 1200f / bmp.height, 1f)
                    ByteArrayOutputStream().also {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
                            .compress(Bitmap.CompressFormat.JPEG, 85, it)
                    }.toByteArray()
                } else raw
            } else raw
            Triple(finalBytes, name, mime)
        } catch (_: Exception) { null }
    }

    fun saveAnchor(
        ctx: Context, az: Float, pt: Float, lockType: String, secret: String,
        note: String, fileData: Triple<ByteArray, String, String>?
    ) {
        val id = System.currentTimeMillis().toString()
        var savedName = ""
        if (fileData != null) {
            savedName = "$id.enc"
            File(vaultDir(ctx), savedName).writeBytes(encrypt(fileData.first, secret))
        }
        val meta = JSONObject().apply {
            put("txt", note)
            put("file", savedName)
            put("name", fileData?.second ?: "")
            put("mime", fileData?.third ?: "")
        }.toString()

        val arr = loadAll(ctx)
        arr.put(JSONObject().apply {
            put("id", id)
            put("az", az.toDouble())
            put("pt", pt.toDouble())
            put("lt", lockType)
            put("meta", Base64.encodeToString(encrypt(meta.toByteArray(), secret), Base64.NO_WRAP))
        })
        capsFile(ctx).writeText(arr.toString())
    }

    fun unlockAnchor(ctx: Context, item: JSONObject, secret: String): Pair<JSONObject, ByteArray?>? {
        val decMeta = decrypt(Base64.decode(item.getString("meta"), Base64.NO_WRAP), secret) ?: return null
        val meta = JSONObject(String(decMeta))
        val fn = meta.optString("file")
        val plainBytes = if (fn.isNotEmpty()) decrypt(File(vaultDir(ctx), fn).readBytes(), secret) else null
        return Pair(meta, plainBytes)
    }

    private fun encrypt(plain: ByteArray, secret: String): ByteArray {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray, secret: String): ByteArray? = try {
        val ivLen = data[0].toInt()
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(1, 1 + ivLen)))
        cipher.doFinal(data.copyOfRange(1 + ivLen, data.size))
    } catch (_: Exception) { null }
}
