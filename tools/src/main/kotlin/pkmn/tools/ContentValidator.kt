package pkmn.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import pkmn.engine.battle.Effects
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.*
import pkmn.engine.world.CompiledScript
import pkmn.engine.world.Cond
import pkmn.engine.world.Instr
import java.io.File

/** All bundled content files, decoded as lists so duplicates remain visible (the [Dex] maps would hide them). */
class Content(
    val species: List<SpeciesData>,
    val moves: List<MoveData>,
    val abilities: List<AbilityData>,
    val items: List<ItemData>,
    val learnsets: List<LearnsetData>,
    val typeChart: TypeChartData,
    val manifest: ContentManifest?,
) {
    val dex: Dex by lazy { Dex(species, moves, abilities, items, TypeChart(typeChart), { learnsets }, manifest) }

    companion object {
        fun load(dataDir: File): Content {
            fun text(name: String) = File(dataDir, name).also { require(it.isFile) { "Missing content file ${it.path}" } }.readText()
            return Content(
                ContentJson.decodeFromString(ListSerializer(SpeciesData.serializer()), text("species.json")),
                ContentJson.decodeFromString(ListSerializer(MoveData.serializer()), text("moves.json")),
                ContentJson.decodeFromString(ListSerializer(AbilityData.serializer()), text("abilities.json")),
                ContentJson.decodeFromString(ListSerializer(ItemData.serializer()), text("items.json")),
                ContentJson.decodeFromString(ListSerializer(LearnsetData.serializer()), text("learnsets.json")),
                ContentJson.decodeFromString(TypeChartData.serializer(), text("typechart.json")),
                File(dataDir, "manifest.json").takeIf { it.isFile }?.let { ContentJson.decodeFromString(ContentManifest.serializer(), it.readText()) },
            )
        }
    }
}

/**
 * Validates bundled content (assets/data) and provenance (data-src), then writes machine-readable reports to
 * reports/. Returns false (exit code 1) when any error is found.
 */
class ContentValidator(private val root: File) {
    private val dataDir = File(root, "assets/data")
    private val reportsDir = File(root, "reports")

    fun run(): Boolean {
        val content = Content.load(dataDir)
        val issues = validate(content, expectedBaseCount = content.manifest?.expectedSpeciesCount ?: 1025)
        validateProvenance(File(root, "data-src"), content.manifest, issues)
        validateMusic(File(root, "assets"), issues)
        validateBattleScripts(WorldContent(DirectoryContentSource(File(root, "assets"))).scripts.values, issues)
        val evo = EvolutionAudit(content.dex).auditAll()
        for (r in evo.filter { !it.satisfiable }) issues.warn("evolution-unsatisfiable", "${r.from}->${r.evo.target}", r.reason ?: "",
            "The data entry mirrors the official games and all its references are valid; the gap is in what pkmn.engine.mon.Progression can trigger, not in the content. Listed in coverage-evolutions.json.")
        for (r in evo.filter { it.evo.conditionCode?.split('&')?.contains("other") == true }) issues.warn("evolution-condition-unparsed",
            "${r.from}->${r.evo.target}", "condition '${r.evo.condition}' was not mapped to a condition code",
            "Unmapped conditions are ignored by Progression; the evolution still works without the extra requirement.")

        val reports = linkedMapOf(
            "content-validation.json" to issues.toJson {
                put("generatedBy", "pkmn.tools.ContentValidator")
                put("contentVersion", content.manifest?.contentVersion ?: 0)
                put("rosterCutoff", content.manifest?.rosterCutoff ?: "")
            },
            "coverage-species.json" to speciesCoverage(content),
            "coverage-forms.json" to formsCoverage(content),
            "coverage-items.json" to itemsCoverage(content),
            "coverage-evolutions.json" to evolutionCoverage(content, evo),
            "coverage-registered.json" to BattleCoverageReport.registeredJson(content.dex),
        )
        val battle = BattleCoverageReport.load(content.dex)
        val totals = mapOf("moves" to content.moves.size, "abilities" to content.abilities.size, "items" to content.items.count { it.hasBattleData })
        val fileFor = mapOf("moves" to "coverage-moves.json", "abilities" to "coverage-abilities.json", "items" to "coverage-held-items.json")
        for (kind in BattleCoverageReport.KINDS) {
            reports[fileFor.getValue(kind)] = battle.fold(
                { BattleCoverageReport.coverageJson(kind, it.getValue(kind), totals.getValue(kind)) },
                { BattleCoverageReport.unavailableJson(kind, "${it::class.simpleName}: ${it.message ?: ""}".trim()) },
            )
        }
        for ((name, json) in reports) writeJson(File(reportsDir, name), json)
        File(reportsDir, "COVERAGE.md").writeText(CoverageMarkdown.render(reports))

        println("Content validation: ${issues.errors.size} error(s), ${issues.warnings.size} warning(s). Reports written to ${reportsDir.path}")
        issues.errors.take(40).forEach { println("  ERROR [${it.code}] ${it.subject}: ${it.message}") }
        if (issues.errors.size > 40) println("  ... ${issues.errors.size - 40} more (see reports/content-validation.json)")
        return issues.ok
    }

