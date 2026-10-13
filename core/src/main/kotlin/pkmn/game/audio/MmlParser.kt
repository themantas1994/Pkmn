package pkmn.game.audio

/** A song-source error with a 1-based [line] / [col] position. */
class MmlException(val songId: String, val line: Int, val col: Int, val detail: String) :
    RuntimeException("$songId:$line:$col: $detail")

/**
 * Parser for the project's MML-like song format (see docs/AUDIO.md for the full reference).
 *
 * Summary:
 * ```
 * #title  Hometown            ; directives start with '#'
 * #tempo  96
 * #time   4/4
 * #key    G major
 * #inst 1 duty=2 env=11,-3 rel=-1 vib=16,12,5
 * #wave 1 0123456789ABCDEFFEDCBA9876543210
 * #drum K f=1200 v=15 n=1 | f=400 e=-1 sw=-2
 * #def groove K8 H8 S8 H8
 * A @1 o4 l8 $ [c d e g | a4 g4 : f e d c]2 |
 * D $ [{groove}]8
 * ```
 * Each channel line starts with `A`/`B` (pulse), `C` (wave) or `D` (noise) followed by whitespace;
 * lines for the same channel concatenate.
 */
object MmlParser {

    /** Built-in drum kit used by the noise channel (overridable per song with `#drum`). */
    val DEFAULT_KIT: Map<Char, DrumDef> by lazy {
        val src = mapOf(
            'K' to "f=6000 v=15 n=1 | f=900 v=14 e=-1 sw=-1.6",            // kick
            'S' to "f=90000 v=15 n=1 | f=24000 v=12 e=-1 sw=-0.15",        // snare
            'H' to "f=262000 v=10 n=1 | v=5 e=-1",                          // closed hat
            'O' to "f=262000 v=9 e=-2",                                     // open hat
            'C' to "f=131000 v=12 n=2 | v=10 e=-4 sw=-0.05",                // crash
            'T' to "f=5200 v=14 w=1 n=1 | e=-1 sw=-0.5",                    // low tom
            'M' to "f=8200 v=14 w=1 n=1 | e=-1 sw=-0.5",                    // mid tom
            'U' to "f=12000 v=14 w=1 n=1 | e=-1 sw=-0.5",                   // high tom
            'R' to "f=60000 v=12 w=1 n=1 | v=6 e=-1",                       // rim click
            'P' to "f=40000 v=13 n=1 | v=5 n=1 | v=13 n=1 | v=11 e=-1",     // clap
            'X' to "f=110000 v=10 n=1 | v=6 e=-1",                          // light snare / ghost
            'N' to "f=2000 v=12 e=-3 sw=0.6",                               // rising noise sweep
            'Z' to "f=200000 v=13 e=-3 sw=-0.4",                            // falling noise sweep
        )
        src.mapValues { (k, v) -> parseDrum(k, v) { msg -> IllegalStateException("default kit '$k': $msg") } }
    }

    fun parse(text: String, id: String = "song"): Song = Impl(text, id).parse()

    internal fun parseDrum(name: Char, spec: String, err: (String) -> Throwable): DrumDef {
        val steps = spec.split('|').map { part ->
            var hz: Double? = null; var vol: Int? = null; var env = 0; var short: Boolean? = null; var frames = 0; var sweep = 0.0
            for (kv in part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val eq = kv.indexOf('=')
                if (eq <= 0) throw err("expected key=value in drum step, got '$kv'")
                val k = kv.substring(0, eq); val v = kv.substring(eq + 1)
                fun num(): Double = v.toDoubleOrNull() ?: throw err("bad number '$v' for '$k'")
                when (k) {
                    "f" -> hz = num().also { if (it < 1.0 || it > 2_000_000.0) throw err("drum frequency $it Hz out of range 1..2000000") }
                    "v" -> vol = num().toInt().also { if (it !in 0..15) throw err("drum volume $it out of range 0..15") }
                    "e" -> env = num().toInt().also { if (it !in -7..7) throw err("envelope step $it out of range -7..7") }
                    "w" -> short = num().toInt() != 0
                    "n" -> frames = num().toInt().also { if (it < 0) throw err("negative frame count") }
                    "sw" -> sweep = num()
                    else -> throw err("unknown drum key '$k' (expected f, v, e, w, n, sw)")
                }
            }
            DrumStep(hz, vol, env, short, frames, sweep)
        }
        if (steps.first().hz == null || steps.first().volume == null) throw err("first drum step needs f= and v=")
        return DrumDef(name, steps)
    }

