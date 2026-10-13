package pkmn.tools

import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetManifestToolTest {
    private val root = Files.createTempDirectory("assets-test").toFile()

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun png(rel: String, w: Int, h: Int) = File(root, rel).also { it.parentFile.mkdirs(); ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }
    private fun text(rel: String, s: String) = File(root, rel).also { it.parentFile.mkdirs(); it.writeText(s) }

    private fun setup(redistributable: Boolean = true) {
        text("assets/ATTRIBUTION.json", """{"version":1,"entries":[
            {"pathGlob":"assets/**","source":"test","license":"CC0","author":"me","redistributable":$redistributable,"status":"placeholder"},
            {"pathGlob":"assets-local/**","source":"local","license":"unknown","author":"?","redistributable":false}]}""")
        text("assets/data/species.json", """[{"id":"bulbasaur","num":1,"name":"Bulbasaur","baseSpecies":"bulbasaur","types":["Grass"],
            "baseStats":{"hp":45,"atk":49,"def":49,"spa":65,"spd":65,"spe":45},"abilities":{"0":"overgrow"},"maleRatio":0.875,"heightm":0.7,"weightkg":6.9}]""")
    }

    @Test
    fun validAssetsProduceManifestEntries() {
        setup()
        png("assets/tiles/grass.png", 32, 16)
        png("assets/characters/player.png", 64, 32)
        text("assets/characters/player.json", """{"frameWidth":16,"frameHeight":16,"frameCount":8,"animations":{"walk":[0,1,2,3]}}""")
        png("assets-local/sprites/pokemon/front/bulbasaur.png", 96, 96)
        val tool = AssetManifestTool(root)
        val scan = tool.scan()
        assertEquals(emptyList(), scan.issues.errors.map { "${it.code} ${it.subject} ${it.message}" })
        val player = scan.bundled.first { it.id == "characters/player" }
        assertEquals("character", player.category); assertEquals(8, player.frames); assertEquals(64, player.width)
        assertEquals("tile", scan.bundled.first { it.id == "tiles/grass" }.category)
        val sprite = scan.local.single()
        assertEquals("pokemon-sprite", sprite.category); assertFalse(sprite.attribution!!.redistributable)
        assertTrue(scan.bundled.none { it.path.startsWith("assets/data") })

        assertTrue(tool.run())
        val manifest = Json.parseToJsonElement(File(root, "assets/manifest/assets.json").readText()).jsonObject
        assertEquals(3, manifest["count"]!!.jsonPrimitive.int)
        assertTrue(File(root, "assets-local/manifest/assets.json").isFile)
        assertTrue(File(root, "reports/asset-validation.json").isFile)
    }

    @Test
    fun detectsInvalidAssets() {
        setup()
        png("assets/tiles/odd.png", 10, 12)
        text("assets/ui/empty.png", "")
        text("assets/ui/logo.bmp", "BM")
        png("assets/characters/npc.png", 32, 16)
        text("assets/characters/npc.json", """{"frameWidth":16,"frameHeight":16,"frameCount":5,"animations":{"idle":[0,7]}}""")
        text("assets/anim/frames.json", """{"frames":["f0.png","f1.png"]}""")
        png("assets/anim/f0.png", 8, 8)
        png("assets/sprites/pokemon/front/notapokemon.png", 96, 96)
        png("assets/sprites/pokemon/sideways/bulbasaur.png", 96, 96)
        val scan = AssetManifestTool(root).scan()
        val codes = scan.issues.errors.map { it.code }.toSet()
        for (c in listOf("asset-grid", "asset-empty", "asset-format", "sheet-missing-frames", "sprite-species", "sprite-kind"))
            assertTrue(c in codes, "missing $c in $codes")
        assertFalse(AssetManifestTool(root).run())
    }

    @Test
    fun bundledAssetsMustBeRedistributableAndAttributed() {
        setup(redistributable = false)
        png("assets/ui/box.png", 16, 16)
        assertTrue("asset-not-redistributable" in AssetManifestTool(root).scan().issues.errors.map { it.code })
        File(root, "assets/ATTRIBUTION.json").writeText("""{"entries":[]}""")
        assertTrue("asset-attribution" in AssetManifestTool(root).scan().issues.errors.map { it.code })
    }

    @Test
    fun repositoryAssetsAreValid() {
        val repo = File(System.getProperty("pkmn.assets")).parentFile
        val scan = AssetManifestTool(repo).scan()
        assertEquals(emptyList(), scan.issues.errors.filter { !it.subject.startsWith("assets-local/") }.map { "${it.code} ${it.subject}" })
    }
}