    // ------------------------------------------------------------------ content checks

    /**
     * Scripted trainer battles: losing a battle that is not `canlose` ends the script (see ScriptVM.resume), so
     * (1) testing for a loss after it is dead code, and (2) a `canlose` battle must test `result` before any
     * reward command, otherwise the reward is granted whatever the outcome.
     */
    fun validateBattleScripts(scripts: Collection<CompiledScript>, issues: Issues) {
        val rewards = setOf("badge", "tm", "giveitem", "givemoney", "givemon", "giveegg", "unlock")
        fun atoms(c: Cond): List<List<String>> = when (c) {
            is Cond.Atom -> listOf(c.words); is Cond.Not -> atoms(c.c); is Cond.And -> atoms(c.a) + atoms(c.b); is Cond.Or -> atoms(c.a) + atoms(c.b)
        }
        for (sc in scripts) {
            var mode: String? = null // "strict" after a plain battle, "canlose" after a canlose one
            var checked = false
            for (ins in sc.code) when (ins) {
                is Instr.Cmd -> when {
                    ins.name == "battle" -> { mode = if ("canlose" in ins.args) "canlose" else "strict"; checked = false }
                    ins.name == "wildbattle" -> mode = null
                    mode == "canlose" && !checked && ins.name in rewards -> issues.error("battle-reward-unchecked", "${sc.id}:${ins.line}",
                        "'${ins.name}' follows a canlose battle without testing 'result'; the reward would be granted after a loss")
                }
                is Instr.JumpIfNot -> {
                    val res = atoms(ins.cond).filter { it.firstOrNull() == "result" }
                    if (res.isNotEmpty()) checked = true
                    if (mode == "strict" && res.any { it.getOrNull(1) in setOf("lose", "no", "0") })
                        issues.error("battle-loss-unreachable", "${sc.id}:${ins.line}", "tests for a loss after a non-canlose battle, which ends the script on loss")
                }
                is Instr.Jump -> {}
            }
        }
    }