    // ------------------------------------------------------------------------------------------------

    private class Seg(val text: String, val line: Int, val col: Int)

    private class Len(val num: Int?, val direct: Int?, val dots: Int)

    private sealed class Node(val line: Int, val col: Int)
    private class NNote(l: Int, c: Int, val letter: Char, val acc: Int, val lens: MutableList<Len>, val legato: Boolean, val porta: Boolean) : Node(l, c)
    private class NDrum(l: Int, c: Int, val name: Char, val lens: MutableList<Len>) : Node(l, c)
    private class NRest(l: Int, c: Int, val lens: MutableList<Len>) : Node(l, c)
    private class NCmd(l: Int, c: Int, val cmd: Char, val value: Double) : Node(l, c)
    private class NDefLen(l: Int, c: Int, val len: Len) : Node(l, c)
    private class NPan(l: Int, c: Int, val pan: Float) : Node(l, c)
    private class NRepeat(l: Int, c: Int, val body: List<Node>, val breakAt: Int, val count: Int) : Node(l, c)
    private class NMacro(l: Int, c: Int, val name: String) : Node(l, c)
    private class NLoop(l: Int, c: Int) : Node(l, c)
    private class NBar(l: Int, c: Int) : Node(l, c)

    private class Reader(val segs: List<Seg>) {
        var si = 0
        var ci = 0
        val eof: Boolean get() { normalize(); return si >= segs.size }
        private fun normalize() { while (si < segs.size && ci >= segs[si].text.length) { si++; ci = 0 } }
        fun peek(): Char { normalize(); return if (si < segs.size) segs[si].text[ci] else '\u0000' }
        fun next(): Char { val c = peek(); ci++; return c }
        val line: Int get() { normalize(); return if (si < segs.size) segs[si].line else segs.lastOrNull()?.line ?: 0 }
        val col: Int get() { normalize(); return if (si < segs.size) segs[si].col + ci else (segs.lastOrNull()?.let { it.col + it.text.length } ?: 0) }
        fun skipWs() { while (!eof && peek().isWhitespace()) ci++ }
    }

    private class Inst(val patch: Patch)

    private class Impl(val text: String, val id: String) {
        var title = id
        var tempo = 120.0
        var beats = 4
        var unit = 4
        var barCheck = true
        var gain = 1f
        val keys = mutableListOf<String>()
        val waves = ChipSynth.DEFAULT_WAVES.toMutableList<ByteArray?>().also { while (it.size < 16) it.add(null) }
        val insts = HashMap<Int, Inst>()
        val kit = HashMap(DEFAULT_KIT)
        val macroSrc = HashMap<String, Seg>()
        val macros = HashMap<String, List<Node>>()
        val chanSegs = Array(4) { mutableListOf<Seg>() }

        fun err(line: Int, col: Int, msg: String): MmlException = MmlException(id, line, col, msg)

        fun parse(): Song {
            readLines()
            val barTicks = TICKS_PER_WHOLE * beats / unit
            val asts = Array(4) { ch -> parseChannel(ch) }
            val tracks = (0 until 4).map { ch -> Expander(this, ch, barTicks).run(asts[ch]) }
            return assemble(tracks)
        }

        fun readLines() {
            val lines = text.split('\n')
            for ((i, raw0) in lines.withIndex()) {
                val lineNo = i + 1
                val raw = raw0.substringBefore(';').trimEnd('\r', ' ', '\t')
                val start = raw.indexOfFirst { !it.isWhitespace() }
                if (start < 0) continue
                val body = raw.substring(start)
                if (body[0] == '#') { directive(body, lineNo, start + 1); continue }
                val ch = "ABCD".indexOf(body[0])
                if (ch >= 0 && (body.length == 1 || body[1].isWhitespace())) {
                    if (body.length > 1) chanSegs[ch].add(Seg(body.substring(1), lineNo, start + 2))
                    continue
                }
                throw err(lineNo, start + 1, "expected a '#' directive or a channel line starting with A, B, C or D followed by a space")
            }
        }

