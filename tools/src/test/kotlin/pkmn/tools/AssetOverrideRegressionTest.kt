package pkmn.tools

import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G0.5 (board condition): the personal-build override keeps working. A sprite or cry in `assets-local/` (desktop) or
 * `imported/` (Android) wins over bundled files, and a release build never carries them.
 *
 * `:tools` cannot load `:core` (LibGDX needs a GL context), so the lookup order is pinned two ways: functionally where
 * the pipeline is in `:tools` (manifest separation, release guard) and as source contracts on the runtime lookup code.
 * If a contract fails, the override order changed: fix the code or move the order deliberately, do not loosen the test.
 * Synthetic fixtures only; no real sprites or cries.
 */
class AssetOverrideRegressionTest {
    private val repo = File(System.getProperty("user.dir"))
    private fun src(path: String) = File(repo, path).readText()
    private fun zip(vararg names: String): File = Files.createTempFile("artifact", ".zip").toFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z -> names.forEach { n -> z.putNextEntry(ZipEntry(n)); z.write(1); z.closeEntry() } }
    }

    // ---- runtime lookup order (core): local override -> bundled -> base species -> placeholder

    private val pokemonFn get() = src("core/src/main/kotlin/pkmn/game/gfx/Assets.kt").let { s ->
        s.substring(s.indexOf("fun pokemon(speciesId: String"), s.indexOf("fun hasOfficialSprites"))
    }

    @Test
    fun localSpriteIsTriedBeforeBundledForEverySpeciesCandidate() {
        val f = pokemonFn
        val local = f.indexOf("importedTexture(path)")
        val bundled = f.indexOf("texture(path, quiet = true)")
        val placeholder = f.indexOf("PlaceholderSprites.generate")
        assertTrue(local in 0 until bundled, "local override must be checked before the bundled texture")
        assertTrue(bundled in 0 until placeholder, "the placeholder is the last resort")
        // Per candidate id (own id first, then changesFrom, then base for G-Max/Totem): local then bundled for that same id, so
        // a local file for the species itself also beats a bundled file of its base species.
        assertTrue(f.indexOf("add(speciesId)") in 0 until f.indexOf("sp.changesFrom?.let { add(it) }"))
        assertTrue(f.indexOf("for (id in candidates)") < local && f.indexOf("for (path in listOf(") in 0 until local)
        assertTrue(f.indexOf("sprites/pokemon/\$kind/\$id.png") in 0 until f.indexOf("gfx/pokemon/\$kind/\$id.png"),
            "sprites/pokemon is looked up under both roots before the legacy gfx/pokemon path")
    }

    @Test
    fun localSpriteRootIsTheImportedDirAndDesktopUsesAssetsLocal() {
        val assets = src("core/src/main/kotlin/pkmn/game/gfx/Assets.kt")
        assertTrue("class Assets(private val importedDir: File?" in assets)
        assertTrue("File(dir, path)" in assets.substringAfter("private fun importedTexture"), "importedTexture reads <importedDir>/<path>")
        assertTrue("Assets(platform.importedAssetsDir" in src("core/src/main/kotlin/pkmn/game/PkmnGame.kt"))
        val desktop = src("desktop/src/main/kotlin/pkmn/desktop/DesktopLauncher.kt")
        assertTrue("""listOf(File("../assets-local"), File("assets-local"))""" in desktop)
    }

    @Test
    fun cryLookupReadsOnlyTheLocalOverrideDirectoryAndNeverBundledAssets() {
        val game = src("core/src/main/kotlin/pkmn/game/PkmnGame.kt")
        val fn = game.substring(game.indexOf("private fun configureCries"), game.indexOf("// ------------------------------------------------------------------ screen stack"))
        assertTrue("""File(it, "audio/cries")""" in fn && "platform.importedAssetsDir" in fn)
        assertFalse("Gdx.files.internal" in fn || "assets/audio" in fn, "cries must not be looked up in bundled assets")
        val bank = src("core/src/main/kotlin/pkmn/game/audio/CryBank.kt")
        assertTrue("class CryResolver(private val dirs: List<File>" in bank, "the resolver only knows the directories it is given")
        assertFalse("Gdx.files.internal" in bank)
    }

    @Test
    fun repositoryBundlesNoCriesAndNoLocalAssets() {
        assertFalse(File(repo, "assets/audio/cries").exists(), "official cries must never be committed under assets/")
        assertFalse(File(repo, "assets/imported").exists(), "assets/imported is the personal-APK staging name and must not be committed")
        val ignore = src(".gitignore").lines().map { it.trim() }
        assertTrue("assets-local/" in ignore && "android/assets-local/" in ignore, "assets-local must stay git-ignored")
    }

    // ---- tools pipeline: a local file shadowing a bundled one never leaks into the bundled manifest

    @Test
    fun localSpriteShadowingABundledOneStaysOutOfTheBundledManifest() {
        val root = Files.createTempDirectory("override").toFile()
        fun text(rel: String, s: String) = File(root, rel).also { it.parentFile.mkdirs(); it.writeText(s) }
        fun png(rel: String, w: Int, h: Int) = File(root, rel).also { it.parentFile.mkdirs(); ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }
        text("assets/ATTRIBUTION.json", """{"version":1,"entries":[
            {"pathGlob":"assets/**","source":"test","license":"CC0","author":"me","redistributable":true,"status":"placeholder"},
            {"pathGlob":"assets-local/**","source":"local","license":"unknown","author":"?","redistributable":false}]}""")
        text("assets/data/species.json", """[{"id":"bulbasaur","num":1,"name":"Bulbasaur","baseSpecies":"bulbasaur","types":["Grass"],
            "baseStats":{"hp":45,"atk":49,"def":49,"spa":65,"spd":65,"spe":45},"abilities":{"0":"overgrow"},"maleRatio":0.875,"heightm":0.7,"weightkg":6.9}]""")
        png("assets/sprites/pokemon/front/bulbasaur.png", 96, 96)
        png("assets-local/sprites/pokemon/front/bulbasaur.png", 96, 96)
        text("assets-local/audio/cries/bulbasaur.ogg", "x")
        try {
            val tool = AssetManifestTool(root)
            val scan = tool.scan()
            assertEquals(listOf("assets/sprites/pokemon/front/bulbasaur.png"), scan.bundled.filter { it.category == "pokemon-sprite" }.map { it.path })
            assertEquals(listOf("assets-local/sprites/pokemon/front/bulbasaur.png"), scan.local.filter { it.category == "pokemon-sprite" }.map { it.path })
            assertTrue(tool.run())
            val bundled = File(root, "assets/manifest/assets.json").readText()
            assertFalse("assets-local" in bundled || "cries" in bundled, "the committed manifest must not mention local files")
            assertTrue(File(root, "assets-local/sprites/pokemon/front/bulbasaur.png").isFile, "tools never delete or move the override")
        } finally { root.deleteRecursively() }
    }

    // ---- release: neither override sprites nor cries can ride along

    @Test
    fun releaseArtifactGuardRejectsBothOverrideSpritesAndCriesInApkAndBundle() {
        val entries = listOf("imported/sprites/pokemon/front/pikachu.png", "imported/audio/cries/pikachu.ogg")
        for (e in entries) {
            val apk = zip("classes.dex", "assets/$e")
            val aab = zip("base/dex/classes.dex", "base/assets/$e")
            assertFalse(ReleaseArtifactGuard.run(listOf(apk)), "apk with $e")
            assertFalse(ReleaseArtifactGuard.run(listOf(aab)), "aab with $e")
        }
        assertTrue(ReleaseArtifactGuard.run(listOf(zip("classes.dex", "assets/data/species.json", "assets/sprites/pokemon/front/original.png"))),
            "original bundled art under assets/sprites/pokemon stays allowed")
    }

    @Test
    fun localAssetsReachOnlyTheDebugVariantAndOnlyWhenAskedFor() {
        val s = src("android/build.gradle.kts")
        val copy = s.substring(s.indexOf("val copyLocalAssets"), s.indexOf("if (bundleLocalAssets)"))
        assertTrue("assets-local/sprites" in copy && "assets-local/audio/cries" in copy, "the personal build copies sprites and cries")
        assertTrue("""if (bundleLocalAssets) tasks.matching { it.name == "mergeDebugAssets" }""" in s)
        assertFalse(Regex("""mergeRelease\w*Assets""").containsMatchIn(copy + s.substringAfter("val copyLocalAssets").substringBefore("checkReleaseConfiguration")),
            "no release merge task may depend on copyLocalAssets")
        assertTrue("pkmn.bundleLocalAssets" in s && "checkReleaseConfiguration" in s, "a release asking for local assets is refused")
    }
}