    fun validate(c: Content, expectedBaseCount: Int = 1025): Issues {
        val issues = Issues()
        val dex = c.dex
        val speciesTypes = c.typeChart.types.filter { it != "Stellar" && it != "???" }.toSet()
        val moveTypes = c.typeChart.types.toSet() + "???"

        fun dupes(kind: String, ids: List<String>) = ids.groupingBy { it }.eachCount().filter { it.value > 1 }
            .forEach { (id, n) -> issues.error("duplicate-id", "$kind:$id", "$n entries share the id '$id'") }
        dupes("species", c.species.map { it.id }); dupes("move", c.moves.map { it.id })
        dupes("ability", c.abilities.map { it.id }); dupes("item", c.items.map { it.id })
        dupes("learnset", c.learnsets.map { it.species })

        // ---- species registry
        val baseByNum = c.species.filter { it.formKind == FormKind.BASE }.groupBy { it.num }
        for (n in 1..expectedBaseCount) {
            val b = baseByNum[n].orEmpty()
            if (b.isEmpty()) issues.error("dex-missing", "#$n", "no BASE species for National Dex #$n")
            else if (b.size > 1) issues.error("dex-duplicate", "#$n", "multiple BASE species: ${b.joinToString { it.id }}")
        }
        baseByNum.keys.filter { it !in 1..expectedBaseCount }.forEach { issues.error("dex-out-of-range", "#$it", "BASE species outside roster cutoff 1..$expectedBaseCount") }
        c.species.filter { it.num !in 1..expectedBaseCount }.forEach { issues.error("dex-out-of-range", it.id, "species number ${it.num} outside 1..$expectedBaseCount") }

        for (sp in c.species) {
            val s = sp.id
            if (sp.name.isBlank()) issues.error("species-name", s, "empty name")
            if (sp.types.isEmpty() || sp.types.size > 2) issues.error("species-types", s, "has ${sp.types.size} types")
            sp.types.filter { it !in speciesTypes }.forEach { issues.error("species-type", s, "unknown type '$it'") }
            if (sp.types.toSet().size != sp.types.size) issues.error("species-types", s, "duplicate type")
            Stat.entries.filter { sp.baseStats[it] <= 0 }.forEach { issues.error("species-stat", s, "base ${it.id} = ${sp.baseStats[it]}") }
            if (sp.abilities.isEmpty()) issues.error("species-abilities", s, "no abilities")
            sp.abilities.forEach { (slot, a) -> if (a !in dex.abilities) issues.error("species-ability", s, "ability '$a' (slot $slot) not in abilities.json") }
            if (sp.catchRate !in 0..255) issues.error("species-catchrate", s, "catch rate ${sp.catchRate} outside 0..255")
            if (sp.maleRatio != -1.0 && sp.maleRatio !in 0.0..1.0) issues.error("species-gender", s, "male ratio ${sp.maleRatio}")
            sp.requiredTeraType?.let { if (it !in c.typeChart.types) issues.error("species-teratype", s, "unknown tera type '$it'") }

            // evolutions
            sp.prevo?.let { p ->
                val pre = dex.species[p]
                if (pre == null) issues.error("evo-prevo-missing", s, "prevo '$p' does not exist")
                else if (pre.evolutions.none { it.target == s }) issues.error("evo-inconsistent", s, "prevo '$p' does not list '$s' among its evolutions")
            }
            for (e in sp.evolutions) {
                val t = dex.species[e.target]
                if (t == null) issues.error("evo-target-missing", s, "evolution target '${e.target}' does not exist")
                else if (t.prevo != s) issues.error("evo-inconsistent", s, "evolution target '${e.target}' has prevo '${t.prevo}'")
                e.item?.let { if (it !in dex.items) issues.error("evo-item-missing", "$s->${e.target}", "evolution item '$it' not in items.json") }
                e.move?.let { if (it !in dex.moves) issues.error("evo-move-missing", "$s->${e.target}", "evolution move '$it' not in moves.json") }
                if (e.method == EvolutionMethod.USE_ITEM && e.item == null) issues.error("evo-item-missing", "$s->${e.target}", "USE_ITEM evolution without item")
                if ((e.method == EvolutionMethod.LEVEL_HOLD) && e.item == null) issues.error("evo-item-missing", "$s->${e.target}", "LEVEL_HOLD evolution without item")
                if (e.method == EvolutionMethod.LEVEL_MOVE && e.move == null) issues.error("evo-move-missing", "$s->${e.target}", "LEVEL_MOVE evolution without move")
                e.level?.let { if (it !in 1..100) issues.error("evo-level", "$s->${e.target}", "level $it outside 1..100") }
            }

            // forms
            val base = dex.species[sp.baseSpecies]
            if (sp.formKind == FormKind.BASE) {
                if (sp.baseSpecies != s) issues.error("form-base", s, "BASE entry has baseSpecies '${sp.baseSpecies}'")
            } else {
                when {
                    base == null -> issues.error("form-base-missing", s, "baseSpecies '${sp.baseSpecies}' does not exist")
                    base.formKind != FormKind.BASE -> issues.error("form-base", s, "baseSpecies '${sp.baseSpecies}' is not a BASE entry")
                    base.num != sp.num -> issues.error("form-base", s, "number ${sp.num} differs from base species #${base.num}")
                }
            }
            sp.otherFormes.forEach { f ->
                val fs = dex.species[f]
                if (fs == null) issues.error("form-other-missing", s, "otherFormes entry '$f' does not exist")
                else if (fs.baseSpecies != s) issues.error("form-other", s, "otherFormes entry '$f' has baseSpecies '${fs.baseSpecies}'")
            }
            sp.battleOnly.forEach { if (it !in dex.species) issues.error("form-battleonly-missing", s, "battleOnly reference '$it' does not exist") }
            sp.changesFrom?.let { if (it !in dex.species) issues.error("form-changesfrom-missing", s, "changesFrom '$it' does not exist") }
            sp.requiredItems.forEach { if (it !in dex.items) issues.error("form-item-missing", s, "required item '$it' not in items.json") }
            sp.requiredMove?.let { if (it !in dex.moves) issues.error("form-move-missing", s, "required move '$it' not in moves.json") }
            sp.requiredAbility?.let { if (it !in dex.abilities) issues.error("form-ability-missing", s, "required ability '$it' not in abilities.json") }
            if (sp.formKind == FormKind.MEGA) {
                val viaStone = sp.requiredItems.any { dex.items[it]?.megaStone?.values?.contains(s) == true }
                if (!viaStone && sp.requiredMove == null) issues.error("form-mega-trigger", s, "Mega forme has no Mega Stone mapping and no required move")
            }
            if (sp.formKind == FormKind.PRIMAL && sp.requiredItems.isEmpty()) issues.error("form-primal-trigger", s, "Primal forme has no required item")
            sp.canGigantamax?.let {
                val g = dex.species[s + "gmax"]
                if (g == null || g.formKind != FormKind.GIGANTAMAX) issues.error("form-gmax-missing", s, "canGigantamax ('$it') but no GIGANTAMAX forme '${s}gmax'")
            }
            if (sp.formKind == FormKind.GIGANTAMAX) {
                val from = dex.species[sp.changesFrom ?: s.removeSuffix("gmax")]
                if (from == null || from.canGigantamax == null) issues.error("form-gmax-orphan", s, "G-Max forme whose source '${sp.changesFrom ?: s.removeSuffix("gmax")}' cannot Gigantamax")
            }
        }

        // ---- items
        for (it in c.items) {
            if (it.name.isBlank()) issues.error("item-name", it.id, "empty name")
            it.megaStone.forEach { (from, to) ->
                val f = dex.species[from]; val t = dex.species[to]
                if (f == null) issues.error("megastone-from-missing", it.id, "megaStone source '$from' does not exist")
                if (t == null) issues.error("megastone-to-missing", it.id, "megaStone target '$to' does not exist")
                else {
                    if (t.formKind != FormKind.MEGA && t.formKind != FormKind.PRIMAL) issues.error("megastone-to-kind", it.id, "megaStone target '$to' is ${t.formKind}, not MEGA")
                    if (f != null && f.baseSpecies != t.baseSpecies) issues.error("megastone-mismatch", it.id, "'$from' and '$to' have different base species")
                    if (it.id !in t.requiredItems) issues.error("megastone-unrequired", it.id, "target '$to' does not list '${it.id}' as required item")
                }
            }
            it.itemUser.forEach { u -> if (u !in dex.species) issues.error("item-user-missing", it.id, "itemUser '$u' does not exist") }
            it.forcedForme?.let { f -> if (f !in dex.species) issues.error("item-forcedforme-missing", it.id, "forcedForme '$f' does not exist") }
            it.zMoveFrom.forEach { m -> if (m !in dex.moves) issues.error("zcrystal-from-missing", it.id, "zMoveFrom '$m' not in moves.json") }
            it.zMoveType?.let { t -> if (t !in moveTypes) issues.error("zcrystal-type", it.id, "unknown zMoveType '$t'") }
            if (it.zMove != null && it.zMove != "true" && pkmn.engine.util.toId(it.zMove!!) !in dex.moves)
                issues.error("zcrystal-move-missing", it.id, "exclusive Z-Move '${it.zMove}' not in moves.json")
            it.naturalGiftType?.let { t -> if (t !in moveTypes) issues.error("item-type", it.id, "unknown naturalGift type '$t'") }
        }
        for (a in c.abilities) if (a.name.isBlank()) issues.error("ability-name", a.id, "empty name")

        // ---- moves
        val categories = setOf("Physical", "Special", "Status")
        val targets = setOf("normal", "self", "adjacentAlly", "adjacentAllyOrSelf", "adjacentFoe", "allAdjacent", "allAdjacentFoes",
            "allies", "allySide", "allyTeam", "any", "foeSide", "randomNormal", "scripted", "all")
        val majorStatuses = setOf("brn", "par", "psn", "tox", "slp", "frz")
        val boostKeys = setOf("atk", "def", "spa", "spd", "spe", "accuracy", "evasion")
        val knownVolatiles = dex.moves.keys + Effects.implementedConditions() + BASE_VOLATILES
        for (m in c.moves) {
            val s = m.id
            if (m.name.isBlank()) issues.error("move-name", s, "empty name")
            if (m.type !in moveTypes) issues.error("move-type", s, "unknown type '${m.type}'")
            if (m.category !in categories) issues.error("move-category", s, "unknown category '${m.category}'")
            if (m.target !in targets) issues.error("move-target", s, "unknown target '${m.target}'")
            if (m.accuracy !in 0..100) issues.error("move-accuracy", s, "accuracy ${m.accuracy}")
            if (m.basePower < 0) issues.error("move-power", s, "negative base power")
            if (m.pp <= 0) issues.error("move-pp", s, "pp ${m.pp}")
            m.status?.let { if (it !in majorStatuses) issues.error("move-status", s, "unknown status '$it'") }
            m.volatileStatus?.let { if (it !in knownVolatiles) issues.error("move-volatile", s, "unknown volatile '$it'") }
            m.boosts.keys.filter { it !in boostKeys }.forEach { issues.error("move-boost", s, "unknown boost stat '$it'") }
            for (sec in m.secondaries) {
                sec.status?.let { if (it !in majorStatuses) issues.error("move-secondary-status", s, "unknown status '$it'") }
                sec.volatileStatus?.let { if (it !in knownVolatiles) issues.error("move-secondary-volatile", s, "unknown volatile '$it'") }
                (sec.boosts.keys + sec.self?.boosts?.keys.orEmpty()).filter { it !in boostKeys }.forEach { issues.error("move-boost", s, "unknown secondary boost '$it'") }
                if (sec.chance !in 1..100) issues.error("move-secondary-chance", s, "chance ${sec.chance}")
            }
            m.isZ?.let { if (it !in dex.items) issues.error("move-zcrystal-missing", s, "isZ crystal '$it' not in items.json") }
        }

        // ---- learnsets
        for (ls in c.learnsets) {
            if (ls.species !in dex.species) issues.error("learnset-species-missing", ls.species, "learnset for unknown species")
            val ids = ls.levelUp.map { it.move } + ls.machine + ls.egg
            ids.filter { it !in dex.moves }.distinct().forEach { issues.error("learnset-move-missing", ls.species, "move '$it' not in moves.json") }
            ls.levelUp.filter { it.level !in 0..100 }.forEach { issues.error("learnset-level", ls.species, "move '${it.move}' at level ${it.level}") }
        }
        for (sp in c.species) {
            if (sp.isBattleOnly) continue
            val ls = dex.learnset(sp.id)
            if (ls == null || ls.levelUp.isEmpty()) issues.error("learnset-unresolved", sp.id, "no level-up learnset resolves via Dex.learnset")
        }
        return issues
    }