        fun directive(body: String, line: Int, col: Int) {
            val name = body.substring(1).takeWhile { !it.isWhitespace() }
            val argStart = 1 + name.length
            val arg = body.substring(argStart).trim()
            val argCol = col + argStart + (body.substring(argStart).length - body.substring(argStart).trimStart().length)
            fun bad(msg: String): Nothing = throw err(line, argCol, msg)
            when (name) {
                "title" -> title = arg
                "composer", "comment" -> {}
                "tempo" -> tempo = arg.toDoubleOrNull()?.takeIf { it in 20.0..400.0 } ?: bad("tempo must be a number between 20 and 400 BPM, got '$arg'")
                "time" -> {
                    val m = Regex("(\\d+)/(\\d+)").matchEntire(arg) ?: bad("time signature must look like 4/4 or 6/8")
                    beats = m.groupValues[1].toInt(); unit = m.groupValues[2].toInt()
                    if (beats !in 1..16 || unit !in listOf(2, 4, 8, 16)) bad("unsupported time signature $arg")
                }
                "key" -> arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { k ->
                    if (ScaleUtil.parseKey(k) == null) bad("unknown key '$k' (examples: 'C major', 'F# minor', 'D dorian')")
                    keys.add(k)
                }
                "barcheck" -> barCheck = when (arg) { "on" -> true; "off" -> false; else -> bad("#barcheck expects on or off") }
                "gain" -> gain = arg.toFloatOrNull()?.takeIf { it in 0f..2f } ?: bad("#gain expects a number 0..2")
                "wave" -> {
                    val parts = arg.split(Regex("\\s+"), limit = 2)
                    val n = parts[0].toIntOrNull()?.takeIf { it in 0..15 } ?: bad("wave index must be 0..15")
                    if (parts.size < 2) bad("missing 32 hex digits for wave $n")
                    val hex = parts[1].filter { !it.isWhitespace() }
                    if (hex.length != 32 || hex.any { Character.digit(it, 16) < 0 }) bad("wave $n needs exactly 32 hex digits (0-F), got ${hex.length} characters")
                    waves[n] = ChipSynth.hexWave(hex)
                }
                "inst" -> {
                    val parts = arg.split(Regex("\\s+"))
                    val n = parts[0].toIntOrNull()?.takeIf { it in 0..63 } ?: bad("instrument number must be 0..63")
                    insts[n] = Inst(parseInst(parts.drop(1), ::bad))
                }
                "drum" -> {
                    if (arg.isEmpty() || arg[0] !in 'A'..'Z' || (arg.length > 1 && !arg[1].isWhitespace())) bad("drum name must be a single uppercase letter")
                    if (arg[0] == 'L') bad("'L' is reserved")
                    kit[arg[0]] = parseDrum(arg[0], arg.substring(1).trim()) { m -> err(line, argCol, m) }
                }
                "def" -> {
                    val mname = arg.takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (mname.isEmpty()) bad("#def needs a name made of letters, digits or '_'")
                    val rest = arg.substring(mname.length)
                    val off = rest.length - rest.trimStart().length
                    macroSrc[mname] = Seg(rest.trimStart(), line, argCol + mname.length + off)
                }
                else -> throw err(line, col, "unknown directive '#$name'")
            }
        }

