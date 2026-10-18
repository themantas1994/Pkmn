package pkmn.engine.world

/**
 * PKS — the event scripting language used for NPC dialogue, story events, gyms and quests.
 *
 * ```
 * script hometown_mom          # comments start with '#'
 *   faceplayer
 *   if flag got_starter and not flag mom_healed
 *     say "MOM: Rest a bit, {player}."
 *     heal
 *   elif badges >= 3
 *     say "MOM: Three badges already!"
 *   else
 *     say "MOM: The Professor is waiting!"
 *   end
 * end
 * ```
 * Scripts compile to a flat instruction list with jumps so execution can pause on any UI command
 * (dialogue, choices, battles, movement) and resume later. See docs/SCRIPTING.md for the command list.
 */
class ScriptError(message: String, val file: String, val line: Int) : Exception("$file:$line: $message")

sealed class Instr {
    abstract val line: Int
    data class Cmd(val name: String, val args: List<String>, override val line: Int) : Instr()
    data class JumpIfNot(val cond: Cond, var target: Int, override val line: Int) : Instr()
    data class Jump(var target: Int, override val line: Int) : Instr()
}

/** Boolean condition tree. */
sealed class Cond {
    data class Atom(val words: List<String>) : Cond()
    data class Not(val c: Cond) : Cond()
    data class And(val a: Cond, val b: Cond) : Cond()
    data class Or(val a: Cond, val b: Cond) : Cond()
}

class CompiledScript(val id: String, val file: String, val code: List<Instr>)

object ScriptParser {
    /** Every command name the VM understands, with minimum argument counts. Used for validation. */
    val COMMANDS: Map<String, Int> = mapOf(
        "say" to 1, "ask" to 1, "choice" to 2, "faceplayer" to 0, "face" to 2, "move" to 2, "wait" to 1, "lock" to 0, "release" to 0,
        "setflag" to 1, "clearflag" to 1, "setvar" to 2, "addvar" to 2, "giveitem" to 1, "takeitem" to 1, "givemoney" to 1, "takemoney" to 1,
        "givemon" to 2, "giveegg" to 2, "battle" to 1, "wildbattle" to 2, "heal" to 0, "healcenter" to 0, "warp" to 3, "music" to 1, "restoremusic" to 0,
        "sfx" to 1, "cry" to 1, "fadeout" to 0, "fadein" to 0, "show" to 1, "hide" to 1, "emote" to 2, "badge" to 1, "shop" to 1, "pc" to 0,
        "setrespawn" to 0, "call" to 1, "return" to 0, "quest" to 2, "pokedex" to 0, "movetutor" to 1, "moverelearner" to 0, "nickname" to 0,
        "weather" to 1, "tm" to 1, "trade" to 3, "credits" to 0, "halloffame" to 0, "settime" to 1, "evolve" to 0, "flash" to 0, "shake" to 0,
        "playerface" to 1, "sign" to 1, "nameinput" to 1, "unlock" to 1, "setmonflag" to 1, "teleport" to 0, "blackout" to 0, "save" to 0,
        "special" to 1, "fly" to 0, "walkto" to 3,
    )

