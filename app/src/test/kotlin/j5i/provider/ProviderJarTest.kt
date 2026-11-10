package j5i.spatial8d.provider

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ProviderJarTest {
    @TempDir lateinit var temporary: Path

    private fun jar(file: File, entries: Map<String, String>) {
        JarOutputStream(file.outputStream()).use { out -> entries.forEach { (name, data) ->
            out.putNextEntry(JarEntry(name)); out.write(data.toByteArray()); out.closeEntry()
        } }
    }

    @Test fun verifiesAllCodeAndMetadataAreSigned() {
        val file = temporary.resolve("provider.jar").toFile()
        jar(file, mapOf("provider.json" to """{"version":1,"id":"test.music","name":"Test","entryClass":"example.Provider","domains":["example.com","*.music.126.net"],"capabilities":["search","resolve"]}""",
            "classes.dex" to "test dex"))
        assertFails { ProviderJar.inspect(file) }
        val javaHome = File(System.getProperty("java.home"), "bin")
        fun run(tool: String, vararg args: String) {
            val exe = File(javaHome, tool + if ((System.getProperty("os.name") ?: "").startsWith("Windows")) ".exe" else "")
            val p = ProcessBuilder(listOf(exe.absolutePath) + args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { output }
        }
        val key = temporary.resolve("test.p12").toString()
        run("keytool", "-genkeypair", "-alias", "test", "-keystore", key, "-storepass", "testpass123",
            "-keypass", "testpass123", "-dname", "CN=Test", "-keyalg", "RSA", "-validity", "1", "-storetype", "PKCS12")
        run("jarsigner", "-keystore", key, "-storepass", "testpass123", file.absolutePath, "test")
        val descriptor = ProviderJar.inspect(file)
        assertEquals("test.music", descriptor.id)
        assertEquals(listOf("example.com", "*.music.126.net"), descriptor.domains)
        assertEquals(64, descriptor.signerSha256.length)
        assertTrue(descriptor.fileSha256.length == 64)
        val combined = temporary.resolve("combined.jar").toFile()
        val first = """{"version":1,"id":"test.one","name":"One","entryClass":"example.One","domains":["one.example.com"],"capabilities":["search"]}"""
        val second = """{"version":1,"id":"test.two","name":"Two","entryClass":"example.Two","domains":["two.example.com"],"capabilities":["resolve"]}"""
        jar(combined, mapOf("provider.json" to """{"version":1,"providers":[$first,$second]}""", "classes.dex" to "test dex"))
        run("jarsigner", "-keystore", key, "-storepass", "testpass123", combined.absolutePath, "test")
        val descriptors = ProviderJar.inspectAll(combined)
        assertEquals(listOf("test.one", "test.two"), descriptors.map { it.id })
        assertEquals(listOf("one.example.com"), descriptors[0].domains)
        assertEquals(listOf("two.example.com"), descriptors[1].domains)
        assertEquals(descriptors[0].fileSha256, descriptors[1].fileSha256)
        val modified = temporary.resolve("modified.jar").toFile()
        JarFile(file).use { source -> JarOutputStream(modified.outputStream()).use { output ->
            val entries = source.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                output.putNextEntry(JarEntry(entry.name))
                if (entry.name == "classes.dex") output.write("tampered".toByteArray())
                else source.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
        } }
        assertFails { ProviderJar.inspect(modified) }
    }
}