        fun parseInst(kvs: List<String>, bad: (String) -> Nothing): Patch {
            var p = Patch()
            for (kv in kvs) {
                val eq = kv.indexOf('=')
                if (eq <= 0) bad("expected key=value, got '$kv'")
                val k = kv.substring(0, eq); val v = kv.substring(eq + 1)
                val nums = v.split(',').map { it.toDoubleOrNull() }
                fun n(i: Int): Double = nums.getOrNull(i) ?: bad("'$k' expects a number at position ${i + 1}, got '$v'")
                p = when (k) {
                    "duty" -> p.copy(duty = n(0).toInt().also { if (it !in 0..3) bad("duty must be 0..3") })
                    "wave" -> p.copy(wave = n(0).toInt().also { if (it !in 0..15) bad("wave must be 0..15") })
                    "env" -> {
                        val vol = n(0).toInt(); val step = if (nums.size > 1) n(1).toInt() else 0
                        if (vol !in 0..15) bad("envelope volume must be 0..15"); if (step !in -7..7) bad("envelope step must be -7..7")
                        p.copy(volume = vol, envStep = step)
                    }
                    "rel" -> p.copy(releaseStep = n(0).toInt().also { if (it !in -7..0) bad("rel must be -7..0 (0 = cut)") })
                    "vib" -> p.copy(vibDelay = n(0) / 64.0, vibDepth = n(1), vibRate = if (nums.size > 2) n(2) else 5.5)
                    "pan" -> p.copy(pan = when (v) { "l" -> -1f; "r" -> 1f; "c" -> 0f; else -> n(0).toFloat().coerceIn(-1f, 1f) })
                    "slide" -> p.copy(slideSpeed = n(0))
                    "gain" -> p.copy(gain = n(0).toFloat())
                    else -> bad("unknown instrument key '$k' (expected duty, wave, env, rel, vib, pan, slide, gain)")
                }
            }
            return p
        }

        fun parseChannel(ch: Int): List<Node> {
            val r = Reader(chanSegs[ch])
            val (nodes, brk) = parseSeq(r, ch, false)
            if (brk >= 0) throw err(r.line, r.col, "':' is only allowed inside [ ]")
            return nodes
        }

        fun macro(name: String, ch: Int, line: Int, col: Int, depth: Int): List<Node> {
            if (depth > 8) throw err(line, col, "macros nested too deeply (recursive {$name}?)")
            macros["$ch:$name"]?.let { return it }
            val seg = macroSrc[name] ?: throw err(line, col, "undefined macro {$name}")
            val r = Reader(listOf(seg))
            val (nodes, brk) = parseSeq(r, ch, false)
            if (brk >= 0) throw err(seg.line, seg.col, "':' outside [ ] in macro {$name}")
            macros["$ch:$name"] = nodes
            return nodes
        }

