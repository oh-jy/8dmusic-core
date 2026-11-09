package j5i.spatial8d.provider

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-encrypted file envelope. It is not a signature or proof of origin. */
internal object ProviderArchive {
    private val magic = byteArrayOf(0x38, 0x44, 0x50, 0x52, 0x4f, 0x56, 0x01, 0x00)
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val ITERATIONS = 210_000

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= magic.size &&
        bytes.copyOfRange(0, magic.size).contentEquals(magic)

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(plain.size in 1..ProviderJar.MAX_BYTES && passphrase.isNotEmpty())
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val header = magic + salt + nonce
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce)
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, passphrase: CharArray): ByteArray {
        val headerSize = magic.size + SALT_SIZE + NONCE_SIZE
        require(isEncrypted(bytes) && bytes.size in (headerSize + 17)..(ProviderJar.MAX_BYTES + headerSize + 16))
        require(passphrase.isNotEmpty())
        val salt = bytes.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val nonce = bytes.copyOfRange(magic.size + SALT_SIZE, headerSize)
        val cipher = cipher(Cipher.DECRYPT_MODE, passphrase, salt, nonce)
        cipher.updateAAD(bytes, 0, headerSize)
        return cipher.doFinal(bytes, headerSize, bytes.size - headerSize)
    }

    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            }
        } finally { key.fill(0) }
    }
}
