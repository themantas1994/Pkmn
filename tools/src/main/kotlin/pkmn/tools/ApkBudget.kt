package pkmn.tools

import java.io.File

/**
 * Android size budget (graphical upgrade roadmap 2.1). Sizes are decimal MB (1 MB = 1,000,000 bytes), the stricter reading.
 * Only the committed `assets/gfx` counts, never `assets-local/` or the personal APK (`outputs/apk/personal`).
 */
object ApkBudget {
    const val MB = 1_000_000L
    const val RELEASE_APK_LIMIT = 12 * MB
    const val DEBUG_APK_LIMIT = 16 * MB
    const val RELEASE_AAB_LIMIT = 11 * MB
    const val GFX_LIMIT = 5 * MB

    data class Entry(val name: String, val limitBytes: Long, val actualBytes: Long?, val path: String?) {
        val missing get() = actualBytes == null
        val exceeded get() = actualBytes != null && actualBytes > limitBytes
    }

    private fun newest(dir: File, extension: String): File? =
        dir.listFiles { f -> f.isFile && f.extension == extension }?.maxByOrNull { it.lastModified() }

    private fun dirBytes(dir: File): Long? =
        if (!dir.isDirectory) null else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun measure(root: File): List<Entry> {
        val out = File(root, "android/build/outputs")
        fun entry(name: String, limit: Long, f: File?) = Entry(name, limit, f?.length(), f?.relativeTo(root)?.path)
        return listOf(
            entry("Release APK", RELEASE_APK_LIMIT, newest(File(out, "apk/release"), "apk")),
            entry("Debug APK", DEBUG_APK_LIMIT, newest(File(out, "apk/debug"), "apk")),
            entry("Release AAB", RELEASE_AAB_LIMIT, newest(File(out, "bundle/release"), "aab")),
            Entry("assets/gfx", GFX_LIMIT, dirBytes(File(root, "assets/gfx")), "assets/gfx"),
        )
    }

    private fun mb(b: Long) = "%.2f MB".format(b / MB.toDouble())

    /** False if a measured size exceeds its budget, or (with [requireAll]) if a budgeted artifact was not built. */
    fun run(root: File, requireAll: Boolean): Boolean {
        var ok = true
        for (e in measure(root)) {
            when {
                e.missing -> {
                    val msg = "apk-budget: ${e.name}: not built (budget <= ${mb(e.limitBytes)})"
                    if (requireAll) { System.err.println("$msg - FAIL (--require)"); ok = false } else println("$msg - skipped")
                }
                e.exceeded -> {
                    System.err.println("apk-budget: ${e.name}: ${mb(e.actualBytes!!)} exceeds budget ${mb(e.limitBytes)} (${e.path}) - FAIL")
                    ok = false
                }
                else -> println("apk-budget: ${e.name}: ${mb(e.actualBytes!!)} <= ${mb(e.limitBytes)} (${e.path}) - ok")
            }
        }
        return ok
    }
}