        /** Parses until EOF or a closing ']' (consumed by the caller). Returns nodes and the ':' break index. */
        fun parseSeq(r: Reader, ch: Int, inRepeat: Boolean): Pair<List<Node>, Int> {
            val out = ArrayList<Node>()
            var breakAt = -1
            var legatoNext = false
            var portaNext = false
            while (true) {
                r.skipWs()
                if (r.eof) {
                    if (inRepeat) throw err(r.line, r.col, "unclosed '[' (missing ']')")
                    break
                }
                val line = r.line; val col = r.col
                val c = r.peek()
                if (c == ']') {
                    if (!inRepeat) throw err(line, col, "']' without matching '['")
                    break
                }
                r.next()
                when {
                    c in 'a'..'g' -> {
                        if (ch == 3) throw err(line, col, "notes are not allowed on the noise channel D; use drum letters (K S H O C T M U R P X N Z or #drum)")
                        var acc = 0
                        while (r.peek() == '+' || r.peek() == '#' || r.peek() == '-') acc += if (r.next() == '-') -1 else 1
                        val lens = mutableListOf(readLen(r, true))
                        out.add(NNote(line, col, c, acc, lens, legatoNext, portaNext)); legatoNext = false; portaNext = false
                    }
                    c in 'A'..'Z' && ch == 3 -> {
                        if (c !in kit) throw err(line, col, "unknown drum '$c' (define it with #drum $c ...)")
                        out.add(NDrum(line, col, c, mutableListOf(readLen(r, true))))
                    }
                    c in 'A'..'Z' -> throw err(line, col, "unexpected '$c': uppercase drum letters are only valid on channel D")
                    c == 'r' -> out.add(NRest(line, col, mutableListOf(readLen(r, true))))
                    c == '&' -> {
                        r.skipWs()
                        val p = r.peek()
                        val last = out.lastOrNull()
                        if (p.isDigit() || p == '%') {
                            val lens = when (last) { is NNote -> last.lens; is NDrum -> last.lens; is NRest -> last.lens; else -> throw err(line, col, "'&' must follow a note, drum or rest") }
                            lens.add(readLen(r, false))
                        } else if (p in 'a'..'g') {
                            if (last !is NNote) throw err(line, col, "'&' slur must follow a note")
                            legatoNext = true
                        } else throw err(line, col, "'&' must be followed by a length (tie) or a note (slur)")
                    }
                    c == '/' -> portaNext = true
                    c == 'o' || c == 'v' || c == '@' || c == 'q' || c == 'w' || c == 't' || c == 'k' || c == 'j' -> {
                        val v = readNumber(r, allowSign = c == 'k' || c == 'j', allowFrac = c == 't')
                            ?: throw err(line, col, "'$c' needs a number")
                        out.add(NCmd(line, col, c, v))
                    }
                    c == '>' || c == '<' -> out.add(NCmd(line, col, c, 0.0))
                    c == 'l' -> {
                        val len = readLen(r, false)
                        out.add(NDefLen(line, col, len))
                    }
                    c == 'p' -> {
                        val pc = r.next()
                        val pan = when (pc) { 'l' -> -1f; 'c' -> 0f; 'r' -> 1f; else -> throw err(line, col, "'p' must be followed by l, c or r") }
                        out.add(NPan(line, col, pan))
                    }
                    c == '[' -> {
                        val (body, brk) = parseSeq(r, ch, true)
                        r.next() // ']'
                        val n = readNumber(r, false, false)?.toInt() ?: 2
                        if (n !in 1..64) throw err(line, col, "repeat count must be 1..64")
                        out.add(NRepeat(line, col, body, brk, n))
                    }
                    c == ':' -> {
                        if (!inRepeat) throw err(line, col, "':' is only allowed inside [ ]")
                        if (breakAt >= 0) throw err(line, col, "only one ':' per repeat")
                        breakAt = out.size
                    }
                    c == '{' -> {
                        val sb = StringBuilder()
                        while (!r.eof && r.peek() != '}') sb.append(r.next())
                        if (r.eof) throw err(line, col, "unclosed '{'")
                        r.next()
                        out.add(NMacro(line, col, sb.toString().trim()))
                    }
                    c == '$' -> out.add(NLoop(line, col))
                    c == '|' -> out.add(NBar(line, col))
                    else -> throw err(line, col, "unexpected character '$c'")
                }
            }
            if (legatoNext || portaNext) throw err(r.line, r.col, "dangling '&' or '/' at end of sequence")
            return out to breakAt
        }

        fun readNumber(r: Reader, allowSign: Boolean, allowFrac: Boolean): Double? {
            val sb = StringBuilder()
            if (allowSign && (r.peek() == '-' || r.peek() == '+')) sb.append(r.next())
            while (r.peek().isDigit() || (allowFrac && r.peek() == '.')) sb.append(r.next())
            return sb.toString().toDoubleOrNull()
        }

        fun readLen(r: Reader, optional: Boolean): Len {
            val line = r.line; val col = r.col
            var num: Int? = null; var direct: Int? = null
            if (r.peek() == '%') {
                r.next()
                direct = readNumber(r, false, false)?.toInt() ?: throw err(line, col, "'%' needs a tick count")
                if (direct <= 0) throw err(line, col, "tick length must be positive")
            } else if (r.peek().isDigit()) {
                num = readNumber(r, false, false)!!.toInt()
                if (num <= 0 || TICKS_PER_WHOLE % num != 0) throw err(line, col, "length $num does not divide a whole note (${TICKS_PER_WHOLE} ticks); use 1,2,3,4,6,8,12,16,24,32,48,64 or %ticks")
            } else if (!optional) throw err(line, col, "expected a length")
            var dots = 0
            while (r.peek() == '.') { r.next(); dots++ }
            return Len(num, direct, dots)
        }

