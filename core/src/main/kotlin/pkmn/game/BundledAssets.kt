package pkmn.game

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Read access to the files packaged in the app (Android `AssetManager`); a fake in tests. */
interface BundledAssetSource {
    /** Children of the directory [path]; empty for a file or a missing path (the AssetManager cannot tell them apart). */
    fun list(path: String): List<String>
    fun open(path: String): InputStream
}

/**
 * Copies the personal-build payload `assets/imported/` out of the APK into app storage so the game reads it like an
 * in-app import (docs/ASSET_IMPORT.md). Pure Kotlin so it is testable without Android, and built for being killed at any point:
 *  - every file is written to `<name>.part` and renamed over the target, so an already valid file is never truncated or
 *    deleted by a failed or interrupted copy;
 *  - the version marker is written last, so an interrupted unpack simply runs again on the next start;
 *  - nothing is deleted before the new set is complete (stale files from an older build are overwritten, not removed).
 * It blocks on I/O: call it from a background thread, never the UI/render thread.
 */
object BundledAssets {
    sealed class Outcome {
        /** The marker for this build exists: nothing to do. */
        object UpToDate : Outcome()
        /** This build bundles no imported assets (every public build). */
        object NothingBundled : Outcome()
        data class Unpacked(val files: Int) : Outcome()
        object Cancelled : Outcome()
        data class Failed(val error: Throwable, val attempts: Int) : Outcome()
    }

    const val ROOT = "imported"
    private const val MARKER_PREFIX = ".bundled-"

    /**
     * @param filesDir app storage root; files land in `<filesDir>/imported/...` (the same paths as in the APK).
     * @param versionKey identifies the installed build (version code + install time) so a rebuilt APK refreshes the copy.
     * @param cancelled polled between files so a destroyed activity stops the work promptly.
     */
    fun unpack(source: BundledAssetSource, filesDir: File, versionKey: String, cancelled: () -> Boolean = { false },
               maxAttempts: Int = 2, onRetry: (Int, Throwable) -> Unit = { _, _ -> }): Outcome {
        val top = source.list(ROOT)
        if (top.isEmpty()) return Outcome.NothingBundled
        val importedDir = File(filesDir, ROOT)
        val marker = File(importedDir, "$MARKER_PREFIX$versionKey")
        if (marker.exists()) return Outcome.UpToDate
        var last: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val n = copyTree(source, filesDir, ROOT, cancelled)
                if (n < 0) return Outcome.Cancelled
                importedDir.mkdirs()
                importedDir.listFiles { f -> f.name.startsWith(MARKER_PREFIX) }?.forEach { it.delete() }
                marker.createNewFile()
                return Outcome.Unpacked(n)
            } catch (e: IOException) {
                last = e
                if (attempt < maxAttempts) onRetry(attempt, e)
            }
        }
        return Outcome.Failed(last!!, maxAttempts)
    }

    /** Returns the number of files copied, or -1 when [cancelled] fired. */
    private fun copyTree(source: BundledAssetSource, filesDir: File, path: String, cancelled: () -> Boolean): Int {
        if (cancelled()) return -1
        val children = source.list(path)
        if (children.isEmpty()) { copyFile(source, filesDir, path); return 1 }
        var total = 0
        for (c in children) {
            val n = copyTree(source, filesDir, "$path/$c", cancelled)
            if (n < 0) return -1
            total += n
        }
        return total
    }

    private fun copyFile(source: BundledAssetSource, filesDir: File, path: String) {
        val out = File(filesDir, path)
        out.parentFile?.mkdirs()
        val part = File(out.parentFile, out.name + ".part")
        try {
            source.open(path).use { input -> part.outputStream().use { input.copyTo(it) } }
            try {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: LinkageError) {
                // java.nio.file is missing below Android API 26: replace with the classic File API.
                if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
            }
        } catch (e: Throwable) {
            part.delete()
            throw if (e is IOException) e else IOException("copying $path failed: $e", e)
        }
    }
}
