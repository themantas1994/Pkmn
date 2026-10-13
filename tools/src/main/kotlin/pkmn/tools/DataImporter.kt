package pkmn.tools

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import pkmn.engine.data.*
import pkmn.engine.util.toId
import java.io.File

/**
 * Converts the raw datasets in data-src/ into the normalised content files in assets/data/.
 *
 * Sources (see data-src/SHA256SUMS and assets/data/manifest.json for provenance):
 *  - Pokémon Showdown data (MIT): species, moves, abilities, items, type chart, learnsets, descriptions.
 *  - PokeAPI CSV data (BSD-3-Clause): catch rates, growth rates, base experience, EV yields,
 *    friendship, item categories/prices and prose for non-battle items.
 */
class DataImporter(private val root: File) {
    private val src = File(root, "data-src")
    private val out = File(root, "assets/data").apply { mkdirs() }

    @OptIn(ExperimentalSerializationApi::class)
    private val lenient = Json {
        isLenient = true
        allowTrailingComma = true
        allowComments = true
        ignoreUnknownKeys = true
    }

    private val outJson = Json { prettyPrint = false; encodeDefaults = false; explicitNulls = false }

    fun run() {
        verifyChecksums()
        val pokedex = json("showdown/pokedex.json")
        val movesRaw = json("showdown/moves.json")
        val learnRaw = json("showdown/learnsets.json")
        val abilitiesRaw = jsObject("showdown/abilities.js")
        val itemsRaw = jsObject("showdown/items.js")
        val typeRaw = jsObject("showdown/typechart.js")
        val moveText = tsObject("showdown/text/moves.ts")
        val abilityText = tsObject("showdown/text/abilities.ts")
        val itemText = tsObject("showdown/text/items.ts")

        val typeChart = importTypeChart(typeRaw)
        val moves = importMoves(movesRaw, moveText)
        val abilities = importAbilities(abilitiesRaw, abilityText)
        val items = importItems(itemsRaw, itemText)
        val species = importSpecies(pokedex)
        val learnsets = importLearnsets(learnRaw, species.map { it.id }.toSet(), moves.map { it.id }.toSet())

        write("typechart.json", outJson.encodeToString(TypeChartData.serializer(), typeChart))
        write("moves.json", outJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(MoveData.serializer()), moves))
        write("abilities.json", outJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AbilityData.serializer()), abilities))
        write("items.json", outJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ItemData.serializer()), items))
        write("species.json", outJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SpeciesData.serializer()), species))
        write("learnsets.json", outJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(LearnsetData.serializer()), learnsets))
        write("manifest.json", Json { prettyPrint = true }.encodeToString(ContentManifest.serializer(), manifest()))
        println("Imported ${species.size} species entries (${species.count { it.formKind == FormKind.BASE }} base), " +
            "${moves.size} moves, ${abilities.size} abilities, ${items.size} items, ${learnsets.size} learnsets.")
    }

    // ---------------------------------------------------------------- sources

    private fun verifyChecksums() {
        val sums = File(src, "SHA256SUMS")
        if (!sums.exists()) error("data-src/SHA256SUMS missing")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (line in sums.readLines().filter { it.isNotBlank() }) {
            val (hash, path) = line.trim().split(Regex("\\s+"), limit = 2)
            val f = File(src, path.removePrefix("./"))
            val actual = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != hash) error("Checksum mismatch for ${f.path}: expected $hash got $actual")
        }
    }

    private fun json(path: String): JsonObject = Json.parseToJsonElement(File(src, path).readText()).jsonObject

    /** Showdown client data: `exports.BattleX = {...};` with unquoted keys. */
    private fun jsObject(path: String): JsonObject {
        val text = File(src, path).readText()
        val body = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
        return lenient.parseToJsonElement(body).jsonObject
    }

    /** Showdown server text tables: `export const X: {...} = {...};` */
    private fun tsObject(path: String): JsonObject {
        val text = File(src, path).readText()
        val start = text.indexOf("= {") + 2
        val body = text.substring(start, text.lastIndexOf('}') + 1)
        return lenient.parseToJsonElement(body).jsonObject
    }

    private fun write(name: String, content: String) = File(out, name).writeText(content)

    private fun JsonObject.str(k: String) = this[k]?.let { if (it is JsonPrimitive && it !is JsonNull) it.content else null }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.dbl(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.bool(k: String) = this[k]?.let { it is JsonPrimitive && (it.booleanOrNull == true || it.intOrNull == 1) } ?: false
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.strList(k: String): List<String> = when (val v = this[k]) {
        is JsonArray -> v.map { it.jsonPrimitive.content }
        is JsonPrimitive -> if (v is JsonNull) emptyList() else listOf(v.content)
        else -> emptyList()
    }
    private fun JsonObject?.boosts(): Map<String, Int> = this?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap()
    private fun JsonElement?.intPair(): List<Int>? = (this as? JsonArray)?.map { it.jsonPrimitive.int }

    // ---------------------------------------------------------------- type chart

    private fun importTypeChart(raw: JsonObject): TypeChartData {
        val types = raw.keys.map { k -> raw.getValue(k).jsonObject; k.replaceFirstChar { it.uppercase() } }
            .map { if (it == "???") "???" else it }
        val defending = raw.mapKeys { it.key.replaceFirstChar { c -> c.uppercase() } }
        // damageTaken codes: 0 neutral, 1 weak (2x), 2 resist (0.5x), 3 immune.
        val chart = LinkedHashMap<String, MutableMap<String, Double>>()
        for (atk in types) chart[atk] = LinkedHashMap()
        for ((defType, v) in defending) {
            val taken = v.jsonObject.obj("damageTaken") ?: continue
            for (atk in types) {
                val code = taken.int(atk) ?: 0
                val mult = when (code) { 1 -> 2.0; 2 -> 0.5; 3 -> 0.0; else -> 1.0 }
                if (mult != 1.0) chart.getValue(atk)[defType] = mult
            }
        }
        return TypeChartData(types, chart)
    }

    // ---------------------------------------------------------------- moves

    private fun importMoves(raw: JsonObject, text: JsonObject): List<MoveData> = raw.mapNotNull { (id, el) ->
        val m = el.jsonObject
        val nonstd = m.str("isNonstandard")
        if (nonstd == "CAP" || nonstd == "Custom" || (m.int("num") ?: 0) <= 0) return@mapNotNull null
        val secondaries = buildList {
            (m["secondary"] as? JsonObject)?.let { add(secondary(it)) }
            (m["secondaries"] as? JsonArray)?.forEach { add(secondary(it.jsonObject)) }
        }
        val acc = m["accuracy"]?.jsonPrimitive?.let { it.intOrNull ?: 0 } ?: 0
        val ignoreImm = when (val v = m["ignoreImmunity"]) {
            is JsonPrimitive -> if (v.booleanOrNull == true) "true" else null
            is JsonObject -> v.keys.joinToString(",")
            else -> null
        }
        val t = text.obj(id)
        MoveData(
            id = id, num = m.int("num") ?: 0, name = m.str("name")!!, type = m.str("type")!!,
            category = m.str("category")!!, basePower = m.int("basePower") ?: 0, accuracy = acc,
            pp = m.int("pp") ?: 1, priority = m.int("priority") ?: 0, target = m.str("target") ?: "normal",
            flags = m.obj("flags")?.keys ?: emptySet(),
            secondaries = secondaries,
            self = m.obj("self")?.let { SelfEffect(it.obj("boosts").boosts(), it.str("volatileStatus")) },
            boosts = m.obj("boosts").boosts(),
            status = m.str("status"), volatileStatus = m.str("volatileStatus"),
            sideCondition = m.str("sideCondition"), slotCondition = m.str("slotCondition"),
            weather = m.str("weather"), terrain = m.str("terrain"), pseudoWeather = m.str("pseudoWeather"),
            drain = m["drain"].intPair(), recoil = m["recoil"].intPair(), heal = m["heal"].intPair(),
            multihit = when (val mh = m["multihit"]) {
                is JsonArray -> mh.map { it.jsonPrimitive.int }
                is JsonPrimitive -> mh.intOrNull?.let { listOf(it, it) }
                else -> null
            },
            multiaccuracy = m.bool("multiaccuracy"),
            critRatio = m.int("critRatio") ?: 1, willCrit = m.bool("willCrit"),
            selfSwitch = m["selfSwitch"]?.jsonPrimitive?.let { if (it.booleanOrNull == true) "true" else it.contentOrNull },
            forceSwitch = m.bool("forceSwitch"),
            selfdestruct = m.str("selfdestruct"),
            ohko = m["ohko"]?.jsonPrimitive?.let { if (it.booleanOrNull == true) "true" else it.contentOrNull },
            ignoreImmunity = ignoreImm,
            ignoreDefensive = m.bool("ignoreDefensive"), ignoreEvasion = m.bool("ignoreEvasion"),
            ignoreAbility = m.bool("ignoreAbility"),
            breaksProtect = m.bool("breaksProtect"), stallingMove = m.bool("stallingMove"),
            sleepUsable = m.bool("sleepUsable"), thawsTarget = m.bool("thawsTarget"),
            hasCrashDamage = m.bool("hasCrashDamage"), mindBlownRecoil = m.bool("mindBlownRecoil"),
            struggleRecoil = m.bool("struggleRecoil"),
            overrideOffensivePokemon = m.str("overrideOffensivePokemon"),
            overrideOffensiveStat = m.str("overrideOffensiveStat"),
            overrideDefensiveStat = m.str("overrideDefensiveStat"),
            fixedDamage = m["damage"]?.jsonPrimitive?.contentOrNull,
            isZ = m.str("isZ"),
            isMax = m["isMax"]?.jsonPrimitive?.let { if (it.booleanOrNull == true) "true" else it.contentOrNull },
            zMove = m.obj("zMove")?.let { ZMoveData(it.int("basePower"), it.str("effect"), it.obj("boost").boosts()) },
            maxMovePower = m.obj("maxMove")?.int("basePower"),
            nonGhostTarget = m.str("nonGhostTarget"),
            tracksTarget = m.bool("tracksTarget"), smartTarget = m.bool("smartTarget"),
            noPPBoosts = m.bool("noPPBoosts"),
            isNonstandard = nonstd,
            desc = t?.str("desc") ?: t?.str("shortDesc") ?: "",
            shortDesc = t?.str("shortDesc") ?: t?.str("desc") ?: "",
        )
    }.sortedBy { it.num }

    private fun secondary(o: JsonObject) = SecondaryEffect(
        chance = o.int("chance") ?: 100,
        status = o.str("status"),
        volatileStatus = o.str("volatileStatus"),
        boosts = o.obj("boosts").boosts(),
        self = o.obj("self")?.let { SelfEffect(it.obj("boosts").boosts(), it.str("volatileStatus")) },
    )

    // ---------------------------------------------------------------- abilities

    private fun importAbilities(raw: JsonObject, text: JsonObject) = raw.mapNotNull { (id, el) ->
        val a = el.jsonObject
        val nonstd = a.str("isNonstandard")
        if (nonstd == "CAP" || nonstd == "Custom" || (a.int("num") ?: 0) < 0) return@mapNotNull null
        val t = text.obj(id)
        AbilityData(
            id = id, num = a.int("num") ?: 0, name = a.str("name")!!, rating = a.dbl("rating") ?: 0.0,
            flags = a.obj("flags")?.keys ?: emptySet(), isNonstandard = nonstd,
            desc = t?.str("desc") ?: t?.str("shortDesc") ?: "", shortDesc = t?.str("shortDesc") ?: t?.str("desc") ?: "",
        )
    }.sortedBy { it.num }

    // ---------------------------------------------------------------- items

    private val excludedApiCategories = setOf(
        "all-machines", "curry-ingredients", "sandwich-ingredients", "tm-materials", "picnic", "mulch",
        "apricorn-box", "data-cards", "unused", "event-items", "gameplay", "plot-advancement", "all-mail",
        "species-candies", "dynamax-crystals", "baking-only", "miracle-shooter",
    )

    /** Official key items used by this game's campaign. Listed explicitly so no unused key items are imported. */
    private val keyItemWhitelist = setOf(
        "bicycle", "old-rod", "good-rod", "super-rod", "town-map", "exp-share", "coin-case", "itemfinder",
        "mega-ring", "z-ring", "dynamax-band", "tera-orb", "linking-cord", "squirt-bottle", "card-key",
        "ss-ticket", "secret-key", "lift-key", "silph-scope", "poke-flute", "rainbow-wing", "silver-wing",
        "clear-bell", "tidal-bell", "red-scale", "basement-key", "machine-part", "lost-item",
    )

    private fun importItems(raw: JsonObject, text: JsonObject): List<ItemData> {
        val apiItems = Csv.read(File(src, "pokeapi/items.csv"))
        val cats = Csv.read(File(src, "pokeapi/item_categories.csv")).associate { it["id"]!! to it["identifier"]!! }
        val prose = Csv.read(File(src, "pokeapi/item_prose.csv")).filter { it["local_language_id"] == "9" }
            .associate { it["item_id"]!! to (it["short_effect"] ?: "") }
        val result = LinkedHashMap<String, ItemData>()

        // Battle-relevant items from Showdown.
        for ((id, el) in raw) {
            val o = el.jsonObject
            val nonstd = o.str("isNonstandard")
            if (nonstd == "CAP" || nonstd == "Custom" || (o.int("num") ?: 0) <= 0) continue
            val t = text.obj(id)
            val megaStone = o.obj("megaStone")?.entries?.associate { (k, v) -> toId(k) to toId(v.jsonPrimitive.content) } ?: emptyMap()
            val zMove = o["zMove"]?.jsonPrimitive?.let { if (it.booleanOrNull == true) "true" else it.contentOrNull }
            val category = when {
                megaStone.isNotEmpty() -> ItemCategory.MEGA_STONE
                zMove != null -> ItemCategory.Z_CRYSTAL
                o.bool("isBerry") -> ItemCategory.BERRY
                o.bool("isPokeball") -> ItemCategory.BALL
                else -> ItemCategory.HELD
            }
            result[id] = ItemData(
                id = id, num = o.int("num") ?: 0, name = o.str("name")!!, category = category,
                pocket = pocketFor(category), gen = o.int("gen") ?: 0,
                flingPower = o.obj("fling")?.int("basePower") ?: 0,
                megaStone = megaStone, itemUser = o.strList("itemUser").map(::toId),
                zMove = zMove, zMoveType = o.str("zMoveType"), zMoveFrom = o.strList("zMoveFrom").map(::toId),
                isBerry = o.bool("isBerry"),
                naturalGiftPower = o.obj("naturalGift")?.int("basePower") ?: 0,
                naturalGiftType = o.obj("naturalGift")?.str("type"),
                onPlate = o.str("onPlate"), onMemory = o.str("onMemory"), onDrive = o.str("onDrive"),
                forcedForme = o.str("forcedForme")?.let(::toId),
                isPokeball = o.bool("isPokeball"), isGem = o.bool("isGem"), isNonstandard = nonstd,
                hasBattleData = true,
                desc = t?.str("desc") ?: t?.str("shortDesc") ?: "", shortDesc = t?.str("shortDesc") ?: t?.str("desc") ?: "",
            )
        }

        // Merge prices/categories and add non-battle items from PokeAPI.
        for (row in apiItems) {
            val ident = row["identifier"]!!
            val catName = cats[row["category_id"]] ?: "unknown"
            val id = toId(ident)
            val price = row["cost"]?.toIntOrNull() ?: 0
            val existing = result[id]
            if (existing != null) {
                val apiCat = categoryFor(catName)
                val cat = if (existing.category == ItemCategory.HELD && apiCat in setOf(ItemCategory.EVOLUTION, ItemCategory.BATTLE, ItemCategory.VALUABLE, ItemCategory.MEDICINE)) apiCat else existing.category
                result[id] = existing.copy(price = price, category = cat, pocket = pocketFor(cat),
                    shortDesc = existing.shortDesc.ifEmpty { prose[row["id"]] ?: "" })
                continue
            }
            val isKey = ident in keyItemWhitelist
            if (!isKey && catName in excludedApiCategories) continue
            if (catName == "unknown") continue
            val category = if (isKey) ItemCategory.KEY else categoryFor(catName)
            if (category == ItemCategory.KEY && !isKey) continue
            val desc = (prose[row["id"]] ?: "").replace(Regex("\\s+"), " ").trim()
            result[id] = ItemData(
                id = id, num = 10000 + (row["id"]?.toIntOrNull() ?: 0), name = displayName(ident), category = category,
                pocket = pocketFor(category), price = price, flingPower = row["fling_power"]?.toIntOrNull() ?: 0,
                isPokeball = category == ItemCategory.BALL, desc = desc, shortDesc = desc,
            )
        }

        // Key items required by the campaign that PokeAPI does not define under these identifiers.
        val extraKeys = mapOf(
            "keystone" to ("Key Stone" to "A stone that lets its holder's Pokémon Mega Evolve in battle, once per battle."),
            "zring" to ("Z-Ring" to "A bracelet that lets the trainer's Pokémon unleash Z-Moves using Z-Crystals, once per battle."),
            "dynamaxband" to ("Dynamax Band" to "A wristband that lets one Pokémon Dynamax or Gigantamax for three turns, once per battle."),
            "teraorb" to ("Tera Orb" to "An orb that lets one Pokémon Terastallize into its Tera Type, once per battle. Recharges at a Pokémon Center."),
            "linkingcord" to ("Linking Cord" to "A cord that evolves Pokémon that would normally evolve by trade."),
            "expshare" to ("Exp. Share" to "When switched on, every Pokémon in the party earns experience from battles."),
            "townmap" to ("Town Map" to "A map of the region showing your current position."),
            "bicycle" to ("Bicycle" to "A folding bicycle that lets you travel faster than running."),
        )
        for ((id, v) in extraKeys) if (id !in result || result[id]!!.category != ItemCategory.KEY) {
            result[id] = (result[id] ?: ItemData(id, 20000 + result.size, v.first, ItemCategory.KEY, BagPocket.KEY))
                .copy(category = ItemCategory.KEY, pocket = BagPocket.KEY, name = v.first, desc = v.second, shortDesc = v.second, price = 0)
        }
        return result.values.sortedBy { it.num }
    }

    private fun displayName(ident: String): String {
        val special = mapOf("poke-ball" to "Poké Ball", "pp-up" to "PP Up", "pp-max" to "PP Max", "hp-up" to "HP Up",
            "x-attack" to "X Attack", "x-defense" to "X Defense", "x-sp-atk" to "X Sp. Atk", "x-sp-def" to "X Sp. Def",
            "x-speed" to "X Speed", "x-accuracy" to "X Accuracy", "exp-share" to "Exp. Share", "ss-ticket" to "S.S. Ticket",
            "poke-doll" to "Poké Doll", "poke-flute" to "Poké Flute")
        special[ident]?.let { return it }
        return ident.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .replace("Poke ", "Poké ")
    }

    private fun categoryFor(cat: String) = when (cat) {
        "standard-balls", "special-balls", "apricorn-balls" -> ItemCategory.BALL
        "healing", "status-cures", "revival", "pp-recovery", "vitamins", "nature-mints" -> ItemCategory.MEDICINE
        "stat-boosts", "flutes", "catching-bonus" -> ItemCategory.BATTLE
        "medicine", "other", "in-a-pinch", "picky-healing", "type-protection", "effort-drop" -> ItemCategory.BERRY
        "held-items", "choice", "effort-training", "bad-held-items", "training", "plates", "species-specific",
        "type-enhancement", "scarves", "jewels", "memories", "tera-shard" -> ItemCategory.HELD
        "evolution" -> ItemCategory.EVOLUTION
        "mega-stones" -> ItemCategory.MEGA_STONE
        "z-crystals" -> ItemCategory.Z_CRYSTAL
        "loot", "collectibles", "dex-completion", "spelunking" -> ItemCategory.VALUABLE
        "all-mail" -> ItemCategory.MAIL
        "event-items", "gameplay", "plot-advancement" -> ItemCategory.KEY
        else -> ItemCategory.OTHER
    }

    private fun pocketFor(c: ItemCategory) = when (c) {
        ItemCategory.BALL -> BagPocket.BALLS
        ItemCategory.MEDICINE -> BagPocket.MEDICINE
        ItemCategory.BERRY -> BagPocket.BERRIES
        ItemCategory.TM -> BagPocket.TMS
        ItemCategory.KEY, ItemCategory.Z_CRYSTAL -> BagPocket.KEY
        else -> BagPocket.ITEMS
    }

    // ---------------------------------------------------------------- species

    private val regionalSuffixes = listOf("Alola", "Galar", "Hisui", "Paldea")
    private val fusions = setOf("kyuremblack", "kyuremwhite", "necrozmaduskmane", "necrozmadawnwings", "calyrexice", "calyrexshadow")

    private fun importSpecies(raw: JsonObject): List<SpeciesData> {
        val apiSpecies = Csv.read(File(src, "pokeapi/pokemon_species.csv")).associateBy { it["id"]!!.toInt() }
        val apiPokemon = Csv.read(File(src, "pokeapi/pokemon.csv"))
        val apiByIdent = apiPokemon.associateBy { toId(it["identifier"]!!) }
        val apiDefaultBySpecies = apiPokemon.filter { it["is_default"] == "1" }.associateBy { it["species_id"]!!.toInt() }
        val statNames = mapOf("1" to "hp", "2" to "atk", "3" to "def", "4" to "spa", "5" to "spd", "6" to "spe")
        val evs = HashMap<String, MutableMap<String, Int>>()
        for (r in Csv.read(File(src, "pokeapi/pokemon_stats.csv"))) {
            val e = r["effort"]!!.toInt()
            if (e > 0) evs.getOrPut(r["pokemon_id"]!!) { HashMap() }[statNames[r["stat_id"]]!!] = e
        }
        val growth = mapOf("1" to GrowthRate.SLOW, "2" to GrowthRate.MEDIUM_FAST, "3" to GrowthRate.FAST,
            "4" to GrowthRate.MEDIUM_SLOW, "5" to GrowthRate.ERRATIC, "6" to GrowthRate.FLUCTUATING)

        val entries = raw.filter { (_, v) ->
            val o = v.jsonObject
            val ns = o.str("isNonstandard")
            (o.int("num") ?: 0) > 0 && ns != "CAP" && ns != "Custom"
        }
        // Build evolution lists on the pre-evolution from data stored on each evolved species.
        val evolutionsOf = HashMap<String, MutableList<EvolutionData>>()
        for ((id, v) in entries) {
            val o = v.jsonObject
            val prevo = o.str("prevo") ?: continue
            val method = when (o.str("evoType")) {
                null -> EvolutionMethod.LEVEL
                "levelFriendship" -> EvolutionMethod.LEVEL_FRIENDSHIP
                "useItem" -> EvolutionMethod.USE_ITEM
                "trade" -> EvolutionMethod.TRADE
                "levelMove" -> EvolutionMethod.LEVEL_MOVE
                "levelHold" -> EvolutionMethod.LEVEL_HOLD
                "levelExtra" -> EvolutionMethod.LEVEL_EXTRA
                else -> EvolutionMethod.OTHER
            }
            val cond = o.str("evoCondition")
            // Regional evolutions (e.g. Pikachu -> Alolan Raichu) only happen in their home region.
            val region = o.str("evoRegion") ?: o.str("forme")?.let { f -> regionalSuffixes.firstOrNull { f == it } }
                ?.takeIf { entries[toId(prevo)]?.jsonObject?.str("forme") == null }
            val code = listOfNotNull(conditionCode(cond), region?.let { "region:$it" }).joinToString("&").ifEmpty { null }
            evolutionsOf.getOrPut(toId(prevo)) { ArrayList() }.add(EvolutionData(
                target = id, method = method, level = o.int("evoLevel"), item = o.str("evoItem")?.let(::toId),
                move = o.str("evoMove")?.let(::toId), condition = cond, conditionCode = code,
                gender = o.str("gender"),
            ))
        }

        return entries.map { (id, v) ->
            val o = v.jsonObject
            val num = o.int("num")!!
            val forme = o.str("forme")
            val battleOnly = o.strList("battleOnly").map(::toId)
            val kind = when {
                forme == null -> FormKind.BASE
                id in fusions -> FormKind.FUSION
                forme.contains("Totem") -> FormKind.TOTEM
                forme.startsWith("Mega") || forme.endsWith("-Mega") || forme.contains("-Mega-") -> FormKind.MEGA
                forme == "Primal" -> FormKind.PRIMAL
                forme.contains("Gmax") -> FormKind.GIGANTAMAX
                forme == "Ultra" -> FormKind.ULTRA_BURST
                battleOnly.isNotEmpty() -> FormKind.BATTLE_ONLY
                regionalSuffixes.any { forme == it || forme.startsWith("$it-") } -> FormKind.REGIONAL
                else -> FormKind.ALTERNATE
            }
            val sp = apiSpecies[num]
            val poke = apiByIdent[id] ?: apiDefaultBySpecies[num]
            val ev = poke?.let { evs[it["id"]] } ?: emptyMap()
            val gr = o.obj("genderRatio")
            val maleRatio = when {
                o.str("gender") == "M" -> 1.0
                o.str("gender") == "F" -> 0.0
                o.str("gender") == "N" -> -1.0
                gr != null -> gr.dbl("M") ?: 0.5
                else -> 0.5
            }
            val bs = o.obj("baseStats")!!
            SpeciesData(
                id = id, num = num, name = o.str("name")!!,
                baseSpecies = toId(o.str("baseSpecies") ?: o.str("name")!!), forme = forme, formKind = kind,
                types = o.strList("types"),
                baseStats = BaseStats(bs.int("hp")!!, bs.int("atk")!!, bs.int("def")!!, bs.int("spa")!!, bs.int("spd")!!, bs.int("spe")!!),
                abilities = o.obj("abilities")!!.mapValues { toId(it.value.jsonPrimitive.content) },
                maleRatio = maleRatio,
                heightm = o.dbl("heightm") ?: 0.0, weightkg = o.dbl("weightkg") ?: 0.0, color = o.str("color") ?: "",
                eggGroups = o.strList("eggGroups"), prevo = o.str("prevo")?.let(::toId),
                evolutions = evolutionsOf[id].orEmpty(),
                otherFormes = o.strList("otherFormes").map(::toId), battleOnly = battleOnly,
                requiredItems = (o.strList("requiredItem") + o.strList("requiredItems")).map(::toId).distinct(),
                requiredMove = o.str("requiredMove")?.let(::toId), requiredAbility = o.str("requiredAbility")?.let(::toId),
                changesFrom = o.str("changesFrom")?.let(::toId), canGigantamax = o.str("canGigantamax"),
                cannotDynamax = o.bool("cannotDynamax"), maxHP = o.int("maxHP"), requiredTeraType = o.str("requiredTeraType"),
                cosmeticFormes = o.strList("cosmeticFormes").map(::toId),
                tags = o.strList("tags"),
                catchRate = sp?.get("capture_rate")?.toIntOrNull() ?: 45,
                baseExp = poke?.get("base_experience")?.toIntOrNull() ?: 64,
                growthRate = growth[sp?.get("growth_rate_id")] ?: GrowthRate.MEDIUM_FAST,
                evYield = BaseStats(ev["hp"] ?: 0, ev["atk"] ?: 0, ev["def"] ?: 0, ev["spa"] ?: 0, ev["spd"] ?: 0, ev["spe"] ?: 0),
                baseFriendship = sp?.get("base_happiness")?.toIntOrNull() ?: 50,
                hatchCycles = sp?.get("hatch_counter")?.toIntOrNull() ?: 20,
                isBaby = sp?.get("is_baby") == "1",
                gen = sp?.get("generation_id")?.toIntOrNull() ?: genOf(num),
                isNonstandard = o.str("isNonstandard"),
            )
        }.sortedWith(compareBy({ it.num }, { it.formKind != FormKind.BASE }, { it.id }))
    }

    private fun genOf(num: Int) = when {
        num <= 151 -> 1; num <= 251 -> 2; num <= 386 -> 3; num <= 493 -> 4; num <= 649 -> 5
        num <= 721 -> 6; num <= 809 -> 7; num <= 905 -> 8; else -> 9
    }

    private fun conditionCode(cond: String?): String? {
        if (cond == null) return null
        val c = cond.lowercase()
        return when {
            c == "at night" -> "night"
            c == "during the day" -> "day"
            c.contains("evening") -> "dusk"
            c.contains("during rain") -> "rain"
            c.contains("atk stat > its def") -> "atk>def"
            c.contains("atk stat < its def") -> "atk<def"
            c.contains("atk stat equal") -> "atk=def"
            c.contains("remoraid in party") -> "party:remoraid"
            c.contains("dark-type in the party") -> "partytype:Dark"
            c.contains("with a shelmet") -> "tradewith:shelmet"
            c.contains("with a karrablast") -> "tradewith:karrablast"
            c.contains("magnetic field") -> "location:magnetic"
            c.contains("fairy-type move") -> "movetype:Fairy+friendship"
            c.contains("upside-down") -> "upsidedown"
            c.contains("critical hits") -> "crits:3"
            c.contains("recoil") -> "recoil:294"
            c.contains("49+ hp lost") -> "hplost:49"
            c.contains("1000 steps") -> "steps:1000"
            c.contains("999 coins") -> "coins:999"
            c.contains("rage fist") -> "moveuses:ragefist:20"
            c.contains("3 bisharp") -> "defeat:bisharp:3"
            c.contains("psyshield bash") -> "moveuses:psyshieldbash:20"
            c.contains("barb barrage") -> "moveuses:barbbarrage:20"
            c.contains("single strike") -> "tower:single"
            c.contains("rapid strike") -> "tower:rapid"
            c.contains("spin") -> "spin"
            c.contains("full moon") -> "fullmoon"
            c.contains("black augurite") -> "item:blackaugurite"
            else -> "other"
        }
    }

    // ---------------------------------------------------------------- learnsets

    private fun importLearnsets(raw: JsonObject, speciesIds: Set<String>, moveIds: Set<String>): List<LearnsetData> {
        val result = ArrayList<LearnsetData>()
        for ((id, el) in raw) {
            if (id !in speciesIds) continue
            val ls = el.jsonObject.obj("learnset") ?: continue
            val levelByGen = HashMap<Int, MutableList<LevelMove>>()
            val machine = LinkedHashSet<String>()
            val egg = LinkedHashSet<String>()
            for ((move, srcs) in ls) {
                if (move !in moveIds) continue
                for (s in srcs.jsonArray.map { it.jsonPrimitive.content }) {
                    val gen = s.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
                    val rest = s.dropWhile { it.isDigit() }
                    when (rest.firstOrNull()) {
                        'L' -> levelByGen.getOrPut(gen) { ArrayList() }.add(LevelMove(rest.drop(1).toIntOrNull() ?: 1, move))
                        'M', 'T' -> machine.add(move)
                        'E' -> egg.add(move)
                    }
                }
            }
            // Gen 1-2 virtual console ("V") and Let's Go lists are excluded from level-up selection.
            val gen = levelByGen.keys.maxOrNull()
            val levelUp = gen?.let { g -> levelByGen.getValue(g).distinct().sortedWith(compareBy({ it.level }, { it.move })) } ?: emptyList()
            result.add(LearnsetData(id, levelUp, gen ?: 0, machine.sorted(), egg.sorted()))
        }
        return result.sortedBy { it.species }
    }

    // ---------------------------------------------------------------- manifest

    private fun manifest(): ContentManifest {
        val sums = File(src, "SHA256SUMS").readLines().filter { it.isNotBlank() }.associate {
            val (h, p) = it.trim().split(Regex("\\s+"), limit = 2); p.removePrefix("./") to h
        }
        return ContentManifest(
            contentVersion = 1,
            rosterCutoff = "National Pokédex #0001-#1025 (Generation IX through The Indigo Disk and Pecharunt). " +
                "Mega Evolutions introduced in Pokémon Legends: Z-A are included as forms.",
            expectedSpeciesCount = 1025,
            provenance = listOf(
                ContentProvenance(
                    name = "Pokémon Showdown data",
                    source = "https://play.pokemonshowdown.com/data/ and https://raw.githubusercontent.com/smogon/pokemon-showdown/master/data/text/",
                    license = "MIT (code/data compilation, Copyright (c) 2011-2026 Guangcong Luo and contributors). " +
                        "Pokémon names and game mechanics are property of Nintendo/Creatures/GAME FREAK.",
                    files = sums.filterKeys { it.startsWith("showdown/") },
                    retrieved = "2026-10-13",
                ),
                ContentProvenance(
                    name = "PokeAPI CSV data",
                    source = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/",
                    license = "BSD-3-Clause (PokeAPI). Pokémon names and game data are property of Nintendo/Creatures/GAME FREAK.",
                    files = sums.filterKeys { it.startsWith("pokeapi/") },
                    retrieved = "2026-10-13",
                ),
            ),
        )
    }
}
