package pkmn.tools

import java.io.File
import kotlin.system.exitProcess

/** Exit codes: 0 success, 1 the tool ran and reported failure (or threw), 2 usage error. */
const val EXIT_OK = 0
const val EXIT_FAILED = 1
const val EXIT_USAGE = 2

private const val USAGE = "usage: tools <import|validate|status [--check]|capture|learnsets|moves|maps|manifest|playtime|progression|entrances|items|perf|screenshots <actual> <baseline> [--update]|sprites [--only=1-151] [--force] [--from=<dir|zip>] [--dest=<dir>] ...|sprites-style [--style=gbc|none] [--strict]|cries [--only=1-151] [--force] ...|release-guard <apk|aab>...|sprite-coverage>"

/**
 * Runs one tool command and returns its process exit code. Never calls exitProcess, so tests can drive it;
 * only [main] terminates the JVM. Every command that reports failure (Boolean false or an exception) yields non-zero.
 */
fun runTool(args: Array<String>): Int {
    val root = File(args.firstOrNull { it.startsWith("--root=") }?.substringAfter("=") ?: ".").absoluteFile
    val write = "--no-write" !in args
    val rest = args.drop(1).filter { !it.startsWith("--root=") }
    val ok: Boolean = try {
        when (args.firstOrNull()) {
            "import" -> { DataImporter(root).run(); true }
            "validate" -> ContentValidator(root).run()
            "maps" -> MapCompilerTool(root).run()
            "manifest" -> AssetManifestTool(root).run()
            "playtime" -> PlaytimeEstimator(root).run()
            "perf" -> PerfBench(root).run()
            "progression" -> ProgressionChecker(root).run()
            "entrances" -> EntranceAudit(root, write).run()
            "items" -> ItemAudit(root, write).run()
            "moves" -> MoveAudit(root, write).run()
            "learnsets" -> LearnsetAudit(root, write).run()
            "screenshots" -> {
                val pos = args.drop(1).filter { !it.startsWith("--") }
                ScreenshotCompare(File(pos.getOrElse(0) { "build/screenshots" }).absoluteFile,
                    File(pos.getOrElse(1) { "docs/screenshots" }).absoluteFile, "--update" in args).run()
            }
            "status" -> StatusCheck(root).run(check = "--check" in args)
            "capture" -> CaptureAudit(root, write).run()
            "sprites" -> SpriteImporter(root, rest).run()
            "cries" -> CryImporter(root, rest).run()
            "release-guard" -> ReleaseArtifactGuard.run(rest.filter { !it.startsWith("--") }.map { File(it).absoluteFile })
            "sprite-coverage" -> SpriteCoverage(root, write).run()
            "sprites-style" -> SpriteStylePipeline(root, rest).run()
            else -> { println(USAGE); return EXIT_USAGE }
        }
    } catch (e: Exception) {
        System.err.println("tools ${args.firstOrNull()} failed: ${e::class.simpleName}: ${e.message}")
        false
    }
    return if (ok) EXIT_OK else EXIT_FAILED
}

fun main(args: Array<String>) {
    val code = runTool(args)
    if (code != EXIT_OK) exitProcess(code)
}
