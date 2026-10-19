package pkmn.tools

import java.io.File
import java.util.zip.ZipFile

/**
 * Release/public artifacts (APK, AAB) must never contain locally imported, non-redistributable files. A personal build
 * puts them under `assets/imported/` (APK) or `base/assets/imported/` (AAB): sprites and official cries. This scans the
 * zip entries of the given artifacts; the Android build runs it after assembleRelease/bundleRelease (see
 * android/build.gradle.kts) and tests use fixture zips.
 */
object ReleaseArtifactGuard {
    /** Entry names that must not be in a public artifact. */
    fun forbiddenEntries(artifact: File): List<String> = ZipFile(artifact).use { z ->
        z.entries().asSequence().map { it.name }.filter { isForbidden(it) }.toList()
    }

    fun isForbidden(entry: String): Boolean {
        val n = entry.removePrefix("base/") // AAB module prefix
        return n.startsWith("assets/imported/") || (n.startsWith("assets/") && "/audio/cries/" in n)
    }

    /** Prints a verdict per artifact; false if one is missing, unreadable or contains forbidden entries. */
    fun run(artifacts: List<File>): Boolean {
        if (artifacts.isEmpty()) { System.err.println("release-guard: no artifact given"); return false }
        var ok = true
        for (a in artifacts) {
            if (!a.isFile) { System.err.println("release-guard: ${a.path} does not exist"); ok = false; continue }
            val bad = try { forbiddenEntries(a) } catch (e: Exception) { System.err.println("release-guard: cannot read ${a.path}: ${e.message}"); ok = false; continue }
            if (bad.isEmpty()) println("release-guard: ${a.name} contains no imported assets")
            else {
                ok = false
                System.err.println("release-guard: ${a.path} contains ${bad.size} locally imported (non-redistributable) file(s), e.g. ${bad.first()}. A release artifact must never bundle them.")
            }
        }
        return ok
    }
}
