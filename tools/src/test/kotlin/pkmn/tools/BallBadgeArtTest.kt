package pkmn.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ball icons (gfx/balls/<item id>.png) and gym badge art (gfx/badges/badge_<n>.png) must exist and be pairwise distinct. */
class BallBadgeArtTest {
    private val assets = File(System.getProperty("pkmn.assets"))

    private fun md5(f: File) = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun everyPokeballItemHasADistinctIcon() {
        val items = Json.parseToJsonElement(File(assets, "data/items.json").readText()).jsonArray.map { it.jsonObject }
        val ballIds = items.filter { it["isPokeball"]?.jsonPrimitive?.boolean == true }.map { it["id"]!!.jsonPrimitive.content }
        assertTrue(ballIds.size >= 39, "expected the full ball list, got ${ballIds.size}")
        val byHash = HashMap<String, String>()
        for (id in ballIds) {
            val f = File(assets, "gfx/balls/$id.png")
            assertTrue(f.isFile, "missing ball icon for $id: ${f.path}")
            val img = ImageIO.read(f)
            assertEquals(16 to 16, img.width to img.height, "$id icon must be 16x16")
            byHash.put(md5(f), id)?.let { other -> throw AssertionError("ball icons $id and $other are identical") }
        }
        for (must in listOf("pokeball", "greatball", "ultraball", "masterball", "premierball", "diveball", "netball", "duskball", "quickball", "timerball")) {
            assertTrue(must in ballIds, "$must is not an isPokeball item")
        }
    }

    @Test fun allEightBadgesExistAndAreDistinct() {
        val campaign = Json.parseToJsonElement(File(assets, "world/campaign.json").readText()).jsonObject["badges"]!!.jsonArray
        assertEquals((1..8).toList(), campaign.map { it.jsonObject["number"]!!.jsonPrimitive.int })
        val byHash = HashMap<String, Int>()
        for (n in 1..8) {
            val f = File(assets, "gfx/badges/badge_$n.png")
            assertTrue(f.isFile, "missing badge image $n")
            val img = ImageIO.read(f)
            assertEquals(24 to 24, img.width to img.height, "badge $n must be 24x24")
            byHash.put(md5(f), n)?.let { other -> throw AssertionError("badges $n and $other are identical") }
        }
    }
}
