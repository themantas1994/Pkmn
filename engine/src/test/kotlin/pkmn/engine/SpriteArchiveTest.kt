package pkmn.engine

import pkmn.engine.util.SpriteArchive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpriteArchiveTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun `extracts sprites and rejects traversal and foreign files`() {
        val dir = Files.createTempDirectory("imp").toFile()
        val data = zip(
            "assets-local/sprites/pokemon/front/pikachu.png" to byteArrayOf(1, 2, 3),
            "sprites/pokemon/back/eevee.png" to byteArrayOf(4),
            "sprites/../../evil.png" to byteArrayOf(5),
            "sprites/run.sh" to byteArrayOf(6),
            "readme.txt" to byteArrayOf(7),
        )
        val r = SpriteArchive.extract(ByteArrayInputStream(data), dir)
        assertEquals(2, r.extracted)
        assertEquals(3, r.skipped)
        assertTrue(File(dir, "sprites/pokemon/front/pikachu.png").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(File(dir, "sprites/pokemon/back/eevee.png").exists())
        assertFalse(File(dir.parentFile.parentFile, "evil.png").exists())
        dir.deleteRecursively()
    }
}