    // ------------------------------------------------------------------ music

    /** Music ids that deliberately play silence. None today; add an id here to allow it. */
    val silentMusicIds: Set<String> = emptySet()

    /** Every `music` id in compiled maps, trainers and trainer classes must have `audio/music/<id>.mml`. */
    fun validateMusic(assets: File, issues: Issues) {
        val musicDir = File(assets, "audio/music")
        fun scan(el: JsonElement, out: MutableSet<String>) {
            when (el) {
                is JsonObject -> el.forEach { (k, v) -> if (k == "music" && v is JsonPrimitive && v.isString) out += v.content else scan(v, out) }
                is JsonArray -> el.forEach { scan(it, out) }
                else -> {}
            }
        }
        val files = (File(assets, "maps").listFiles { f -> f.extension == "json" }?.toList().orEmpty().sortedBy { it.name }) +
            listOf("trainers.json", "trainer_classes.json").map { File(assets, "world/$it") }.filter { it.isFile }
        for (f in files) {
            val ids = sortedSetOf<String>()
            scan(Json.parseToJsonElement(f.readText()), ids)
            for (id in ids) if (id !in silentMusicIds && !File(musicDir, "$id.mml").isFile)
                issues.error("music-missing", f.nameWithoutExtension, "music id '$id' has no assets/audio/music/$id.mml")
        }
    }

