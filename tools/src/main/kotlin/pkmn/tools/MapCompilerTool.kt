package pkmn.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pkmn.engine.data.Dex
import pkmn.engine.util.toId
import pkmn.engine.world.*
import java.io.File

/**
 * Compiles human-authored world sources (world/) into runtime content (assets/maps, assets/world, assets/scripts).
 *
 *  world/legend.txt        default char -> tile mapping shared by all maps
 *  world/prefabs.txt       multi-tile building stamps
 *  world/maps/NAME.map        map sources (see docs/WORLD_FORMAT.md)
 *  world/trainers/NAME.txt    trainer classes and trainers
 *  world/encounters/NAME.txt  wild encounter tables
 *  world/shops.txt, world/quests.txt, world/campaign.json
 *  world/scripts/NAME.pks     event scripts (copied after validation)
 */
class MapCompilerTool(private val root: File) {
    private val world = File(root, "world")
    private val outMaps = File(root, "assets/maps")
    private val outWorld = File(root, "assets/world")
    private val outScripts = File(root, "assets/scripts")
    private val json = Json { prettyPrint = false; encodeDefaults = false; explicitNulls = false }
    val errors = ArrayList<String>()
    val warnings = ArrayList<String>()

    data class Prefab(val name: String, val rows: List<List<String>>, val doorX: Int, val doorY: Int, val solidOverride: Boolean = false)

