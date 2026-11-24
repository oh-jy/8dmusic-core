package j5i.spatial8d.nfc

import android.content.Context
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.content.pm.PackageManager
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log

private const val TAG = "8DNfc"

internal object NfcTagWriter {
    fun available(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) &&
            NfcAdapter.getDefaultAdapter(context.applicationContext)?.isEnabled == true

    fun write(tag: Tag, uri: String) {
        Log.i(TAG, "write start tech=${tag.techList.joinToString()}")
        fun message(value: String) = android.nfc.NdefMessage(arrayOf(NdefRecord.createUri(value)))
        Ndef.get(tag)?.let { ndef ->
            require(ndef.isWritable) { "nfc.error.readonly" }
            val message = message(uri)
            require(message.toByteArray().size <= ndef.maxSize) { "nfc.error.capacity" }
            try {
                ndef.connect()
                ndef.writeNdefMessage(message)
                Log.i(TAG, "ndef message written, verifying")
                verify(ndef, uri)
            } finally {
                runCatching { ndef.close() }
            }
            Log.i(TAG, "write success via Ndef")
            return
        }
        val formatable = NdefFormatable.get(tag) ?: throw IllegalStateException("nfc.error.format")
        try {
            formatable.connect()
            formatable.format(message(uri))
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun verify(ndef: Ndef, uri: String) {
        val message = ndef.ndefMessage ?: throw IllegalStateException("nfc.write.failed")
        val record = message.records.singleOrNull() ?: throw IllegalStateException("nfc.write.failed")
        require(record.toUri()?.toString() == uri) { "nfc.write.failed" }
    }
}