    // ------------------------------------------------------------------ provenance

    fun validateProvenance(dataSrc: File, manifest: ContentManifest?, issues: Issues) {
        val sumsFile = File(dataSrc, "SHA256SUMS")
        if (!sumsFile.isFile) { issues.error("provenance-sums-missing", "data-src/SHA256SUMS", "checksum file missing"); return }
        val sums = readSha256Sums(sumsFile)
        val files = dataSrc.walkTopDown().filter { it.isFile && it != sumsFile }
            .map { it.relativeTo(dataSrc).invariantSeparatorsPath }.sorted().toList()
        val manifestFiles = manifest?.provenance?.flatMap { p -> p.files.entries }?.associate { it.key to it.value }.orEmpty()
        if (manifest == null) issues.error("provenance-manifest-missing", "assets/data/manifest.json", "content manifest missing")
        for (f in files) {
            val actual = sha256(File(dataSrc, f).readBytes())
            when (val h = sums[f]) {
                null -> issues.error("provenance-unlisted", "data-src/$f", "file not listed in SHA256SUMS")
                actual -> {}
                else -> issues.error("provenance-checksum", "data-src/$f", "SHA256SUMS has $h but file hashes to $actual")
            }
            if (manifest != null) when (val h = manifestFiles[f]) {
                null -> issues.error("provenance-manifest-unlisted", "data-src/$f", "file not listed in manifest.json provenance")
                actual -> {}
                else -> issues.error("provenance-manifest-checksum", "data-src/$f", "manifest.json has $h but file hashes to $actual (re-run importData)")
            }
        }
        (sums.keys - files.toSet()).forEach { issues.error("provenance-missing-file", "data-src/$it", "listed in SHA256SUMS but missing") }
        (manifestFiles.keys - files.toSet()).forEach { issues.error("provenance-missing-file", "data-src/$it", "listed in manifest.json but missing") }
        manifest?.provenance?.forEach { p ->
            if (p.license.isBlank()) issues.error("provenance-license", p.name, "no license recorded")
            if (p.source.isBlank()) issues.error("provenance-source", p.name, "no source recorded")
        }
    }