    fun run(): Boolean {
        val dex = Dex.loadDefault()
        val tileNames = loadTileNames()
        val legend = parseLegend(File(world, "legend.txt").takeIf { it.exists() }?.readLines() ?: emptyList(), "legend.txt")
        val prefabs = HashMap(parsePrefabs(File(world, "prefabs.txt")))
        (File(world, "prefabs").listFiles { f -> f.extension == "txt" } ?: emptyArray()).sortedBy { it.name }.forEach { prefabs.putAll(parsePrefabs(it)) }
        val maps = (File(world, "maps").listFiles { f -> f.extension == "map" } ?: emptyArray()).sortedBy { it.name }
            .mapNotNull { f -> try { compileMap(f, legend, prefabs, tileNames) } catch (e: CompileError) { errors.add("${f.name}:${e.line}: ${e.message}"); null } }
        val (classes, trainers) = compileTrainers(dex)
        val encounters = compileEncounters(dex)
        val shops = compileShops(dex)
        val quests = compileQuests()
        val scripts = compileScripts()

        validateReferences(dex, maps, trainers, encounters, shops, scripts)

        if (errors.isEmpty()) {
            outMaps.mkdirs(); outWorld.mkdirs(); outScripts.mkdirs()
            outMaps.listFiles()?.forEach { if (it.extension == "json") it.delete() }
            for (m in maps) File(outMaps, "${m.id}.json").writeText(json.encodeToString(MapData.serializer(), m))
            File(outWorld, "trainers.json").writeText(json.encodeToString(ListSerializer(TrainerDef.serializer()), trainers))
            File(outWorld, "trainer_classes.json").writeText(json.encodeToString(ListSerializer(TrainerClass.serializer()), classes))
            File(outWorld, "encounters.json").writeText(json.encodeToString(ListSerializer(EncounterTable.serializer()), encounters))
            File(outWorld, "shops.json").writeText(json.encodeToString(ListSerializer(ShopDef.serializer()), shops))
            File(outWorld, "quests.json").writeText(json.encodeToString(ListSerializer(QuestDef.serializer()), quests))
            val campaign = File(world, "campaign.json")
            if (campaign.exists()) campaign.copyTo(File(outWorld, "campaign.json"), overwrite = true) else errors.add("world/campaign.json missing")
            val camp = if (campaign.exists()) Json.parseToJsonElement(campaign.readText()).jsonObject else null
            val index = WorldIndex(
                startMap = camp?.get("startMap")?.jsonPrimitive?.content ?: maps.firstOrNull()?.id ?: "",
                startX = camp?.get("startX")?.jsonPrimitive?.int ?: 0, startY = camp?.get("startY")?.jsonPrimitive?.int ?: 0,
                maps = maps.map { it.id },
            )
            File(outWorld, "index.json").writeText(json.encodeToString(WorldIndex.serializer(), index))
            outScripts.listFiles()?.forEach { it.delete() }
            val scriptFiles = (File(world, "scripts").listFiles { f -> f.extension == "pks" } ?: emptyArray()).sortedBy { it.name }
            for (f in scriptFiles) f.copyTo(File(outScripts, f.name), overwrite = true)
            File(outScripts, "index.txt").writeText(scriptFiles.joinToString("\n") { it.name })
        }
        File(root, "reports").mkdirs()
        File(root, "reports/world-compile.json").writeText(Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("maps", kotlinx.serialization.json.JsonPrimitive(maps.size))
                put("trainers", kotlinx.serialization.json.JsonPrimitive(trainers.size))
                put("encounterTables", kotlinx.serialization.json.JsonPrimitive(encounters.size))
                put("scripts", kotlinx.serialization.json.JsonPrimitive(scripts.size))
                put("errors", kotlinx.serialization.json.JsonArray(errors.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("warnings", kotlinx.serialization.json.JsonArray(warnings.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }))
        println("World: ${maps.size} maps, ${trainers.size} trainers, ${encounters.size} encounter tables, ${scripts.size} scripts; " +
            "${errors.size} errors, ${warnings.size} warnings")
        errors.take(60).forEach { println("ERROR $it") }
        warnings.take(30).forEach { println("warn  $it") }
        return errors.isEmpty()
    }

    class CompileError(message: String, val line: Int) : Exception(message)

    private fun loadTileNames(): Set<String>? {
        val f = File(root, "assets/gfx/tiles/world.json")
        if (!f.exists()) { warnings.add("assets/gfx/tiles/world.json not found: tile names are not validated"); return null }
        return Json.parseToJsonElement(f.readText()).jsonObject["tiles"]!!.jsonObject.keys
    }

    private fun tok(line: String, file: String, n: Int) = try { ScriptParser.tokenize(line, file, n) } catch (e: ScriptError) { throw CompileError(e.message ?: "", n) }

    // ------------------------------------------------------------------ legend / prefabs

    private fun parseLegend(lines: List<String>, file: String): MutableMap<Char, String> {
        val m = LinkedHashMap<Char, String>()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val t = line.trim()
            if (t.length < 3 || t[1] != ' ') throw CompileError("legend entries are '<char> <tile>'", i + 1)
            m[t[0]] = t.substring(2).trim()
        }
        return m
    }

    private fun parsePrefabs(f: File): Map<String, Prefab> {
        if (!f.exists()) return emptyMap()
        val out = HashMap<String, Prefab>()
        val lines = f.readLines()
        var i = 0
        while (i < lines.size) {
            val t = tok(lines[i], f.name, i + 1)
            if (t.isEmpty()) { i++; continue }
            if (t[0] != "prefab") throw CompileError("expected prefab", i + 1)
            val name = t[1]
            var door = Pair(-1, -1)
            val rows = ArrayList<List<String>>()
            i++
            while (i < lines.size) {
                val r = tok(lines[i], f.name, i + 1)
                i++
                if (r.isEmpty()) continue
                if (r[0] == "end") break
                if (r[0] == "door") { door = r[1].toInt() to r[2].toInt(); continue }
                rows.add(r)
            }
            out[name] = Prefab(name, rows, door.first, door.second)
        }
        return out
    }

    // ------------------------------------------------------------------ maps

    private fun kv(tokens: List<String>): Map<String, String> = tokens.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun compileMap(f: File, defaultLegend: Map<Char, String>, prefabs: Map<String, Prefab>, tileNames: Set<String>?): MapData {
        val lines = f.readLines()
        var id = f.nameWithoutExtension
        var name = id
        var location = ""
        var music = "route_early"
        var environment = "plain"
        var weather: String? = null
        var indoor = false
        var dark = false
        var regionX = -1; var regionY = -1
        var flyX = -1; var flyY = -1
        var encounters: String? = null
        var onEnter: String? = null
        val tags = ArrayList<String>()
        val connections = ArrayList<Connection>()
        val warps = ArrayList<Warp>()
        val npcs = ArrayList<NpcDef>()
        val signs = ArrayList<SignDef>()
        val items = ArrayList<ItemPickup>()
        val triggers = ArrayList<TriggerDef>()
        val obstacles = ArrayList<ObstacleDef>()
        val flagTiles = ArrayList<FlagTile>()
        val legend = LinkedHashMap(defaultLegend)
        val layout = ArrayList<String>()
        data class Stamp(val prefab: Prefab, val x: Int, val y: Int, val line: Int)
        val stamps = ArrayList<Stamp>()
        val tileOverrides = ArrayList<Triple<Int, Int, String>>()

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val n = i + 1
            i++
            val t = tok(raw, f.name, n)
            if (t.isEmpty()) continue
            when (t[0]) {
                "map" -> id = t[1]
                "name" -> name = t[1]
                "location" -> location = t[1]
                "music" -> music = t[1]
                "environment" -> environment = t[1]
                "weather" -> weather = t[1].takeIf { it != "none" }
                "indoor" -> indoor = true
                "dark" -> dark = true
                "region" -> { regionX = t[1].toInt(); regionY = t[2].toInt() }
                "fly" -> { flyX = t[1].toInt(); flyY = t[2].toInt() }
                "encounters" -> encounters = t[1].takeIf { it != "none" }
                "onenter" -> onEnter = t[1]
                "tags" -> tags.addAll(t.drop(1))
                "connect" -> connections.add(Connection(t[1], t[2], t.getOrNull(3)?.toInt() ?: 0))
                "legend" -> {
                    while (i < lines.size) {
                        val l = lines[i].trimEnd(); i++
                        if (l.trim() == "end") break
                        if (l.isBlank() || l.trimStart().startsWith("#")) continue
                        val s = l.trim()
                        legend[s[0]] = s.substring(2).trim()
                    }
                }
                "layout" -> {
                    while (i < lines.size) {
                        val l = lines[i]; i++
                        if (l.trim() == "end") break
                        layout.add(l.trimEnd())
                    }
                }
                "building", "stamp" -> {
                    val p = prefabs[t[1]] ?: throw CompileError("unknown prefab '${t[1]}'", n)
                    val x = t[2].toInt(); val y = t[3].toInt()
                    stamps.add(Stamp(p, x, y, n))
                    val arrow = t.indexOf("->")
                    if (arrow > 0) {
                        if (p.doorX < 0) throw CompileError("prefab '${p.name}' has no door", n)
                        warps.add(Warp(x + p.doorX, y + p.doorY, t[arrow + 1], t[arrow + 2].toInt(), t[arrow + 3].toInt(), t.getOrNull(arrow + 4)))
                    }
                }
                "tile" -> tileOverrides.add(Triple(t[1].toInt(), t[2].toInt(), t[3]))
                "tileif" -> flagTiles.add(FlagTile(t[1], t[2].toInt(), t[3].toInt(), t[4]))
                "warp" -> {
                    val arrow = t.indexOf("->")
                    if (arrow != 3) throw CompileError("warp syntax: warp x y -> map x y [facing]  (or -> @back)", n)
                    if (t[4] == Warp.BACK) warps.add(Warp(t[1].toInt(), t[2].toInt(), Warp.BACK))
                    else warps.add(Warp(t[1].toInt(), t[2].toInt(), t[4], t[5].toInt(), t[6].toInt(), t.getOrNull(7)))
                }
                "npc", "trainer" -> {
                    val p = kv(t)
                    val isTrainer = t[0] == "trainer"
                    npcs.add(NpcDef(
                        id = t[1], x = t[2].toInt(), y = t[3].toInt(), sprite = p["sprite"] ?: throw CompileError("npc needs sprite=", n),
                        facing = p["face"] ?: "down", movement = p["move"] ?: "static", script = p["script"],
                        trainer = if (isTrainer) (p["trainer"] ?: t[1]) else p["trainer"],
                        sight = p["sight"]?.toInt() ?: if (isTrainer) 4 else 0,
                        hideIf = p["hideif"], showIf = p["showif"], text = p["text"],
                    ))
                }
                "sign" -> { val p = kv(t); signs.add(SignDef(t[1].toInt(), t[2].toInt(), p["text"], p["script"])) }
                "item", "hidden" -> items.add(ItemPickup("${id}_${t[1]}", t[2].toInt(), t[3].toInt(), toId(t[4]), t.getOrNull(5)?.toIntOrNull() ?: 1, t[0] == "hidden"))
                "trigger" -> {
                    val p = kv(t)
                    triggers.add(TriggerDef(t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt(), p["script"] ?: throw CompileError("trigger needs script=", n), p["unless"], p["if"]))
                }
                "obstacle" -> { val p = kv(t); obstacles.add(ObstacleDef(t[1].toInt(), t[2].toInt(), t[3], p["flag"] ?: "${id}_${t[3]}_${t[1]}_${t[2]}")) }
                else -> throw CompileError("unknown directive '${t[0]}'", n)
            }
        }
        if (layout.isEmpty()) throw CompileError("map has no layout", 1)
        val width = layout.maxOf { it.length }
        val height = layout.size
        val grid = Array(height) { y -> Array(width) { x ->
            val ch = layout[y].getOrElse(x) { ' ' }
            legend[ch] ?: throw CompileError("unknown legend char '$ch' at $x,$y", 0)
        } }
        // Water autotiling: "water@auto" picks shoreline variants based on neighbours.
        val isWater = { x: Int, y: Int -> x !in 0 until width || y !in 0 until height || grid[y][x].startsWith("water@auto") || grid[y][x] == "water" }
        for (y in 0 until height) for (x in 0 until width) if (grid[y][x] == "water@auto") grid[y][x] = waterVariant(isWater, x, y, tileNames)
        for (s in stamps) for ((ry, row) in s.prefab.rows.withIndex()) for ((rx, tile) in row.withIndex()) {
            if (tile == "_") continue
            val x = s.x + rx; val y = s.y + ry
            if (x !in 0 until width || y !in 0 until height) throw CompileError("prefab '${s.prefab.name}' out of bounds", s.line)
            grid[y][x] = tile
        }
        for ((x, y, tile) in tileOverrides) grid[y][x] = tile
        for (o in obstacles) grid[o.y][o.x] = when (o.kind) { "cut" -> "cut_tree"; "smash", "rocksmash" -> "rock_smash"; "boulder", "strength" -> "boulder"; else -> o.kind }
        val palette = ArrayList<String>()
        val pIndex = HashMap<String, Int>()
        val ground = ArrayList<Int>(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val tname = grid[y][x]
            if (tileNames != null && tname !in tileNames) errors.add("${f.name}: unknown tile '$tname' at $x,$y")
            ground.add(pIndex.getOrPut(tname) { palette.add(tname); palette.size - 1 })
        }
        for (ft in flagTiles) {
            if (ft.x !in 0 until width || ft.y !in 0 until height) throw CompileError("tileif ${ft.x},${ft.y} out of bounds", 0)
            if (tileNames != null && ft.tile !in tileNames) errors.add("${f.name}: unknown tile '${ft.tile}' in tileif")
        }
        return MapData(id, name, width, height, "world", palette, ground, emptyList(), music, environment, weather, indoor, dark,
            regionX, regionY, location.ifEmpty { name }, flyX, flyY, connections, warps, npcs, signs, items, triggers, obstacles,
            flagTiles, encounters, onEnter, tags)
    }

