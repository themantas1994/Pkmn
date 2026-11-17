package pkmn.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** L10 (W7-1, W7-5, W7-8 to W7-12, W7-14): player-visible text in world/ avoids UK spellings, anachronisms, generic "Badges" and reused names. */
class NarrativeConsistencyTest {
    private val world = File(System.getProperty("pkmn.assets")).parentFile.resolve("world")
    private val files = listOf("scripts", "maps", "trainers", "quests").flatMap { d -> world.resolve(d).walkTopDown().filter { it.isFile }.toList() }
    /** Lines with the leading comment lines removed (comments are developer notes, not shown to the player). */
    private fun visible(f: File) = f.readLines().withIndex().filter { !it.value.trimStart().startsWith("#") }

    private fun hits(re: Regex) = files.flatMap { f -> visible(f).filter { re.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }

    @Test fun noUkSpellings() {
        val re = Regex("behaviour|defence|stabilis|labelled|colour|authoris|neighbour|favour|honour|armour|harbour", RegexOption.IGNORE_CASE)
        assertEquals(emptyList(), hits(re))
    }

    @Test fun noAnachronisms() = assertEquals(emptyList(), hits(Regex("PokéNav|Galar particles")))

    @Test fun genericBadgesAreLowercase() {
        val generic = Regex("\\b(?:[Tt]wo|[Tt]hree|[Ff]our|[Ff]ive|[Ss]ix|[Ss]even|[Ee]ight|[Aa]ll|[Yy]our|[Tt]hose|[Tt]hese|[Tt]he) Badges\\b")
        assertEquals(emptyList(), hits(generic), "generic badge counts are lowercase; proper names like Tide Badge keep the capital")
    }

    @Test fun trainerNamesDoNotReuseNamedCharacters() {
        val names = files.filter { it.parentFile.name == "trainers" }.flatMap { f ->
            f.readLines().mapNotNull { Regex("""^\s*name "(.+)"""").find(it)?.groupValues?.get(1) }
        }
        for (reused in listOf("Rook", "Mira", "Pip", "Kaito", "Mrs. Rowan")) assertTrue(reused !in names, "trainer name $reused is reused from a story character")
    }

    @Test fun eclipseOriginAndAimAgree() {
        val doc1 = world.resolve("scripts/p5c_eclipse.pks").readText().substringAfter("DOCUMENT 1").substringBefore("\n")
        assertTrue("after the Great Heat" in doc1 && "ancient" in doc1, "Doc 1 must say Umbra revived an older cult after the Great Heat")
        assertTrue("feeding it until it never wakes" !in world.resolve("scripts/p3b_summit.pks").readText(), "the lens is meant to wake the Prism Pokémon")
    }
}
