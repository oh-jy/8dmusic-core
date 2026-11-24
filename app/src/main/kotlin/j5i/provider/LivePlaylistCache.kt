package j5i.spatial8d.provider

import java.io.File

internal object LivePlaylistCache {
    private const val MAX_BYTES = 2 * 1024 * 1024

    @Synchronized
    fun get(file: File, validate: (String) -> Boolean = { it.contains(",#genre#") }, download: () -> String): String {
        if (file.isFile && file.length() in 1..MAX_BYTES.toLong()) return file.readText(Charsets.UTF_8)
        return refresh(file, validate, download)
    }

    @Synchronized
    fun refresh(file: File, validate: (String) -> Boolean = { it.contains(",#genre#") }, download: () -> String): String {
        val body = download()
        require(body.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES && validate(body)) { "直播目录内容无效" }
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("live-catalog-", ".tmp", file.parentFile)
        try {
            temp.writeText(body, Charsets.UTF_8)
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        return body
    }
}