        fun assemble(tracks: List<Track>): Song {
            val used = tracks.filter { !it.isEmpty }
            if (used.isEmpty()) throw err(1, 1, "song has no notes")
            val len = used.first().lengthTicks
            if (used.any { it.lengthTicks != len }) {
                val barTicks = TICKS_PER_WHOLE * beats / unit
                val desc = used.joinToString(", ") { "${"ABCD"[it.channel]}=${it.lengthTicks} ticks (${it.lengthTicks / barTicks} bars + ${it.lengthTicks % barTicks})" }
                throw err(1, 1, "channels have different lengths: $desc")
            }
            val loopTicks = used.map { t -> if (t.loopIndex >= 0) t.events[t.loopIndex].tick else -1 }
            val loopTick = loopTicks.first()
            if (loopTicks.any { it != loopTick }) {
                throw err(1, 1, "loop points ('$') differ between channels: " + used.indices.joinToString(", ") { "${"ABCD"[used[it].channel]}=${loopTicks[it]}" } + " (all used channels need '$' at the same tick)")
            }
            if (loopTick >= len) throw err(1, 1, "loop point is at the very end of the song")
            return Song(id, title, keys.toList(), beats, unit, tempo, tracks, loopTick, len, waves.map { it ?: ChipSynth.DEFAULT_WAVES[0] }, gain)
        }
    }

    private class Expander(val p: Impl, val ch: Int, val barTicks: Int) {
        var octave = 4
        var defLen = TICKS_PER_QUARTER
        var q = 8
        var inst = 0
        var vol: Int? = null
        var wsel: Int? = null
        var pan: Float? = null
        var transpose = 0
        var sweep = 0
        var drumVol = 15
        var tick = 0
        var loopIndex = -1
        val events = ArrayList<Event>()
        val patchCache = HashMap<List<Any?>, Patch>()
        var repeatDepth = 0

        fun run(nodes: List<Node>): Track {
            expand(nodes, 0)
            // a note followed by a slurred note keeps its gate open
            for (i in events.indices) {
                val e = events[i]
                if (e.type != Event.NOTE) continue
                var j = i + 1
                while (j < events.size && events[j].type == Event.TEMPO) j++
                if (j < events.size && events[j].type == Event.NOTE && (events[j].legato || events[j].portamento) && e.gate != e.ticks) {
                    events[i] = Event(Event.NOTE, e.tick, e.ticks, e.ticks, e.pitch, e.patch, e.drum, e.legato, e.portamento, e.sweep, e.volume, e.bpm, e.line, e.col)
                }
            }
            return Track(ch, events, loopIndex, tick)
        }

        fun ticks(lens: List<Len>, line: Int, col: Int): Int = lens.sumOf { lenTicks(it, line, col) }

        fun lenTicks(l: Len, line: Int, col: Int): Int {
            val base = l.direct ?: l.num?.let { TICKS_PER_WHOLE / it } ?: defLen
            var t = base; var add = base
            repeat(l.dots) {
                if (add % 2 != 0) throw p.err(line, col, "dotted length is not a whole number of ticks")
                add /= 2; t += add
            }
            return t
        }

        fun patch(line: Int, col: Int): Patch {
            val key = listOf(inst, vol, wsel, pan)
            return patchCache.getOrPut(key) {
                val base = p.insts[inst]?.patch ?: if (inst == 0) Patch() else throw p.err(line, col, "undefined instrument @$inst")
                var pt = base
                if (vol != null) pt = pt.copy(volume = vol!!)
                if (wsel != null) pt = if (ch == 2) pt.copy(wave = wsel!!) else pt.copy(duty = wsel!!)
                if (pan != null) pt = pt.copy(pan = pan!!)
                if (ch == 2 && p.waves[pt.wave] == null) throw p.err(line, col, "wave ${pt.wave} is not defined (#wave ${pt.wave} ...)")
                pt
            }
        }

        fun gate(t: Int): Int = if (q >= 8) t else maxOf(1, t * q / 8)