    private fun waterVariant(isWater: (Int, Int) -> Boolean, x: Int, y: Int, names: Set<String>?): String {
        val n = isWater(x, y - 1); val s = isWater(x, y + 1); val w = isWater(x - 1, y); val e = isWater(x + 1, y)
        val pick = when {
            !n && !w -> "water_nw"; !n && !e -> "water_ne"; !s && !w -> "water_sw"; !s && !e -> "water_se"
            !n -> "water_n"; !s -> "water_s"; !w -> "water_w"; !e -> "water_e"
            !isWater(x - 1, y - 1) -> "water_inw"; !isWater(x + 1, y - 1) -> "water_ine"
            !isWater(x - 1, y + 1) -> "water_isw"; !isWater(x + 1, y + 1) -> "water_ise"
            else -> "water"
        }
        return if (names == null || pick in names) pick else "water"
    }

    // ------------------------------------------------------------------ trainers

    private fun compileTrainers(dex: Dex): Pair<List<TrainerClass>, List<TrainerDef>> {
        val classes = ArrayList<TrainerClass>()
        val trainers = ArrayList<TrainerDef>()
        for (f in (File(world, "trainers").listFiles { x -> x.extension == "txt" } ?: emptyArray()).sortedBy { it.name }) {
            val lines = f.readLines()
            var i = 0
            while (i < lines.size) {
                val n = i + 1
                val t = try { tok(lines[i], f.name, n) } catch (e: CompileError) { errors.add("${f.name}:$n: ${e.message}"); i++; continue }
                i++
                if (t.isEmpty()) continue
                when (t[0]) {
                    "class" -> { val p = kv(t); classes.add(TrainerClass(t[1], t[2], p["payout"]?.toInt() ?: 20, p["music"] ?: "battle_trainer", p["portrait"] ?: t[1], p["gender"] ?: "m")) }
                    "trainer" -> {
                        val id = t[1]
                        var cls = ""; var name = ""; var ai = 1; var intro = ""; var defeat = ""; var after = ""; var music: String? = null; var portrait: String? = null
                        var prize: Int? = null
                        val gimmicks = ArrayList<String>(); val items = LinkedHashMap<String, Int>(); val team = ArrayList<TrainerMon>(); val tags = ArrayList<String>()
                        while (i < lines.size) {
                            val ln = i + 1
                            val r = tok(lines[i], f.name, ln); i++
                            if (r.isEmpty()) continue
                            when (r[0]) {
                                "end" -> break
                                "class" -> cls = r[1]
                                "name" -> name = r[1]
                                "ai" -> ai = r[1].toInt()
                                "intro" -> intro = r[1]
                                "defeat" -> defeat = r[1]
                                "after" -> after = r[1]
                                "music" -> music = r[1]
                                "portrait" -> portrait = r[1]
                                "prize" -> prize = r[1].toInt()
                                "tags" -> tags.addAll(r.drop(1))
                                "gimmicks" -> gimmicks.addAll(r.drop(1))
                                "items" -> r.drop(1).forEach { s -> items[toId(s.substringBefore('='))] = s.substringAfter('=', "1").toInt() }
                                "mon" -> {
                                    val p = kv(r)
                                    val sp = toId(r[1])
                                    if (dex.speciesOrNull(sp) == null) errors.add("${f.name}:$ln: unknown species '${r[1]}'")
                                    val moves = p["moves"]?.split(',')?.map { toId(it) } ?: emptyList()
                                    for (m in moves) if (dex.moveOrNull(m) == null) errors.add("${f.name}:$ln: unknown move '$m'")
                                    p["item"]?.let { if (dex.itemOrNull(toId(it)) == null) errors.add("${f.name}:$ln: unknown item '$it'") }
                                    team.add(TrainerMon(sp, r[2].toInt(), moves, p["item"]?.let { toId(it) }, p["ability"], p["nature"],
                                        p["ivs"]?.toInt() ?: -1, p["evs"]?.split(',')?.map { it.toInt() } ?: emptyList(), p["tera"],
                                        "gmax" in r, "shiny" in r, p["nick"]))
                                }
                                else -> errors.add("${f.name}:$ln: unknown trainer field '${r[0]}'")
                            }
                        }
                        if (team.isEmpty()) errors.add("${f.name}: trainer '$id' has no Pokémon")
                        if (team.size > 6) errors.add("${f.name}: trainer '$id' has more than 6 Pokémon")
                        trainers.add(TrainerDef(id, cls, name, team, ai, intro, defeat, after, gimmicks, items, music, portrait, tags, prize))
                    }
                    else -> errors.add("${f.name}:$n: unknown directive '${t[0]}'")
                }
            }
        }
        val dup = trainers.groupBy { it.id }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) errors.add("duplicate trainer ids: $dup")
        for (t in trainers) if (classes.none { it.id == t.trainerClass }) errors.add("trainer '${t.id}' uses unknown class '${t.trainerClass}'")
        return classes to trainers
    }

    // ------------------------------------------------------------------ encounters / shops / quests

    private fun compileEncounters(dex: Dex): List<EncounterTable> {
        val out = ArrayList<EncounterTable>()
        for (f in (File(world, "encounters").listFiles { x -> x.extension == "txt" } ?: emptyArray()).sortedBy { it.name }) {
            val lines = f.readLines()
            var i = 0
            while (i < lines.size) {
                val t = tok(lines[i], f.name, i + 1); i++
                if (t.isEmpty()) continue
                if (t[0] != "encounters") { errors.add("${f.name}:$i: expected 'encounters <id>'"); continue }
                val id = t[1]
                val methods = LinkedHashMap<String, LinkedHashMap<String, MutableList<EncounterSlot>>>()
                val rates = LinkedHashMap<String, Int>()
                while (i < lines.size) {
                    val ln = i + 1
                    val r = tok(lines[i], f.name, ln); i++
                    if (r.isEmpty()) continue
                    if (r[0] == "end") break
                    if (r[0] == "rate") { rates[r[1]] = r[2].toInt(); continue }
                    // <method> <time> <species> <min-max> <weight>
                    if (r.size < 5) { errors.add("${f.name}:$ln: encounter syntax: <method> <time> <species> <min-max> <weight>"); continue }
                    val sp = toId(r[2])
                    if (dex.speciesOrNull(sp) == null) errors.add("${f.name}:$ln: unknown species '${r[2]}'")
                    val (lo, hi) = r[3].split('-').let { it[0].toInt() to (it.getOrNull(1) ?: it[0]).toInt() }
                    methods.getOrPut(r[0]) { LinkedHashMap() }.getOrPut(r[1]) { ArrayList() }.add(EncounterSlot(sp, lo, hi, r[4].toInt()))
                }
                out.add(EncounterTable(id, methods, rates))
            }
        }
        return out
    }

    private fun sourcesFor(file: String, dir: String): List<File> =
        listOfNotNull(File(world, file).takeIf { it.exists() }) + (File(world, dir).listFiles { f -> f.extension == "txt" } ?: emptyArray()).sortedBy { it.name }

    private fun compileShops(dex: Dex): List<ShopDef> {
        val out = ArrayList<ShopDef>()
        for (f in sourcesFor("shops.txt", "shops")) for ((i, l) in f.readLines().withIndex()) {
            val t = tok(l, f.name, i + 1)
            if (t.isEmpty()) continue
            if (t[0] != "shop") { errors.add("shops.txt:${i + 1}: expected 'shop <id> items...'"); continue }
            val prices = LinkedHashMap<String, Int>()
            val items = t.drop(2).map { s ->
                val id = toId(s.substringBefore('='))
                if ('=' in s) prices[id] = s.substringAfter('=').toInt()
                if (!id.startsWith("tm") && dex.itemOrNull(id) == null) errors.add("shops.txt:${i + 1}: unknown item '$id'")
                id
            }
            out.add(ShopDef(t[1], items, prices))
        }
        val dup = out.groupBy { it.id }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) errors.add("duplicate shop ids: $dup")
        return out
    }

    private fun compileQuests(): List<QuestDef> {
        val out = ArrayList<QuestDef>()
        for (f in sourcesFor("quests.txt", "quests")) {
        val lines = f.readLines()
        var i = 0
        while (i < lines.size) {
            val t = tok(lines[i], f.name, i + 1); i++
            if (t.isEmpty()) continue
            if (t[0] != "quest") { errors.add("quests.txt:$i: expected quest"); continue }
            var desc = ""; val steps = ArrayList<String>()
            while (i < lines.size) {
                val r = tok(lines[i], f.name, i + 1); i++
                if (r.isEmpty()) continue
                if (r[0] == "end") break
                when (r[0]) { "desc" -> desc = r[1]; "step" -> steps.add(r[1]) }
            }
            out.add(QuestDef(t[1], t[2], desc, steps, "main" in t))
        }
        }
        return out
    }

    private fun compileScripts(): Map<String, CompiledScript> {
        val out = HashMap<String, CompiledScript>()
        for (f in (File(world, "scripts").listFiles { x -> x.extension == "pks" } ?: emptyArray()).sortedBy { it.name }) {
            try {
                for (s in ScriptParser.parseFile(f.readText(), f.name)) {
                    if (s.id in out) errors.add("${f.name}: duplicate script id '${s.id}' (also in ${out[s.id]!!.file})")
                    out[s.id] = s
                }
            } catch (e: ScriptError) { errors.add(e.message ?: "script error") }
        }
        return out
    }

    // ------------------------------------------------------------------ cross references

    private fun validateReferences(dex: Dex, maps: List<MapData>, trainers: List<TrainerDef>,
                                   encounters: List<EncounterTable>, shops: List<ShopDef>, scripts: Map<String, CompiledScript>) {
        val mapsById = maps.associateBy { it.id }
        val trainerIds = trainers.map { it.id }.toSet()
        val charDir = File(root, "assets/gfx/chars")
        val sprites = charDir.listFiles()?.map { it.nameWithoutExtension }?.toSet()
        fun walkable(m: MapData, x: Int, y: Int) = m.inBounds(x, y) && !TileRules.isSolid(TileRules.behavior(m.tileAt(x, y))) || TileRules.behavior(m.tileAt(x, y)) == TileBehavior.WARP
        for (m in maps) {
            val where = "map ${m.id}"
            for (c in m.connections) if (c.map !in mapsById) errors.add("$where: connection to unknown map '${c.map}'")
            for (w in m.warps) {
                if (w.toMap == Warp.BACK) continue
                val target = mapsById[w.toMap]
                if (target == null) { errors.add("$where: warp at ${w.x},${w.y} to unknown map '${w.toMap}'"); continue }
                if (!m.inBounds(w.x, w.y)) errors.add("$where: warp at ${w.x},${w.y} out of bounds")
                if (!target.inBounds(w.toX, w.toY)) errors.add("$where: warp target ${w.toMap} ${w.toX},${w.toY} out of bounds")
                else if (!walkable(target, w.toX, w.toY)) warnings.add("$where: warp target ${w.toMap} ${w.toX},${w.toY} is on '${target.tileAt(w.toX, w.toY)}'")
            }
            for (n in m.npcs) {
                if (!m.inBounds(n.x, n.y)) errors.add("$where: npc ${n.id} out of bounds")
                n.trainer?.let { if (it !in trainerIds) errors.add("$where: npc ${n.id} uses unknown trainer '$it'") }
                n.script?.let { if (it !in scripts) errors.add("$where: npc ${n.id} uses unknown script '$it'") }
                if (sprites != null && n.sprite !in sprites) warnings.add("$where: npc ${n.id} sprite '${n.sprite}' not in assets/gfx/chars")
                if (n.script == null && n.trainer == null && n.text == null) warnings.add("$where: npc ${n.id} has no script, trainer or text")
            }
            for (s in m.signs) s.script?.let { if (it !in scripts) errors.add("$where: sign uses unknown script '$it'") }
            for (t in m.triggers) if (t.script !in scripts) errors.add("$where: trigger uses unknown script '${t.script}'")
            m.onEnter?.let { if (it !in scripts) errors.add("$where: onenter script '$it' missing") }
            m.encounters?.let { e -> if (encounters.none { it.id == e }) errors.add("$where: unknown encounter table '$e'") }
            for (it in m.items) if (dex.itemOrNull(it.item) == null && !it.item.startsWith("tm")) errors.add("$where: unknown item '${it.item}'")
            if (m.items.map { it.id }.toSet().size != m.items.size) errors.add("$where: duplicate item ids")
        }
        // Scripts: referenced trainers, maps, shops, items, species.
        for (s in scripts.values) for (ins in s.code) if (ins is Instr.Cmd) {
            val a = ins.args
            val loc = "${s.file}:${ins.line}"
            when (ins.name) {
                "battle" -> if (a[0] !in trainerIds) errors.add("$loc: unknown trainer '${a[0]}'")
                "warp" -> mapsById[a[0]]?.let { if (!it.inBounds(a[1].toInt(), a[2].toInt())) errors.add("$loc: warp out of bounds") } ?: errors.add("$loc: unknown map '${a[0]}'")
                "shop" -> if (shops.none { it.id == a[0] }) errors.add("$loc: unknown shop '${a[0]}'")
                "giveitem", "takeitem" -> if (dex.itemOrNull(toId(a[0])) == null) errors.add("$loc: unknown item '${a[0]}'")
                "givemon", "wildbattle", "giveegg" -> if (dex.speciesOrNull(toId(a[0])) == null) errors.add("$loc: unknown species '${a[0]}'")
                "tm" -> if (dex.moveOrNull(toId(a[0])) == null) errors.add("$loc: unknown move '${a[0]}'")
                "call" -> if (a[0] !in scripts) errors.add("$loc: unknown script '${a[0]}'")
            }
        }
        val usedTrainers = maps.flatMap { m -> m.npcs.mapNotNull { it.trainer } } +
            scripts.values.flatMap { s -> s.code.filterIsInstance<Instr.Cmd>().filter { it.name == "battle" }.map { it.args[0] } }
        for (t in trainers) if (t.id !in usedTrainers) warnings.add("trainer '${t.id}' is never used")
    }
}
