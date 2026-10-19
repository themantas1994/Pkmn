package pkmn.tools

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AN-3, AN-4, S-7: release artifacts never carry imported official assets, release signing has no silent debug-key
 * fallback, and the backup configuration keeps official sprites/cries out of cloud backup and device transfer.
 * Only synthetic fixtures are used; no real sprites or cries.
 */
class AndroidReleaseGuardTest {
    private val repo = File(System.getProperty("user.dir"))
    private fun zip(vararg names: String): File = Files.createTempFile("artifact", ".zip").toFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z -> names.forEach { n -> z.putNextEntry(ZipEntry(n)); z.write(1); z.closeEntry() } }
    }

    // ---- AN-3: the artifact scan (the code the release tasks run after assembleRelease/bundleRelease)

    @Test
    fun cleanReleaseArtifactsPass() {
        val apk = zip("classes.dex", "assets/data/species.json", "assets/audio/music/town.mml", "res/mipmap-hdpi/ic_launcher.png")
        val aab = zip("base/dex/classes.dex", "base/assets/data/species.json", "BundleConfig.pb")
        assertTrue(ReleaseArtifactGuard.run(listOf(apk, aab)))
    }

    @Test
    fun importedSpritesAndCriesInAnApkOrBundleAreRejected() {
        val apk = zip("assets/data/species.json", "assets/imported/sprites/pokemon/front/pikachu.png")
        val aab = zip("base/assets/data/species.json", "base/assets/imported/audio/cries/pikachu.ogg")
        val loose = zip("assets/audio/cries/pikachu.mp3")
        assertEquals(listOf("assets/imported/sprites/pokemon/front/pikachu.png"), ReleaseArtifactGuard.forbiddenEntries(apk))
        assertEquals(listOf("base/assets/imported/audio/cries/pikachu.ogg"), ReleaseArtifactGuard.forbiddenEntries(aab))
        assertEquals(1, ReleaseArtifactGuard.forbiddenEntries(loose).size)
        assertFalse(ReleaseArtifactGuard.run(listOf(apk))); assertFalse(ReleaseArtifactGuard.run(listOf(aab)))
        assertFalse(ReleaseArtifactGuard.run(listOf(zip("assets/data/x.json"), apk)), "one bad artifact fails the whole check")
    }

    @Test
    fun missingOrUnreadableArtifactsAndNoArtifactsFail() {
        assertFalse(ReleaseArtifactGuard.run(emptyList()))
        assertFalse(ReleaseArtifactGuard.run(listOf(File("/nonexistent/app.aab"))))
        assertFalse(ReleaseArtifactGuard.run(listOf(Files.createTempFile("notzip", ".aab").toFile().also { it.writeText("not a zip") })))
    }

    @Test
    fun toolsMainRoutesReleaseGuardWithExitCodes() {
        assertEquals(EXIT_OK, runTool(arrayOf("release-guard", zip("assets/data/a.json").path)))
        assertEquals(EXIT_FAILED, runTool(arrayOf("release-guard", zip("assets/imported/sprites/a.png").path)))
    }

    // ---- AN-3 / AN-4: build script and CI invariants

    private val buildScript get() = File(repo, "android/build.gradle.kts").readText()

    @Test
    fun releaseNeverFallsBackSilentlyToTheDebugKey() {
        val s = buildScript
        assertFalse(Regex("""signingConfigs\.findByName\("release"\)\s*\?:\s*signingConfigs\.getByName\("debug"\)""").containsMatchIn(s),
            "the unconditional debug-key fallback came back")
        assertTrue("pkmn.release.allowDebugSigning" in s && "checkReleaseConfiguration" in s, "explicit opt-in and the signing guard must exist")
        assertTrue("producesReleaseArtifact" in s && "dependsOn(checkReleaseConfiguration)" in s, "the guard must be wired into the release tasks")
        assertTrue("verifyReleaseArtifacts" in s && "finalizedBy(verifyReleaseArtifacts)" in s, "artifacts must be scanned after assembleRelease/bundleRelease")
    }

    @Test
    fun localAssetsAreWiredToTheDebugVariantOnlyAndPersonalPipelineKeepsItsOutput() {
        val s = buildScript
        assertTrue("""sourceSets.getByName("debug").assets.srcDir(localAssetsDir)""" in s)
        assertFalse(Regex("""sourceSets\["main"\][^}]*localAssetsDir""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(s.substringBefore("signingConfigs")),
            "local assets must not be a main source set (that reaches release)")
        assertTrue("""tasks.matching { it.name == "mergeDebugAssets" }""" in s)
        assertTrue("assemblePersonalDebugWithSprites" in s && "pkmn-crystalline-personal-debug.apk" in s && """"outputs/apk/personal"""" in s)
    }

    @Test
    fun ciBuildsReleaseOnlyWithTheExplicitNonPublishableOptInAndNeverUploadsIt() {
        val ci = File(repo, ".github/workflows/ci.yml").readText()
        val releaseLines = ci.lines().filter { "bundleRelease" in it || "assembleRelease" in it }
        assertTrue(releaseLines.isNotEmpty() && releaseLines.all { "-Ppkmn.release.allowDebugSigning=true" in it || it.trim().startsWith("#") },
            "CI release builds need the explicit opt-in: $releaseLines")
        assertFalse(".aab" in ci && "upload-artifact" in ci && Regex("""path:[\s\S]*\.aab""").containsMatchIn(ci), "the debug-signed bundle must not be uploaded")
    }

    // ---- S-7: manifest and backup rules

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(repo, path))

    @Test
    fun manifestPointsAtBackupRulesThatKeepSavesAndExcludeImportedAssets() {
        val app = xml("android/AndroidManifest.xml").getElementsByTagName("application").item(0)
        fun attr(n: String) = app.attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", n)?.nodeValue
        assertEquals("true", attr("allowBackup"))
        assertEquals("@xml/backup_rules", attr("fullBackupContent"), "fullBackupContent=\"true\" would back up everything, including imported/")
        assertEquals("@xml/data_extraction_rules", attr("dataExtractionRules"))
        for ((file, sections) in mapOf("android/res/xml/backup_rules.xml" to listOf(""), "android/res/xml/data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"))) {
            val doc = xml(file)
            val scopes = if (sections == listOf("")) listOf(doc.documentElement) else sections.map { doc.getElementsByTagName(it).item(0) as org.w3c.dom.Element }
            for (scope in scopes) {
                val includes = scope.getElementsByTagName("include").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttribute("path") } }
                val excludes = scope.getElementsByTagName("exclude").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttribute("path") } }
                assertTrue("saves" in includes, "$file ${scope.tagName}: saves must be backed up")
                assertTrue(includes.none { it.startsWith("imported") || it == "." || it.isEmpty() }, "$file ${scope.tagName}: imported/ or the whole domain must not be included: $includes")
                assertTrue("saves/save.json.tmp" in excludes, "$file ${scope.tagName}: a stale in-flight save must not be restored")
            }
        }
    }
}