    fun parseFile(text: String, file: String): List<CompiledScript> {
        val result = ArrayList<CompiledScript>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val toks = tokenize(lines[i], file, i + 1)
            if (toks.isEmpty()) { i++; continue }
            if (toks[0] != "script" || toks.size != 2) throw ScriptError("expected 'script <id>'", file, i + 1)
            val id = toks[1]
            val start = i + 1
            // Find matching end (track nested blocks).
            var depth = 0
            var j = start
            while (j < lines.size) {
                val t = tokenize(lines[j], file, j + 1)
                if (t.isNotEmpty()) {
                    when (t[0]) {
                        "if" -> depth++
                        "end" -> { if (depth == 0) break else depth-- }
                    }
                }
                j++
            }
            if (j >= lines.size) throw ScriptError("script '$id' has no matching 'end'", file, i + 1)
            result.add(CompiledScript(id, file, compileBody(lines, start, j, file)))
            i = j + 1
        }
        return result
    }

    private fun compileBody(lines: List<String>, from: Int, to: Int, file: String): List<Instr> {
        val code = ArrayList<Instr>()
        // Stack of open if-blocks: pending JumpIfNot index and list of end-jumps to patch.
        class Block(var pendingCond: Int, val endJumps: MutableList<Int>, var sawElse: Boolean = false)
        val stack = ArrayList<Block>()
        for (ln in from until to) {
            val toks = tokenize(lines[ln], file, ln + 1)
            if (toks.isEmpty()) continue
            val lineNo = ln + 1
            when (toks[0]) {
                "if" -> {
                    if (toks.size < 2) throw ScriptError("if needs a condition", file, lineNo)
                    code.add(Instr.JumpIfNot(parseCond(toks.drop(1), file, lineNo), -1, lineNo))
                    stack.add(Block(code.size - 1, ArrayList()))
                }
                "elif" -> {
                    val b = stack.lastOrNull() ?: throw ScriptError("elif without if", file, lineNo)
                    if (b.sawElse) throw ScriptError("elif after else", file, lineNo)
                    code.add(Instr.Jump(-1, lineNo)); b.endJumps.add(code.size - 1)
                    (code[b.pendingCond] as Instr.JumpIfNot).target = code.size
                    code.add(Instr.JumpIfNot(parseCond(toks.drop(1), file, lineNo), -1, lineNo))
                    b.pendingCond = code.size - 1
                }
                "else" -> {
                    val b = stack.lastOrNull() ?: throw ScriptError("else without if", file, lineNo)
                    if (b.sawElse) throw ScriptError("duplicate else", file, lineNo)
                    code.add(Instr.Jump(-1, lineNo)); b.endJumps.add(code.size - 1)
                    (code[b.pendingCond] as Instr.JumpIfNot).target = code.size
                    b.pendingCond = -1
                    b.sawElse = true
                }
                "end" -> {
                    val b = stack.removeLastOrNull() ?: throw ScriptError("unexpected end", file, lineNo)
                    if (b.pendingCond >= 0) (code[b.pendingCond] as Instr.JumpIfNot).target = code.size
                    for (j in b.endJumps) (code[j] as Instr.Jump).target = code.size
                }
                else -> {
                    val name = toks[0]
                    val min = COMMANDS[name] ?: throw ScriptError("unknown command '$name'", file, lineNo)
                    if (toks.size - 1 < min) throw ScriptError("'$name' needs at least $min argument(s)", file, lineNo)
                    code.add(Instr.Cmd(name, toks.drop(1), lineNo))
                }
            }
        }
        if (stack.isNotEmpty()) throw ScriptError("unclosed if-block", file, to)
        return code
    }

    private fun parseCond(words: List<String>, file: String, line: Int): Cond {
        // Precedence: not > and > or. Split on "or" first, then "and".
        val orParts = split(words, "or")
        if (orParts.size > 1) return orParts.map { parseCond(it, file, line) }.reduce { a, b -> Cond.Or(a, b) }
        val andParts = split(words, "and")
        if (andParts.size > 1) return andParts.map { parseCond(it, file, line) }.reduce { a, b -> Cond.And(a, b) }
        if (words.firstOrNull() == "not") return Cond.Not(parseCond(words.drop(1), file, line))
        if (words.isEmpty()) throw ScriptError("empty condition", file, line)
        val known = setOf("flag", "result", "badges", "badge", "item", "money", "var", "party", "partysize", "time", "caught", "seen", "caughtcount",
            "trainer", "gender", "field", "random", "quest", "gimmick", "visited", "true", "false", "hasmon", "playerx", "playery")
        if (words[0] !in known) throw ScriptError("unknown condition '${words[0]}'", file, line)
        return Cond.Atom(words)
    }

    private fun split(words: List<String>, sep: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (w in words) if (w == sep) { out.add(cur); cur = ArrayList() } else cur.add(w)
        out.add(cur)
        return out
    }

    /** Reads the quoted string starting at [start] (a `"`) into [sb], applying the [tokenize] escapes; returns the index after it. */
    private fun readQuoted(line: String, start: Int, sb: StringBuilder, file: String, lineNo: Int): Int {
        var i = start + 1
        while (i < line.length) {
            val d = line[i]
            if (d == '\\' && i + 1 < line.length) {
                when (line[i + 1]) { 'n' -> sb.append('\n'); 'p' -> sb.append('\u000C'); '"' -> sb.append('"'); '\\' -> sb.append('\\'); else -> sb.append(line[i + 1]) }
                i += 2
            } else if (d == '"') return i + 1 else { sb.append(d); i++ }
        }
        throw ScriptError("unterminated string", file, lineNo)
    }

    /** Splits a line into tokens; double-quoted strings keep spaces and support \n, \p, \" and \\ escapes. */
    fun tokenize(line: String, file: String = "", lineNo: Int = 0): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '#' -> break
                c.isWhitespace() -> i++
                c == '"' -> {
                    val sb = StringBuilder()
                    i = readQuoted(line, i, sb, file, lineNo)
                    out.add(sb.toString())
                }
                else -> {
                    // A bare token; `key="quoted value"` keeps the quoted part (spaces included) as the value.
                    val sb = StringBuilder()
                    while (i < line.length && !line[i].isWhitespace() && line[i] != '#') {
                        if (line[i] == '"' && sb.endsWith("=")) i = readQuoted(line, i, sb, file, lineNo)
                        else sb.append(line[i++])
                    }
                    out.add(sb.toString())
                }
            }
        }
        return out
    }
}
