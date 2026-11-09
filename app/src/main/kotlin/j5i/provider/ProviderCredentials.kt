package j5i.spatial8d.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-key encrypted, per-provider sessions. Never written to logs or provider JSON. */
internal class ProviderCredentials(private val context: Context) {
    private val prefs = context.getSharedPreferences("provider_credentials_v1", Context.MODE_PRIVATE)

    fun save(id: String, cookie: String) = synchronized(lock) {
        require(cookie.isNotBlank() && cookie.length <= 16 * 1024)
        val key = key()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(cookie.toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("iv", iv.joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("data", encrypted.joinToString("") { "%02x".format(it.toInt() and 255) })
        check(prefs.edit().putString(id, json.toString()).remove("logged_out:$id").commit())
    }

    fun get(id: String): String = synchronized(lock) {
        val value = prefs.getString(id, "") ?: ""
        if (value.isNotEmpty() && !prefs.getBoolean("logged_out:$id", false)) {
            try {
                val json = JSONObject(value)
                val iv = decode(json.getString("iv")); val encrypted = decode(json.getString("data"))
                require(iv.size == 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                return@synchronized String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        if (prefs.getBoolean("logged_out:$id", false)) return@synchronized ""
        val restored = try { ProviderLoginBackup.restore(context, id) } catch (_: Exception) { "" }
        if (restored.isNotEmpty()) save(id, restored)
        restored
    }

    fun clear(id: String) = synchronized(lock) { check(prefs.edit().putBoolean("logged_out:$id", true).commit()) }

    companion object { private val lock = Any() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey("provider_credentials", null)?.let { return it as SecretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("provider_credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun decode(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { ((Character.digit(value[it * 2], 16) shl 4) + Character.digit(value[it * 2 + 1], 16)).toByte() }
    }
}
