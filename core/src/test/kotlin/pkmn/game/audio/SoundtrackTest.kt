package pkmn.game.audio

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Offline render-and-verify tool for the whole soundtrack and every sound effect. Renders WAVs to
 * `core/build/audio-render/` (scratch, never committed) and checks each song parses, has a sensible
 * duration and a valid loop, is not silent, does not clip, stays in range and in its declared key
 * (both from the note data and from a chroma analysis of the rendered audio).
 */
class SoundtrackTest {
    private val assets = File(System.getProperty("pkmn.assets") ?: "assets").absoluteFile
    private val musicDir = File(assets, "audio/music")
    private val outDir = File(assets.parentFile, "core/build/audio-render").also { it.mkdirs() }
    private val sr = 22050

    private val expectedTracks = listOf(
        "title", "intro_professor", "hometown", "town_coastal", "town_mountain", "city_big", "city_night",
        "route_early", "route_mid", "route_late", "forest", "cave", "ocean_surf", "bike", "pokecenter", "mart",
        "gym", "villain_hideout", "rival_theme", "battle_wild", "battle_trainer", "battle_gym_leader",
        "battle_rival", "battle_villain_boss", "battle_legendary", "battle_elite_four", "battle_champion",
        "victory_wild", "victory_trainer", "victory_gym", "league_hall", "ruins", "ending_credits", "evolution",
    )
    private val expectedJingles = listOf("jingle_heal", "jingle_item", "jingle_badge", "jingle_levelup", "jingle_caught", "jingle_save")

    class Report(val id: String, val intro: Double, val loop: Double, val rms: Double, val peak: Double, val clipFrac: Double,
                 val inKey: Double, val chromaInKey: Double, val tonicRank: Int, val problems: List<String>)

    @Test
    fun allSongsRenderAndPassChecks() {
        val files = musicDir.listFiles { f -> f.name.endsWith(".mml") }?.sortedBy { it.name } ?: emptyList()
        val ids = files.map { it.nameWithoutExtension }
        val missing = (expectedTracks + expectedJingles).filter { it !in ids }
        val reports = files.map { f -> analyse(f.nameWithoutExtension, f.readText()) }
        println("%-22s %6s %6s %6s %6s %7s %6s %6s %4s".format("song", "intro", "loop", "rms", "peak", "clip%", "key%", "chrom%", "tonR"))
        for (r in reports) println("%-22s %6.1f %6.1f %6.3f %6.3f %7.4f %6.1f %6.1f %4d %s".format(
            r.id, r.intro, r.loop, r.rms, r.peak, r.clipFrac * 100, r.inKey * 100, r.chromaInKey * 100, r.tonicRank,
            if (r.problems.isEmpty()) "ok" else r.problems.joinToString("; ")))
        val totalLoop = reports.filter { it.id in expectedTracks }.sumOf { it.loop }
        println("tracks=${reports.count { it.id in expectedTracks }} jingles=${reports.count { it.id in expectedJingles }} total loop length=%.1f s".format(totalLoop))
        val problems = reports.filter { it.problems.isNotEmpty() }.map { "${it.id}: ${it.problems.joinToString("; ")}" }
        if (missing.isNotEmpty() || problems.isNotEmpty()) fail("missing=$missing\n" + problems.joinToString("\n"))
    }

    @Test
    fun allSoundEffectsRender() {
        val lines = mutableListOf<String>()
        for (s in Sfx.entries) {
            val song = SoundEffects.song(s)
            assertTrue(!song.loops, "${s.id} must not loop")
            val secs = song.totalSeconds
            assertTrue(secs in 0.01..2.0, "${s.id} duration $secs")
            val pcm = renderSong(song, secs + 0.3)
            writeWav(File(outDir, "sfx_${s.id}.wav"), pcm)
            val st = stats(pcm)
            lines += "%-24s %.2fs rms=%.3f peak=%.3f".format(s.id, secs, st.first, st.second)
            assertTrue(st.first > 0.005, "${s.id} is silent (rms ${st.first})")
            assertTrue(st.second < 0.999, "${s.id} clips")
        }
        println(lines.joinToString("\n"))
    }

    // ------------------------------------------------------------------------------------------