    // ------------------------------------------------------------------ coverage

    private fun speciesCoverage(c: Content): JsonObject {
        val dex = c.dex
        val base = dex.nationalDex
        val pokeapiCatch = runCatching {
            Csv.read(File(root, "data-src/pokeapi/pokemon_species.csv")).mapNotNull { r -> r["id"]?.toIntOrNull()?.let { it to r["capture_rate"] } }
                .filter { !it.second.isNullOrBlank() }.map { it.first }.toSet()
        }.getOrDefault(emptySet())
        val sprites = SpriteLayout.availability(root, dex)
        return buildJsonObject {
            put("speciesEntries", c.species.size)
            put("baseSpecies", base.size)
            put("expectedBaseSpecies", c.manifest?.expectedSpeciesCount ?: 1025)
            put("formsByKind", countBy(c.species.filter { it.formKind != FormKind.BASE }.map { it.formKind.name }))
            put("battleOnlyEntries", c.species.count { it.isBattleOnly })
            put("nonstandardByTag", countBy(c.species.mapNotNull { it.isNonstandard }))
            putJsonObject("learnsets") {
                put("entries", c.learnsets.size)
                put("speciesWithOwnLevelUp", c.species.count { s -> c.learnsets.any { it.species == s.id && it.levelUp.isNotEmpty() } })
                put("nonBattleOnlySpecies", c.species.count { !it.isBattleOnly })
                put("nonBattleOnlyResolved", c.species.count { !it.isBattleOnly && dex.learnset(it.id)?.levelUp?.isNotEmpty() == true })
                put("baseSpeciesResolved", base.count { dex.learnset(it.id)?.levelUp?.isNotEmpty() == true })
                put("levelUpGeneration", countBy(c.learnsets.filter { it.levelUp.isNotEmpty() }.map { "gen${it.levelUpGen}" }))
            }
            putJsonObject("catchRates") {
                put("baseSpeciesWithPokeApiCaptureRate", base.count { it.num in pokeapiCatch })
                put("baseSpeciesUsingDefault", base.count { it.num !in pokeapiCatch })
                put("min", base.minOf { it.catchRate }); put("max", base.maxOf { it.catchRate })
                put("distribution", countBy(base.map { r -> when { r.catchRate <= 3 -> "003"; r.catchRate <= 45 -> "004-045"; r.catchRate <= 120 -> "046-120"; r.catchRate <= 200 -> "121-200"; else -> "201-255" } }))
            }
            putJsonObject("evolutions") {
                put("edges", c.species.sumOf { it.evolutions.size })
                put("speciesThatEvolve", c.species.count { it.evolutions.isNotEmpty() })
                put("speciesWithPrevo", c.species.count { it.prevo != null })
                put("baseSpeciesFullyEvolvedOrSingleStage", base.count { it.evolutions.isEmpty() })
            }
            put("legendary", base.count { it.isLegendary }); put("mythical", base.count { it.isMythical })
            put("baseSpeciesByGeneration", countBy(base.map { "gen${it.gen}" }))
            put("allEntriesByGeneration", countBy(c.species.map { "gen${it.gen}" }))
            put("sprites", sprites)
        }
    }

