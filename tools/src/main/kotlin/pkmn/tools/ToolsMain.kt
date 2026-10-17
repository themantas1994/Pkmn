package pkmn.tools

import java.io.File

fun main(args: Array<String>) {
    val root = File(args.firstOrNull { it.startsWith("--root=") }?.substringAfter("=") ?: ".").absoluteFile
    val write = "--no-write" !in args
    when (args.firstOrNull()) {
        "import" -> DataImporter(root).run()
        "validate" -> { val ok = ContentValidator(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "maps" -> MapCompilerTool(root).run()
        "manifest" -> { val ok = AssetManifestTool(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "playtime" -> PlaytimeEstimator(root).run()
        "perf" -> PerfBench(root).run()
        "progression" -> { val ok = ProgressionChecker(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "entrances" -> { val ok = EntranceAudit(root, write).run(); if (!ok) kotlin.system.exitProcess(1) }
        "items" -> { val ok = ItemAudit(root, write).run(); if (!ok) kotlin.system.exitProcess(1) }
        "moves" -> { val ok = MoveAudit(root, write).run(); if (!ok) kotlin.system.exitProcess(1) }
        "learnsets" -> { val ok = LearnsetAudit(root, write).run(); if (!ok) kotlin.system.exitProcess(1) }
        "screenshots" -> {
            val rest = args.drop(1).filter { !it.startsWith("--") }
            val ok = ScreenshotCompare(File(rest.getOrElse(0) { "build/screenshots" }).absoluteFile,
                File(rest.getOrElse(1) { "docs/screenshots" }).absoluteFile, "--update" in args).run()
            if (!ok) kotlin.system.exitProcess(1)
        }
        "sprites" -> { val ok = SpriteImporter(root, args.drop(1)).run(); if (!ok) kotlin.system.exitProcess(1) }
        else -> {
            println("usage: tools <import|validate|maps|manifest|playtime|progression|entrances|items|learnsets|moves|perf|screenshots <actual> <baseline> [--update]|sprites [--only=1-151] [--force] [--from=<dir|zip>] ...>")
            kotlin.system.exitProcess(2)
        }
    }
}
