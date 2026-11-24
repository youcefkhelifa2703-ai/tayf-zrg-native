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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultEngine {
    private const val ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_LENGTH = 16

    private fun vaultDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "tayf_vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun capsFile(ctx: Context): File {
        return File(ctx.filesDir, "tayf_spatial_v2.json")
    }

    fun loadAll(ctx: Context): JSONArray {
        val file = capsFile(ctx)
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun clearAll(ctx: Context) {
        capsFile(ctx).delete()
        vaultDir(ctx).deleteRecursively()
    }

    fun readUri(ctx: Context, uri: Uri): Triple<ByteArray, String, String>? {
        try {
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = "DOC_${System.currentTimeMillis()}"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
            val raw = ctx.contentResolver.openInputStream(uri)?.readBytes() ?: return null
            if (!mime.startsWith("image/")) return Triple(raw, name, mime)

            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return Triple(raw, name, mime)
            val scale = minOf(1280f / bitmap.width, 1280f / bitmap.height, 1f)
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 88, output)
            if (!resized.isRecycled) resized.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
            return Triple(output.toByteArray(), name, mime)
        } catch (_: Exception) {
            return null
        }
    }

    fun saveSpatialItem(
        ctx: Context,
        x: Float,
        y: Float,
        z: Float,
        lockType: String,
        secret: String,
        note: String,
        fileData: Triple<ByteArray, String, String>?
    ) {
        val id = System.currentTimeMillis().toString()
        var saved = ""
        if (fileData != null) {
            saved = "$id.enc"
            File(vaultDir(ctx), saved).writeBytes(encrypt(fileData.first, secret))
        }
        val metadata = JSONObject().apply {
            put("txt", note)
            put("file", saved)
            put("name", fileData?.second ?: "")
            put("mime", fileData?.third ?: "")
        }.toString()

        val arr = loadAll(ctx)
        arr.put(JSONObject().apply {
            put("id", id)
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("z", z.toDouble())
            put("lt", lockType)
            put("meta", Base64.encodeToString(encrypt(metadata.toByteArray(Charsets.UTF_8), secret), Base64.NO_WRAP))
        })
        capsFile(ctx).writeText(arr.toString())
    }

    fun unlockItem(ctx: Context, item: JSONObject, secret: String): Pair<JSONObject, ByteArray?>? {
        val encryptedMetadata = try {
            Base64.decode(item.getString("meta"), Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
        val metadataBytes = decrypt(encryptedMetadata, secret) ?: return null
        val metadata = try {
            JSONObject(String(metadataBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val fileName = metadata.optString("file")
        val bytes = if (fileName.isNotEmpty()) {
            val file = File(vaultDir(ctx), fileName)
            if (!file.exists()) null else decrypt(file.readBytes(), secret)
        } else {
            null
        }
        return Pair(metadata, bytes)
    }

    private fun deriveKey(secret: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(plain: ByteArray, secret: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val key = deriveKey(secret, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plain)

        val output = ByteArray(1 + SALT_LENGTH + 1 + iv.size + cipherText.size)
        var offset = 0
        output[offset++] = SALT_LENGTH.toByte()
        salt.copyInto(output, offset)
        offset += salt.size
        output[offset++] = iv.size.toByte()
        iv.copyInto(output, offset)
        offset += iv.size
        cipherText.copyInto(output, offset)
        return output
    }

    private fun decrypt(data: ByteArray, secret: String): ByteArray? {
        try {
            if (data.size < 2) return null
            var offset = 0
            val saltLength = data[offset++].toInt() and 0xFF
            if (saltLength <= 0 || offset + saltLength >= data.size) return null
            val salt = data.copyOfRange(offset, offset + saltLength)
            offset += saltLength

            val ivLength = data[offset++].toInt() and 0xFF
            if (ivLength <= 0 || offset + ivLength >= data.size) return null
            val iv = data.copyOfRange(offset, offset + ivLength)
            offset += ivLength

            val cipherText = data.copyOfRange(offset, data.size)
            val key = deriveKey(secret, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(cipherText)
        } catch (_: Exception) {
            return null
        }
    }
}
