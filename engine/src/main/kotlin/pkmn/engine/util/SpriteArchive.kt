package pkmn.engine.util

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts a user-supplied sprite archive (layout of `assets-local/`, see docs/ASSET_IMPORT.md) into [destDir].
 * Only PNG/JSON files under `sprites/` are accepted; paths escaping the destination are rejected.
 */
object SpriteArchive {
    data class Result(val extracted: Int, val skipped: Int)

    private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    fun extract(input: InputStream, destDir: File): Result {
        val root = destDir.canonicalFile
        var extracted = 0
        var skipped = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/').removePrefix("assets-local/").trimStart('/')
                val ext = name.substringAfterLast('.', "").lowercase()
                if (entry.isDirectory || !name.startsWith("sprites/") || ext !in setOf("png", "json")) { skipped++; continue }
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) { skipped++; continue }
                out.parentFile.mkdirs()
                val tmp = File(out.path + ".part")
                var size = 0L
                tmp.outputStream().use { os ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_ENTRY_BYTES || total + size > MAX_TOTAL_BYTES) break
                        os.write(buf, 0, n)
                    }
                }
                if (size > MAX_ENTRY_BYTES || total + size > MAX_TOTAL_BYTES) { tmp.delete(); skipped++; continue }
                total += size
                if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
                extracted++
            }
        }
        return Result(extracted, skipped)
    }
}
