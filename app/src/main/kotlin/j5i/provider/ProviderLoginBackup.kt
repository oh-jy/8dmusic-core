package j5i.spatial8d.provider

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM backup with a device-wrapped key; only the platform id is public. */
internal object ProviderLoginBackup {
    private const val path = "Download/8d/login/"
    private val magic = "8DLOGIN1".toByteArray(Charsets.US_ASCII)

    fun fileName(id: String) = "${id.removePrefix("platform.")}.8dp"

    fun savePortable(context: Context, cookies: Map<String, String>, password: CharArray): String {
        require(password.size >= 8) { "备份密码至少需要 8 个字符" }
        require(cookies.isNotEmpty() && cookies.size <= 64) { "没有可导出的登录信息" }
        val entries = JSONObject()
        cookies.forEach { (id, cookie) -> entries.put(id, cookie) }
        val plain = JSONObject().put("version", 2).put("providers", entries).toString().toByteArray(Charsets.UTF_8)
        val bytes = try { ProviderArchive.encrypt(plain, password) } finally { plain.fill(0) }
        val name = "all-providers-${System.currentTimeMillis()}.8dp"
        write(context, name, bytes)
        return name
    }

    fun portableCookies(bytes: ByteArray, id: String, password: CharArray): Map<String, String> {
        val plain = ProviderArchive.decrypt(bytes, password)
        try {
            val text = String(plain, Charsets.UTF_8)
            if (!text.trimStart().startsWith("{")) return mapOf(id to text)
            val json = JSONObject(text)
            if (json.optInt("version") == 1) {
                require(json.optString("provider") == id) { "登录文件不属于当前平台" }
                return mapOf(id to json.getString("cookie"))
            }
            require(json.optInt("version") == 2) { "登录备份版本不支持" }
            val providers = json.getJSONObject("providers")
            val ids = providers.keys().asSequence().toList()
            require(ids.size in 1..64 && ids.all { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) }) { "登录备份来源无效" }
            return ids.associateWith { providers.getString(it).also { cookie -> require(cookie.length in 1..16 * 1024) } }
        } finally { plain.fill(0) }
    }

    fun save(context: Context, id: String, cookie: String) {
        val plaintext = JSONObject().put("version", 1).put("provider", id).put("cookie", cookie).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = magic + id.toByteArray(Charsets.UTF_8).size.toByte() + id.toByteArray(Charsets.UTF_8) +
            cipher.iv.size.toByte() + cipher.iv + cipher.doFinal(plaintext)
        write(context, fileName(id), bytes)
    }

    fun read(bytes: ByteArray, id: String): String {
        require(isEncrypted(bytes)) { "不是受支持的加密登录文件" }
        val idLength = bytes[magic.size].toInt() and 255
        val ivPosition = magic.size + 1 + idLength
        require(idLength in 1..64 && bytes.size > ivPosition + 2)
        require(String(bytes, magic.size + 1, idLength, Charsets.UTF_8) == id) { "登录文件不属于当前平台" }
        val ivLength = bytes[ivPosition].toInt() and 255
        require(ivLength == 12 && bytes.size > ivPosition + 1 + ivLength + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, ivPosition + 1, ivLength))
        val plaintext = try {
            cipher.doFinal(bytes, ivPosition + 1 + ivLength, bytes.size - ivPosition - 1 - ivLength)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("登录备份无法解密：文件可能损坏，或卸载应用后原设备密钥已丢失", e)
        }
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("provider") == id)
        return json.getString("cookie")
    }

    fun isEncrypted(bytes: ByteArray) = bytes.size >= magic.size + 1 && bytes.copyOf(magic.size).contentEquals(magic)

    fun exists(context: Context, id: String) = find(context, fileName(id)) != null

    fun restore(context: Context, id: String): String {
        val uri = find(context, fileName(id)) ?: return ""
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= 16 * 1024 + 128) { "登录备份过大" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        return read(bytes, id)
    }

    fun delete(context: Context, id: String) {
        val uri = find(context, fileName(id))
        if (uri != null) context.contentResolver.delete(uri, null, null)
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "8d/login/${fileName(id)}").delete()
    }

    private fun write(context: Context, name: String, bytes: ByteArray) {
        require(Build.VERSION.SDK_INT >= 29) { "自动登录备份需要 Android 10 或更高版本" }
        val resolver = context.contentResolver
        find(context, name)?.let { resolver.delete(it, null, null) }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "无法创建登录备份" }
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    private fun find(context: Context, name: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(name, path), null)?.use {
                if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey("provider_login_backup", null)?.let { return it as SecretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("provider_login_backup", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