    private fun formsCoverage(c: Content): JsonObject = buildJsonObject {
        val forms = c.species.filter { it.formKind != FormKind.BASE }
        put("total", forms.size)
        put("countsByKind", countBy(forms.map { it.formKind.name }))
        putJsonObject("byKind") {
            for (kind in FormKind.entries.filter { it != FormKind.BASE }) {
                val list = forms.filter { it.formKind == kind }
                if (list.isEmpty()) continue
                put(kind.name, JsonArray(list.map { f ->
                    buildJsonObject {
                        put("id", f.id); put("baseSpecies", f.baseSpecies); f.forme?.let { put("forme", it) }
                        if (f.requiredItems.isNotEmpty()) put("requiredItems", strArray(f.requiredItems))
                        f.requiredMove?.let { put("requiredMove", it) }
                        f.requiredAbility?.let { put("requiredAbility", it) }
                        f.changesFrom?.let { put("changesFrom", it) }
                        if (f.battleOnly.isNotEmpty()) put("battleOnly", strArray(f.battleOnly))
                        put("isBattleOnly", f.isBattleOnly)
                        f.isNonstandard?.let { put("isNonstandard", it) }
                    }
                }))
            }
        }
        put("cosmeticFormes", c.species.sumOf { it.cosmeticFormes.size })
        put("speciesWithCanGigantamax", c.species.count { it.canGigantamax != null })
    }

