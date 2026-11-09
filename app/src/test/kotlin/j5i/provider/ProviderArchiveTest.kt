package j5i.spatial8d.provider

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderArchiveTest {
    @Test fun encryptsAndAuthenticatesProviderFile() {
        val data = "provider test data".toByteArray()
        val secret = "test passphrase".toCharArray()
        val encrypted = ProviderArchive.encrypt(data, secret)
        assertTrue(ProviderArchive.isEncrypted(encrypted))
        assertFalse(encrypted.contentEquals(data))
        assertContentEquals(data, ProviderArchive.decrypt(encrypted, secret))
        assertFails { ProviderArchive.decrypt(encrypted, "wrong".toCharArray()) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFails { ProviderArchive.decrypt(encrypted, secret) }
    }
}