        fun expand(nodes: List<Node>, depth: Int) {
            for (n in nodes) {
                if (events.size > 200_000) throw p.err(n.line, n.col, "song expands to too many events (runaway repeat?)")
                when (n) {
                    is NNote -> {
                        val semi = when (n.letter) { 'c' -> 0; 'd' -> 2; 'e' -> 4; 'f' -> 5; 'g' -> 7; 'a' -> 9; else -> 11 }
                        val midi = (octave + 1) * 12 + semi + n.acc + transpose
                        if (midi !in 0..127) throw p.err(n.line, n.col, "note out of range (MIDI $midi)")
                        val t = ticks(n.lens, n.line, n.col)
                        events.add(Event(Event.NOTE, tick, t, gate(t), midi, patch(n.line, n.col), null, n.legato, n.porta, sweep, 15, 0.0, n.line, n.col))
                        tick += t
                    }
                    is NDrum -> {
                        val t = ticks(n.lens, n.line, n.col)
                        events.add(Event(Event.NOTE, tick, t, gate(t), -1, null, p.kit[n.name], false, false, 0, drumVol, 0.0, n.line, n.col))
                        tick += t
                    }
                    is NRest -> {
                        val t = ticks(n.lens, n.line, n.col)
                        val last = events.lastOrNull()
                        if (last != null && last.type == Event.REST) {
                            events[events.size - 1] = Event(Event.REST, last.tick, last.ticks + t, line = last.line, col = last.col)
                        } else events.add(Event(Event.REST, tick, t, line = n.line, col = n.col))
                        tick += t
                    }
                    is NDefLen -> defLen = lenTicks(n.len, n.line, n.col)
                    is NPan -> pan = n.pan
                    is NCmd -> {
                        val v = n.value.toInt()
                        when (n.cmd) {
                            'o' -> { if (v !in 0..8) throw p.err(n.line, n.col, "octave must be 0..8"); octave = v }
                            '>' -> { octave++; if (octave > 8) throw p.err(n.line, n.col, "octave above 8") }
                            '<' -> { octave--; if (octave < 0) throw p.err(n.line, n.col, "octave below 0") }
                            'v' -> { if (v !in 0..15) throw p.err(n.line, n.col, "volume must be 0..15"); if (ch == 3) drumVol = v else vol = v }
                            '@' -> { if (v != 0 && v !in p.insts) throw p.err(n.line, n.col, "undefined instrument @$v"); inst = v; vol = null; wsel = null; pan = null }
                            'q' -> { if (v !in 1..8) throw p.err(n.line, n.col, "q must be 1..8"); q = v }
                            'w' -> {
                                when (ch) {
                                    0, 1 -> if (v !in 0..3) throw p.err(n.line, n.col, "duty must be 0..3 (12.5/25/50/75%)")
                                    2 -> if (v !in 0..15 || p.waves[v] == null) throw p.err(n.line, n.col, "wave $v is not defined")
                                    else -> throw p.err(n.line, n.col, "'w' is not valid on the noise channel")
                                }
                                wsel = v
                            }
                            't' -> {
                                if (n.value !in 20.0..400.0) throw p.err(n.line, n.col, "tempo must be 20..400")
                                events.add(Event(Event.TEMPO, tick, 0, bpm = n.value, line = n.line, col = n.col))
                            }
                            'k' -> transpose = v
                            'j' -> sweep = v
                        }
                    }
                    is NRepeat -> {
                        repeatDepth++
                        if (repeatDepth > 8) throw p.err(n.line, n.col, "repeats nested too deeply")
                        for (i in 1..n.count) {
                            if (n.breakAt >= 0) {
                                expand(n.body.subList(0, n.breakAt), depth)
                                if (i < n.count) expand(n.body.subList(n.breakAt, n.body.size), depth)
                            } else expand(n.body, depth)
                        }
                        repeatDepth--
                    }
                    is NMacro -> expand(p.macro(n.name, ch, n.line, n.col, depth + 1), depth + 1)
                    is NLoop -> {
                        if (repeatDepth > 0) throw p.err(n.line, n.col, "loop point '$' cannot be inside a repeat")
                        if (loopIndex >= 0) throw p.err(n.line, n.col, "duplicate loop point '$'")
                        loopIndex = events.size
                        // ensure the loop index points at an event starting exactly here
                        events.add(Event(Event.REST, tick, 0, line = n.line, col = n.col))
                    }
                    is NBar -> if (p.barCheck && tick % barTicks != 0) {
                        val bar = tick / barTicks + 1
                        throw p.err(n.line, n.col, "bar check failed: position is ${tick % barTicks} ticks into bar $bar (bar = $barTicks ticks, quarter = $TICKS_PER_QUARTER)")
                    }
                }
            }
        }
    }
}
