package pkmn.tools

import java.io.File

fun main(args: Array<String>) {
    val root = File(".").absoluteFile
    when (args.firstOrNull()) {
        "import" -> DataImporter(root).run()
        "validate" -> { val ok = ContentValidator(root).run(); if (!ok) kotlin.system.exitProcess(1) }
        "maps" -> MapCompilerTool(root).run()
        "manifest" -> AssetManifestTool(root).run()
        "playtime" -> PlaytimeEstimator(root).run()
        else -> {
            println("usage: tools <import|validate|maps|manifest|playtime>")
            kotlin.system.exitProcess(2)
        }
    }
}