    private fun analyse(id: String, text: String): Report {
        val problems = mutableListOf<String>()
        val song = try { MmlParser.parse(text, id) } catch (e: MmlException) {
            return Report(id, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, listOf("parse error: ${e.message}"))
        }
        val isJingle = id.startsWith("jingle_")
        if (isJingle) {
            if (song.loops) problems += "jingle must not loop"
            if (song.totalSeconds !in 1.0..10.0) problems += "jingle length %.1f s not in 1..10".format(song.totalSeconds)
        } else {
            if (!song.loops) problems += "track must loop"
            val (lo, hi) = when {
                id == "victory_wild" -> 8.0 to 40.0
                id.startsWith("victory_") -> 15.0 to 90.0
                else -> 30.0 to 90.0
            }
            if (song.loopSeconds !in lo..hi) problems += "loop length %.1f s not in %.0f..%.0f".format(song.loopSeconds, lo, hi)
            if (song.introSeconds > 20.0) problems += "intro too long"
        }
        if (song.keys.isEmpty()) problems += "no #key"
        // ranges and key membership from note data
        val scale = ScaleUtil.scaleOf(song.keys)
        var inKey = 0L; var total = 0L
        for (t in song.tracks) {
            val range = if (t.channel == 2) 28..76 else 43..100
            for (e in t.events) {
                if (e.type != Event.NOTE || e.pitch < 0) continue
                if (e.pitch !in range) { problems += "${"ABCD"[t.channel]} note ${e.pitch} out of range $range at ${e.line}:${e.col}"; break }
                total += e.ticks
                if (e.pitch % 12 in scale) inKey += e.ticks
            }
        }
        val inKeyFrac = if (total == 0L) 0.0 else inKey.toDouble() / total
        if (inKeyFrac < 0.85) problems += "only %.0f%% of note time in key ${song.keys}".format(inKeyFrac * 100)

        // render: intro + one full loop + 4 s past the loop point (exercises the wrap)
        val secs = if (song.loops) song.totalSeconds + 4.0 else song.totalSeconds + 1.0
        val pcm = renderSong(song, secs)
        writeWav(File(outDir, "$id.wav"), pcm)
        val (rms, peak) = stats(pcm)
        var clip = 0
        for (s in pcm) if (abs(s.toInt()) >= 32000) clip++
        val clipFrac = clip.toDouble() / pcm.size
        if (rms < 0.03) problems += "too quiet (rms %.3f)".format(rms)
        if (clipFrac > 0.001) problems += "clipping %.3f%%".format(clipFrac * 100)
        // the audio right after the loop point must not be silent (catches broken wraps)
        if (song.loops) {
            val start = (song.totalSeconds * sr).toInt() * 2
            val (wrms, _) = stats(pcm.copyOfRange(start, minOf(pcm.size, start + sr * 2 * 2)))
            if (wrms < 0.01) problems += "silent after loop wrap"
        }
        val chroma = chroma(pcm)
        val chromaIn = scale.sumOf { chroma[it] } / chroma.sum().coerceAtLeast(1e-9)
        val tonic = ScaleUtil.parseKey(song.keys.firstOrNull() ?: "C major")?.first ?: 0
        val rank = chroma.indices.sortedByDescending { chroma[it] }.indexOf(tonic) + 1
        if (chromaIn < 0.7) problems += "rendered chroma only %.0f%% in key".format(chromaIn * 100)
        if (rank > 5) problems += "tonic ranks $rank in rendered chroma"
        return Report(id, song.introSeconds, song.loopSeconds, rms, peak, clipFrac, inKeyFrac, chromaIn, rank, problems)
    }

    private fun renderSong(song: Song, seconds: Double): ShortArray {
        val p = SongPlayer(ChipSynth(sr, true))
        p.load(song)
        val frames = (seconds * sr).toInt()
        val f = FloatArray(frames * 2)
        var pos = 0
        while (pos < frames) { val n = minOf(512, frames - pos); p.render(f, pos, n); pos += n }
        val pcm = ShortArray(f.size)
        ChipSynth.floatToPcm(f, 0, pcm, 0, f.size)
        return pcm
    }

    private fun stats(pcm: ShortArray): Pair<Double, Double> {
        if (pcm.isEmpty()) return 0.0 to 0.0
        var s = 0.0; var peak = 0
        for (x in pcm) { s += x.toDouble() * x; peak = maxOf(peak, abs(x.toInt())) }
        return sqrt(s / pcm.size) / 32768.0 to peak / 32768.0
    }

    /** 12-bin chroma of the mono mix (55 Hz .. 2 kHz) from 4096-point FFT frames. */
    private fun chroma(pcm: ShortArray): DoubleArray {
        val n = 4096
        val out = DoubleArray(12)
        val re = DoubleArray(n); val im = DoubleArray(n)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        var start = 0
        val frames = pcm.size / 2
        while (start + n <= frames) {
            for (i in 0 until n) { re[i] = (pcm[(start + i) * 2] + pcm[(start + i) * 2 + 1]) * 0.5 * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in 1 until n / 2) {
                val hz = k.toDouble() * sr / n
                if (hz < 55.0 || hz > 2000.0) continue
                val midi = 69 + 12 * ln(hz / 440.0) / ln(2.0)
                val pc = ((midi.roundToInt() % 12) + 12) % 12
                out[pc] += re[k] * re[k] + im[k] * im[k]
            }
            start += n
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        DataOutputStream(FileOutputStream(f).buffered()).use { o ->
            fun i32(v: Int) { o.writeByte(v); o.writeByte(v shr 8); o.writeByte(v shr 16); o.writeByte(v shr 24) }
            fun i16(v: Int) { o.writeByte(v); o.writeByte(v shr 8) }
            o.writeBytes("RIFF"); i32(36 + pcm.size * 2); o.writeBytes("WAVEfmt ")
            i32(16); i16(1); i16(2); i32(sr); i32(sr * 4); i16(4); i16(16)
            o.writeBytes("data"); i32(pcm.size * 2)
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) { bytes[i * 2] = pcm[i].toByte(); bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
            o.write(bytes)
        }
    }
}
