package pkmn.tools

import java.io.File

fun main(args: Array<String>) {
    val root = File(".").absoluteFile
    when (args.firstOrNull()) {
        "import" -> DataImporter(root).run()
        "validate" -> { val ok = ContentValidator(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "maps" -> MapCompilerTool(root).run()
        "manifest" -> { val ok = AssetManifestTool(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "playtime" -> PlaytimeEstimator(root).run()
        "perf" -> PerfBench(root).run()
        "progression" -> { val ok = ProgressionChecker(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "sprites" -> { val ok = SpriteImporter(root, args.drop(1)).run(); if (!ok) kotlin.system.exitProcess(1) }
        else -> {
            println("usage: tools <import|validate|maps|manifest|playtime|progression|perf|sprites [--only=1-151] [--force] [--from=<dir|zip>] ...>")
            kotlin.system.exitProcess(2)
        }
    }
}
