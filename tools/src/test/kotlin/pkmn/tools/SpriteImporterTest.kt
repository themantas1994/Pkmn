package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.FormKind
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpriteImporterTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val content by lazy { Content.load(File(repo, "assets/data")) }
    private val mapping by lazy {
        SpriteMapping(Csv.read(File(repo, "data-src/pokeapi/pokemon.csv")), Csv.read(File(repo, "data-src/pokeapi/pokemon_forms.csv")),
            Csv.read(File(repo, "data-src/pokeapi/pokemon_species.csv")))
    }

    private fun stem(id: String) = mapping.map(content.dex.species(id))?.stem

    @Test
    fun mapsSpeciesToPokeApiStems() {
        assertEquals("1", stem("bulbasaur"))
        assertEquals("10034", stem("charizardmegax"))
        assertEquals("493-fire", stem("arceusfire"))
        assertEquals("10025", stem("meowsticf"))
        assertEquals("10257", stem("maushold"))
        assertEquals("925", stem("mausholdfour"))
        assertEquals("10093", stem("raticatealolatotem"))
        assertEquals("10155", stem("necrozmaduskmane"))
    }

    @Test
    fun everyBaseSpeciesAndNearlyEveryFormIsMapped() {
        val unmapped = content.species.filter { mapping.map(it) == null }
        assertTrue(unmapped.none { it.formKind == FormKind.BASE })
        // PokeAPI has no separate Pokémon for Ogerpon's Terastallized masks.
        assertTrue(unmapped.all { it.id.startsWith("ogerpon") && it.id.endsWith("tera") }, "unmapped: ${unmapped.map { it.id }}")
    }

    @Test
    fun parsesOptions() {
        val o = SpriteImporter.parseArgs(listOf("--only=1-151,251", "--force", "--kinds=front,icon", "--jobs=4"))
        assertEquals(listOf(1..151, 251..251), o.only)
        assertTrue(o.force); assertEquals(listOf("front", "icon"), o.kinds); assertEquals(4, o.jobs)
        assertEquals(listOf(1..30), SpriteImporter.parseArgs(listOf("--only=1-30 --forms=false")).only)
        assertFailsWith<IllegalArgumentException> { SpriteImporter.parseArgs(listOf("--kinds=sideways")) }
        assertFailsWith<IllegalArgumentException> { SpriteImporter.parseArgs(listOf("--bogus")) }
    }

    @Test
    fun iconFallsBackToGen7() {
        val urls = SpriteImporter(repo, emptyList()).candidatesFor("icon", "6")
        assertEquals(2, urls.size); assertTrue(urls[0].contains("generation-viii") && urls[1].contains("generation-vii/"))
    }

    @Test
    fun importsUserSuppliedZip() {
        val root = Files.createTempDirectory("sprites").toFile()
        try {
            File(root, "assets/data").mkdirs()
            File(repo, "assets/data/species.json").copyTo(File(root, "assets/data/species.json"))
            val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
            val zip = File(root, "pack.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                for ((name, data) in listOf("pack/front/pikachu.png" to png, "pack/back/notamon.png" to png, "pack/icon/eevee.png" to "x".toByteArray(), "readme.txt" to png)) {
                    z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
                }
            }
            val ok = SpriteImporter(root, listOf("--from=${zip.path}")).run()
            assertTrue(!ok) // notamon + non-PNG icon are reported as failures
            assertTrue(File(root, "assets-local/sprites/pokemon/front/pikachu.png").isFile)
            val log = Json.parseToJsonElement(File(root, "assets-local/sprites/IMPORT_LOG.json").readText()).jsonObject
            assertEquals(false, log["redistributable"]!!.jsonPrimitive.boolean)
            val entry = log["files"]!!.jsonArray.single().jsonObject
            assertEquals(sha256(png), entry["sha256"]!!.jsonPrimitive.content)
            assertEquals(2, log["failures"]!!.jsonArray.size)
        } finally { root.deleteRecursively() }
    }
}