    private fun itemsCoverage(c: Content): JsonObject = buildJsonObject {
        val dex = c.dex
        put("total", c.items.size)
        put("byCategory", countBy(c.items.map { it.category.name }))
        put("byPocket", countBy(c.items.map { it.pocket.name }))
        put("withBattleData", c.items.count { it.hasBattleData })
        put("nonstandardByTag", countBy(c.items.mapNotNull { it.isNonstandard }))
        val evoItems = c.species.flatMap { s -> s.evolutions.mapNotNull { e -> e.item?.let { it to "${s.id}->${e.target}" } } }.groupBy({ it.first }, { it.second })
        putJsonObject("evolutionItems") {
            put("categoryEVOLUTION", c.items.count { it.category == ItemCategory.EVOLUTION })
            put("referencedByEvolutions", evoItems.size)
            put("referencedButMissing", strArray(evoItems.keys.filter { it !in dex.items }.sorted()))
            putJsonObject("uses") { evoItems.toSortedMap().forEach { (k, v) -> put(k, strArray(v)) } }
        }
        putJsonObject("megaStones") {
            val stones = c.items.filter { it.megaStone.isNotEmpty() }
            put("count", stones.size)
            putJsonObject("mapping") { stones.sortedBy { it.id }.forEach { s -> put(s.id, JsonObject(s.megaStone.mapValues { JsonPrimitive(it.value) })) } }
        }
        putJsonObject("zCrystals") {
            val z = c.items.filter { it.zMove != null }
            put("count", z.size)
            put("typeCrystals", z.count { it.zMove == "true" })
            put("exclusiveCrystals", z.count { it.zMove != "true" })
            putJsonArray("list") {
                z.sortedBy { it.id }.forEach { i -> addJsonObject {
                    put("id", i.id); i.zMoveType?.let { put("type", it) }
                    if (i.zMove != "true") put("exclusiveMove", i.zMove)
                    if (i.zMoveFrom.isNotEmpty()) put("from", strArray(i.zMoveFrom))
                    if (i.itemUser.isNotEmpty()) put("users", strArray(i.itemUser))
                } }
            }
        }
        put("primalOrbs", strArray(c.species.filter { it.formKind == FormKind.PRIMAL }.flatMap { it.requiredItems }.distinct().sorted()))
    }

    private fun evolutionCoverage(c: Content, results: List<EvolutionAudit.Result>): JsonObject = buildJsonObject {
        put("edges", results.size)
        put("byMethod", countBy(results.map { it.evo.method.name }))
        put("byConditionCode", countBy(results.map { it.evo.conditionCode ?: "(none)" }))
        put("satisfiable", results.count { it.satisfiable })
        put("unsatisfiable", results.count { !it.satisfiable })
        put("method", "Each evolution is checked by searching for a game state under which pkmn.engine.mon.Progression.evolutionTarget returns its target. The game's region is fixed to Progression.EvolutionContext().region.")
        put("gameRegion", EvolutionAudit(c.dex).gameRegion)
        fun entry(r: EvolutionAudit.Result) = buildJsonObject {
            put("from", r.from); put("to", r.evo.target); put("method", r.evo.method.name)
            r.evo.level?.let { put("level", it) }; r.evo.item?.let { put("item", it) }; r.evo.move?.let { put("move", it) }
            r.evo.condition?.let { put("condition", it) }; r.evo.conditionCode?.let { put("conditionCode", it) }
            r.evo.gender?.let { put("gender", it) }
            if (r.satisfiable) put("requires", strArray(r.requires)) else put("reason", r.reason ?: "")
        }
        put("unsatisfiableEvolutions", JsonArray(results.filter { !it.satisfiable }.map(::entry)))
        val needsContext = results.filter { r -> r.satisfiable && r.requires.any { it.startsWith("locationTag:") || it == "deviceUpsideDown" } }
        put("satisfiableOnlyWithMapOrDeviceSupport", JsonArray(needsContext.map(::entry)))
        put("all", JsonArray(results.map(::entry)))
    }

    companion object {
        /** Showdown condition ids used as volatiles that are not named after a move. */
        val BASE_VOLATILES = setOf("confusion", "flinch", "partiallytrapped", "trapped", "mustrecharge", "lockedmove",
            "twoturnmove", "choicelock", "stall", "futuremove", "healreplacement", "dynamax", "commanded", "commanding")
    }
}
